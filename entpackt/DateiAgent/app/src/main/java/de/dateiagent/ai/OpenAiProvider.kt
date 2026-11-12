package de.dateiagent.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Chat-Completions-Client mit Function Calling. Funktioniert mit OpenAI und allen
 * OpenAI-kompatiblen Endpunkten (eigener Proxy, OpenRouter, Azure-kompatible Gateways, Ollama ...).
 */
class OpenAiProvider(
    private val baseUrl: String,
    private val apiKey: () -> String?,
    private val model: String,
    private val client: OkHttpClient = defaultClient,
    override val id: String = "openai",
) : LlmProvider {

    override suspend fun chat(messages: List<ChatMessage>, tools: List<ToolSpec>): LlmResponse {
        val key = apiKey()
        if (key.isNullOrBlank() && baseUrl.contains("api.openai.com")) throw LlmException("Kein API-Schlüssel hinterlegt. Bitte in den KI-Einstellungen eintragen.", fatal = true)
        val body = buildRequest(messages, tools)
        var attempt = 0
        while (true) {
            try {
                return withContext(Dispatchers.IO) { execute(body, key) }
            } catch (e: LlmException) {
                if (!e.retryable || ++attempt >= 4) throw e
                delay(1500L * (1 shl attempt))
            }
        }
    }

    internal fun buildRequest(messages: List<ChatMessage>, tools: List<ToolSpec>): String = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            for (m in messages) add(buildJsonObject {
                put("role", m.role)
                when {
                    m.role == "tool" -> { put("tool_call_id", m.toolCallId ?: ""); put("content", m.content ?: "") }
                    m.role == "assistant" -> {
                        put("content", m.content?.let { JsonPrimitive(it) } ?: JsonNull)
                        if (m.toolCalls.isNotEmpty()) putJsonArray("tool_calls") {
                            for (c in m.toolCalls) add(buildJsonObject {
                                put("id", c.id); put("type", "function")
                                putJsonObject("function") { put("name", c.name); put("arguments", c.arguments) }
                            })
                        }
                    }
                    m.images.isNotEmpty() -> putJsonArray("content") {
                        add(buildJsonObject { put("type", "text"); put("text", m.content ?: "") })
                        for (img in m.images) add(buildJsonObject { put("type", "image_url"); putJsonObject("image_url") { put("url", img) } })
                    }
                    else -> put("content", m.content ?: "")
                }
            })
        }
        if (tools.isNotEmpty()) {
            putJsonArray("tools") {
                for (t in tools) add(buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") { put("name", t.name); put("description", t.description); put("parameters", t.parameters) }
                })
            }
            put("tool_choice", "auto")
        }
    }.toString()

    private suspend fun execute(body: String, key: String?): LlmResponse {
        val req = Request.Builder().url("$baseUrl/chat/completions")
            .apply { if (!key.isNullOrBlank()) header("Authorization", "Bearer $key") }
            .post(body.toRequestBody("application/json".toMediaType())).build()
        val call = client.newCall(req)
        coroutineContext.job.invokeOnCompletion { call.cancel() }
        val text: String
        try {
            call.execute().use { resp ->
                text = resp.body?.string() ?: ""
                if (!resp.isSuccessful) throw httpError(resp.code, text)
            }
        } catch (e: IOException) {
            throw LlmException("Netzwerkfehler bei der KI-Anfrage: ${e.message}", retryable = true)
        }
        return parseResponse(text)
    }

    internal fun httpError(code: Int, body: String): LlmException {
        val msg = runCatching { Json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content }.getOrNull() ?: body.take(300)
        return when (code) {
            401 -> LlmException("API-Schlüssel ungültig oder abgelaufen (401). $msg", fatal = true)
            403 -> LlmException("Zugriff verweigert (403). $msg", fatal = true)
            404 -> LlmException("Modell oder Endpunkt nicht gefunden (404). Prüfe Modellname und Basis-URL. $msg", fatal = true)
            408, 409, 429, 500, 502, 503, 504 -> LlmException("KI-Dienst vorübergehend nicht verfügbar ($code). $msg", retryable = true)
            else -> LlmException("KI-Fehler ($code): $msg", fatal = code in 400..499)
        }
    }

    internal fun parseResponse(text: String): LlmResponse {
        val root = Json.parseToJsonElement(text).jsonObject
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: throw LlmException("Leere Antwort der KI.", retryable = true)
        val msg = choice["message"]!!.jsonObject
        val content = (msg["content"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        val calls = msg["tool_calls"]?.jsonArray?.map {
            val o = it.jsonObject; val f = o["function"]!!.jsonObject
            ToolCall(o["id"]!!.jsonPrimitive.content, f["name"]!!.jsonPrimitive.content, f["arguments"]?.jsonPrimitive?.content ?: "{}")
        } ?: emptyList()
        return LlmResponse(content, calls, (choice["finish_reason"] as? JsonPrimitive)?.content)
    }

    companion object {
        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
        }
    }
}

data class ProviderPreset(val id: String, val label: String, val baseUrl: String, val defaultModel: String, val needsKey: Boolean = true)

object Providers {
    val presets = listOf(
        ProviderPreset("openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o"),
        ProviderPreset("custom", "OpenAI-kompatibel (eigene URL / Proxy)", "https://", "", needsKey = false),
    )
}
