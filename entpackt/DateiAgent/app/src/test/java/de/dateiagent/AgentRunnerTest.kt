package de.dateiagent

import de.dateiagent.agent.*
import de.dateiagent.ai.*
import de.dateiagent.data.*
import de.dateiagent.extract.TextExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AgentRunnerTest {
    private lateinit var fs: InMemoryFs
    private lateinit var tasks: FakeTaskDao
    private lateinit var act: FakeActivityDao
    private lateinit var mem: FakeMemoryDao
    private var consent = true
    private val notices = mutableListOf<String>()

    @Before fun setup() { fs = InMemoryFs(); tasks = FakeTaskDao(); act = FakeActivityDao(); mem = FakeMemoryDao(); consent = true; notices.clear() }

    private fun runner(p: LlmProvider) = AgentRunner(tasks, act, MemoryRepository(mem),
        { AgentConfig(consent, false, 20, "", "api.test", "m", "Test") }, { p }, { fs }, TextExtractor(), null,
        object : AgentNotifier { override fun approvalNeeded(task: TaskEntity) { notices += "approval" }; override fun finished(task: TaskEntity) { notices += "finished" } })

    private fun newTask(prompt: String) = runBlocking { tasks.insert(TaskEntity(prompt = prompt)) }

    @Test fun multiStepTaskActuallyChangesFilesAndIsVerified() = runBlocking {
        fs.put("Rechnungen/a.txt", "Rechnung 1 ACME 100 EUR")
        val id = newTask("Fasse zusammen")
        val p = ScriptedProvider(mutableListOf(
            resp(call("1", "set_plan", """{"goal":"Zusammenfassen","steps":["Lesen","Schreiben","Prüfen"]}""")),
            resp(call("2", "update_plan_step", """{"index":1,"status":"in_progress"}"""), call("3", "read_file", """{"path":"Rechnungen/a.txt"}""")),
            resp(call("4", "create_file", """{"path":"Ergebnis/z.txt","content":"ACME 100"}""")),
            resp(call("5", "read_file", """{"path":"Ergebnis/z.txt"}""")),
            resp(call("6", "finish_task", """{"summary":"Fertig"}""")),
        ))
        assertEquals(Outcome.Completed, runner(p).run(id))
        val t = tasks.map[id]!!
        assertEquals(TaskStatus.COMPLETED, t.status); assertEquals("ACME 100", fs.text("Ergebnis/z.txt"))
        assertTrue(t.resultText.contains("Fertig")); assertTrue(t.resultText.contains("create_file: Ergebnis/z.txt"))
        assertTrue(act.list.any { it.kind == "TRANSMIT" }); assertTrue(mem.list.any { it.kind == "TASK_RESULT" }); assertEquals(listOf("finished"), notices)
        // die KI hat das echte Tool-Ergebnis gesehen
        assertTrue(p.seen.last().any { it.role == "tool" && it.content!!.contains("Datei erstellt und verifiziert") })
    }

    @Test fun deleteRequiresApprovalThenExecutesAfterApproval() = runBlocking {
        fs.put("alt.txt", "x"); val id = newTask("Lösche alt.txt")
        val script = mutableListOf(resp(call("d1", "delete_file", """{"path":"alt.txt"}""")))
        assertEquals(Outcome.WaitingApproval, runner(ScriptedProvider(script)).run(id))
        assertEquals("x", fs.text("alt.txt")); assertEquals(TaskStatus.WAITING_APPROVAL, tasks.map[id]!!.status); assertEquals(Approval.PENDING, tasks.map[id]!!.approvalState); assertTrue("approval" in notices)
        // Nutzer erlaubt, Worker startet neu (neuer Runner = neuer Prozess), Zustand kommt aus der DB
        tasks.map[id] = tasks.map[id]!!.copy(approvalState = Approval.APPROVED, status = TaskStatus.QUEUED)
        val p2 = ScriptedProvider(mutableListOf(resp(call("f", "finish_task", """{"summary":"Gelöscht"}"""))))
        assertEquals(Outcome.Completed, runner(p2).run(id))
        assertNull(fs.text("alt.txt")); assertTrue(act.list.any { it.kind == "APPROVAL" && it.detail.contains("erlaubt") })
    }

    @Test fun denialKeepsFileAndTellsModel() = runBlocking {
        fs.put("alt.txt", "x"); val id = newTask("Lösche")
        runner(ScriptedProvider(mutableListOf(resp(call("d1", "delete_file", """{"path":"alt.txt"}"""))))).run(id)
        tasks.map[id] = tasks.map[id]!!.copy(approvalState = Approval.DENIED, status = TaskStatus.QUEUED)
        val p2 = ScriptedProvider(mutableListOf(resp(call("f", "finish_task", """{"summary":"Nicht gelöscht"}"""))))
        assertEquals(Outcome.Completed, runner(p2).run(id)); assertEquals("x", fs.text("alt.txt"))
        assertTrue(p2.seen.first().any { it.role == "tool" && it.content!!.startsWith("ABGELEHNT") })
    }

    @Test fun sandboxViolationIsReportedToModelNotExecuted() = runBlocking {
        val id = newTask("x")
        val p = ScriptedProvider(mutableListOf(resp(call("1", "read_file", """{"path":"../geheim.txt"}""")), resp(call("2", "finish_task", """{"summary":"ok"}"""))))
        runner(p).run(id)
        assertTrue(p.seen.last().any { it.role == "tool" && it.content!!.startsWith("FEHLER") && it.content!!.contains("verlässt") })
        assertTrue(act.list.any { it.kind == "TOOL" && !it.success })
    }

    @Test fun unknownToolAndBadJsonAreHandled() = runBlocking {
        val id = newTask("x")
        val p = ScriptedProvider(mutableListOf(resp(call("1", "format_c", "{}"), call("2", "read_file", "{kaputt")), resp(call("3", "finish_task", """{"summary":"ok"}"""))))
        assertEquals(Outcome.Completed, runner(p).run(id))
        assertEquals(2, p.seen.last().count { it.role == "tool" && it.content!!.startsWith("FEHLER") })
    }

    @Test fun resumesFromCheckpointAfterProcessDeath() = runBlocking {
        val id = newTask("Erstelle a.txt")
        val first = ScriptedProvider(mutableListOf(resp(call("1", "create_file", """{"path":"a.txt","content":"A"}"""))))   // danach "stirbt" der Prozess: Skript leer -> LlmException
        assertTrue(runner(first).run(id) is Outcome.Failed)
        assertEquals("A", fs.text("a.txt"))
        tasks.setStatus(id, TaskStatus.QUEUED)
        val second = ScriptedProvider(mutableListOf(resp(call("2", "finish_task", """{"summary":"fertig"}"""))))
        assertEquals(Outcome.Completed, runner(second).run(id))
        assertTrue(second.seen.first().size >= 4)   // system, user, assistant(tool call), tool result bleiben erhalten
    }

    @Test fun unansweredToolCallIsExecutedOnResume() = runBlocking {
        val id = newTask("x")
        // Checkpoint, bei dem die Assistentennachricht gespeichert, das Tool aber noch nicht ausgefuehrt wurde
        val msgs = listOf(ChatMessage("system", "s"), ChatMessage("user", "x"), ChatMessage("assistant", null, listOf(call("c1", "create_file", """{"path":"r.txt","content":"R"}"""))))
        tasks.map[id] = tasks.map[id]!!.copy(messagesJson = kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ChatMessage.serializer()), msgs))
        val p = ScriptedProvider(mutableListOf(resp(call("f", "finish_task", """{"summary":"ok"}"""))))
        runner(p).run(id); assertEquals("R", fs.text("r.txt"))
    }

    @Test fun pauseStopsBeforeNextStep() = runBlocking {
        val id = newTask("x"); tasks.setStatus(id, TaskStatus.PAUSED)
        assertEquals(Outcome.Stopped, runner(ScriptedProvider(mutableListOf())).run(id))
    }

    @Test fun missingConsentBlocksAnyCloudCall() = runBlocking {
        consent = false; val id = newTask("x"); val p = ScriptedProvider(mutableListOf())
        assertTrue(runner(p).run(id) is Outcome.Failed); assertTrue(p.seen.isEmpty()); assertEquals(TaskStatus.FAILED, tasks.map[id]!!.status)
    }

    @Test fun invalidFolderGivesUnderstandableError() = runBlocking {
        val id = newTask("x")
        val r = AgentRunner(tasks, act, MemoryRepository(mem), { AgentConfig(true, false, 20, "", "h", "m", "T") }, { ScriptedProvider(mutableListOf()) }, { throw de.dateiagent.fs.FsException("Die Berechtigung für den Ordner wurde widerrufen.") }, TextExtractor(), null, null)
        val o = r.run(id); assertTrue(o is Outcome.Failed && (o as Outcome.Failed).message.contains("widerrufen"))
    }

    @Test fun llmErrorFailsTaskWithMessage() = runBlocking {
        val id = newTask("x")
        val p = object : LlmProvider { override val id = "t"; override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): LlmResponse = throw LlmException("API-Schlüssel ungültig (401)", fatal = true) }
        val o = runner(p).run(id); assertTrue(o is Outcome.Failed); assertTrue(tasks.map[id]!!.error.contains("401"))
    }

    @Test fun iterationLimitStopsRunaway() = runBlocking {
        val id = newTask("x")
        val script = MutableList(30) { resp(call("c$it", "list_files", "{}")) }
        val o = runner(ScriptedProvider(script)).run(id); assertTrue(o is Outcome.Failed && (o as Outcome.Failed).message.contains("Maximale Schrittzahl"))
    }

    @Test fun nudgesWhenPlanOpenThenAcceptsFinalText() = runBlocking {
        val id = newTask("x")
        val p = ScriptedProvider(mutableListOf(resp(call("1", "set_plan", """{"goal":"g","steps":["a"]}""")), resp(text = "fertig?"), resp(text = "noch fertig?"), resp(text = "wirklich fertig")))
        assertEquals(Outcome.Completed, runner(p).run(id)); assertTrue(tasks.map[id]!!.resultText.startsWith("wirklich fertig"))
    }

    @Test fun compactionShrinksOldToolResultsOnly() {
        val r = runner(ScriptedProvider(mutableListOf()))
        val big = "x".repeat(50_000)
        val msgs = listOf(ChatMessage("system", "s"), ChatMessage("user", "u")) + (1..8).flatMap { listOf(ChatMessage("assistant", null, listOf(call("$it", "read_file", "{}"))), ChatMessage("tool", big, toolCallId = "$it")) }
        val c = r.compact(msgs, budgetChars = 100_000, keepLast = 4)
        assertTrue(c.sumOf { it.content?.length ?: 0 } < msgs.sumOf { it.content?.length ?: 0 }); assertEquals(big, c.last().content); assertEquals(msgs.size, c.size)
    }
}
