package de.dateiagent

import de.dateiagent.ai.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OpenAiProviderTest {
    private val p = OpenAiProvider("https://example.test/v1", { "sk-test" }, "gpt-x")

    @Test fun requestContainsToolsAndMessageShapes() {
        val msgs = listOf(ChatMessage("system", "s"), ChatMessage("user", "hi"),
            ChatMessage("assistant", null, listOf(ToolCall("c1", "read_file", """{"path":"a"}"""))),
            ChatMessage("tool", "ergebnis", toolCallId = "c1"),
            ChatMessage("user", "bild", images = listOf("data:image/jpeg;base64,AAA")))
        val j = Json.parseToJsonElement(p.buildRequest(msgs, listOf(ToolSpec("read_file", "d", buildJsonObject { put("type", "object") })))).jsonObject
        assertEquals("gpt-x", j["model"]!!.jsonPrimitive.content)
        val m = j["messages"]!!.jsonArray
        assertEquals("c1", m[2].jsonObject["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("c1", m[3].jsonObject["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("image_url", m[4].jsonObject["content"]!!.jsonArray[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("read_file", j["tools"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }
    @Test fun parsesToolCallResponse() {
        val r = p.parseResponse("""{"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[{"id":"x","type":"function","function":{"name":"list_files","arguments":"{}"}}]}}]}""")
        assertNull(r.content); assertEquals("list_files", r.toolCalls.single().name)
    }
    @Test fun parsesTextResponse() { assertEquals("Hallo", p.parseResponse("""{"choices":[{"finish_reason":"stop","message":{"content":"Hallo"}}]}""").content) }
    @Test fun mapsHttpErrors() {
        assertTrue(p.httpError(401, """{"error":{"message":"bad key"}}""").fatal)
        assertTrue(p.httpError(429, "{}").retryable); assertTrue(p.httpError(503, "x").retryable)
        assertTrue(p.httpError(404, "{}").message!!.contains("Modell"))
    }
    @Test fun imagesAreNotPersisted() {
        val s = Json.encodeToString(ChatMessage.serializer(), ChatMessage("user", "x", images = listOf("data:AAAA")))
        assertFalse(s.contains("AAAA"))
    }
}
