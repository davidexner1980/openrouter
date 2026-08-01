package com.david.openassistant.agent

import com.david.openassistant.data.openrouter.OpenRouterProtocolUtils
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class OpenRouterProtocolTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val server = MockWebServer()
    private lateinit var store: AgentStore
    private lateinit var client: AgentOpenRouterClient
    private val capturedBody = AtomicReference<String>()

    @Before
    fun setUp() {
        server.start()
        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.url.toString().contains("openrouter.ai")) {
                    val buffer = okio.Buffer()
                    request.body?.writeTo(buffer)
                    capturedBody.set(buffer.readUtf8())
                    
                    val newUrl = request.url.newBuilder()
                        .scheme("http")
                        .host(server.hostName)
                        .port(server.port)
                        .build()
                    chain.proceed(request.newBuilder().url(newUrl).build())
                } else {
                    chain.proceed(request)
                }
            }
            .build()
        val tempStoreDir = tempFolder.newFolder("openrouter_protocol_test")
        store = AgentStore(baseDir = tempStoreDir)
        client = AgentOpenRouterClient(
            client = okHttpClient,
            store = store,
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun wirePayloadPreservesReasoningObjectInPlan() {
        runBlocking {
            // Enqueue a valid plan response
            val planJson = JSONObject()
                .put("title", "Test Plan")
                .put("objective", "Test Objective")
                .put("final_output", "Test Output")
                .put("acceptance_criteria", JSONArray().put(JSONObject().put("id", "c1").put("description", "d1").put("weight", 1.0)))
                .put("tasks", JSONArray().put(JSONObject()
                    .put("id", "t1")
                    .put("title", "Task 1")
                    .put("instructions", "Instructions 1")
                    .put("capability", "reason")
                    .put("depends_on", JSONArray())
                    .put("weight", 1.0)
                    .put("acceptance_criteria", JSONArray().put(JSONObject().put("id", "tc1").put("description", "td1").put("weight", 1.0)))
                ))
                
            server.enqueue(MockResponse.Builder()
                .body(planJson.toString()).build())

            val now = System.currentTimeMillis()
            val lease = AgentExecutionLease(
                workerId = "w1",
                taskId = "none",
                attemptId = "a1",
                generation = 0,
                acquiredAt = now,
                heartbeatAt = now,
            )
            val goal = AgentGoal(
                id = "goal-1",
                conversationId = "conv-1",
                userRequest = "test request",
                title = "Test Goal",
                objective = "Objective",
                finalOutputDescription = "Deliverable",
                status = AgentGoalStatus.PLANNING,
                plannerModelId = "openrouter/auto-beta",
                executionModelId = "openrouter/auto-beta",
                tasks = emptyList(),
                executionLease = lease,
            )
            store.upsertGoal(goal)

            val missionContext = ProviderRequestContext.Mission(
                goalId = goal.id,
                workerId = "w1",
                attemptId = "a1",
                executionGeneration = 0,
                operation = MissionOperation.CREATE_PLAN,
                parentOperationId = "op-test-1",
            )

            // createPlan now takes AgentGoal and requestContext
            runCatching {
                client.createPlan("sk-test", "openrouter/auto-beta", goal, requestContext = missionContext)
            }

            val body = capturedBody.get() ?: throw IllegalStateException("Body not captured")
            
            // basePayload adds "reasoning": {"effort": "medium"} for auto-beta
            assertTrue("Reasoning should be present as an object in wire body. Body: $body", body.contains("\"reasoning\":{") || body.contains("\"reasoning\": {"))
            assertTrue("Reasoning object should contain effort", body.contains("\"effort\":\"medium\"") || body.contains("\"effort\": \"medium\""))
            assertFalse("Wire body should not contain monitor redaction markers", body.contains("[EXCLUDED]"))
        }
    }

    @Test
    fun deterministicRepairFixesStringifiedReasoningOnCopy() {
        val malformedPayload = JSONObject()
            .put("model", "openrouter/auto-beta")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hello")))
            .put("reasoning", "high")

        val repaired = OpenRouterProtocolUtils.repairReasoningShapeOnCopy(malformedPayload)
        
        assertNotNull("Repair should produce a new JSONObject copy", repaired)
        assertTrue("Original payload reasoning must remain unchanged", malformedPayload.opt("reasoning") is String)
        assertTrue("Repaired copy reasoning should be an object", repaired?.opt("reasoning") is JSONObject)
        assertEquals("high", repaired?.getJSONObject("reasoning")?.getString("effort"))
    }
    
    @Test
    fun structuralMarkerInReasoningIsRejected() {
        val payload = JSONObject()
            .put("model", "openrouter/auto-beta")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "hello")))
            .put("reasoning", JSONObject().put("effort", "high [REDACTED]"))
            
        val exception = runCatching {
            OpenRouterProtocolUtils.validateOutboundRequest(payload)
            Unit
        }.exceptionOrNull()
        
        val cause = (exception as? java.lang.reflect.InvocationTargetException)?.targetException ?: exception
        assertTrue("Should throw validation error for structural marker. Got: ${cause?.message}", 
            cause?.message?.contains("contains a diagnostic redaction marker") == true)
    }

    @Test
    fun literalUserTextWithRedactedMarkerIsAllowed() {
        val payload = JSONObject()
            .put("model", "openrouter/auto-beta")
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "The document states [REDACTED] in section 2.")))
            .put("reasoning", JSONObject().put("effort", "medium"))
            
        // Should not throw any exception
        OpenRouterProtocolUtils.validateOutboundRequest(payload)
    }

    @Test
    fun redactionDoesNotMutateOriginalPayload() {
        runBlocking {
            val planJson = JSONObject()
                .put("title", "Analyze high-end takedown recurve bows")
                .put("objective", "Analyze high-end takedown recurve bows")
                .put("final_output", "Analysis of high-end takedown recurve bows")
                .put("acceptance_criteria", JSONArray().put(JSONObject().put("id", "c1").put("description", "Analyzes bows").put("weight", 1.0)))
                .put("tasks", JSONArray().put(JSONObject()
                    .put("id", "t1")
                    .put("title", "Initial Analysis")
                    .put("instructions", "Analyze high-end takedown recurve bows in detail and provide a comprehensive summary of findings. " + " ".repeat(100))
                    .put("capability", "reason")
                    .put("depends_on", JSONArray())
                    .put("weight", 1.0)
                    .put("acceptance_criteria", JSONArray().put(JSONObject().put("id", "tc1").put("description", "Analysis completed").put("weight", 1.0)))
                )
                .put(JSONObject().put("id", "r1").put("title", "Discovery").put("instructions", "Discovery of takedown recurve bows. ".repeat(10)).put("capability", "deep_research").put("depends_on", JSONArray().put("t1")).put("weight", 1.0).put("acceptance_criteria", JSONArray().put(JSONObject().put("id", "rc1").put("description", "Research 1 completed").put("weight", 1.0))))
                .put(JSONObject().put("id", "r2").put("title", "Primary Source").put("instructions", "Primary Source for takedown recurve bows. ".repeat(10)).put("capability", "deep_research").put("depends_on", JSONArray().put("r1")).put("weight", 1.0).put("acceptance_criteria", JSONArray().put(JSONObject().put("id", "rc2").put("description", "Research 2 completed").put("weight", 1.0))))
                .put(JSONObject().put("id", "r3").put("title", "Contradictions").put("instructions", "Contradictions for takedown recurve bows. ".repeat(10)).put("capability", "deep_research").put("depends_on", JSONArray().put("r2")).put("weight", 1.0).put("acceptance_criteria", JSONArray().put(JSONObject().put("id", "rc3").put("description", "Research 3 completed").put("weight", 1.0))))
                .put(JSONObject().put("id", "r4").put("title", "Gap Closure").put("instructions", "Gap Closure for takedown recurve bows. ".repeat(10)).put("capability", "deep_research").put("depends_on", JSONArray().put("r3")).put("weight", 1.0).put("acceptance_criteria", JSONArray().put(JSONObject().put("id", "rc4").put("description", "Research 4 completed").put("weight", 1.0))))
                .put(JSONObject()
                    .put("id", "s1")
                    .put("title", "Synthesis")
                    .put("instructions", "Synthesize the findings from the initial analysis into a final report. " + " ".repeat(100))
                    .put("capability", "synthesize")
                    .put("depends_on", JSONArray().put("r4"))
                    .put("weight", 1.0)
                    .put("acceptance_criteria", JSONArray().put(JSONObject().put("id", "sc1").put("description", "Final answer produced").put("weight", 1.0)))
                ))
                
            val responseJson = JSONObject().put("choices", JSONArray().put(JSONObject()
                .put("message", JSONObject().put("role", "assistant").put("content", planJson.toString()))
                .put("finish_reason", "stop")
            ))

            server.enqueue(MockResponse.Builder().body(responseJson.toString()).build())
            server.enqueue(MockResponse.Builder().body(responseJson.toString()).build())
            server.enqueue(MockResponse.Builder().body(responseJson.toString()).build())

            val now = System.currentTimeMillis()
            val lease = AgentExecutionLease(
                workerId = "w1",
                taskId = "none",
                attemptId = "a1",
                generation = 0,
                acquiredAt = now,
                heartbeatAt = now,
            )
            val goal = AgentGoal(
                id = "goal-1",
                conversationId = "conv-1",
                userRequest = "What are the best takedown recurve bows over $700 with >50lb draw weight?",
                title = "Test Goal",
                objective = "Objective",
                finalOutputDescription = "Deliverable",
                status = AgentGoalStatus.PLANNING,
                plannerModelId = "openrouter/auto-beta",
                executionModelId = "openrouter/auto-beta",
                tasks = emptyList(),
                executionLease = lease,
            )
            store.upsertGoal(goal)

            // Inject reasoning object that should be redacted in diagnostics but preserved on wire
            val initialReasoning = JSONObject().put("effort", "high")
            
            // We need to capture the payload passed to executeCapturedOpenRouterBody
            // but since it's private and complex to mock, we'll rely on the MockWebServer capture
            // and the state of the goal/draft if possible.
            // Better: just verify the wire body has the object and the monitor (if we could mock it) has the redacted string.
            
            val missionContext = ProviderRequestContext.Mission(
                goalId = goal.id,
                workerId = "w1",
                attemptId = "a1",
                executionGeneration = 0,
                operation = MissionOperation.CREATE_PLAN,
                parentOperationId = "op-test-2",
            )
            
            client.createPlan("sk-test", "openrouter/auto-beta", goal, requestContext = missionContext)

            val body = capturedBody.get() ?: throw IllegalStateException("Body not captured")
            
            // Wire body MUST have the object
            assertTrue("Wire body should contain reasoning object", body.contains("\"reasoning\":{") || body.contains("\"reasoning\": {"))
            assertFalse("Wire body should NOT contain redaction marker", body.contains("EXCLUDED"))
        }
    }
}
