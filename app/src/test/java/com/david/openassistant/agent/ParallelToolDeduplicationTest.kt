package com.david.openassistant.agent

import com.david.openassistant.data.diagnostics.RuntimeDiagnostics
import com.david.openassistant.domain.tools.AutonomousToolRuntime
import com.david.openassistant.domain.tools.OpenRouterToolCall
import com.david.openassistant.domain.tools.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ParallelToolDeduplicationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: AgentStore
    private lateinit var diagnostics: RuntimeDiagnostics
    private lateinit var goalId: String
    private lateinit var taskId: String

    @Before
    fun setup() {
        val baseDir = tempFolder.newFolder("agent_store_test")
        store = AgentStore(baseDir = baseDir)
        val diagDir = tempFolder.newFolder("diagnostics")
        diagnostics = RuntimeDiagnostics(null, diagDir, null)
        goalId = "goal-${UUID.randomUUID()}"
        taskId = "task-${UUID.randomUUID()}"
    }

    private class MockRuntime : AutonomousToolRuntime(null, null, null, null, null, null, null, null, null, null, null, null) {
        val callCount = AtomicInteger(0)
        override suspend fun execute(call: OpenRouterToolCall, apiKey: String?, modelId: String?, goal: AgentGoal?): ToolExecutionResult {
            if (call.name == "public_web_fetch") {
                callCount.incrementAndGet()
            }
            // Small delay to trigger race
            kotlinx.coroutines.delay(100)
            return ToolExecutionResult(
                outputJson = JSONObject().put("url", "https://example.com/duplicate").put("content", "Content").toString(),
                displaySummary = "Fetched duplicate"
            )
        }
    }

    private class DeduplicationTestClient(
        runtime: AutonomousToolRuntime,
        store: AgentStore,
        diagnostics: RuntimeDiagnostics,
        val responses: List<JSONObject>
    ) : AgentOpenRouterClient(
        toolRuntime = runtime,
        autonomyPolicy = AutonomyPolicy(),
        client = OkHttpClient(),
        researchMonitor = null,
        diagnostics = diagnostics,
        store = store
    ) {
        var responseIndex = 0
        override suspend fun executeRawJsonRequest(
            apiKey: String,
            payload: JSONObject,
            generation: Int,
            requestContext: ProviderRequestContext.Mission
        ): JSONObject {
            return responses[responseIndex++]
        }
    }

    @Test
    fun testIntraRoundParallelDeduplication() = runBlocking(kotlinx.coroutines.Dispatchers.Default) {
        val url = "https://example.com/duplicate"
        val mockRuntime = MockRuntime()

        val toolCall = JSONObject()
            .put("id", "call-1")
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "public_web_fetch")
                .put("arguments", JSONObject().put("url", url).toString())
            )

        val response1 = JSONObject()
            .put("id", "gen-1")
            .put("choices", JSONArray().put(JSONObject()
                .put("finish_reason", "tool_calls")
                .put("message", JSONObject()
                    .put("role", "assistant")
                    .put("tool_calls", JSONArray().apply {
                        for (i in 1..10) {
                            put(JSONObject(toolCall.toString()).put("id", "call-$i"))
                        }
                    })
                )
            ))
            .put("usage", JSONObject().put("total_tokens", 10))

        val response2 = JSONObject()
            .put("id", "gen-2")
            .put("choices", JSONArray().put(JSONObject()
                .put("finish_reason", "stop")
                .put("message", JSONObject()
                    .put("role", "assistant")
                    .put("content", "Done")
                )
            ))
            .put("usage", JSONObject().put("total_tokens", 20))

        val client = DeduplicationTestClient(mockRuntime, store, diagnostics, listOf(response1, response2))

        val requestContext = ProviderRequestContext.Mission(
            goalId = goalId,
            workerId = "worker-1",
            taskId = taskId,
            attemptId = "attempt-1",
            executionGeneration = 1,
            acquiredAt = System.currentTimeMillis(),
            operation = MissionOperation.EXECUTE_TASK,
            parentOperationId = "op-1"
        )

        val priorOutputs = ConcurrentHashMap<String, String>()
        val payload = JSONObject().put("model", "test-model").put("messages", JSONArray())
        
        client.executeToolAwareJsonRequest(
            apiKey = "key",
            originalPayload = payload,
            generation = 1,
            onProgress = { _: AgentSourceCitation -> },
            requestContext = requestContext,
            goal = null,
            maxAttempts = 3,
            priorOutputsBySignature = priorOutputs
        )

        // ASSERTION: Only 1 external call to the tool runtime for 2 identical requests in the same round
        assertEquals("Should only execute tool runtime once for identical parallel calls", 1, mockRuntime.callCount.get())
    }
}
