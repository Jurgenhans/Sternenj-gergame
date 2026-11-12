package de.dateiagent.tools

import de.dateiagent.ai.ToolSpec
import de.dateiagent.data.MemoryRepository
import de.dateiagent.extract.ImageEncoder
import de.dateiagent.extract.TextExtractor
import de.dateiagent.fs.FsException
import de.dateiagent.fs.VirtualFs
import kotlinx.serialization.json.*

class ToolContext(
    val fs: VirtualFs,
    val extractor: TextExtractor,
    val memory: MemoryRepository?,
    val taskId: Long,
    val images: ImageEncoder? = null,
    val imageConsent: Boolean = false,
    val now: () -> Long = { System.currentTimeMillis() },
)

data class ToolResult(
    val ok: Boolean,
    val content: String,
    val images: List<String> = emptyList(),
    val paths: List<String> = emptyList(),
    /** true, wenn Dateiinhalte in [content] enthalten sind, die an die Cloud-KI gehen. */
    val transmitsFileContent: Boolean = false,
    val mutated: Boolean = false,
)

abstract class AgentTool {
    abstract val name: String
    abstract val description: String
    abstract val parameters: JsonObject
    /** Liefert eine Nutzerfrage, wenn die Ausfuehrung bestaetigt werden muss; sonst null. */
    open fun approvalPrompt(args: JsonObject, ctx: ToolContext): String? = null
    abstract suspend fun run(args: JsonObject, ctx: ToolContext): ToolResult
    fun spec() = ToolSpec(name, description, parameters)
}

// ---- Argument-Helfer ----
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
fun JsonObject.reqStr(key: String): String = str(key) ?: throw FsException("Pflichtparameter '$key' fehlt.")
fun JsonObject.int(key: String, def: Int): Int = (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() ?: def
fun JsonObject.bool(key: String, def: Boolean): Boolean = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: def

// ---- Schema-Helfer ----
fun prop(type: String, desc: String, enum: List<String>? = null): JsonObject = buildJsonObject {
    put("type", type); put("description", desc)
    if (enum != null) putJsonArray("enum") { enum.forEach { add(it) } }
}
fun schema(required: List<String>, vararg props: Pair<String, JsonObject>): JsonObject = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
    putJsonArray("required") { required.forEach { add(it) } }
}
