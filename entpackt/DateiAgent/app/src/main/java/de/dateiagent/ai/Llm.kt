package de.dateiagent.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject

@Serializable
data class ToolCall(val id: String, val name: String, val arguments: String)

/** Provider-neutrale Nachricht. [images] (Data-URLs) werden bewusst NICHT persistiert. */
@Serializable
data class ChatMessage(
    val role: String,                      // system | user | assistant | tool
    val content: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    @Transient val images: List<String> = emptyList(),
)

data class ToolSpec(val name: String, val description: String, val parameters: JsonObject)

data class LlmResponse(val content: String?, val toolCalls: List<ToolCall>, val finishReason: String?)

class LlmException(message: String, val retryable: Boolean = false, val fatal: Boolean = false) : Exception(message)

/** Austauschbare KI-Anbindung. Weitere Anbieter = weitere Implementierung + Eintrag in [Providers]. */
interface LlmProvider {
    val id: String
    suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): LlmResponse
}
