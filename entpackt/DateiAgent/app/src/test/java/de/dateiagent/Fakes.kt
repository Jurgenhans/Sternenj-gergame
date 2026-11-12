package de.dateiagent

import de.dateiagent.ai.*
import de.dateiagent.data.*
import de.dateiagent.fs.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class InMemoryFs : VirtualFs {
    val files = linkedMapOf<String, ByteArray>()
    val dirs = linkedSetOf("")
    private val clock = 1_700_000_000_000L
    private fun e(p: String, dir: Boolean) = FsEntry(p, PathValidator.nameOf(p), dir, if (dir) 0 else files[p]!!.size.toLong(), clock, null)
    fun put(path: String, text: String) { var d = PathValidator.parent(path); while (d.isNotEmpty()) { dirs += d; d = PathValidator.parent(d) }; files[path] = text.toByteArray() }
    fun text(path: String) = files[path]?.toString(Charsets.UTF_8)
    override fun list(path: String): List<FsEntry> {
        val p = PathValidator.normalize(path); if (p !in dirs) throw FsException("Nicht gefunden: '$p'")
        return (dirs.filter { it.isNotEmpty() && PathValidator.parent(it) == p }.map { e(it, true) } + files.keys.filter { PathValidator.parent(it) == p }.map { e(it, false) })
    }
    override fun stat(path: String): FsEntry? { val p = PathValidator.normalize(path); return if (p in dirs) e(p, true) else if (p in files) e(p, false) else null }
    override fun readBytes(path: String, maxBytes: Long): ByteArray = files[PathValidator.normalize(path)] ?: throw FsException("Nicht gefunden: '$path'")
    override fun writeBytes(path: String, data: ByteArray, overwrite: Boolean): FsEntry {
        val p = PathValidator.normalize(path)
        if (PathValidator.parent(p) !in dirs) throw FsException("Zielordner existiert nicht.")
        if (p in files && !overwrite) throw FsException("Datei existiert bereits: '$p'")
        files[p] = data; return e(p, false)
    }
    override fun mkdir(path: String): FsEntry { val p = PathValidator.normalize(path); if (PathValidator.parent(p) !in dirs) throw FsException("Elternordner fehlt"); dirs += p; return e(p, true) }
    override fun delete(path: String) { val p = PathValidator.normalize(path); if (p in files) files.remove(p) else if (p in dirs) { dirs.removeAll { it == p || it.startsWith("$p/") }; files.keys.removeAll { it.startsWith("$p/") } } else throw FsException("Nicht gefunden: '$p'") }
    override fun rename(path: String, newName: String): FsEntry { val p = PathValidator.normalize(path); val n = PathValidator.join(PathValidator.parent(p), PathValidator.validateName(newName)); return move(p, n) }
    override fun copy(from: String, to: String): FsEntry { val t = PathValidator.normalize(to); if (stat(t) != null) throw FsException("Ziel existiert bereits"); return writeBytes(t, readBytes(from), false) }
    override fun move(from: String, to: String): FsEntry {
        val f = PathValidator.normalize(from); val t = PathValidator.normalize(to)
        if (stat(t) != null) throw FsException("Ziel existiert bereits: '$t'"); if (PathValidator.parent(t) !in dirs) throw FsException("Zielordner existiert nicht.")
        if (f in files) { files[t] = files.remove(f)!!; return e(t, false) }
        if (f in dirs) { dirs.remove(f); dirs += t; return e(t, true) }
        throw FsException("Nicht gefunden: '$f'")
    }
}

class FakeTaskDao : TaskDao {
    val map = linkedMapOf<Long, TaskEntity>(); private var next = 1L
    private val flow = MutableStateFlow(0)
    override suspend fun insert(t: TaskEntity): Long { val id = next++; map[id] = t.copy(id = id); return id }
    override suspend fun update(t: TaskEntity) { map[t.id] = t }
    override suspend fun get(id: Long) = map[id]
    override fun observe(id: Long): Flow<TaskEntity?> = flow.map { map[id] }
    override fun observeAll(): Flow<List<TaskEntity>> = flow.map { map.values.toList() }
    override suspend fun getActive() = map.values.filter { it.status in TaskStatus.active }
    override suspend fun statusOf(id: Long) = map[id]?.status
    override suspend fun setStatus(id: Long, status: String) { map[id]?.let { map[id] = it.copy(status = status) } }
    override suspend fun delete(id: Long) { map.remove(id) }
}
class FakeActivityDao : ActivityDao {
    val list = mutableListOf<ActivityEntity>(); private var n = 1L
    override suspend fun insert(a: ActivityEntity): Long { list += a.copy(id = n++); return n }
    override fun observeRecent(): Flow<List<ActivityEntity>> = MutableStateFlow(list.toList())
    override suspend fun forTask(taskId: Long) = list.filter { it.taskId == taskId }
}
class FakeMemoryDao : MemoryDao {
    val list = mutableListOf<MemoryEntity>(); private var n = 1L
    override suspend fun insert(m: MemoryEntity): Long { list += m.copy(id = n++); return n }
    override suspend fun latest(kind: String, key: String) = list.lastOrNull { it.kind == kind && it.key == key }
    override suspend fun recent() = list.filter { it.kind != "ANALYZED_FILE" }.reversed()
    override fun observe(): Flow<List<MemoryEntity>> = MutableStateFlow(list.toList())
    override suspend fun delete(id: Long) { list.removeAll { it.id == id } }
    override suspend fun clear() { list.clear() }
}

/** Skriptgesteuerte "KI": liefert vorbereitete Antworten und merkt sich, was ihr gesendet wurde. */
class ScriptedProvider(private val script: MutableList<LlmResponse>) : LlmProvider {
    override val id = "test"
    val seen = mutableListOf<List<ChatMessage>>()
    override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): LlmResponse {
        seen += messages.toList()
        if (script.isEmpty()) throw LlmException("Skript zu Ende", fatal = true)
        return script.removeAt(0)
    }
}
fun call(id: String, name: String, args: String) = ToolCall(id, name, args)
fun resp(vararg calls: ToolCall, text: String? = null) = LlmResponse(text, calls.toList(), if (calls.isEmpty()) "stop" else "tool_calls")
