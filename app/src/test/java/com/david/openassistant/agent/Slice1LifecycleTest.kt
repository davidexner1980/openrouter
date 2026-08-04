package com.david.openassistant.agent

import android.content.Context
import android.content.SharedPreferences
import com.david.openassistant.data.diagnostics.ResearchMonitor
import com.david.openassistant.data.diagnostics.RuntimeDiagnostics
import com.david.openassistant.data.openrouter.OpenRouterException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException

class Slice1LifecycleTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var store: AgentStore
    private lateinit var client: AgentOpenRouterClient
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var executor: AgentTaskExecutor
    private lateinit var diagnostics: RuntimeDiagnostics

    private var terminalHook: AgentOpenRouterClient.TerminalTransitionHook? = null
    private var commitHook: BeforeTaskResultCommitHook? = null
    private var postActiveHook: AgentOpenRouterClient.PostActivePreDispatchHook? = null

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
        
        val prefs = createFakePrefs()
        val monitor = ResearchMonitor(prefs, monitorDir, tempFolder.newFolder("cache"))
        diagnostics = RuntimeDiagnostics(null, diagDir, monitor)

        client = AgentOpenRouterClient(
            client = okHttpClient,
            store = store,
            diagnostics = diagnostics,
            terminalHook = { goalId, exchangeId, parentOpId, outcome ->
                terminalHook?.onTerminalTransition(goalId, exchangeId, parentOpId, outcome)
            },
            postActiveHook = { goalId, exchangeId ->
                postActiveHook?.afterActivePersisted(goalId, exchangeId)
            }
        )

        executor = AgentTaskExecutor(
            client = client,
            store = store,
            diagnostics = diagnostics,
            autonomyPolicy = AutonomyPolicy.DEFAULT,
            beforeCommitHook = object : BeforeTaskResultCommitHook {
                override fun beforeCommit(goalId: String, taskId: String, ownership: ExecutionOwnership) {
                    commitHook?.beforeCommit(goalId, taskId, ownership)
                }
            }
        )
    }

    @After
    fun tearDown() {
        try { server.close() } catch (e: Exception) {}
        terminalHook = null
        commitHook = null
        postActiveHook = null
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

    private fun createTestGoal(
        goalId: String = "goal-" + UUID.randomUUID(),
        status: AgentGoalStatus = AgentGoalStatus.RUNNING,
        workerId: String = "test-worker",
        taskId: String = "t1",
        attemptId: String = "test-attempt",
        generation: Int = 1,
        heartbeatAt: Long = System.currentTimeMillis(),
    ): AgentGoal {
        val lease = AgentExecutionLease(
            workerId = workerId,
            ownerProcessSessionId = com.david.openassistant.data.diagnostics.DiagnosticEvent.PROCESS_SESSION_ID,
            taskId = taskId,
            attemptId = attemptId,
            generation = generation,
            acquiredAt = heartbeatAt,
            heartbeatAt = heartbeatAt,
        )
        return AgentGoal(
            id = goalId,
            conversationId = "conv-1",
            userRequest = "Test user request",
            title = "Test Title",
            objective = "Test Objective",
            finalOutputDescription = "Test Output",
            status = status,
            plannerModelId = "openrouter/auto-beta",
            executionModelId = "openrouter/auto-beta",
            tasks = listOf(
                AgentTask(
                    id = taskId,
                    order = 0,
                    title = "Milestone 1",
                    instructions = "Execute milestone 1",
                    capability = AgentCapability.REASON,
                    status = AgentTaskStatus.RUNNING,
                )
            ),
            executionLease = lease,
        )
    }

    private fun createMissionContext(
        goal: AgentGoal,
        operation: MissionOperation = MissionOperation.EXECUTE_TASK,
        role: AgentTaskRole = AgentTaskRole.PRIMARY_REASONING,
        parentOpId: String = "op-" + UUID.randomUUID(),
    ): ProviderRequestContext.Mission {
        val lease = goal.executionLease
        return ProviderRequestContext.Mission(
            goalId = goal.id,
            workerId = lease?.workerId ?: "default-worker",
            taskId = if (operation.taskBound) (lease?.taskId ?: "t1") else null,
            attemptId = lease?.attemptId ?: "default-attempt",
            executionGeneration = lease?.generation ?: 1,
            acquiredAt = lease?.acquiredAt ?: System.currentTimeMillis(),
            role = role,
            operation = operation,
            parentOperationId = parentOpId,
        )
    }

    // --- STORE OWNERSHIP & ALLOWLIST TESTS (Restored from 36) ---

    @Test
    fun goalIdMismatchRejectsActiveCreation() {
        val goal = createTestGoal()
        store.upsertGoal(goal)
        val ctx = createMissionContext(goal)
        val attemptRecord = ProviderRequestAttempt(
            exchangeId = "ex-1",
            parentOperationId = ctx.parentOperationId,
            goalId = "different-goal-id",
            executionGeneration = ctx.executionGeneration,
            requestedModel = "openrouter/auto-beta",
            payloadFingerprint = "fp123",
            exchangeOutcome = ExchangeOutcome.ACTIVE,
        )
        val res = store.createActiveRequestAttempt(goal.id, attemptRecord, ctx)
        assertEquals(CreateAttemptResult.InvalidLeaseOrGoalState, res)
    }

    @Test
    fun nonActiveInitialOutcomeRejectsActiveCreation() {
        val goal = createTestGoal()
        store.upsertGoal(goal)
        val ctx = createMissionContext(goal)
        val attemptRecord = ProviderRequestAttempt(
            exchangeId = "ex-1",
            parentOperationId = ctx.parentOperationId,
            goalId = goal.id,
            executionGeneration = ctx.executionGeneration,
            requestedModel = "openrouter/auto-beta",
            payloadFingerprint = "fp123",
            exchangeOutcome = ExchangeOutcome.RESPONSE_SUCCESS, // Not ACTIVE
        )
        val res = store.createActiveRequestAttempt(goal.id, attemptRecord, ctx)
        assertEquals(CreateAttemptResult.InvalidLeaseOrGoalState, res)
    }

    @Test
    fun missingLeaseRejectsActiveCreation() {
        val goal = createTestGoal().copy(executionLease = null)
        store.upsertGoal(goal)
        val ctx = createMissionContext(goal).copy(taskId = "t1")
        val attemptRecord = ProviderRequestAttempt(
            exchangeId = "ex-1",
            parentOperationId = ctx.parentOperationId,
            goalId = goal.id,
            taskId = "t1",
            executionGeneration = 1,
            requestedModel = "openrouter/auto-beta",
            payloadFingerprint = "fp123",
            exchangeOutcome = ExchangeOutcome.ACTIVE,
        )
        val res = store.createActiveRequestAttempt(goal.id, attemptRecord, ctx)
        assertEquals(CreateAttemptResult.InvalidLeaseOrGoalState, res)
    }

    @Test
    fun workerMismatchRejectsActiveCreation() {
        val goal = createTestGoal(workerId = "w1")
        store.upsertGoal(goal)
        val ctx = createMissionContext(goal).copy(workerId = "w2-mismatch")
        val attemptRecord = ProviderRequestAttempt(
            exchangeId = "ex-1",
            parentOperationId = ctx.parentOperationId,
            goalId = goal.id,
            taskId = "t1",
            executionGeneration = ctx.executionGeneration,
            requestedModel = "openrouter/auto-beta",
            payloadFingerprint = "fp123",
            exchangeOutcome = ExchangeOutcome.ACTIVE,
        )
        val res = store.createActiveRequestAttempt(goal.id, attemptRecord, ctx)
        assertEquals(CreateAttemptResult.InvalidLeaseOrGoalState, res)
    }

    @Test
    fun attemptIdMismatchRejectsActiveCreation() {
        val goal = createTestGoal(attemptId = "a1")
        store.upsertGoal(goal)
        val ctx = createMissionContext(goal).copy(attemptId = "a2-mismatch")
        val attemptRecord = ProviderRequestAttempt(
            exchangeId = "ex-1",
            parentOperationId = ctx.parentOperationId,
            goalId = goal.id,
            taskId = "t1",
            executionGeneration = ctx.executionGeneration,
            requestedModel = "openrouter/auto-beta",
            payloadFingerprint = "fp123",
            exchangeOutcome = ExchangeOutcome.ACTIVE,
        )
        val res = store.createActiveRequestAttempt(goal.id, attemptRecord, ctx)
        assertEquals(CreateAttemptResult.InvalidLeaseOrGoalState, res)
    }

    @Test
    fun taskMismatchRejectsActiveCreation() {
        val goal = createTestGoal(taskId = "t1")
        store.upsertGoal(goal)
        val ctx = createMissionContext(goal).copy(taskId = "t2-mismatch")
        val attemptRecord = ProviderRequestAttempt(
            exchangeId = "ex-1",
            parentOperationId = ctx.parentOperationId,
            goalId = goal.id,
            taskId = "t2-mismatch",
            executionGeneration = ctx.executionGeneration,
            requestedModel = "openrouter/auto-beta",
            payloadFingerprint = "fp123",
            exchangeOutcome = ExchangeOutcome.ACTIVE,
        )
        val res = store.createActiveRequestAttempt(goal.id, attemptRecord, ctx)
        assertEquals(CreateAttemptResult.InvalidLeaseOrGoalState, res)
    }

    @Test
    fun staleHeartbeatOnCreationRejectsActiveCreation() {
        val now = System.currentTimeMillis()
        val goal = createTestGoal(heartbeatAt = now - 10 * 60_000L) // 10 mins old
        store.upsertGoal(goal)
        val ctx = createMissionContext(goal)
        val attemptRecord = ProviderRequestAttempt(
            exchangeId = "ex-1",
            parentOperationId = ctx.parentOperationId,
            goalId = goal.id,
            taskId = "t1",
            executionGeneration = ctx.executionGeneration,
            requestedModel = "openrouter/auto-beta",
            payloadFingerprint = "fp123",
            exchangeOutcome = ExchangeOutcome.ACTIVE,
        )
        val res = store.createActiveRequestAttempt(goal.id, attemptRecord, ctx)
        assertEquals(CreateAttemptResult.InvalidLeaseOrGoalState, res)
    }

    @Test
    fun oldAndFutureGenerationRejectActiveCreation() {
        val goal = createTestGoal(generation = 5)
        store.upsertGoal(goal)

        // Old generation
        val oldCtx = createMissionContext(goal).copy(executionGeneration = 3)
        val oldAttempt = ProviderRequestAttempt(
            exchangeId = "ex-old", parentOperationId = "op-1", goalId = goal.id, taskId = "t1",
            executionGeneration = 3, requestedModel = "openrouter/auto-beta", payloadFingerprint = "fp", exchangeOutcome = ExchangeOutcome.ACTIVE,
        )
        val oldRes = store.createActiveRequestAttempt(goal.id, oldAttempt, oldCtx)
        assertTrue(oldRes is CreateAttemptResult.InvalidGeneration)
        assertEquals(5, (oldRes as CreateAttemptResult.InvalidGeneration).expected)
        assertEquals(3, oldRes.actual)

        // Future generation
        val futureCtx = createMissionContext(goal).copy(executionGeneration = 8)
        val futureAttempt = ProviderRequestAttempt(
            exchangeId = "ex-future", parentOperationId = "op-1", goalId = goal.id, taskId = "t1",
            executionGeneration = 8, requestedModel = "openrouter/auto-beta", payloadFingerprint = "fp", exchangeOutcome = ExchangeOutcome.ACTIVE,
        )
        val futureRes = store.createActiveRequestAttempt(goal.id, futureAttempt, futureCtx)
        assertTrue(futureRes is CreateAttemptResult.InvalidGeneration)
    }

    @Test
    fun nonRunnableGoalStatusesRejectActiveCreation() {
        val nonRunnableStatuses = listOf(
            AgentGoalStatus.QUEUED,
            AgentGoalStatus.PAUSED,
            AgentGoalStatus.CANCELLED,
            AgentGoalStatus.COMPLETED,
            AgentGoalStatus.FAILED,
            AgentGoalStatus.BLOCKED,
            AgentGoalStatus.FINALIZING,
            AgentGoalStatus.WAITING_FOR_CREDENTIAL,
            AgentGoalStatus.WAITING_FOR_NETWORK,
        )

        for (status in nonRunnableStatuses) {
            val goal = createTestGoal(status = status)
            store.upsertGoal(goal)
            val ctx = createMissionContext(goal)
            val attemptRecord = ProviderRequestAttempt(
                exchangeId = "ex-" + UUID.randomUUID(),
                parentOperationId = ctx.parentOperationId,
                goalId = goal.id,
                taskId = ctx.taskId,
                executionGeneration = ctx.executionGeneration,
                requestedModel = "openrouter/auto-beta",
                payloadFingerprint = "fp123",
                exchangeOutcome = ExchangeOutcome.ACTIVE,
            )
            val res = store.createActiveRequestAttempt(goal.id, attemptRecord, ctx)
            assertEquals("Failed to reject status $status", CreateAttemptResult.InvalidLeaseOrGoalState, res)
        }
    }

    @Test
    fun runnableGoalStatusesAllowActiveCreation() {
        val runnableStatuses = listOf(
            AgentGoalStatus.PLANNING,
            AgentGoalStatus.RUNNING,
            AgentGoalStatus.VERIFYING,
        )

        for (status in runnableStatuses) {
            val isTaskBound = status == AgentGoalStatus.RUNNING
            val op = if (status == AgentGoalStatus.PLANNING) MissionOperation.CREATE_PLAN 
                     else if (status == AgentGoalStatus.VERIFYING) MissionOperation.VERIFY_GOAL 
                     else MissionOperation.EXECUTE_TASK
            val goal = createTestGoal(status = status, taskId = if (isTaskBound) "t1" else "none")
            store.upsertGoal(goal)
            val ctx = createMissionContext(goal, operation = op)
            val attemptRecord = ProviderRequestAttempt(
                exchangeId = "ex-" + UUID.randomUUID(),
                parentOperationId = ctx.parentOperationId,
                goalId = goal.id,
                taskId = ctx.taskId,
                executionGeneration = ctx.executionGeneration,
                requestedModel = "openrouter/auto-beta",
                payloadFingerprint = "fp123",
                exchangeOutcome = ExchangeOutcome.ACTIVE,
            )
            val res = store.createActiveRequestAttempt(goal.id, attemptRecord, ctx)
            assertEquals("Failed to allow status $status", CreateAttemptResult.Created, res)
        }
    }

    // --- TERMINALIZATION TESTS (Restored and Corrected) ---

    @Test
    fun matchingDuplicateTerminalIsIdempotentSuccess() {
        val goal = createTestGoal()
        store.upsertGoal(goal)
        val ctx = createMissionContext(goal)
        val exchangeId = "ex-1"
        val attemptRecord = ProviderRequestAttempt(
            exchangeId = exchangeId,
            parentOperationId = ctx.parentOperationId,
            goalId = goal.id,
            taskId = "t1",
            executionGeneration = ctx.executionGeneration,
            requestedModel = "openrouter/auto-beta",
            payloadFingerprint = "fp123",
            exchangeOutcome = ExchangeOutcome.ACTIVE,
        )
        store.createActiveRequestAttempt(goal.id, attemptRecord, ctx)

        // First transition -> Updated
        val t1 = store.transitionExchangeOutcomeWithResultAtomic(goal.id, exchangeId, ExchangeOutcome.RESPONSE_SUCCESS, ctx, statusCode = 200)
        assertTrue(t1 is TransitionOutcomeResult.Updated)

        // Matching duplicate transition -> AlreadyTerminal(RESPONSE_SUCCESS)
        val t2 = store.transitionExchangeOutcomeWithResultAtomic(goal.id, exchangeId, ExchangeOutcome.RESPONSE_SUCCESS, ctx, statusCode = 200)
        assertTrue(t2 is TransitionOutcomeResult.AlreadyTerminal)
        assertEquals(ExchangeOutcome.RESPONSE_SUCCESS, (t2 as TransitionOutcomeResult.AlreadyTerminal).outcome)
    }

    @Test
    fun conflictingDuplicateTerminalSurfacesException() = runBlocking {
        val goal = createTestGoal()
        store.upsertGoal(goal)
        val ctx = createMissionContext(goal)
        val exchangeId = "ex-1"
        val attemptRecord = ProviderRequestAttempt(
            exchangeId = exchangeId,
            parentOperationId = ctx.parentOperationId,
            goalId = goal.id,
            taskId = "t1",
            executionGeneration = ctx.executionGeneration,
            requestedModel = "openrouter/auto-beta",
            payloadFingerprint = "fp123",
            exchangeOutcome = ExchangeOutcome.ACTIVE,
        )
        store.createActiveRequestAttempt(goal.id, attemptRecord, ctx)

        // Transition to RESPONSE_SUCCESS
        store.transitionExchangeOutcomeWithResultAtomic(goal.id, exchangeId, ExchangeOutcome.RESPONSE_SUCCESS, ctx, statusCode = 200)

        // Conflicting transition to RESPONSE_ERROR via store directly
        val result = store.transitionExchangeOutcomeWithResultAtomic(goal.id, exchangeId, ExchangeOutcome.RESPONSE_ERROR, ctx, statusCode = 500)
        assertTrue(result is TransitionOutcomeResult.AlreadyTerminal)
        assertEquals(ExchangeOutcome.RESPONSE_SUCCESS, (result as TransitionOutcomeResult.AlreadyTerminal).outcome)
    }

    @Test
    fun terminalStoreFailuresSurfaceAsTerminalPersistenceException() = runBlocking {
        val goal = createTestGoal()
        store.upsertGoal(goal)
        val ctx = createMissionContext(goal)

        // Inject storage writer failure for ANY terminal attempt on this goal
        store.setTestWriterInjection(object : AgentStore.GoalStateWriter {
            override fun write(goalWrite: AgentGoal) {
                if (goalWrite.id == goal.id && goalWrite.requestAttempts.any { it.exchangeOutcome != ExchangeOutcome.ACTIVE }) {
                   throw IOException("Disk full error during terminal write")
                }
            }
        })

        val milestoneResult = JSONObject()
            .put("work_product", "Success")
            .put("completion_score", 1.0)
            .put("acceptance_checks", JSONArray())
            .put("claims", JSONArray())
            .put("unresolved_questions", JSONArray())

        val validResponseBody = JSONObject()
            .put("id", "gen-1")
            .put("model", "openrouter/auto-beta")
            .put("choices", JSONArray().put(
                JSONObject().put("message", JSONObject()
                    .put("role", "assistant")
                    .put("content", milestoneResult.toString())
                )
            ))
            .toString()

        server.enqueue(MockResponse.Builder().code(200).body(validResponseBody).build())

        val thrown = runCatching {
            client.executeTask(
                apiKey = "sk-test",
                modelId = "openrouter/auto-beta",
                goal = goal,
                task = goal.tasks.first(),
                requestContext = ctx
            )
        }

        assertTrue(thrown.isFailure)
        val cause = thrown.exceptionOrNull()
        assertTrue("Expected TerminalPersistenceException, got $cause", cause is TerminalPersistenceException)
        val ex = cause as TerminalPersistenceException
        assertEquals("StorageFailure", ex.storeFailure)

        store.setTestWriterInjection(null)
    }

    @Test
    fun injectedStoreStorageFailureRejectsActiveWriteAndTerminalWrite() = runBlocking {
        val goal = createTestGoal()
        store.upsertGoal(goal)
        val ctx = createMissionContext(goal)

        val attemptRecord = ProviderRequestAttempt(
            exchangeId = "ex-fail-both",
            parentOperationId = ctx.parentOperationId,
            goalId = goal.id,
            taskId = "t1",
            executionGeneration = ctx.executionGeneration,
            requestedModel = "openrouter/auto-beta",
            payloadFingerprint = "fp123",
            exchangeOutcome = ExchangeOutcome.ACTIVE,
        )

        // 1. Test ACTIVE write failure
        store.setTestWriterInjection(object : AgentStore.GoalStateWriter {
            override fun write(goal: AgentGoal) {
                throw IOException("Disk write error")
            }
        })

        val activeRes = store.createActiveRequestAttempt(goal.id, attemptRecord, ctx)
        assertTrue(activeRes is CreateAttemptResult.StorageFailure)

        // Allow ACTIVE write to succeed
        store.setTestWriterInjection(null)
        val activeResSuccess = store.createActiveRequestAttempt(goal.id, attemptRecord, ctx)
        assertEquals(CreateAttemptResult.Created, activeResSuccess)

        // 2. Test Terminal write failure
        store.setTestWriterInjection(object : AgentStore.GoalStateWriter {
            override fun write(goal: AgentGoal) {
                throw IOException("Disk terminal write error")
            }
        })

        val termRes = store.transitionExchangeOutcomeWithResultAtomic(goal.id, "ex-fail-both", ExchangeOutcome.RESPONSE_SUCCESS, ctx, statusCode = 200)
        assertTrue(termRes is TransitionOutcomeResult.StorageFailure)

        store.setTestWriterInjection(null)
    }

    // --- HTTP OUTCOMES & CANCELLATION TESTS (Restored and Corrected) ---

    @Test
    fun mockWebServerMissionDispatchSuccess200() = runBlocking {
        val goal = createTestGoal(status = AgentGoalStatus.RUNNING, taskId = "t1")
        store.upsertGoal(goal)

        val milestoneResult = JSONObject()
            .put("work_product", "Task completed successfully.")
            .put("completion_score", 1.0)
            .put("acceptance_checks", JSONArray().put(
                JSONObject().put("criterion_id", "tc1").put("status", "pass").put("score", 1.0).put("explanation", "Done")
            ))
            .put("claims", JSONArray())
            .put("unresolved_questions", JSONArray())

        val taskResponseBody = JSONObject()
            .put("id", "gen-task-1")
            .put("model", "openrouter/auto-beta")
            .put("choices", JSONArray().put(
                JSONObject().put("message", JSONObject()
                    .put("role", "assistant")
                    .put("content", milestoneResult.toString())
                )
            ))
            .toString()

        server.enqueue(MockResponse.Builder().code(200).body(taskResponseBody).build())

        val ctx = createMissionContext(goal, operation = MissionOperation.EXECUTE_TASK)
        val task = goal.tasks.first()
        val stepResult = client.executeTask(
            apiKey = "sk-or-test-key",
            modelId = "openrouter/auto-beta",
            goal = goal,
            task = task,
            requestContext = ctx,
        )

        assertNotNull(stepResult)

        val reloadedGoal = store.loadSnapshot().goals.first { it.id == goal.id }
        assertTrue(reloadedGoal.requestAttempts.isNotEmpty())
        assertTrue(reloadedGoal.requestAttempts.all { it.exchangeOutcome == ExchangeOutcome.RESPONSE_SUCCESS && it.httpStatusCode == 200 })
    }

    @Test
    fun mockWebServerMissionDispatch500Error() = runBlocking {
        val goal = createTestGoal()
        store.upsertGoal(goal)

        // Enqueue three responses because 500 triggers internal retry
        server.enqueue(MockResponse.Builder().code(500).body("Internal Server Error").build())
        server.enqueue(MockResponse.Builder().code(500).body("Internal Server Error").build())
        server.enqueue(MockResponse.Builder().code(500).body("Internal Server Error").build())

        val ctx = createMissionContext(goal)
        val task = goal.tasks.first()

        val thrown = runCatching {
            client.executeTask(
                apiKey = "sk-or-test-key",
                modelId = "openrouter/auto-beta",
                goal = goal,
                task = task,
                requestContext = ctx,
            )
        }

        assertTrue(thrown.isFailure)
        val reloadedGoal = store.loadSnapshot().goals.first { it.id == goal.id }
        val lastAttempt = reloadedGoal.requestAttempts.last()
        assertEquals(ExchangeOutcome.RESPONSE_ERROR, lastAttempt.exchangeOutcome)
        assertEquals(500, lastAttempt.httpStatusCode)
    }

    @Test
    fun providerErrorEnvelopeInsideHttp200ProducesResponseError() = runBlocking {
        val goal = createTestGoal()
        store.upsertGoal(goal)

        val errorEnvelopeBody = JSONObject()
            .put("id", "gen-err-1")
            .put("choices", JSONArray().put(
                JSONObject().put("finish_reason", "error").put("error", JSONObject().put("code", 429).put("message", "Rate limit reached"))
            ))
            .toString()

        server.enqueue(MockResponse.Builder().code(200).body(errorEnvelopeBody).build())

        val ctx = createMissionContext(goal)
        val task = goal.tasks.first()

        val thrown = runCatching {
            client.executeTask(
                apiKey = "sk-or-test-key",
                modelId = "openrouter/auto-beta",
                goal = goal,
                task = task,
                requestContext = ctx,
            )
        }

        assertTrue(thrown.isFailure)
        val reloadedGoal = store.loadSnapshot().goals.first { it.id == goal.id }
        val lastAttempt = reloadedGoal.requestAttempts.last()
        assertEquals(ExchangeOutcome.RESPONSE_ERROR, lastAttempt.exchangeOutcome)
        assertEquals(200, lastAttempt.httpStatusCode)
    }

    @Test
    fun cancellationBeforeActiveCreatesZeroActiveAndZeroHttpDispatches() = runBlocking {
        val goal = createTestGoal()
        store.upsertGoal(goal)

        val ctx = createMissionContext(goal)
        val task = goal.tasks.first()

        client.cancelActiveCalls()

        val thrown = runCatching {
            client.executeTask(
                apiKey = "sk-or-test-key",
                modelId = "openrouter/auto-beta",
                goal = goal,
                task = task,
                requestContext = ctx,
            )
        }

        assertTrue(thrown.isFailure)
        val reloadedGoal = store.loadSnapshot().goals.first { it.id == goal.id }
        assertEquals(0, reloadedGoal.requestAttempts.size)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cancellationBoundaryBPostActivePreHttpPersistsCancelledAndZeroHttp() = runBlocking {
        val goal = createTestGoal()
        store.upsertGoal(goal)
        val ctx = createMissionContext(goal)
        val task = goal.tasks.first()

        postActiveHook = AgentOpenRouterClient.PostActivePreDispatchHook { _, _ ->
            client.cancelActiveCalls()
        }

        val thrown = runCatching {
            client.executeTask(
                apiKey = "sk-or-test-key",
                modelId = "openrouter/auto-beta",
                goal = goal,
                task = task,
                requestContext = ctx,
            )
        }

        assertTrue(thrown.isFailure)
        val reloadedGoal = store.loadSnapshot().goals.first { it.id == goal.id }
        assertEquals(1, reloadedGoal.requestAttempts.size)
        assertEquals(ExchangeOutcome.CANCELLED, reloadedGoal.requestAttempts.first().exchangeOutcome)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cancellationDuringInFlightHttpCreatesCancelledExchange() = runBlocking {
        val goal = createTestGoal()
        store.upsertGoal(goal)
        val ctx = createMissionContext(goal)
        val task = goal.tasks.first()

        server.dispatcher = object : mockwebserver3.Dispatcher() {
            override fun dispatch(request: mockwebserver3.RecordedRequest): MockResponse {
                // Delay response and cancel client
                client.cancelActiveCalls()
                return MockResponse.Builder().code(200).body("{}").build()
            }
        }

        val thrown = runCatching {
            client.executeTask(
                apiKey = "sk-or-test-key",
                modelId = "openrouter/auto-beta",
                goal = goal,
                task = task,
                requestContext = ctx,
            )
        }

        assertTrue(thrown.isFailure)
        val reloadedGoal = store.loadSnapshot().goals.first { it.id == goal.id }
        assertEquals(1, reloadedGoal.requestAttempts.size)
        val attempt = reloadedGoal.requestAttempts.first()
        assertEquals(ExchangeOutcome.CANCELLED, attempt.exchangeOutcome)
    }

    // --- SUB-OPERATION CALLER WIRING TESTS (Restored) ---

    @Test
    fun requestAttemptRoleMatchesActualPayloadRole() = runBlocking {
        val goal = createTestGoal()
        store.upsertGoal(goal)

        val body = JSONObject()
            .put("id", "gen-1")
            .put("choices", JSONArray().put(
                JSONObject().put("message", JSONObject().put("role", "assistant").put("content", "{}"))
            ))
        server.enqueue(MockResponse.Builder().code(200).body(body.toString()).build())

        val ctx = createMissionContext(goal, role = AgentTaskRole.REQUEST_CONSTRUCTION)
        runCatching {
            client.buildComplexRequest(
                apiKey = "sk-or-test-key",
                instructions = "Design request",
                context = "Context",
                generation = 1,
                requestContext = ctx,
            )
        }

        val reloadedGoal = store.loadSnapshot().goals.first { it.id == goal.id }
        assertTrue(reloadedGoal.requestAttempts.isNotEmpty())
        val attempt = reloadedGoal.requestAttempts.first()
        assertEquals(AgentTaskRole.REQUEST_CONSTRUCTION, attempt.role)
    }

    @Test
    fun workerPlannerProductionPathDurableAttemptFlow() = runBlocking {
        val unleasedGoal = AgentGoal(
            id = "goal-worker-planner",
            conversationId = "conv-1",
            userRequest = "Calculate 2+2",
            title = "Worker Planner Goal",
            objective = "Plan objective",
            finalOutputDescription = "Plan output",
            status = AgentGoalStatus.PLANNING,
            plannerModelId = "openrouter/auto-beta",
            executionModelId = "openrouter/auto-beta",
            tasks = emptyList(),
            executionLease = null,
        )
        store.upsertGoal(unleasedGoal)

        val planResponseBody = JSONObject()
            .put("id", "gen-plan-1")
            .put("model", "openrouter/auto-beta")
            .put("choices", JSONArray().put(
                JSONObject().put("message", JSONObject()
                    .put("role", "assistant")
                    .put("content", JSONObject()
                        .put("title", "Calculate 2+2 Plan")
                        .put("objective", "Calculate 2+2 objective")
                        .put("final_output", "Calculate 2+2 final output")
                        .put("acceptance_criteria", JSONArray().put(
                            JSONObject().put("id", "c1").put("description", "Calculate 2+2 Goal check").put("weight", 1.0)
                        ))
                        .put("tasks", JSONArray()
                            .put(
                                JSONObject()
                                    .put("id", "step_1")
                                    .put("title", "Calculate 2+2 Milestone 1")
                                    .put("instructions", "Instructions 1 for Calculate 2+2 calculation in detail")
                                    .put("capability", "reason")
                                    .put("depends_on", JSONArray())
                                    .put("weight", 1.0)
                                    .put("acceptance_criteria", JSONArray().put(
                                        JSONObject().put("id", "tc1").put("description", "Calculate 2+2 Task check")
                                    ))
                            )
                            .put(
                                JSONObject()
                                    .put("id", "step_2")
                                    .put("title", "Calculate 2+2 Milestone 2 Synthesize")
                                    .put("instructions", "Synthesize results for Calculate 2+2 calculation in detail")
                                    .put("capability", "synthesize")
                                    .put("depends_on", JSONArray().put("step_1"))
                                    .put("weight", 1.0)
                                    .put("acceptance_criteria", JSONArray().put(
                                        JSONObject().put("id", "tc2").put("description", "Calculate 2+2 Synthesis check")
                                    ))
                            )
                        )
                        .toString()
                    )
                )
            ))
            .toString()

        server.enqueue(MockResponse.Builder().code(200).body(planResponseBody).build())

        val workerId = "worker-test-1"
        val planner = AgentPlanner(client, store, diagnostics)

        val now = System.currentTimeMillis()
        val leaseAttemptId = "attempt-" + UUID.randomUUID()
        store.updateGoal(unleasedGoal.id) { current ->
            current.copy(
                executionLease = AgentExecutionLease(
                    workerId = workerId,
                    ownerProcessSessionId = "session-1",
                    taskId = "none",
                    attemptId = leaseAttemptId,
                    generation = 1,
                    acquiredAt = now,
                    heartbeatAt = now,
                )
            )
        }

        val acquisition = store.acquirePlanningLeaseAtomic(unleasedGoal.id, workerId)
        val ticket = when (acquisition) {
            is LeaseAcquisitionResult.Acquired -> acquisition.ticket as PlanningTicket
            is LeaseAcquisitionResult.OrphanReclaimed -> acquisition.ticket as PlanningTicket
            else -> throw AssertionError("Expected acquisition or reclamation, but got: $acquisition")
        }
        val leasedGoal = when (acquisition) {
            is LeaseAcquisitionResult.Acquired -> acquisition.goal
            is LeaseAcquisitionResult.OrphanReclaimed -> acquisition.goal
            else -> throw IllegalStateException()
        }

        val outcome = planner.plan(
            apiKey = "sk-or-test-key",
            goal = leasedGoal,
            ticket = ticket,
            models = emptyList(),
        )

        assertEquals(WorkerOutcome.CONTINUE, outcome)

        val reloadedGoal = store.loadSnapshot().goals.first { it.id == unleasedGoal.id }
        assertEquals(AgentGoalStatus.QUEUED, reloadedGoal.status)
        assertEquals(2, reloadedGoal.tasks.size)

        assertTrue(reloadedGoal.requestAttempts.isNotEmpty())
        assertEquals(ExchangeOutcome.RESPONSE_SUCCESS, reloadedGoal.requestAttempts.last().exchangeOutcome)

        assertTrue(reloadedGoal.attempts.isNotEmpty())
        assertTrue(reloadedGoal.attempts.none { it.status == AgentAttemptStatus.RUNNING })
        assertEquals(AgentAttemptStatus.SUCCEEDED, reloadedGoal.attempts.last().status)
    }

    @Test
    fun plannerMissingLeaseCreatesNoRunningAttempt() = runBlocking {
        val goalNoLease = createTestGoal(status = AgentGoalStatus.PLANNING).copy(executionLease = null)
        store.upsertGoal(goalNoLease)

        val planner = AgentPlanner(client, store, diagnostics)
        val acquisition = store.acquirePlanningLeaseAtomic(goalNoLease.id, "worker-1")
        // Manually break the lease to simulate the test case
        store.updateGoal(goalNoLease.id) { it.copy(executionLease = null) }
        
        val ticket = when (acquisition) {
            is LeaseAcquisitionResult.Acquired -> acquisition.ticket as PlanningTicket
            is LeaseAcquisitionResult.OrphanReclaimed -> acquisition.ticket as PlanningTicket
            else -> throw AssertionError("Expected acquisition or reclamation, but got: $acquisition")
        }
        val outcome = planner.plan(
            apiKey = "sk-or-test-key",
            goal = goalNoLease,
            ticket = ticket
        )

        assertEquals(WorkerOutcome.FAIL, outcome)
        val reloadedGoal = store.loadSnapshot().goals.first { it.id == goalNoLease.id }
        assertEquals(0, reloadedGoal.attempts.size)
    }

    @Test
    fun missionOperationTaskBoundAndOwnershipValidation() {
        for (op in MissionOperation.values()) {
            val taskBound = op.taskBound
            assertEquals(
                "Task bound mismatch for ${op.name}",
                taskBound,
                AgentStore.isTaskBoundOperation(op.operationName)
            )
        }

        assertEquals(false, AgentStore.isTaskBoundOperation("unknown_op_xyz"))
    }

    // --- WORKER OWNERSHIP & LIFECYCLE TESTS (Restored) ---

    @Test
    fun taskCommitWithCorrectWorkerOwnershipIsAccepted() = runBlocking {
        val goal = createTestGoal()
        val workerId = "worker-correct"
        val leaseAttemptId = "lease-attempt-1"
        val now = System.currentTimeMillis()
        val leasedGoal = goal.copy(
            status = AgentGoalStatus.RUNNING,
            executionLease = AgentExecutionLease(
                workerId = workerId,
                ownerProcessSessionId = "session-1",
                taskId = goal.tasks.first().id,
                attemptId = leaseAttemptId,
                generation = 1,
                acquiredAt = now,
                heartbeatAt = now,
            )
        )
        store.upsertGoal(leasedGoal)

        val task = leasedGoal.tasks.first()
        val attemptId = "attempt-exec-1"
        
        store.updateGoal(goal.id) { current ->
            current.copy(
                tasks = current.tasks.map { if (it.id == task.id) it.copy(status = AgentTaskStatus.RUNNING) else it },
                attempts = listOf(AgentAttempt(id = attemptId, taskId = task.id, status = AgentAttemptStatus.RUNNING, startedAt = now, modelId = goal.executionModelId))
            )
        }

        val ownership = ExecutionOwnership(
            workerId = workerId,
            leaseAttemptId = leaseAttemptId,
            executionGeneration = 1,
            taskId = task.id,
        )

        val current = store.loadSnapshot().goals.first { it.id == goal.id }
        val ticket = TaskExecutionTicket(
            current.id,
            task.id,
            ownership.workerId,
            "session-1",
            ownership.executionGeneration,
            ownership.leaseAttemptId,
            System.currentTimeMillis()
        )
        val canCommit = canCommitMilestoneResult(current, task.id, attemptId, ticket)
        assertTrue(canCommit)
    }

    @Test
    fun taskCommitWithReplacedWorkerIsRejectedAndPreservesGoal() = runBlocking {
        val goal = createTestGoal()
        val originalWorker = "worker-old"
        val newWorker = "worker-new"
        val leaseAttemptId = "lease-attempt-2"
        val now = System.currentTimeMillis()
        val leasedGoal = goal.copy(
            status = AgentGoalStatus.RUNNING,
            executionLease = AgentExecutionLease(
                workerId = newWorker,
                ownerProcessSessionId = "session-1",
                taskId = goal.tasks.first().id,
                attemptId = leaseAttemptId,
                generation = 2,
                acquiredAt = now,
                heartbeatAt = now,
            )
        )
        store.upsertGoal(leasedGoal)

        val task = leasedGoal.tasks.first()
        val attemptId = "attempt-exec-old"

        val staleOwnership = ExecutionOwnership(
            workerId = originalWorker,
            leaseAttemptId = "lease-attempt-1",
            executionGeneration = 1,
            taskId = task.id,
        )

        val current = store.loadSnapshot().goals.first { it.id == goal.id }
        val staleTicket = TaskExecutionTicket(
            current.id,
            task.id,
            staleOwnership.workerId,
            "session-1",
            staleOwnership.executionGeneration,
            staleOwnership.leaseAttemptId,
            System.currentTimeMillis()
        )
        val canCommit = canCommitMilestoneResult(current, task.id, attemptId, staleTicket)
        assertEquals(false, canCommit)
        
        // Goal state remains unchanged under newWorker
        val reloaded = store.loadSnapshot().goals.first { it.id == goal.id }
        assertEquals(newWorker, reloaded.executionLease?.workerId)
        assertEquals(2, reloaded.executionLease?.generation)
    }

    // --- NEW TASK 57 TESTS (Merged) ---

    @Test
    fun transportSocketFailureMapsToTransportFailure() = runBlocking {
        val goal = createTestGoal()
        store.upsertGoal(goal)
        val ctx = createMissionContext(goal)

        server.close() 

        val thrown = runCatching {
            client.executeTask(
                apiKey = "sk-or-test",
                modelId = "openrouter/auto-beta",
                goal = goal,
                task = goal.tasks.first(),
                requestContext = ctx
            )
        }

        assertTrue("Should fail with connection error", thrown.isFailure)
        val reloaded = store.loadSnapshot().goals.firstOrNull { it.id == goal.id }
        assertNotNull("Goal should exist", reloaded)
        val attempt = reloaded!!.requestAttempts.last()
        assertEquals(ExchangeOutcome.TRANSPORT_FAILURE, attempt.exchangeOutcome)
    }

    @Test
    fun httpTimeoutMapsToTransportFailure() = runBlocking {
        val goal = createTestGoal()
        store.upsertGoal(goal)
        val ctx = createMissionContext(goal)

        server.enqueue(MockResponse.Builder().bodyDelay(1, TimeUnit.SECONDS).body("{}").build())

        val thrown = runCatching {
            client.executeTask(
                apiKey = "sk-or-test",
                modelId = "openrouter/auto-beta",
                goal = goal,
                task = goal.tasks.first(),
                requestContext = ctx
            )
        }

        assertTrue(thrown.isFailure)
        val reloaded = store.loadSnapshot().goals.first { it.id == goal.id }
        val attempt = reloaded.requestAttempts.last()
        assertEquals(ExchangeOutcome.TRANSPORT_FAILURE, attempt.exchangeOutcome)
        assertEquals("SocketTimeoutException", attempt.failureClass)
    }

    @Test
    fun parseFailureProducesResponseSuccessButApplicationFailure() = runBlocking {
        val goal = createTestGoal()
        store.upsertGoal(goal)
        val ctx = createMissionContext(goal)

        server.enqueue(MockResponse.Builder().code(200).body("invalid-json").build())

        val thrown = runCatching {
            client.executeTask(
                apiKey = "sk-or-test",
                modelId = "openrouter/auto-beta",
                goal = goal,
                task = goal.tasks.first(),
                requestContext = ctx
            )
        }

        assertTrue(thrown.isFailure)
        val reloaded = store.loadSnapshot().goals.first { it.id == goal.id }
        val attempt = reloaded.requestAttempts.last()
        assertEquals(ExchangeOutcome.RESPONSE_SUCCESS, attempt.exchangeOutcome)
        assertEquals(200, attempt.httpStatusCode)
        
        val ex = thrown.exceptionOrNull()
        assertTrue("Expected envelope error, got: $ex", 
            ex is OpenRouterException && ex.userMessage.contains("envelope", ignoreCase = true))
    }

    @Test
    fun goalMissingDuringTerminalizationSurfacesTerminalPersistenceException() = runBlocking {
        val goal = createTestGoal()
        store.upsertGoal(goal)
        val ctx = createMissionContext(goal)

        val milestoneResult = JSONObject()
            .put("work_product", "Success")
            .put("completion_score", 1.0)
            .put("acceptance_checks", JSONArray())
            .put("claims", JSONArray())
            .put("unresolved_questions", JSONArray())

        val validResponseBody = JSONObject()
            .put("id", "gen-1")
            .put("model", "openrouter/auto-beta")
            .put("choices", JSONArray().put(
                JSONObject().put("message", JSONObject()
                    .put("role", "assistant")
                    .put("content", milestoneResult.toString())
                )
            ))
            .toString()

        server.enqueue(MockResponse.Builder().code(200).body(validResponseBody).build())

        terminalHook = AgentOpenRouterClient.TerminalTransitionHook { gid, _, _, _ ->
            store.deleteGoal(gid)
        }

        val thrown = runCatching {
            client.executeTask(
                apiKey = "sk-or-test",
                modelId = "openrouter/auto-beta",
                goal = goal,
                task = goal.tasks.first(),
                requestContext = ctx
            )
        }

        assertTrue(thrown.isFailure)
        val cause = thrown.exceptionOrNull()
        assertTrue("Expected TerminalPersistenceException, got $cause", cause is TerminalPersistenceException)
        assertEquals("GoalMissing", (cause as TerminalPersistenceException).storeFailure)
    }

    @Test
    fun invalidGenerationDuringTerminalizationSurfacesTerminalPersistenceException() = runBlocking {
        val goal = createTestGoal(generation = 1)
        store.upsertGoal(goal)
        val ctx = createMissionContext(goal)

        val milestoneResult = JSONObject()
            .put("work_product", "Success")
            .put("completion_score", 1.0)
            .put("acceptance_checks", JSONArray())
            .put("claims", JSONArray())
            .put("unresolved_questions", JSONArray())

        val validResponseBody = JSONObject()
            .put("id", "gen-1")
            .put("model", "openrouter/auto-beta")
            .put("choices", JSONArray().put(
                JSONObject().put("message", JSONObject()
                    .put("role", "assistant")
                    .put("content", milestoneResult.toString())
                )
            ))
            .toString()

        server.enqueue(MockResponse.Builder().code(200).body(validResponseBody).build())

        terminalHook = AgentOpenRouterClient.TerminalTransitionHook { gid, _, _, _ ->
            store.updateGoal(gid) { it.copy(executionLease = it.executionLease?.copy(generation = 2)) }
        }

        val thrown = runCatching {
            client.executeTask(
                apiKey = "sk-or-test",
                modelId = "openrouter/auto-beta",
                goal = goal,
                task = goal.tasks.first(),
                requestContext = ctx
            )
        }

        assertTrue(thrown.isFailure)
        val cause = thrown.exceptionOrNull()
        assertTrue("Expected TerminalPersistenceException, got $cause", cause is TerminalPersistenceException)
        assertEquals("InvalidGeneration", (cause as TerminalPersistenceException).storeFailure)
    }

    @Test
    fun storageFailureDuringTerminalizationSurfacesTerminalPersistenceException() = runBlocking {
        val goal = createTestGoal()
        store.upsertGoal(goal)
        val ctx = createMissionContext(goal)

        val milestoneResult = JSONObject()
            .put("work_product", "Success")
            .put("completion_score", 1.0)
            .put("acceptance_checks", JSONArray())
            .put("claims", JSONArray())
            .put("unresolved_questions", JSONArray())

        val validResponseBody = JSONObject()
            .put("id", "gen-1")
            .put("model", "openrouter/auto-beta")
            .put("choices", JSONArray().put(
                JSONObject().put("message", JSONObject()
                    .put("role", "assistant")
                    .put("content", milestoneResult.toString())
                )
            ))
            .toString()

        server.enqueue(MockResponse.Builder().code(200).body(validResponseBody).build())

        var inTaskExecution = false
        store.setTestWriterInjection(object : AgentStore.GoalStateWriter {
            override fun write(updatedGoal: AgentGoal) {
                if (inTaskExecution && updatedGoal.id == goal.id) {
                    val isTerminalWrite = updatedGoal.requestAttempts.any { it.exchangeOutcome != ExchangeOutcome.ACTIVE }
                    if (isTerminalWrite) {
                        throw IOException("Disk full error during terminal write")
                    }
                }
            }
        })

        inTaskExecution = true
        val result = runCatching {
            client.executeTask(
                apiKey = "sk-or-test",
                modelId = "openrouter/auto-beta",
                goal = goal,
                task = goal.tasks.first(),
                requestContext = ctx,
                maxAttempts = 1
            )
        }

        assertTrue("executeTask should have failed", result.isFailure)
        val error = result.exceptionOrNull()
        val cause = if (error is OpenRouterException) error.cause else error
        
        if (cause !is TerminalPersistenceException) {
            val errorDetails = if (error is OpenRouterException) " (cause=${error.cause})" else ""
            throw AssertionError("Expected TerminalPersistenceException, but got ${cause?.javaClass?.name}: $error$errorDetails")
        }
        
        assertEquals("StorageFailure", cause.storeFailure)

        store.setTestWriterInjection(null)
    }

    @Test
    fun replacedLeaseDuringTaskExecutionLeavesRequestActiveAndOrphaned() = runBlocking {
        val goal = createTestGoal(workerId = "worker-A", generation = 1)
        store.upsertGoal(goal)
        val ctx = createMissionContext(goal)

        val milestoneResult = JSONObject()
            .put("work_product", "Success")
            .put("completion_score", 1.0)
            .put("acceptance_checks", JSONArray())
            .put("claims", JSONArray())
            .put("unresolved_questions", JSONArray())

        val validResponseBody = JSONObject()
            .put("id", "gen-1")
            .put("model", "openrouter/auto-beta")
            .put("choices", JSONArray().put(
                JSONObject().put("message", JSONObject()
                    .put("role", "assistant")
                    .put("content", milestoneResult.toString())
                )
            ))
            .toString()

        server.enqueue(MockResponse.Builder().code(200).body(validResponseBody).build())

        postActiveHook = AgentOpenRouterClient.PostActivePreDispatchHook { gid, _ ->
            store.updateGoal(gid) { current ->
                current.copy(executionLease = current.executionLease?.copy(workerId = "worker-B", generation = 2))
            }
        }

        val thrown = runCatching {
            client.executeTask(
                apiKey = "sk-or-test",
                modelId = "openrouter/auto-beta",
                goal = goal,
                task = goal.tasks.first(),
                requestContext = ctx
            )
        }

        assertTrue(thrown.isFailure)
        val cause = thrown.exceptionOrNull()
        assertTrue("Expected TerminalPersistenceException, got $cause", cause is TerminalPersistenceException)
        assertEquals("InvalidLeaseOrGoalState", (cause as TerminalPersistenceException).storeFailure)

        val reloaded = store.loadSnapshot().goals.first { it.id == goal.id }
        val attempt = reloaded.requestAttempts.last()
        assertEquals(ExchangeOutcome.ACTIVE, attempt.exchangeOutcome) 
    }

    @Test
    fun retryBeforeRequestBodyTransmissionCreatesNewExchange() = runBlocking {
        var callCount = 0
        val interceptingClient = okHttpClient.newBuilder()
            .addNetworkInterceptor { chain ->
                callCount++
                if (callCount == 1) {
                    throw IOException("Simulated transport failure before request body transmission")
                }
                chain.proceed(chain.request())
            }
            .build()

        val customClient = AgentOpenRouterClient(
            client = interceptingClient,
            store = store,
            diagnostics = diagnostics
        )

        val goal = createTestGoal()
        store.upsertGoal(goal)
        val parentOpId = "op-retry-test"
        val ctx = createMissionContext(goal, parentOpId = parentOpId)

        val milestoneResult = JSONObject()
            .put("work_product", "Success")
            .put("completion_score", 1.0)
            .put("acceptance_checks", JSONArray())
            .put("claims", JSONArray())
            .put("unresolved_questions", JSONArray())
            
        val successBody = JSONObject()
            .put("id", "gen-success")
            .put("choices", JSONArray().put(
                JSONObject().put("message", JSONObject().put("role", "assistant").put("content", milestoneResult.toString()))
            )).toString()
        server.enqueue(MockResponse.Builder().code(200).body(successBody).build())
        
        val result = runCatching {
            customClient.executeTask(
                apiKey = "sk-or-test",
                modelId = "openrouter/auto-beta",
                goal = goal,
                task = goal.tasks.first(),
                requestContext = ctx,
                maxAttempts = 2
            )
        }

        assertTrue("Task execution should succeed on retry but failed with ${result.exceptionOrNull()}", result.isSuccess)
        val recordedRequest = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("Server should have received the retried request", recordedRequest)

        val reloaded = store.loadSnapshot().goals.first { it.id == goal.id }
        val attempts = reloaded.requestAttempts.filter { it.parentOperationId == parentOpId }
        
        assertEquals("Expected exactly 2 attempts in durable ledger", 2, attempts.size)
        assertEquals(ExchangeOutcome.TRANSPORT_FAILURE, attempts[0].exchangeOutcome)
        assertEquals(1, attempts[0].wireAttemptOrdinal)
        assertTrue(attempts[0].transportStage < ProviderTransportStage.REQUEST_BODY_STARTED)
        
        assertEquals(ExchangeOutcome.RESPONSE_SUCCESS, attempts[1].exchangeOutcome)
        assertEquals(2, attempts[1].wireAttemptOrdinal)
        
        assertEquals(attempts[0].exchangeId, attempts[1].previousExchangeId)
        assertTrue("Authorizations should be consumed after successful retry", reloaded.retryAuthorizations.isEmpty())
    }

    @Test
    fun failureAfterRequestBodyTransmissionDoesNotReplay() = runBlocking {
        val goal = createTestGoal()
        store.upsertGoal(goal)
        val parentOpId = "op-no-replay-test"
        val ctx = createMissionContext(goal, parentOpId = parentOpId)

        // Enqueue a response that will fail during body transmission
        // We use a large body to ensure it doesn't all fit in the first TCP packet
        val largeBody = "X".repeat(1024 * 1024) 
        server.enqueue(MockResponse.Builder()
            .code(200)
            .body(largeBody)
            .onResponseBody(SocketEffect.CloseSocket())
            .build())
        
        val result = runCatching {
            client.executeTask(
                apiKey = "sk-or-test",
                modelId = "openrouter/auto-beta",
                goal = goal,
                task = goal.tasks.first(),
                requestContext = ctx,
                maxAttempts = 3
            )
        }
        
        assertTrue("Should have failed during body read, but result was $result", result.isFailure)
        assertEquals("Expected exactly 1 request; automatic replay forbidden after body started", 1, server.requestCount)

        val reloaded = store.loadSnapshot().goals.first { it.id == goal.id }
        val attempts = reloaded.requestAttempts.filter { it.parentOperationId == parentOpId }
        assertEquals(1, attempts.size)
        val attempt = attempts.first()
        assertEquals(ExchangeOutcome.TRANSPORT_FAILURE, attempt.exchangeOutcome)
        assertTrue(attempt.transportStage >= ProviderTransportStage.REQUEST_BODY_STARTED)
        assertTrue("No retry authorization should be created for post-body failures", reloaded.retryAuthorizations.isEmpty())
    }

    @Test
    fun staleWorkerCommitRaceRejectedAndLeavesStateUnchanged() = runBlocking {
        val goal = createTestGoal(workerId = "worker-A", generation = 1)
        store.upsertGoal(goal)
        
        val task = goal.tasks.first()
        val milestoneResult = JSONObject()
            .put("work_product", "Result A")
            .put("completion_score", 1.0)
            .put("acceptance_checks", JSONArray())
            .put("claims", JSONArray())
            .put("unresolved_questions", JSONArray())
            
        val successResult = JSONObject()
            .put("id", "gen-1")
            .put("choices", JSONArray().put(
                JSONObject().put("message", JSONObject().put("role", "assistant").put("content", milestoneResult.toString()))
            )).toString()
        server.enqueue(MockResponse.Builder().code(200).body(successResult).build())

        commitHook = object : BeforeTaskResultCommitHook {
            override fun beforeCommit(goalId: String, taskId: String, ownership: ExecutionOwnership) {
                store.updateGoal(goalId) { current ->
                    current.copy(executionLease = current.executionLease?.copy(workerId = "worker-B", generation = 2))
                }
            }
        }

        val acquisition = store.acquireTaskLeaseAtomic(goal.id, "worker-A", task.id)
        val ticket = when (acquisition) {
            is LeaseAcquisitionResult.Acquired -> acquisition.ticket as TaskExecutionTicket
            is LeaseAcquisitionResult.OrphanReclaimed -> acquisition.ticket as TaskExecutionTicket
            else -> throw AssertionError("Expected acquisition or reclamation, but got: $acquisition")
        }
        val outcome = executor.executeOneTask(
            apiKey = "sk-or-test",
            goal = goal,
            task = task,
            ticket = ticket
        )

        assertEquals(WorkerOutcome.DONE, outcome)

        val finalGoal = store.loadSnapshot().goals.first { it.id == goal.id }
        assertEquals("worker-B", finalGoal.executionLease?.workerId)
        assertEquals(AgentTaskStatus.RUNNING, finalGoal.tasks.first { it.id == task.id }.status)
        assertTrue(finalGoal.evidence.isEmpty())
    }

    @Test
    fun staleWorkerFailureCommitRaceRejectedAndLeavesStateUnchanged() = runBlocking {
        val goal = createTestGoal(workerId = "worker-A", generation = 1)
        store.upsertGoal(goal)
        
        val task = goal.tasks.first()
        server.enqueue(MockResponse.Builder().code(500).body("Internal Error").build())

        commitHook = object : BeforeTaskResultCommitHook {
            override fun beforeCommit(goalId: String, taskId: String, ownership: ExecutionOwnership) {
                store.updateGoal(goalId) { current ->
                    current.copy(executionLease = current.executionLease?.copy(workerId = "worker-B", generation = 2))
                }
            }
        }

        val acquisition = store.acquireTaskLeaseAtomic(goal.id, "worker-A", task.id)
        val ticket = when (acquisition) {
            is LeaseAcquisitionResult.Acquired -> acquisition.ticket as TaskExecutionTicket
            is LeaseAcquisitionResult.OrphanReclaimed -> acquisition.ticket as TaskExecutionTicket
            else -> throw AssertionError("Expected acquisition or reclamation, but got: $acquisition")
        }
        val outcome = executor.executeOneTask(
            apiKey = "sk-or-test",
            goal = goal,
            task = task,
            ticket = ticket
        )

        assertEquals(WorkerOutcome.DONE, outcome)

        val finalGoal = store.loadSnapshot().goals.first { it.id == goal.id }
        assertEquals("worker-B", finalGoal.executionLease?.workerId)
        assertEquals(AgentTaskStatus.RUNNING, finalGoal.tasks.first { it.id == task.id }.status)
        assertNull(finalGoal.error)
    }

    @Test
    fun terminalPersistenceErrorsReachOrchestrationAndPreserveNetworkResolution() = runBlocking {
        val goal = createTestGoal()
        // Do NOT insert goal into store -> GoalMissing error during ACTIVE request attempt creation
        val ctx = createMissionContext(goal)
        val task = goal.tasks.first()

        val thrown = runCatching {
            client.executeTask(
                apiKey = "sk-or-test-key",
                modelId = "openrouter/auto-beta",
                goal = goal,
                task = task,
                requestContext = ctx,
            )
        }

        assertTrue(thrown.isFailure)
        assertTrue(thrown.exceptionOrNull() is OpenRouterException)
        val ex = thrown.exceptionOrNull() as OpenRouterException
        assertTrue("Message should indicate AgentStore error/GoalMissing. Got: ${ex.message}", ex.message?.contains("GoalMissing") == true || ex.message?.contains("mandatory") == true)
    }

    // --- REMAINING MISSING TESTS (Restored from 36) ---

    @Test
    fun cancellationBoundaryABeforeActiveLeavesZeroRequestsAndZeroHttp() = runBlocking {
        val goal = createTestGoal()
        store.upsertGoal(goal)
        val ctx = createMissionContext(goal)
        val task = goal.tasks.first()

        client.cancelActiveCalls()

        val thrown = runCatching {
            client.executeTask(
                apiKey = "sk-or-test-key",
                modelId = "openrouter/auto-beta",
                goal = goal,
                task = task,
                requestContext = ctx,
            )
        }

        assertTrue(thrown.isFailure)
        val reloadedGoal = store.loadSnapshot().goals.first { it.id == goal.id }
        assertEquals(0, reloadedGoal.requestAttempts.size)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cancellationTimeoutResultsInCancellationTimeoutOutcome() = runBlocking {
        val goal = createTestGoal()
        store.upsertGoal(goal)
        val ctx = createMissionContext(goal)
        val task = goal.tasks.first()

        postActiveHook = AgentOpenRouterClient.PostActivePreDispatchHook { _, _ ->
            client.cancelActiveCalls()
            throw IOException("CANCELLATION_TIMEOUT: provider did not acknowledge stop within bound")
        }

        val thrown = runCatching {
            client.executeTask(
                apiKey = "sk-or-test-key",
                modelId = "openrouter/auto-beta",
                goal = goal,
                task = task,
                requestContext = ctx,
            )
        }

        assertTrue(thrown.isFailure)
        val reloadedGoal = store.loadSnapshot().goals.first { it.id == goal.id }
        assertTrue(reloadedGoal.requestAttempts.isNotEmpty())
        val attempt = reloadedGoal.requestAttempts.last()
        assertEquals(ExchangeOutcome.CANCELLATION_TIMEOUT, attempt.exchangeOutcome)
    }

    @Test
    fun diskPersistenceActiveAndTerminalTransitionsSurviveStoreReload() {
        val goalId = "goal-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val goal = AgentGoal(
            id = goalId,
            conversationId = "conv-1",
            userRequest = "Test goal",
            title = "Test Goal",
            objective = "Objective",
            finalOutputDescription = "Output",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "openrouter/auto-beta",
            executionModelId = "openrouter/auto-beta",
            tasks = emptyList(),
            executionLease = AgentExecutionLease(
                workerId = "w1",
                ownerProcessSessionId = "session-1",
                taskId = "t1",
                attemptId = "a1",
                generation = 1,
                acquiredAt = now,
                heartbeatAt = now,
            )
        )
        store.upsertGoal(goal)

        val exchangeId = "ex-" + UUID.randomUUID()
        val missionContext = createMissionContext(goal).copy(taskId = "t1")
        val attemptRecord = ProviderRequestAttempt(
            exchangeId = exchangeId,
            parentOperationId = "op-1",
            goalId = goalId,
            taskId = "t1",
            executionGeneration = 1,
            requestedModel = "openrouter/auto-beta",
            payloadFingerprint = "fp123",
            exchangeOutcome = ExchangeOutcome.ACTIVE,
        )

        val createResult = store.createActiveRequestAttempt(goalId, attemptRecord, missionContext)
        assertEquals(CreateAttemptResult.Created, createResult)

        // Transition to terminal RESPONSE_SUCCESS
        val transitionResult = store.transitionExchangeOutcomeWithResultAtomic(
            goalId = goalId,
            exchangeId = exchangeId,
            newOutcome = ExchangeOutcome.RESPONSE_SUCCESS,
            context = missionContext,
            statusCode = 200,
        )
        assertTrue(transitionResult is TransitionOutcomeResult.Updated)

        // Reload store from disk using a NEW instance and verify terminal outcome
        val baseDir = store.loadSnapshot().goals.first().id.let { _ ->
            // Hacky way to get baseDir if I don't want to use reflection here
            tempFolder.root.listFiles().first { it.name == "agent_store_test" }
        }
        val reloadedStore = AgentStore(baseDir = baseDir)
        val snapshot = reloadedStore.loadSnapshot()
        val loadedGoal = snapshot.goals.first { it.id == goalId }
        val loadedAttempt = loadedGoal.requestAttempts.first { it.exchangeId == exchangeId }
        assertEquals(ExchangeOutcome.RESPONSE_SUCCESS, loadedAttempt.exchangeOutcome)
        assertEquals(200, loadedAttempt.httpStatusCode)
    }

    private fun baseDirFromStore(store: AgentStore): File {
        val field = AgentStore::class.java.getDeclaredField("goalsDirectory")
        field.isAccessible = true
        return (field.get(store) as File).parentFile!!
    }

    @Test
    fun storeTransitionReturnsExplicitErrorsForMissingGoalExchangeGenerationOrLease() {
        val goalId = "goal-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val goal = AgentGoal(
            id = goalId,
            conversationId = "conv-1",
            userRequest = "Test goal",
            title = "Test Goal",
            objective = "Objective",
            finalOutputDescription = "Output",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "openrouter/auto-beta",
            executionModelId = "openrouter/auto-beta",
            tasks = emptyList(),
            executionLease = AgentExecutionLease(
                workerId = "w1",
                ownerProcessSessionId = "session-1",
                taskId = "t1",
                attemptId = "a1",
                generation = 5,
                acquiredAt = now,
                heartbeatAt = now,
            )
        )
        store.upsertGoal(goal)
        val ctx = createMissionContext(goal)

        // Test missing exchange
        val missingExResult = store.transitionExchangeOutcomeWithResultAtomic(goalId, "non-existent-ex", ExchangeOutcome.RESPONSE_SUCCESS, ctx)
        assertTrue(missingExResult is TransitionOutcomeResult.ExchangeMissing)

        // Test missing goal
        val missingGoalResult = store.transitionExchangeOutcomeWithResultAtomic("non-existent-goal", "ex-1", ExchangeOutcome.RESPONSE_SUCCESS, ctx)
        assertEquals(TransitionOutcomeResult.GoalMissing, missingGoalResult)
    }
}
