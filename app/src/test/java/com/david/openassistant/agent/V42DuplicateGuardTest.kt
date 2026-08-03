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
        
        val fingerprint = FingerprintUtils.calculateExecutionFingerprint(goal, task)
        val goalWithFingerprint = goal.copy(
            tasks = goal.tasks.map { it.copy(lastRequestFingerprint = fingerprint) }
        )
        
        store.upsertGoal(goalWithFingerprint)

        val acquisition = store.acquireTaskLeaseAtomic(
            goalId = goalId,
            workerId = "worker-1",
            taskId = taskId
        )
        
        val ticket = when (acquisition) {
            is LeaseAcquisitionResult.Acquired -> acquisition.ticket as TaskExecutionTicket
            is LeaseAcquisitionResult.OrphanReclaimed -> acquisition.ticket as TaskExecutionTicket
            else -> fail("Expected task lease acquisition, got $acquisition")
        } as TaskExecutionTicket

        val freshGoalSnapshot = store.loadSnapshot().goals.first { it.id == goalId }
        val freshTaskSnapshot = freshGoalSnapshot.tasks.first { it.id == taskId }

        val outcome = executor.executeOneTask("api-key", freshGoalSnapshot, freshTaskSnapshot, ticket)

        assertEquals(WorkerOutcome.CONTINUE, outcome)
        
        val freshGoal = store.loadSnapshot().goals.first { it.id == goalId }
        val freshTask = freshGoal.tasks.first { it.id == taskId }

        assertEquals(0, freshGoal.attempts.size) 
        assertEquals(1, freshTask.attemptCount)
        assertEquals(0, freshTask.lifetimeAttemptCount)
        assertEquals(AgentGoalStatus.RECOVERING, freshGoal.status)
        assertEquals(0, server.requestCount)
        assertTrue(freshGoal.events.any { it.message.contains("Identical context detected") })
        assertNotNull(freshGoal.activeRecoveryPlanId)
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
        
        val fingerprint = FingerprintUtils.calculateExecutionFingerprint(goal, task)
        val goalWithAuth = goal.copy(
            tasks = goal.tasks.map { it.copy(
                lastRequestFingerprint = fingerprint,
                retryAuthorizedFingerprint = fingerprint
            ) }
        )
        
        store.upsertGoal(goalWithAuth)

        val acquisition = store.acquireTaskLeaseAtomic(
            goalId = goalId,
            workerId = "worker-1",
            taskId = taskId
        )
        
        val ticket = when (acquisition) {
            is LeaseAcquisitionResult.Acquired -> acquisition.ticket as TaskExecutionTicket
            is LeaseAcquisitionResult.OrphanReclaimed -> acquisition.ticket as TaskExecutionTicket
            else -> fail("Expected task lease acquisition, got $acquisition")
        } as TaskExecutionTicket

        val freshGoalSnapshot = store.loadSnapshot().goals.first { it.id == goalId }
        val freshTaskSnapshot = freshGoalSnapshot.tasks.first { it.id == taskId }

        val successJson = """
            {
              "id": "gen-123",
              "choices": [
                {
                  "message": {
                    "content": "{\"work_product\": \"Test result\", \"completion_score\": 1.0, \"claims\": [], \"acceptance_checks\": [], \"unresolved_questions\": []}"
                  }
                }
              ]
            }
        """.trimIndent()
        server.enqueue(MockResponse.Builder().code(200).body(successJson).build())

        executor.executeOneTask("api-key", freshGoalSnapshot, freshTaskSnapshot, ticket)

        assertEquals(1, server.requestCount)
        
        val freshGoal = store.loadSnapshot().goals.first { it.id == goalId }
        val freshTask = freshGoal.tasks.first { it.id == taskId }

        assertEquals(1, freshGoal.attempts.size)
        assertEquals(2, freshTask.attemptCount)
        assertNull(freshTask.retryAuthorizedFingerprint)
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

    @Test
    fun testUnauthorizedRetryWithMismatchedFingerprintRejectsExecution() = runBlocking {
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
        
        val correctFingerprint = FingerprintUtils.calculateExecutionFingerprint(goal, task)
        val goalWithMismatchAuth = goal.copy(
            tasks = goal.tasks.map { it.copy(
                lastRequestFingerprint = correctFingerprint,
                retryAuthorizedFingerprint = "mismatched-fp"
            ) }
        )
        
        store.upsertGoal(goalWithMismatchAuth)

        val acquisition = store.acquireTaskLeaseAtomic(
            goalId = goalId,
            workerId = "worker-1",
            taskId = taskId
        )
        val ticket = (acquisition as LeaseAcquisitionResult.Acquired).ticket as TaskExecutionTicket

        val freshGoalSnapshot = store.loadSnapshot().goals.first { it.id == goalId }
        val freshTaskSnapshot = freshGoalSnapshot.tasks.first { it.id == taskId }

        val outcome = executor.executeOneTask("api-key", freshGoalSnapshot, freshTaskSnapshot, ticket)

        // Should be suppressed and return CONTINUE
        assertEquals(WorkerOutcome.CONTINUE, outcome)
        assertEquals(0, server.requestCount)
        
        val finalGoal = store.loadSnapshot().goals.first { it.id == goalId }
        assertEquals(AgentGoalStatus.RECOVERING, finalGoal.status)
        assertNotNull(finalGoal.activeRecoveryPlanId)
    }
}
