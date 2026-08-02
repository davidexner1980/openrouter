package com.david.openassistant.agent

import com.david.openassistant.data.openrouter.OpenRouterException
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        val body = JSONObject()
            .put("id", "gen-123")
            .put("choices", JSONArray().put(
                JSONObject()
                    .put("finish_reason", "error")
                    .put("error", JSONObject()
                        .put("code", 400)
                        .put("message", "Model failed")
                    )
                    .put("message", JSONObject().put("role", "assistant").put("content", JSONObject.NULL))
            ))
            .toString()

        val exception = assertThrows(OpenRouterException::class.java) {
            val method = AgentOpenRouterClient::class.java.getDeclaredMethod("parseResponse", String::class.java, String::class.java, JSONObject::class.java, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, String::class.java)
            method.isAccessible = true
            try {
                method.invoke(client, body, "sk-or-test", JSONObject(), 200, 100L, "ex-123")
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        }

        assertEquals(400, exception.statusCode)
        assertEquals("Model failed", exception.userMessage)
    }

    @Test
    fun parseResponseStopScanningContentForRateLimit() {
        val body = JSONObject()
            .put("id", "gen-123")
            .put("model", "google/gemini-2.5-flash-lite")
            .put("choices", JSONArray().put(
                JSONObject()
                    .put("finish_reason", "stop")
                    .put("message", JSONObject().put("role", "assistant").put("content", "Upstream provider error: rate limit exceeded for gemini-2.5-flash-lite"))
            ))
            .toString()

        val method = AgentOpenRouterClient::class.java.getDeclaredMethod("parseResponse", String::class.java, String::class.java, JSONObject::class.java, Int::class.javaPrimitiveType, Long::class.javaPrimitiveType, String::class.java)
        method.isAccessible = true
        
        // V36: Should NOT throw 429 even if content says rate limit, because HTTP status is 200.
        val response = method.invoke(client, body, "sk-or-test", JSONObject(), 200, 100L, "ex-123")
        assertNotNull(response)
    }

    @Test
    fun withRecoveredInlineSourcesFiltersFabricatedUrls() {
        val rawResponseClass = Class.forName("com.david.openassistant.agent.AgentOpenRouterClient\$RawAgentResponse")
        val constructor = rawResponseClass.getDeclaredConstructors().first { it.parameterCount == 8 }
        constructor.isAccessible = true
        
        val summary = AgentApiSummary()
        val sources = emptyList<AgentSourceCitation>()
        val executions = emptyList<AgentToolExecution>()
        val queryFp = emptyList<String>()
        val rejectedQ = emptyList<RejectedResearchQuery>()
        val verifiedUrls = setOf("https://trusted.com/page1")
        
        // Content contains one verified and one fabricated URL
        val content = "Check out https://trusted.com/page1 and also https://fabricated.com/fake"
        
        val sourceReads = emptyList<SourceRead>()
        val rawResponse = constructor.newInstance(content, summary, sources, executions, queryFp, rejectedQ, verifiedUrls, sourceReads)
        
        val method = AgentOpenRouterClient::class.java.getDeclaredMethod("withRecoveredInlineSources", rawResponseClass, Set::class.java)
        method.isAccessible = true
        
        val result = method.invoke(client, rawResponse, verifiedUrls)
        
        // Get 'sources' property (sources field is private but accessible via reflection)
        val sourcesField = rawResponseClass.getDeclaredField("sources")
        sourcesField.isAccessible = true
        val resultSources = sourcesField.get(result) as List<*>
        
        assertEquals(1, resultSources.size)
        val firstSource = resultSources[0] as AgentSourceCitation
        assertEquals("https://trusted.com/page1", firstSource.url)
    }

    @Test
    fun basePayloadRestrictsToAllowlist() {
        val method = AgentOpenRouterClient::class.java.getDeclaredMethods().first { it.name == "basePayload" }
        method.isAccessible = true
        
        // Signature: modelId, systemPrompt, userPrompt, reasoningEffort, role, selectionReason, freeOnly, goalId, taskId
        val payload = method.invoke(client, "google/gemini", "system", "user", null, null, null, false, null, null) as JSONObject
        
        val models = payload.getJSONArray("models")
        assertEquals("openrouter/auto-beta", payload.getString("model"))
        assertEquals(1, models.length())
        assertEquals("openrouter/free", models.getString(0))
    }

    @Test
    fun validateAndNormalizeGeneratedRequestAcceptsValidMessages() {
        val method = AgentOpenRouterClient::class.java.getDeclaredMethods().first { it.name == "validateAndNormalizeGeneratedRequest" }
        method.isAccessible = true

        val valid = JSONObject()
            .put("model", "openrouter/auto")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hi")))

        val normalized = method.invoke(client, valid) as JSONObject
        assertEquals(1, normalized.getJSONArray("messages").length())
    }
}
