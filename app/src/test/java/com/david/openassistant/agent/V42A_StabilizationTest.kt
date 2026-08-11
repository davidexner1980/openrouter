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
        assertEquals(0, gaps.remainingDomainGap)

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
        // Use a free model where web tools are blocked by route policy, but sandbox remains allowed.
        val modelId = "google/gemma-2-9b-it:free"
        val goal = createTestGoal(complexity = ResearchComplexity.HIGH).copy(executionModelId = modelId)
        val task = createTestTask(AgentCapability.DEEP_RESEARCH)
        store.upsertGoal(goal.copy(tasks = listOf(task)))

        val acquisition = store.acquireTaskLeaseAtomic(goal.id, workerId, task.id)
        val ticket = (acquisition as LeaseAcquisitionResult.Acquired).ticket as TaskExecutionTicket

        // 1. Network and Credentials are UP, so tools are generally operational.
        every { toolRuntime.isNetworkAvailable() } returns true
        every { toolRuntime.isPublicWebConfigured() } returns true
        
        // Define sandbox tool in runtime so it's audited as operational
        val sandboxTool = mockk<com.david.openassistant.domain.tools.SafeToolDefinition>()
        every { sandboxTool.name } returns "sandbox_workbench"
        every { toolRuntime.definitions() } returns listOf(sandboxTool)
        
        // 2. Authoritative Audit: Prove sandbox is OK while web search is BLOCKED by model route
        val audit = AgentToolRegistry.availableToolsForUserWork(
            runtime = toolRuntime,
            networkAvailable = true,
            credentialsAvailable = true,
            isFreeOnly = true
        )
        
        assertTrue("Sandbox tool must remain operational on free route", 
            audit.requirements.any { it.toolName == "sandbox_workbench" && it.operational })
        
        val webSearchReq = audit.requirements.find { it.toolName == "openrouter:web_search" }
        assertNotNull("Web search requirement must be present in audit", webSearchReq)
        assertFalse("Web search must be blocked on free route", webSearchReq!!.operational)
        assertEquals("Web search block reason must be ROUTE_UNSUPPORTED", 
            ToolUnavailabilityReason.ROUTE_UNSUPPORTED, webSearchReq.unavailabilityReason)

        // 3. Dispatch: Verify gate blocks the task
        val outcome = executor.executeOneTask(apiKey, goal, task, ticket)

        assertEquals(WorkerOutcome.DONE, outcome)
        val updatedGoal = freshStore().loadSnapshot().goals.first()
        assertEquals(AgentGoalStatus.BLOCKED_NEEDS_ACTION, updatedGoal.status)
        // Should be blocked specifically by ROUTE_UNSUPPORTED for web tools
        assertTrue(updatedGoal.error!!.contains("Web Search required but unavailable (ROUTE_UNSUPPORTED)"))
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
        
        // Prove terminal attempt is non-ACTIVE and logical identity is set
        assertTrue("Logical request ID should be captured", lastAttempt.logicalRequestId.isNotEmpty())
        assertNotEquals("Terminal outcome should not be ACTIVE", ExchangeOutcome.ACTIVE, lastAttempt.exchangeOutcome)
        // No unexpected duplicates (only one attempt should exist if we didn't retry)
        assertEquals("Should have exactly one attempt record", 1, reloadedGoal.requestAttempts.size)
        
        // V42 Regression Fix: Prove logical identity captured from parent operation
        assertTrue("Logical ID should be derived from op-task prefix", lastAttempt.logicalRequestId.startsWith("op-task-"))
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
        val firstGoal = freshStore().loadSnapshot().goals.first()
        val firstAttempt = firstGoal.requestAttempts.first()
        assertEquals(ExchangeOutcome.RATE_LIMITED, firstAttempt.exchangeOutcome)
        assertEquals(429, firstAttempt.httpStatusCode)
        assertEquals("HTTP_429", firstAttempt.failureClass)
        val exchangeId = firstAttempt.exchangeId
        val logicalRequestId = firstAttempt.logicalRequestId
        
        // 2. Conflicting secondary terminalization via authoritative handleTerminalTransition
        // Derive context from real production ownership persisted in the first request
        val ctx = ProviderRequestContext.Mission(
            goalId = firstGoal.id,
            workerId = firstAttempt.reconciliationClaimOwner ?: ticket.workerId,
            taskId = firstAttempt.taskId,
            attemptId = ticket.attemptId,
            executionGeneration = firstAttempt.executionGeneration,
            leaseGeneration = firstGoal.leaseGeneration,
            acquiredAt = firstAttempt.startedAt,
            role = firstAttempt.role ?: AgentTaskRole.PRIMARY_REASONING,
            operation = MissionOperation.EXECUTE_TASK,
            parentOperationId = firstAttempt.parentOperationId
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
        
        assertEquals("Outcome must be preserved from first terminalization", ExchangeOutcome.RATE_LIMITED, finalAttempt.exchangeOutcome)
        assertEquals("HTTP status must be preserved", 429, finalAttempt.httpStatusCode)
        assertEquals("Logical identity must be preserved", logicalRequestId, finalAttempt.logicalRequestId)
        assertEquals("Wire ordinal must be preserved", firstAttempt.wireAttemptOrdinal, finalAttempt.wireAttemptOrdinal)
        assertEquals("Exchange ID must be preserved", exchangeId, finalAttempt.exchangeId)
        assertEquals("Failure class must be preserved", "HTTP_429", finalAttempt.failureClass)
        assertEquals("Should have exactly one attempt record (no duplicates)", 1, reloadedGoal.requestAttempts.size)
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
