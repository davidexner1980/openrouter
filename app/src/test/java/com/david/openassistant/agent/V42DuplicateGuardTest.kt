package com.david.openassistant.agent

import android.content.SharedPreferences
import com.david.openassistant.data.diagnostics.ResearchMonitor
import com.david.openassistant.data.diagnostics.RuntimeDiagnostics
import com.david.openassistant.data.openrouter.OpenRouterModel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.*
import java.util.concurrent.TimeUnit

class V42DuplicateGuardTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var store: AgentStore
    private lateinit var client: AgentOpenRouterClient
    private lateinit var executor: AgentTaskExecutor
    private lateinit var diagnostics: RuntimeDiagnostics

    @Before
    fun setUp() {
        AgentStore.processSessionIdOverride = "session-1"
        server = MockWebServer()
        server.start()

        val baseDir = tempFolder.newFolder("agent_store_test")
        store = AgentStore(baseDir = baseDir)

        val diagDir = tempFolder.newFolder("diagnostics")
        val monitorDir = tempFolder.newFolder("monitor")
        val cacheDir = tempFolder.newFolder("cache")
        
        val prefs = createFakePrefs()
        val monitor = ResearchMonitor(prefs, monitorDir, cacheDir)
        diagnostics = RuntimeDiagnostics(null, diagDir, monitor)

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val newUrl = request.url.newBuilder()
                    .scheme("http")
                    .host(server.hostName)
                    .port(server.port)
                    .build()
                chain.proceed(request.newBuilder().url(newUrl).build())
            }
            .build()

        client = AgentOpenRouterClient(
            client = okHttpClient,
            store = store,
            diagnostics = diagnostics
        )

        executor = AgentTaskExecutor(
            client = client,
            store = store,
            diagnostics = diagnostics,
            autonomyPolicy = AutonomyPolicy.DEFAULT
        )
    }

    @org.junit.After
    fun tearDown() {
        AgentStore.processSessionIdOverride = null
        server.close()
    }

    private fun createFakePrefs(): SharedPreferences {
        return object : SharedPreferences {
            override fun getAll(): Map<String, *> = emptyMap<String, Any>()
            override fun getString(key: String?, defValue: String?): String? = defValue
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
            override fun getInt(key: String?, defValue: Int): Int = defValue
            override fun getLong(key: String?, defValue: Long): Long = defValue
            override fun getFloat(key: String?, defValue: Float): Float = defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
            override fun contains(key: String?): Boolean = false
            override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }
    }

    @Test
    fun testDuplicateContextSuppression() = runBlocking {
        val goalId = UUID.randomUUID().toString()
        val taskId = "task-1"
        
        val task = AgentTask(
            id = taskId,
            order = 0,
            title = "Test Task",
            instructions = "Instructions",
            capability = AgentCapability.REASON,
            attemptCount = 1
        )
        val goal = AgentGoal(
            id = goalId,
            conversationId = "conv-1",
            userRequest = "Request",
            title = "Goal",
            objective = "Objective",
            finalOutputDescription = "Output",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "model-1",
            executionModelId = "model-1",
            tasks = listOf(task)
        )
        
        store.upsertGoal(goal)
        val acquisition = store.acquireTaskLeaseAtomic(goalId, taskId, "worker-1") as LeaseAcquisitionResult.Acquired
        val ticket = acquisition.ticket as TaskExecutionTicket

        // Ensure baseline cycle is persisted and reloaded
        val freshGoalFromStore = store.updateGoal(goalId) { it }.goals.first { it.id == goalId }
        val freshTaskFromStore = freshGoalFromStore.tasks.first { it.id == taskId }

        val fingerprint = FingerprintUtils.calculateExecutionFingerprint(freshGoalFromStore, freshTaskFromStore)
        val goalWithFingerprint = freshGoalFromStore.copy(
            tasks = freshGoalFromStore.tasks.map { it.copy(lastRequestFingerprint = fingerprint) }
        )
        
        store.upsertGoal(goalWithFingerprint)

        val finalSnapshot = store.loadSnapshot()
        val goalForCall = finalSnapshot.goals.first { it.id == goalId }
        val taskForCall = goalForCall.tasks.first { it.id == taskId }

        val outcome = executor.executeOneTask("api-key", goalForCall, taskForCall, ticket)

        assertEquals(WorkerOutcome.DONE, outcome)
        
        val reSnapshot = store.loadSnapshot()
        val finalGoal = reSnapshot.goals.first { it.id == goalId }
        val finalTask = finalGoal.tasks.first { it.id == taskId }

        assertEquals(1, finalTask.attemptCount)
        assertEquals(AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE, finalTask.status)
        assertEquals(0, server.requestCount)
        assertTrue(finalGoal.events.any { it.message.contains("identical context fingerprint detected") })
    }

    @Test
    fun testAuthorizedRetry() = runBlocking {
        val goalId = UUID.randomUUID().toString()
        val taskId = "task-1"
        
        val task = AgentTask(
            id = taskId,
            order = 0,
            title = "Test Task",
            instructions = "Instructions",
            capability = AgentCapability.REASON,
            attemptCount = 1
        )
        val goal = AgentGoal(
            id = goalId,
            conversationId = "conv-1",
            userRequest = "Request",
            title = "Goal",
            objective = "Objective",
            finalOutputDescription = "Output",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "model-1",
            executionModelId = "model-1",
            tasks = listOf(task)
        )
        
        store.upsertGoal(goal)
        val acquisition = store.acquireTaskLeaseAtomic(goalId, taskId, "worker-1") as LeaseAcquisitionResult.Acquired
        val ticket = acquisition.ticket as TaskExecutionTicket

        // Ensure baseline cycle is persisted and reloaded
        val freshGoalFromStore = store.updateGoal(goalId) { it }.goals.first { it.id == goalId }
        val freshTaskFromStore = freshGoalFromStore.tasks.first { it.id == taskId }

        val fingerprint = FingerprintUtils.calculateExecutionFingerprint(freshGoalFromStore, freshTaskFromStore)
        val goalWithAuth = freshGoalFromStore.copy(
            tasks = freshGoalFromStore.tasks.map { it.copy(
                lastRequestFingerprint = fingerprint,
                retryAuthorizedFingerprint = fingerprint
            ) }
        )
        
        store.upsertGoal(goalWithAuth)

        val finalSnapshot = store.loadSnapshot()
        val goalForCall = finalSnapshot.goals.first { it.id == goalId }
        val taskForCall = goalForCall.tasks.first { it.id == taskId }

        val successJson = """
            {
              "id": "gen-123",
              "choices": [
                {
                  "message": {
                    "content": "```json\n{\n  \"work_product\": \"Test result\",\n  \"completion_score\": 1.0,\n  \"claims\": [],\n  \"acceptance_checks\": [],\n  \"unresolved_questions\": []\n}\n```"
                  }
                }
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse.Builder().body(successJson).build())

        val outcome = executor.executeOneTask("api-key", goalForCall, taskForCall, ticket)

        val reSnapshot = store.loadSnapshot()
        val finalGoalActual = reSnapshot.goals.first { it.id == goalId }

        assertEquals("Outcome should be CONTINUE. Goal error: ${finalGoalActual.error}, Events: ${finalGoalActual.events.joinToString { it.message }}", WorkerOutcome.CONTINUE, outcome)
        assertEquals(1, server.requestCount)
        
        val finalTask = finalGoalActual.tasks.first { it.id == taskId }

        assertEquals(2, finalTask.attemptCount)
        assertNull(finalTask.retryAuthorizedFingerprint)
    }

    @Test
    fun testMaterialChangeProducesNewFingerprint() {
        val task = AgentTask(id = "t1", order = 0, title = "T", instructions = "I", capability = AgentCapability.REASON)
        val goal = AgentGoal(id = "g1", conversationId = "c1", userRequest = "R", title = "G", objective = "O", finalOutputDescription = "D", status = AgentGoalStatus.RUNNING, plannerModelId = "m", executionModelId = "m", tasks = listOf(task))
        
        val fp1 = FingerprintUtils.calculateExecutionFingerprint(goal, task)
        
        val task2 = task.copy(instructions = "New Instructions")
        val goal2 = goal.copy(tasks = listOf(task2))
        val fp2 = FingerprintUtils.calculateExecutionFingerprint(goal2, task2)
        
        assertNotEquals(fp1, fp2)
    }

    @Test
    fun testCosmeticChangePreservesFingerprint() {
        val task = AgentTask(id = "t1", order = 0, title = "T", instructions = "Instructions  with  extra  space", capability = AgentCapability.REASON)
        val goal = AgentGoal(id = "g1", conversationId = "c1", userRequest = "R", title = "G", objective = "O", finalOutputDescription = "D", status = AgentGoalStatus.RUNNING, plannerModelId = "m", executionModelId = "m", tasks = listOf(task))
        
        val fp1 = FingerprintUtils.calculateExecutionFingerprint(goal, task)
        
        val task2 = task.copy(instructions = "Instructions with extra space")
        val goal2 = goal.copy(tasks = listOf(task2))
        val fp2 = FingerprintUtils.calculateExecutionFingerprint(goal2, task2)
        
        assertEquals(fp1, fp2)
    }
}
