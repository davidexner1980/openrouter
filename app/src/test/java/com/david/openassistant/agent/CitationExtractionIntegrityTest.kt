package com.david.openassistant.agent

import com.david.openassistant.data.openrouter.OpenRouterException
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

class CitationExtractionIntegrityTest {

    @Test
    fun executeToolAwareJsonRequestFiltersHallucinatedCitationsFromText() {
        // Goal has one verified source read
        val verifiedUrl = "https://trusted.com/real"
        val goal = AgentGoal(
            conversationId = "conv-1",
            userRequest = "Test",
            title = "Test",
            objective = "Test",
            finalOutputDescription = "Test",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "model-1",
            executionModelId = "model-1",
            tasks = emptyList(),
            evidence = listOf(
                AgentEvidence(
                    kind = AgentEvidenceKind.WEB_RESEARCH,
                    title = "Real source",
                    summary = "Real summary",
                    content = "Real content",
                    sources = listOf(AgentSourceCitation("Real", verifiedUrl))
                )
            ),
            sourceReads = listOf(
                SourceRead(
                    id = "read-1",
                    url = verifiedUrl,
                    canonicalUrl = ResearchQualityGate.canonicalSourceUrl(verifiedUrl),
                    httpCode = 200,
                    contentType = "text/html",
                    content = "Real content",
                    sourceRole = "PRIMARY",
                    authorityScore = 100,
                    provenance = SourceReadProvenance.VERIFIED_FETCH,
                    readAt = 0L
                )
            )
        )

        // Mock model response with both verified and hallucinated citations in text
        val hallucinatedUrl = "https://hallucinated.com/fake"
        val responseBody = JSONObject()
            .put("id", "gen-1")
            .put("model", "model-1")
            .put("choices", JSONArray().put(
                JSONObject().put("message", JSONObject()
                    .put("role", "assistant")
                    .put("content", "I found [Real]($verifiedUrl) and also [Fake]($hallucinatedUrl)")
                )
            ))
            .put("usage", JSONObject().put("total_tokens", 100))

        val requestContext = ProviderRequestContext.Mission(
            goalId = goal.id,
            workerId = "worker-1",
            taskId = "task-1",
            attemptId = "attempt-1",
            executionGeneration = 1,
            acquiredAt = System.currentTimeMillis(),
            operation = MissionOperation.EXECUTE_TASK,
            parentOperationId = "parent-1"
        )

        // Subclass to mock executeRawJsonRequest
        val testClient = object : AgentOpenRouterClient(
            autonomyPolicy = AutonomyPolicy.DEFAULT,
            client = OkHttpClient(),
            store = AgentStore(java.io.File("build/test-store")), // dummy store
            toolRuntime = object : com.david.openassistant.domain.tools.AutonomousToolRuntime(
                null, null, null, null, null, null, null, null, null, null, null, null
            ) {
                override fun isNetworkAvailable(): Boolean = true
                override fun isPublicWebConfigured(): Boolean = true
            }
        ) {
            override suspend fun executeRawJsonRequest(
                apiKey: String,
                payload: JSONObject,
                attribution: ProviderResponseAttribution,
                generation: Int,
                requestContext: ProviderRequestContext.Mission,
                wireVariantKind: ProviderWireVariantKind,
                wireVariantOrdinal: Int
            ): JSONObject = responseBody
        }
        
        val priorOutputs = ConcurrentHashMap<String, String>()
        
        // We need to run this in a coroutine
        val result = kotlinx.coroutines.runBlocking {
            testClient.executeToolAwareJsonRequest(
                apiKey = "sk-or-test",
                originalPayload = JSONObject().put("model", "model-1").put("messages", JSONArray()),
                attribution = ProviderResponseAttribution(AgentTaskRole.PRIMARY_REASONING, "test"),
                generation = 1,
                requestContext = requestContext,
                goal = goal,
                priorOutputsBySignature = priorOutputs
            )
        }
        
        val sources = result.sources
        
        // REPAIR ASSERTION:
        val containsHallucinated = sources.any { it.url == hallucinatedUrl }
        assertFalse("Hallucinated citations from text MUST be filtered", containsHallucinated)
        
        assertEquals("Should only contain the verified source", 1, sources.size)
        assertEquals(verifiedUrl, sources[0].url)
    }

    @Test
    fun withRecoveredInlineSourcesFiltersFabricatedUrls() {
        val rawResponseClass = Class.forName("com.david.openassistant.agent.AgentOpenRouterClient\$RawAgentResponse")
        val constructor = rawResponseClass.getDeclaredConstructors().first { it.parameterCount == 10 }
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
        val rawResponse = constructor.newInstance(content, summary, sources, executions, queryFp, rejectedQ, verifiedUrls, sourceReads, null, null)
        
        val client = AgentOpenRouterClient(
            autonomyPolicy = AutonomyPolicy.DEFAULT,
            client = OkHttpClient()
        )
        val method = AgentOpenRouterClient::class.java.getDeclaredMethod("withRecoveredInlineSources", rawResponseClass, Set::class.java)
        method.isAccessible = true
        
        val result = method.invoke(client, rawResponse, verifiedUrls)
        
        val sourcesField = rawResponseClass.getDeclaredField("sources")
        sourcesField.isAccessible = true
        val resultSources = sourcesField.get(result) as List<*>
        
        assertEquals(1, resultSources.size)
        val firstSource = resultSources[0] as AgentSourceCitation
        assertEquals("https://trusted.com/page1", firstSource.url)
    }

    @Test
    fun recoverHttpsSourceCitationsHandlesMalformedMarkdownWithSpaces() {
        val text = "Check this [Space] (https://example.com/a) and this [Normal](https://example.com/b)"
        
        // This method is internal top-level in ResearchSourceRecovery.kt, but we can access it via its name
        // Wait, it's not a class member. It's a package-level function.
        // In Kotlin, these are compiled into a class like ResearchSourceRecoveryKt.
        val className = "com.david.openassistant.agent.ResearchSourceRecoveryKt"
        val clazz = Class.forName(className)
        val method = clazz.getDeclaredMethod("recoverHttpsSourceCitations", Array<String>::class.java)
        method.isAccessible = true
        
        @Suppress("UNCHECKED_CAST")
        val result = method.invoke(null, arrayOf(text)) as List<AgentSourceCitation>
        
        // Current implementation: MARKDOWN_LINK_PATTERN is Regex("""\[([^\]\r\n]{1,240})]\((https://[^\s)]+)\)""")
        // It won't match "[Space] (https://...)" because of the space.
        // But HTTPS_URL_PATTERN should catch the bare URL.
        
        assertTrue("Should find both URLs", result.any { it.url == "https://example.com/a" })
        assertTrue("Should find both URLs", result.any { it.url == "https://example.com/b" })
        
        val spaceCitation = result.find { it.url == "https://example.com/a" }!!
        // Fixed implementation: MARKDOWN_LINK_PATTERN now allows optional space
        assertEquals("Space", spaceCitation.title)
    }
}
