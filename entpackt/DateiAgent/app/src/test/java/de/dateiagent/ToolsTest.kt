package de.dateiagent

import de.dateiagent.data.MemoryRepository
import de.dateiagent.extract.TextExtractor
import de.dateiagent.tools.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ToolsTest {
    private lateinit var fs: InMemoryFs
    private lateinit var ctx: ToolContext
    private val tools = ToolRegistry.default().associateBy { it.name }
    private fun a(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun run(name: String, args: String) = runBlocking { tools.getValue(name).run(a(args), ctx) }

    @Before fun setup() { fs = InMemoryFs(); ctx = ToolContext(fs, TextExtractor(), MemoryRepository(FakeMemoryDao()), 1) }

    @Test fun allSpecifiedToolsExist() {
        listOf("list_files", "list_directories", "read_file", "search_files", "extract_text", "create_file", "update_file", "rename_file", "copy_file", "move_file", "create_directory", "get_file_info", "generate_report")
            .forEach { assertNotNull(it, tools[it]) }
    }
    @Test fun createVerifiesAndCreatesParents() {
        val r = run("create_file", """{"path":"Berichte/2025/a.txt","content":"Hallo"}""")
        assertTrue(r.content, r.ok); assertEquals("Hallo", fs.text("Berichte/2025/a.txt")); assertTrue(r.mutated)
    }
    @Test fun createRefusesExistingWithoutOverwrite() {
        fs.put("a.txt", "alt"); assertFalse(run("create_file", """{"path":"a.txt","content":"neu"}""").ok); assertEquals("alt", fs.text("a.txt"))
    }
    @Test fun overwriteNeedsApprovalAndBacksUp() {
        fs.put("a.txt", "alt")
        assertNotNull(tools.getValue("create_file").approvalPrompt(a("""{"path":"a.txt","content":"x","overwrite":true}"""), ctx))
        assertNull(tools.getValue("create_file").approvalPrompt(a("""{"path":"neu.txt","content":"x","overwrite":true}"""), ctx))
        assertTrue(run("create_file", """{"path":"a.txt","content":"neu","overwrite":true}""").ok)
        assertEquals("neu", fs.text("a.txt")); assertTrue(fs.files.keys.any { it.startsWith("$BACKUP_DIR/") && fs.text(it) == "alt" })
    }
    @Test fun deleteAlwaysNeedsApproval() { fs.put("a.txt", "x"); assertNotNull(tools.getValue("delete_file").approvalPrompt(a("""{"path":"a.txt"}"""), ctx)) }
    @Test fun updateModes() {
        fs.put("n.txt", "eins zwei drei")
        assertTrue(run("update_file", """{"path":"n.txt","mode":"replace_text","find":"zwei","replace":"2"}""").ok); assertEquals("eins 2 drei", fs.text("n.txt"))
        assertFalse(run("update_file", """{"path":"n.txt","mode":"replace_text","find":"fehlt","replace":"x"}""").ok)
        assertTrue(run("update_file", """{"path":"n.txt","mode":"append","content":"vier"}""").ok); assertEquals("eins 2 drei\nvier", fs.text("n.txt"))
        assertNull(tools.getValue("update_file").approvalPrompt(a("""{"path":"n.txt","mode":"append"}"""), ctx))
        assertNotNull(tools.getValue("update_file").approvalPrompt(a("""{"path":"n.txt","mode":"overwrite"}"""), ctx))
    }
    @Test fun ambiguousReplaceRejected() { fs.put("n.txt", "a a"); assertFalse(run("update_file", """{"path":"n.txt","mode":"replace_text","find":"a","replace":"b"}""").ok) }
    @Test fun moveCopyRenameMkdir() {
        fs.put("x/a.txt", "A")
        assertTrue(run("move_file", """{"from":"x/a.txt","to":"Neu/Unter/a.txt"}""").ok); assertNull(fs.text("x/a.txt")); assertEquals("A", fs.text("Neu/Unter/a.txt"))
        assertTrue(run("copy_file", """{"from":"Neu/Unter/a.txt","to":"Kopie/b.txt"}""").ok); assertEquals("A", fs.text("Kopie/b.txt"))
        assertTrue(run("rename_file", """{"path":"Kopie/b.txt","new_name":"c.txt"}""").ok); assertEquals("A", fs.text("Kopie/c.txt"))
        assertTrue(run("create_directory", """{"path":"P/Q/R"}""").ok); assertTrue("P/Q/R" in fs.dirs)
    }
    @Test fun moveNeverOverwrites() { fs.put("a.txt", "1"); fs.put("b.txt", "2"); assertThrows(Exception::class.java) { run("move_file", """{"from":"a.txt","to":"b.txt"}""") }; assertEquals("2", fs.text("b.txt")) }
    @Test fun sandboxEscapeRejected() {
        listOf("""{"path":"../etc/passwd"}""", """{"path":"a/../../x"}""").forEach { assertThrows(Exception::class.java) { run("read_file", it) } }
        assertThrows(Exception::class.java) { run("create_file", """{"path":"../x.txt","content":"x"}""") }
    }
    @Test fun readChunksAndFlagsTransmission() {
        fs.put("g.txt", (1..3000).joinToString("\n") { "Zeile $it" })
        val r0 = run("read_file", """{"path":"g.txt","chunk":0,"chunk_size":2000}"""); assertTrue(r0.ok && r0.transmitsFileContent); assertTrue(r0.content.contains("chunk=1"))
        assertFalse(run("read_file", """{"path":"g.txt","chunk":999}""").ok)
    }
    @Test fun readOfficeFormats() {
        fs.files["r.xlsx"] = de.dateiagent.extract.OfficeWriter.xlsx(listOf(listOf("Firma", "Betrag"), listOf("ACME", "10")))
        assertTrue(run("extract_text", """{"path":"r.xlsx"}""").content.contains("ACME"))
    }
    @Test fun imageNeedsConsent() { fs.files["b.png"] = byteArrayOf(1, 2, 3); assertFalse(run("read_file", """{"path":"b.png"}""").ok) }
    @Test fun searchByNameAndContent() {
        fs.put("Rechnung_2025.txt", "x"); fs.put("sub/notiz.txt", "Das Projekt Alpha startet"); fs.put("sub/andere.txt", "nichts")
        val r = run("search_files", """{"query":"alpha"}""").content; assertTrue(r, r.contains("sub/notiz.txt")); assertFalse(r.contains("andere"))
        assertTrue(run("search_files", """{"query":"rechnung"}""").content.contains("NAME: Rechnung_2025.txt"))
    }
    @Test fun listFilesRecursiveAndFilter() {
        fs.put("a.pdf", "x"); fs.put("d/b.pdf", "x"); fs.put("d/c.txt", "x")
        val r = run("list_files", """{"recursive":true,"extension":"pdf"}""").content; assertTrue(r.contains("a.pdf") && r.contains("d/b.pdf") && !r.contains("c.txt"))
        assertTrue(run("list_directories", "{}").content.contains("d"))
    }
    @Test fun reportNeverOverwrites() {
        fs.put("Bericht.md", "alt")
        assertTrue(run("generate_report", """{"path":"Bericht.md","title":"T","content":"Inhalt"}""").ok); assertEquals("alt", fs.text("Bericht.md")); assertTrue(fs.text("Bericht (2).md")!!.contains("Inhalt"))
    }
    @Test fun xlsxCreationIsReadable() {
        assertTrue(run("create_file", """{"path":"Rechnungen.xlsx","content":"Datum,Nr,Firma,Betrag\n2025-01-02,R1,ACME,100"}""").ok)
        assertTrue(TextExtractor().extract("Rechnungen.xlsx", fs.files["Rechnungen.xlsx"]!!).text.contains("ACME"))
    }
    @Test fun fileInfoReportsAnalyzed() {
        fs.put("a.txt", "x"); assertTrue(run("get_file_info", """{"path":"a.txt"}""").content.contains("noch nie"))
        run("read_file", """{"path":"a.txt"}"""); assertTrue(run("get_file_info", """{"path":"a.txt"}""").content.contains("bereits analysiert"))
    }
}
