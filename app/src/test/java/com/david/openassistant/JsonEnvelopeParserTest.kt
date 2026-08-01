package com.david.openassistant

import com.david.openassistant.agent.JsonEnvelopeParser
import com.david.openassistant.data.openrouter.OpenRouterException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonEnvelopeParserTest {
    @Test
    fun parsesObjectEnvelope() {
        assertEquals("ok", JsonEnvelopeParser.requireObject("{\"status\":\"ok\"}", "test").getString("status"))
    }

    @Test
    fun unwrapsSingleObjectArrayEnvelope() {
        assertEquals(7, JsonEnvelopeParser.requireObject("[{\"value\":7}]", "test").getInt("value"))
    }

    @Test
    fun rejectsEmptyArrayAsRetryableProviderError() {
        val error = assertThrows(OpenRouterException::class.java) {
            JsonEnvelopeParser.requireObject("[]", "test")
        }
        assertEquals(null, error.statusCode)
    }

    @Test
    fun extractsTextFromArrayContentBlocks() {
        val message = JSONObject().put(
            "content",
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", "hello "))
                .put(JSONObject().put("type", "text").put("text", "world")),
        )
        assertEquals("hello world", JsonEnvelopeParser.messageText(message))
    }

    @Test
    fun recoversObjectFromMarkdownFence() {
        val raw = """
            Here is the result:
            ```json
            {"status":"recovered","work_product":"brace } inside a string"}
            ```
        """.trimIndent()

        assertEquals(
            "recovered",
            JsonEnvelopeParser.requireEmbeddedObject(raw, "test").getString("status"),
        )
    }

    @Test
    fun choosesFinalValidObjectAfterReasoningFragment() {
        val raw = """
            Example: {"status":"draft"}
            Final answer: {"status":"final","nested":{"ok":true}}
        """.trimIndent()

        assertEquals(
            "final",
            JsonEnvelopeParser.requireEmbeddedObject(raw, "test").getString("status"),
        )
    }

    @Test
    fun rejectsProseWithoutAValidObject() {
        assertThrows(OpenRouterException::class.java) {
            JsonEnvelopeParser.requireEmbeddedObject("Useful prose with {broken JSON} only.", "test")
        }
    }
}
