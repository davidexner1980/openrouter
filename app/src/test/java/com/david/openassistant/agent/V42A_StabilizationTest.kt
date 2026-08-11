package com.david.openassistant.agent

import com.david.openassistant.data.diagnostics.RuntimeDiagnostics
import com.david.openassistant.data.openrouter.OpenRouterModel
import com.david.openassistant.domain.tools.AutonomousToolRuntime
import io.mockk.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class V42A_StabilizationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var store: AgentStore
    private lateinit var diagnostics: RuntimeDiagnostics
    private lateinit var client: AgentOpenRouterClient
    private lateinit var toolRuntime: AutonomousToolRuntime
    private lateinit var executor: AgentTaskExecutor
    private lateinit var okHttpClient: OkHttpClient
    private lateinit var baseDir: File

    private val goalId = "test-goal"
    private val workerId = "test-worker"
    private val apiKey = "sk-or-test"

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        server = MockWebServer()
        server.start()

        baseDir = tempFolder.newFolder("agent_store")
        store = AgentStore(baseDir = baseDir)
        diagnostics = mockk(relaxed = true)
        toolRuntime = mockk(relaxed = true)

        okHttpClient = OkHttpClient.Builder()
            .connectTimeout(500, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
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
        
        client = spyk(AgentOpenRouterClient(
            client = okHttpClient,
            store = store,
            diagnostics = diagnostics,
            toolRuntime = toolRuntime
        ))

        executor = AgentTaskExecutor(
            client = client,
            store = store,
            diagnostics = diagnostics,
            autonomyPolicy = AutonomyPolicy.DEFAULT
        )
    }

    private fun freshStore() = AgentStore(baseDir = baseDir)

    @After
    fun tearDown() {
        try { server.close() } catch (e: Exception) {}
    }

    @Test
    fun `ResearchEvidenceCapabilityGateTest - Case A - blocks when web unavailable and gap exists`() = runBlocking {
        val goal = createTestGoal(complexity = ResearchComplexity.HIGH) 
        val task = createTestTask(AgentCapability.DEEP_RESEARCH) 
        store.upsertGoal(goal.copy(tasks = listOf(task)))

        val acquisition = store.acquireTaskLeaseAtomic(goal.id, workerId, task.id)
        val ticket = (acquisition as LeaseAcquisitionResult.Acquired).ticket as TaskExecutionTicket

        every { toolRuntime.isNetworkAvailable() } returns false
        every { toolRuntime.isPublicWebConfigured() } returns false
        
        val outcome = executor.executeOneTask(apiKey, goal, task, ticket)

        assertEquals(WorkerOutcome.DONE, outcome)
        val updatedGoal = freshStore().loadSnapshot().goals.first()
        assertEquals(AgentGoalStatus.BLOCKED_NEEDS_ACTION, updatedGoal.status)
        assertTrue(updatedGoal.error!!.contains("Web Search required but unavailable"))
        
        // Verify task was NOT marked RUNNING (gate should trigger before mutation)
        val updatedTask = updatedGoal.tasks.first()
        assertEquals(AgentTaskStatus.QUEUED, updatedTask.status)
    }

    @Test
    fun `ResearchEvidenceCapabilityGateTest - Case B - allows synthesis when enough evidence exists`() = runBlocking {
        val sources = listOf(
            createSourceRead("https://a.com/1", "Substantial content A", SourceReadProvenance.VERIFIED_FETCH),
            createSourceRead("https://b.com/1", "Substantial content B", SourceReadProvenance.VERIFIED_FETCH)
        )
        
        val stepResult = AgentStepResult(
            content = "Research findings",
            summary = AgentApiSummary(responseId = "test", httpStatusCode = 200),
            sources = sources.map { AgentSourceCitation(it.url, it.url) },
            toolExecutions = listOf(
                AgentToolExecution(LOCAL_WEB_FETCH_TOOL, "Fetched A", true),
                AgentToolExecution(LOCAL_WEB_FETCH_TOOL, "Fetched B", true)
            )
        )
        
        val evidence = AgentEvidence(
            id = "evidence-1",
            kind = AgentEvidenceKind.DEEP_RESEARCH,
            cycleId = "cycle-1",
            title = "Research",
            summary = "Summary",
            content = durableEvidenceContent(stepResult, 1000),
            sources = stepResult.sources
        )
        
        val goal = createTestGoal(complexity = ResearchComplexity.LOW).copy(
            sourceReads = sources,
            evidence = listOf(evidence)
        )
        
        val profile = AgentResearchAllocator.profileForGoal(goal)
        val gaps = AgentResearchAllocator.evaluateGaps(goal, profile)
        assertEquals(0, gaps.remainingSourceGap)
        assertEquals(0, gaps.remainingReadGap)

        val task = createTestTask(AgentCapability.SYNTHESIZE)
        store.upsertGoal(goal.copy(tasks = listOf(task)))

        val acquisition = store.acquireTaskLeaseAtomic(goal.id, workerId, task.id)
        val ticket = (acquisition as LeaseAcquisitionResult.Acquired).ticket as TaskExecutionTicket

        every { toolRuntime.isNetworkAvailable() } returns false
        
        val mockResponse = JSONObject().put("work_product", "result").put("completion_score", 1.0)
            .put("acceptance_checks", JSONArray())
            .put("claims", JSONArray()).put("unresolved_questions", JSONArray()).toString()
        val providerResponse = JSONObject().put("id", "gen-1").put("model", "test")
            .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", mockResponse))))
            .toString()
        server.enqueue(MockResponse.Builder().code(200).body(providerResponse).build())

        executor.executeOneTask(apiKey, goal, task, ticket)

        val updatedGoal = freshStore().loadSnapshot().goals.first()
        assertNotEquals(AgentGoalStatus.BLOCKED_NEEDS_ACTION, updatedGoal.status)
    }

    @Test
    fun `ResearchEvidenceCapabilityGateTest - Case C - allows local reasoning without web`() = runBlocking {
        val goal = createTestGoal()
        val task = createTestTask(AgentCapability.REASON)
        store.upsertGoal(goal.copy(tasks = listOf(task)))

        val acquisition = store.acquireTaskLeaseAtomic(goal.id, workerId, task.id)
        val ticket = (acquisition as LeaseAcquisitionResult.Acquired).ticket as TaskExecutionTicket

        every { toolRuntime.isNetworkAvailable() } returns false
        
        val mockResponse = JSONObject().put("work_product", "result").put("completion_score", 1.0)
            .put("acceptance_checks", JSONArray())
            .put("claims", JSONArray()).put("unresolved_questions", JSONArray()).toString()
        val providerResponse = JSONObject().put("id", "gen-1").put("model", "test")
            .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", mockResponse))))
            .toString()
        server.enqueue(MockResponse.Builder().code(200).body(providerResponse).build())

        executor.executeOneTask(apiKey, goal, task, ticket)

        val updatedGoal = freshStore().loadSnapshot().goals.first()
        assertNotEquals(AgentGoalStatus.BLOCKED_NEEDS_ACTION, updatedGoal.status)
        assertEquals(AgentTaskStatus.COMPLETED, updatedGoal.tasks.first().status)
    }

    @Test
    fun `ResearchEvidenceCapabilityGateTest - Case D - sandbox capability does not satisfy web need`() = runBlocking {
        val goal = createTestGoal(complexity = ResearchComplexity.HIGH)
        val task = createTestTask(AgentCapability.DEEP_RESEARCH)
        store.upsertGoal(goal.copy(tasks = listOf(task)))

        val acquisition = store.acquireTaskLeaseAtomic(goal.id, workerId, task.id)
        val ticket = (acquisition as LeaseAcquisitionResult.Acquired).ticket as TaskExecutionTicket

        // Network is down (so public web is unavailable) but tool runtime itself exists
        every { toolRuntime.isNetworkAvailable() } returns false
        every { toolRuntime.isPublicWebConfigured() } returns true
        
        val outcome = executor.executeOneTask(apiKey, goal, task, ticket)

        assertEquals(WorkerOutcome.DONE, outcome)
        val updatedGoal = freshStore().loadSnapshot().goals.first()
        assertEquals(AgentGoalStatus.BLOCKED_NEEDS_ACTION, updatedGoal.status)
    }

    @Test
    fun `ProviderFailureClassificationTest - HTTP 500 maps to PROVIDER_UNAVAILABLE`() = runBlocking {
        val goal = createTestGoal()
        val task = createTestTask(AgentCapability.REASON)
        store.upsertGoal(goal.copy(tasks = listOf(task)))
        
        val acquisition = store.acquireTaskLeaseAtomic(goal.id, workerId, task.id)
        val ticket = (acquisition as LeaseAcquisitionResult.Acquired).ticket as TaskExecutionTicket

        // Enqueue 500s (AgentOpenRouterClient has internal retries for 500)
        repeat(3) {
            server.enqueue(MockResponse.Builder().code(500).body("Internal Server Error").build())
        }

        executor.executeOneTask(apiKey, goal, task, ticket)
        
        // Assert durable classification via fresh store
        val reloadedGoal = freshStore().loadSnapshot().goals.first()
        val lastAttempt = reloadedGoal.requestAttempts.last()
        assertEquals(ExchangeOutcome.PROVIDER_UNAVAILABLE, lastAttempt.exchangeOutcome)
        assertEquals(500, lastAttempt.httpStatusCode)
        assertEquals("HTTP_5XX", lastAttempt.failureClass)
    }

    @Test
    fun `ProviderFailureClassificationTest - HTTP 429 maps to RATE_LIMITED`() = runBlocking {
        val goal = createTestGoal()
        val task = createTestTask(AgentCapability.REASON)
        store.upsertGoal(goal.copy(tasks = listOf(task)))
        
        val acquisition = store.acquireTaskLeaseAtomic(goal.id, workerId, task.id)
        val ticket = (acquisition as LeaseAcquisitionResult.Acquired).ticket as TaskExecutionTicket

        server.enqueue(MockResponse.Builder().code(429).body("Too Many Requests").build())

        executor.executeOneTask(apiKey, goal, task, ticket)
        
        // Assert durable classification via fresh store
        val reloadedGoal = freshStore().loadSnapshot().goals.first()
        val lastAttempt = reloadedGoal.requestAttempts.last()
        assertEquals(ExchangeOutcome.RATE_LIMITED, lastAttempt.exchangeOutcome)
        assertEquals(429, lastAttempt.httpStatusCode)
        assertEquals("HTTP_429", lastAttempt.failureClass)
    }

    @Test
    fun `ProviderTimeoutClassificationTest - post-dispatch timeout maps to CALL_TIMEOUT`() = runBlocking {
        val goal = createTestGoal()
        val task = createTestTask(AgentCapability.REASON)
        store.upsertGoal(goal.copy(tasks = listOf(task)))
        
        val acquisition = store.acquireTaskLeaseAtomic(goal.id, workerId, task.id)
        val ticket = (acquisition as LeaseAcquisitionResult.Acquired).ticket as TaskExecutionTicket

        // Deterministic timeout: server receives request but withholds response
        server.enqueue(
            MockResponse.Builder()
                .bodyDelay(1, TimeUnit.SECONDS) // Delay exceeds 500ms client timeout
                .body("{}")
                .build()
        )

        executor.executeOneTask(apiKey, goal, task, ticket)
        
        // Assert durable classification via fresh store
        val reloadedGoal = freshStore().loadSnapshot().goals.first()
        val lastAttempt = reloadedGoal.requestAttempts.last()
        
        assertEquals(ExchangeOutcome.TRANSPORT_FAILURE, lastAttempt.exchangeOutcome)
        assertEquals("CALL_TIMEOUT", lastAttempt.failureClass)
    }

    @Test
    fun `ProviderExactlyOnceTerminalTest - second terminalization preserves original outcome`() = runBlocking {
        val goal = createTestGoal()
        val task = createTestTask(AgentCapability.REASON)
        store.upsertGoal(goal.copy(tasks = listOf(task)))
        
        val acquisition = store.acquireTaskLeaseAtomic(goal.id, workerId, task.id)
        val ticket = (acquisition as LeaseAcquisitionResult.Acquired).ticket as TaskExecutionTicket

        // 1. Initial production-shaped terminalization (HTTP 429)
        server.enqueue(MockResponse.Builder().code(429).body("Too Many Requests").build())
        
        executor.executeOneTask(apiKey, goal, task, ticket)
        
        // Prove first truth durable via fresh store
        val firstStore = freshStore()
        val firstGoal = firstStore.loadSnapshot().goals.first()
        val firstAttempt = firstGoal.requestAttempts.first()
        assertEquals(ExchangeOutcome.RATE_LIMITED, firstAttempt.exchangeOutcome)
        assertEquals(429, firstAttempt.httpStatusCode)
        val exchangeId = firstAttempt.exchangeId
        
        // 2. Conflicting secondary terminalization via authoritative handleTerminalTransition
        val ctx = ProviderRequestContext.Mission(
            goalId = goal.id,
            workerId = workerId,
            taskId = task.id,
            attemptId = ticket.attemptId,
            executionGeneration = 1,
            leaseGeneration = 0,
            acquiredAt = System.currentTimeMillis(),
            role = AgentTaskRole.PRIMARY_REASONING,
            operation = MissionOperation.EXECUTE_TASK,
            parentOperationId = "parent"
        )
        
        val resolution = AgentOpenRouterClient.ExchangeResolution(
            outcome = ExchangeOutcome.TRANSPORT_FAILURE,
            failureClass = "SocketTimeoutException"
        )
        
        client.handleTerminalTransition(ctx, exchangeId, resolution)
        
        // 3. Final reload from fresh store to assert original truth preserved
        val finalStore = freshStore()
        val reloadedGoal = finalStore.loadSnapshot().goals.first()
        val finalAttempt = reloadedGoal.requestAttempts.first { it.exchangeId == exchangeId }
        assertEquals(ExchangeOutcome.RATE_LIMITED, finalAttempt.exchangeOutcome)
        assertEquals(429, finalAttempt.httpStatusCode)
    }

    @Test
    fun `ResearchEvidenceCapabilityGateTest - Case E - model URL does not reduce gap`() = runBlocking {
        val profile = ResearchAllocationProfile(targetDistinctSources = 3)
        val evidence = AgentEvidence(
            id = "ev-unverified",
            kind = AgentEvidenceKind.DEEP_RESEARCH,
            cycleId = "cycle-1",
            title = "Research",
            summary = "Summary",
            content = "Content",
            sources = listOf(AgentSourceCitation("Title", "https://unverified.com"))
        )
        val goal = createTestGoal().copy(evidence = listOf(evidence))
        
        val gaps = AgentResearchAllocator.evaluateGaps(goal, profile)
        assertEquals(3, gaps.remainingSourceGap)
    }

    @Test
    fun `ResearchEvidenceCapabilityGateTest - Case F - verified fetch reduces gap`() = runBlocking {
        val profile = ResearchAllocationProfile(targetDistinctSources = 3)
        val url = "https://verified.com"
        val read = createSourceRead(url, "Substantial content", SourceReadProvenance.VERIFIED_FETCH)
        val evidence = AgentEvidence(
            id = "ev-verified",
            kind = AgentEvidenceKind.DEEP_RESEARCH,
            cycleId = "cycle-1",
            title = "Research",
            summary = "Summary",
            content = "Content",
            sources = listOf(AgentSourceCitation("Title", url))
        )
        val goal = createTestGoal().copy(evidence = listOf(evidence), sourceReads = listOf(read))
        
        val gaps = AgentResearchAllocator.evaluateGaps(goal, profile)
        assertEquals(2, gaps.remainingSourceGap)
    }

    @Test
    fun `ResearchEvidenceCapabilityGateTest - Case G - quality gate rejects completion with unmet source gap`() = runBlocking {
        val profile = ResearchAllocationProfile(targetDistinctSources = 3)
        val evidence = AgentEvidence(
            id = "ev-polished",
            kind = AgentEvidenceKind.MODEL_OUTPUT,
            cycleId = "cycle-1",
            title = "Final Answer",
            summary = "Summary",
            content = "This is a very polished and professional final answer.",
            sources = listOf(AgentSourceCitation("Hallucinated", "https://hallucinated.com"))
        )
        val goal = createTestGoal().copy(
            evidence = listOf(evidence),
            tasks = listOf(
                createTestTask(AgentCapability.WEB_RESEARCH).copy(status = AgentTaskStatus.COMPLETED),
                createTestTask(AgentCapability.SYNTHESIZE).copy(status = AgentTaskStatus.COMPLETED)
            )
        )
        
        val decision = ResearchQualityGate.evaluateGoal(goal, allocation = profile)
        assertFalse("Goal should not pass quality gate with unmet source requirements", decision.passed)
        assertTrue(decision.reasons.any { it.contains("distinct research source(s) were preserved") })
    }

    private fun createTestGoal(complexity: ResearchComplexity = ResearchComplexity.LOW): AgentGoal {
        val goal = AgentGoal(
            id = goalId,
            conversationId = "conv",
            userRequest = if (complexity == ResearchComplexity.HIGH) "Deep research about everything" else "test",
            title = "Test",
            objective = "Test",
            finalOutputDescription = "Test",
            status = AgentGoalStatus.QUEUED,
            plannerModelId = "model",
            executionModelId = "model",
            tasks = emptyList()
        )
        // Add a cycle so it passes invariants
        val cycle = ResearchCycle(
            id = "cycle-1",
            ordinal = 1,
            parentCycleId = null,
            status = ResearchCycleStatus.ACTIVE,
            objectiveRevisionId = "rev-1",
            triggerDiagnosis = ExecutionStallDiagnosis.NONE,
            selectedAdvancementTactic = EscalationTactic.NONE,
            strategyFingerprint = "fp",
            queryPortfolioFingerprint = "fp",
            acceptedEvidenceFingerprint = "fp",
            unresolvedGapFingerprint = "fp",
            learningSummary = null
        )
        return goal.copy(
            researchCycles = listOf(cycle),
            activeResearchCycleId = cycle.id
        )
    }

    private fun createTestTask(capability: AgentCapability) = AgentTask(
        id = "test-task",
        cycleId = "cycle-1",
        order = 0,
        title = "Test Task",
        instructions = "Do it",
        capability = capability,
        status = AgentTaskStatus.QUEUED,
        weight = 1.0,
        acceptanceCriteria = emptyList()
    )

    private fun createSourceRead(url: String, content: String, provenance: SourceReadProvenance): SourceRead {
        val canonical = ResearchQualityGate.canonicalSourceUrl(url)
        val hash = FingerprintUtils.hash(content)
        return SourceRead(
            id = scopedSourceReadId(canonical, hash),
            url = url,
            canonicalUrl = canonical,
            documentId = scopedSourceDocumentId(canonical),
            contentHash = hash,
            httpCode = 200,
            contentType = "text/html",
            content = content,
            sourceRole = "research",
            authorityScore = 50,
            retrievedAt = System.currentTimeMillis(),
            readAt = System.currentTimeMillis(),
            provenance = provenance
        )
    }
}
