package com.david.openassistant.agent

import android.content.SharedPreferences
import com.david.openassistant.data.diagnostics.ResearchMonitor
import com.david.openassistant.data.diagnostics.RuntimeDiagnostics
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Proxy

/**
 * Verifies that the provider request ledger correctly reconciles and retrieves
 * durable response content across process restarts or aborted domain commits.
 */
class DurableContentReconciliationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var store: AgentStore
    private lateinit var client: AgentOpenRouterClient
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var diagnostics: RuntimeDiagnostics

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()

        val baseDir = tempFolder.newFolder("agent_store_test")
        store = AgentStore(baseDir = baseDir)

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .writeTimeout(500, TimeUnit.MILLISECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                if (request.url.toString().contains("openrouter.ai")) {
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

        val diagDir = tempFolder.newFolder("diagnostics")
        val monitorDir = tempFolder.newFolder("monitor")

        val monitor = ResearchMonitor(createFakePrefs(), monitorDir, tempFolder.newFolder("cache"))
        diagnostics = RuntimeDiagnostics(null, diagDir, monitor)

        client = AgentOpenRouterClient(
            client = okHttpClient,
            store = store,
            diagnostics = diagnostics
        )
    }

    @After
    fun tearDown() {
        try { server.close() } catch (e: Exception) {}
    }

    @Test
    fun testReconcilesDurableContentAfterProcessRestart() = runBlocking {
        val goalId = UUID.randomUUID().toString()
        val logicalRequestId = "test-logic-id"
        val taskId = "task-1"
        val workerId = "worker-1"

        val task = AgentTask(id = taskId, order = 0, title = "T", instructions = "I", capability = AgentCapability.REASON)
        val initialGoal = AgentGoal(
            id = goalId,
            conversationId = "c1",
            userRequest = "R",
            title = "T",
            objective = "O",
            finalOutputDescription = "D",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "m1",
            executionModelId = "openrouter/auto-beta",
            tasks = listOf(task)
        )
        store.saveSnapshot(AgentSnapshot(goals = listOf(initialGoal)))

        // V43: Must acquire lease to have a valid ticket
        val acquisition = store.acquireTaskLeaseAtomic(goalId, workerId, taskId)
        assertTrue(acquisition is LeaseAcquisitionResult.Acquired)
        val ticket = (acquisition as LeaseAcquisitionResult.Acquired).ticket

        val context = ProviderRequestContext.Mission(
            goalId = goalId,
            workerId = workerId,
            taskId = taskId,
            attemptId = ticket.attemptId,
            executionGeneration = ticket.executionGeneration,
            leaseGeneration = ticket.leaseGeneration,
            acquiredAt = ticket.acquiredAt,
            role = AgentTaskRole.PRIMARY_REASONING,
            operation = MissionOperation.EXECUTE_TASK,
            parentOperationId = "parent-op",
            logicalRequestId = logicalRequestId
        )

        val expectedContent = "{\"work_product\": \"Success result\", \"completion_score\": 1.0, \"acceptance_checks\": [], \"claims\": [{\"id\":\"c1\", \"text\":\"Fact\", \"type\":\"fact\", \"confidence\":1.0, \"supporting_evidence_ids\":[], \"source_urls\":[]}], \"unresolved_questions\": []}"
        val providerResponseBody = JSONObject()
            .put("id", "res-1")
            .put("model", "meta-llama/llama-3-70b-instruct")
            .put("choices", org.json.JSONArray().put(
                JSONObject().put("message", JSONObject().put("content", expectedContent))
            ))
            .put("usage", JSONObject().put("total_tokens", 100))
            .toString()

        server.enqueue(MockResponse(body = providerResponseBody))

        // 1. Initial execution: receives and persists response in ledger
        val requestPayload = JSONObject()
            .put("model", "openrouter/auto-beta")
            .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", "Hello")))

        val attribution = ProviderResponseAttribution(AgentTaskRole.PRIMARY_REASONING, "test")
        val response1 = client.executeRawJsonRequest("key", requestPayload, attribution, ticket.leaseGeneration, context)
        assertNotNull(response1)

        // Verify it was persisted
        val expectedLogicalId = "$logicalRequestId-test"
        val snapshot = store.loadSnapshot().goals.first { it.id == goalId }
        val attempt = snapshot.requestAttempts.first { it.logicalRequestId == expectedLogicalId }
        assertEquals(ExchangeOutcome.RESPONSE_SUCCESS, attempt.exchangeOutcome)
        assertEquals(providerResponseBody, attempt.reconciledResponseContent)

        // 2. Simulate restart: call again with same logical ID
        // The server SHOULD NOT be called again
        val response2 = client.executeRawJsonRequest("key", requestPayload, attribution, ticket.leaseGeneration, context)

        // It should have returned the same body from the store
        assertEquals(providerResponseBody, response2.toString())
        assertEquals(1, server.requestCount)
    }

    private fun createFakePrefs(): SharedPreferences {
        val map = mutableMapOf<String, Any?>()
        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString" -> map[args[0] as String] ?: args[1]
                "getBoolean" -> map[args[0] as String] ?: args[1]
                "getLong" -> map[args[0] as String] ?: args[1]
                "getInt" -> map[args[0] as String] ?: args[1]
                "edit" -> createFakeEditor(map)
                "registerOnSharedPreferenceChangeListener" -> Unit
                "unregisterOnSharedPreferenceChangeListener" -> Unit
                else -> null
            }
        } as SharedPreferences
    }

    private fun createFakeEditor(map: MutableMap<String, Any?>): SharedPreferences.Editor {
        val tempMap = mutableMapOf<String, Any?>()
        return Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { _, method, args ->
            when (method.name) {
                "putString" -> { tempMap[args[0] as String] = args[1]; null }
                "putBoolean" -> { tempMap[args[0] as String] = args[1]; null }
                "putLong" -> { tempMap[args[0] as String] = args[1]; null }
                "putInt" -> { tempMap[args[0] as String] = args[1]; null }
                "remove" -> { tempMap.remove(args[0] as String); null }
                "clear" -> { tempMap.clear(); null }
                "commit", "apply" -> { map.putAll(tempMap); true }
                else -> null
            }
        } as SharedPreferences.Editor
    }
}
