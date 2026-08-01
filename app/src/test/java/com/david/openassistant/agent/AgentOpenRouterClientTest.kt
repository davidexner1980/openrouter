package com.david.openassistant.agent

import com.david.openassistant.data.openrouter.OpenRouterException
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.TimeUnit

class AgentOpenRouterClientTest {

    private val client = AgentOpenRouterClient(
        client = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .build()
    )

    @Test
    fun parseResponseThrowsExceptionOnChoiceLevelError() {
        // ... (existing test code)
    }

    @Test
    fun parseResponseHandlesLeadingWhitespaceWithChoiceError() {
        val jsonBody = JSONObject()
            .put("id", "gen-123")
            .put("model", "google/gemini-2.5-flash-lite")
            .put("choices", org.json.JSONArray().put(
                JSONObject()
                    .put("finish_reason", "error")
                    .put("error", JSONObject()
                        .put("code", 429)
                        .put("message", "JSON error injected into SSE stream")
                    )
                    .put("message", JSONObject().put("role", "assistant").put("content", JSONObject.NULL))
            ))
            .toString()

        val bodyWithWhitespace = "\n\n  \n  $jsonBody"

        val exception = assertThrows(OpenRouterException::class.java) {
            val method = AgentOpenRouterClient::class.java.getDeclaredMethod("parseResponse", String::class.java, String::class.java, JSONObject::class.java, Int::class.javaPrimitiveType)
            method.isAccessible = true
            try {
                method.invoke(client, bodyWithWhitespace, "sk-or-test", JSONObject(), 200)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        }

        assertEquals(429, exception.statusCode)
        assertEquals("JSON error injected into SSE stream", exception.userMessage)
    }

    @Test
    fun parseResponseThrowsExceptionOnBlankContent() {
        val body = JSONObject()
            .put("id", "gen-123")
            .put("choices", org.json.JSONArray().put(
                JSONObject()
                    .put("finish_reason", "stop")
                    .put("message", JSONObject().put("role", "assistant").put("content", ""))
            ))
            .toString()

        val exception = assertThrows(OpenRouterException::class.java) {
            val method = AgentOpenRouterClient::class.java.getDeclaredMethod("parseResponse", String::class.java, String::class.java, JSONObject::class.java, Int::class.javaPrimitiveType)
            method.isAccessible = true
            try {
                method.invoke(client, body, "sk-or-test", JSONObject(), 200)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        }

        assertEquals("The agent model returned no usable text or tool calls. Finish reason: stop.", exception.userMessage)
    }

    @Test
    fun parseResponseAcceptsToolCallsWithBlankContent() {
        val body = JSONObject()
            .put("id", "gen-123")
            .put("choices", org.json.JSONArray().put(
                JSONObject()
                    .put("finish_reason", "tool_calls")
                    .put("message", JSONObject()
                        .put("role", "assistant")
                        .put("content", "")
                        .put("tool_calls", org.json.JSONArray().put(
                            JSONObject()
                                .put("id", "call-1")
                                .put("type", "function")
                                .put("function", JSONObject().put("name", "test").put("arguments", "{}"))
                        ))
                    )
            ))
            .toString()

        val method = AgentOpenRouterClient::class.java.getDeclaredMethod("parseResponse", String::class.java, String::class.java, JSONObject::class.java, Int::class.javaPrimitiveType)
        method.isAccessible = true
        val response = method.invoke(client, body, "sk-or-test", JSONObject(), 200)
        
        // This should not throw an exception because tool_calls are present
        // The return type is RawAgentResponse, which is private. 
        // We can just verify it didn't throw.
    }

    @Test
    fun parseResponseDetectsUpstreamRateLimitInContent() {
        val body = JSONObject()
            .put("id", "gen-123")
            .put("model", "google/gemini-2.5-flash-lite")
            .put("choices", org.json.JSONArray().put(
                JSONObject()
                    .put("finish_reason", "stop") // Sometimes finish_reason is stop but content is error
                    .put("message", JSONObject().put("role", "assistant").put("content", "Upstream provider error: rate limit exceeded for gemini-2.5-flash-lite"))
            ))
            .toString()

        val exception = assertThrows(OpenRouterException::class.java) {
            val method = AgentOpenRouterClient::class.java.getDeclaredMethod("parseResponse", String::class.java, String::class.java, JSONObject::class.java, Int::class.javaPrimitiveType)
            method.isAccessible = true
            try {
                method.invoke(client, body, "sk-or-test", JSONObject(), 200)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        }

        assertEquals(429, exception.statusCode)
        assertEquals("Upstream provider error: rate limit exceeded for gemini-2.5-flash-lite", exception.userMessage)
    }

    @Test
    fun basePayloadRestrictsToAllowlist() {
        val method = AgentOpenRouterClient::class.java.getDeclaredMethods().first { it.name == "basePayload" }
        method.isAccessible = true
        
        // modelId "google/gemini" should be overridden by allowlist logic in basePayload to openrouter/auto-beta with fallback to openrouter/free
        val payload = method.invoke(client, "google/gemini", "system", "user", null, null, null, false) as JSONObject
        
        val models = payload.getJSONArray("models")
        assertEquals("openrouter/auto-beta", payload.getString("model"))
        assertEquals(1, models.length())
        assertEquals("openrouter/free", models.getString(0))
    }

    @Test
    fun repairOversizedPayloadShrinksModelsArray() {
        val method = AgentOpenRouterClient::class.java.getDeclaredMethod("repairOversizedPayload", JSONObject::class.java)
        method.isAccessible = true
        
        val oversized = JSONObject()
            .put("models", org.json.JSONArray(listOf("m1", "m2", "m3", "m4", "m5")))
            
        val repaired = method.invoke(client, oversized) as JSONObject
        
        val models = repaired.getJSONArray("models")
        assertEquals(3, models.length())
        assertEquals("m1", models.getString(0))
        assertEquals("m2", models.getString(1))
        assertEquals("m3", models.getString(2))
    }

    @Test
    fun validateAndNormalizeGeneratedRequestRejectsEmptyMessages() {
        val method = AgentOpenRouterClient::class.java.getDeclaredMethods().first { it.name == "validateAndNormalizeGeneratedRequest" }
        method.isAccessible = true

        val malformed = JSONObject()
            .put("model", "openrouter/auto")
            // Missing "messages"

        val exception = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            method.invoke(client, malformed)
        }
        val cause = exception.cause as OpenRouterException
        assertEquals("Generated request is missing mandatory 'messages' array.", cause.message)
    }

    @Test
    fun validateAndNormalizeGeneratedRequestAcceptsValidMessages() {
        val method = AgentOpenRouterClient::class.java.getDeclaredMethods().first { it.name == "validateAndNormalizeGeneratedRequest" }
        method.isAccessible = true

        val valid = JSONObject()
            .put("model", "openrouter/auto")
            .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))

        val normalized = method.invoke(client, valid) as JSONObject
        assertEquals(1, normalized.getJSONArray("messages").length())
    }

    @Test
    fun validateAndNormalizeGeneratedRequestRejectsMalformedMessages() {
        val method = AgentOpenRouterClient::class.java.getDeclaredMethods().first { it.name == "validateAndNormalizeGeneratedRequest" }
        method.isAccessible = true

        val malformed = JSONObject()
            .put("model", "openrouter/auto")
            .put("messages", "This should be an array but is a string")

        val exception = assertThrows(java.lang.reflect.InvocationTargetException::class.java) {
            method.invoke(client, malformed)
        }
        val cause = exception.cause as OpenRouterException
        assertEquals("Generated request is missing mandatory 'messages' array.", cause.message)
    }
}
