package com.david.openassistant.agent

import com.david.openassistant.data.diagnostics.RuntimeDiagnostics
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
import java.util.UUID
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

    private val goalId = "test-goal"
    private val workerId = "test-worker"
    private val apiKey = "sk-or-test"

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        server = MockWebServer()
        server.start()

        val baseDir = tempFolder.newFolder("agent_store")
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

    @After
    fun tearDown() {
        try { server.close() } catch (e: Exception) {}
    }

    @Test
    fun `ResearchEvidenceCapabilityGateTest - blocks when web unavailable and gap exists`() = runBlocking {
        val goal = createTestGoal(complexity = ResearchComplexity.HIGH) 
        val task = createTestTask(AgentCapability.CORRECT)
        store.upsertGoal(goal.copy(tasks = listOf(task)))

        // Protocol V4.2-A.1: Acquire valid production lease
        val acquisition = store.acquireTaskLeaseAtomic(goal.id, workerId, task.id)
        val ticket = (acquisition as LeaseAcquisitionResult.Acquired).ticket as TaskExecutionTicket

        every { toolRuntime.isNetworkAvailable() } returns false
        every { toolRuntime.isPublicWebConfigured() } returns false
        
        val outcome = executor.executeOneTask(apiKey, goal, task, ticket)

        assertEquals(WorkerOutcome.DONE, outcome)
        val updatedGoal = store.loadSnapshot().goals.first()
        assertEquals(AgentGoalStatus.BLOCKED_NEEDS_ACTION, updatedGoal.status)
        assertTrue(updatedGoal.error!!.contains("Web Search required but unavailable"))
        
        // Verify task was NOT marked RUNNING (gate should trigger before mutation)
        val updatedTask = updatedGoal.tasks.first()
        assertEquals(AgentTaskStatus.QUEUED, updatedTask.status)
    }

    @Test
    fun `ResearchEvidenceCapabilityGateTest - allows synthesis when enough evidence exists`() = runBlocking {
        val sources = (1..10).map { i ->
            createSourceRead("https://example.com/$i", "Substantial content $i", SourceReadProvenance.VERIFIED_FETCH)
        }
        
        val goal = createTestGoal(complexity = ResearchComplexity.HIGH).copy(
            sourceReads = sources
        )
        
        val evidence = AgentEvidence(
            id = "evidence-1",
            kind = AgentEvidenceKind.DEEP_RESEARCH,
            title = "Research",
            summary = "Summary",
            content = "Content",
            sources = sources.map { AgentSourceCitation(it.url, it.url) }
        )
        
        val task = createTestTask(AgentCapability.SYNTHESIZE)
        store.upsertGoal(goal.copy(tasks = listOf(task), evidence = listOf(evidence)))

        val acquisition = store.acquireTaskLeaseAtomic(goal.id, workerId, task.id)
        val ticket = (acquisition as LeaseAcquisitionResult.Acquired).ticket as TaskExecutionTicket

        every { toolRuntime.isNetworkAvailable() } returns false
        
        // Mock success response
        val mockResponse = JSONObject().put("work_product", "result").put("completion_score", 1.0)
            .put("acceptance_checks", JSONArray())
            .put("claims", JSONArray()).put("unresolved_questions", JSONArray()).toString()
        val providerResponse = JSONObject().put("id", "gen-1").put("model", "test")
            .put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", mockResponse))))
            .toString()
        server.enqueue(MockResponse.Builder().code(200).body(providerResponse).build())

        executor.executeOneTask(apiKey, goal, task, ticket)

        val updatedGoal = store.loadSnapshot().goals.first()
        assertNotEquals(AgentGoalStatus.BLOCKED_NEEDS_ACTION, updatedGoal.status)
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
        
        val updatedGoal = store.loadSnapshot().goals.first()
        val lastAttempt = updatedGoal.requestAttempts.last()
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
        
        val updatedGoal = store.loadSnapshot().goals.first()
        val lastAttempt = updatedGoal.requestAttempts.last()
        assertEquals(ExchangeOutcome.RATE_LIMITED, lastAttempt.exchangeOutcome)
        assertEquals(429, lastAttempt.httpStatusCode)
        assertEquals("HTTP_429", lastAttempt.failureClass)
    }

    @Test
    fun `ProviderExactlyOnceTerminalTest - second terminalization preserves original outcome`() = runBlocking {
        val goal = createTestGoal()
        val task = createTestTask(AgentCapability.REASON)
        store.upsertGoal(goal.copy(tasks = listOf(task)))
        
        val acquisition = store.acquireTaskLeaseAtomic(goal.id, workerId, task.id)
        val ticket = (acquisition as LeaseAcquisitionResult.Acquired).ticket as TaskExecutionTicket

        val exchangeId = "test-exchange"
        ProviderRequestLedger.start(exchangeId)
        
        val ctx = ProviderRequestContext.Mission(
            goalId = goal.id,
            workerId = workerId,
            taskId = task.id,
            attemptId = ticket.attemptId,
            executionGeneration = 1,
            leaseGeneration = 1,
            acquiredAt = System.currentTimeMillis(),
            role = AgentTaskRole.PRIMARY_REASONING,
            operation = MissionOperation.EXECUTE_TASK,
            parentOperationId = "parent"
        )
        
        // 1. Initial terminalization (HTTP 500)
        store.transitionExchangeOutcomeWithResultAtomic(
            goalId = goal.id,
            exchangeId = exchangeId,
            newOutcome = ExchangeOutcome.PROVIDER_UNAVAILABLE,
            context = ctx,
            statusCode = 500,
            failureClass = "HTTP_5XX"
        )
        
        // 2. Secondary terminalization (e.g. later catch block reporting transport failure)
        // This should NOT throw TerminalPersistenceException in V4.2-A.1
        val res = store.transitionExchangeOutcomeWithResultAtomic(
            goalId = goal.id,
            exchangeId = exchangeId,
            newOutcome = ExchangeOutcome.TRANSPORT_FAILURE,
            context = ctx,
            failureClass = "SocketTimeoutException"
        )
        
        assertTrue(res is TransitionOutcomeResult.AlreadyTerminal)
        assertEquals(ExchangeOutcome.PROVIDER_UNAVAILABLE, (res as TransitionOutcomeResult.AlreadyTerminal).outcome)
        
        val reloadedGoal = store.loadSnapshot().goals.first()
        val attempt = reloadedGoal.requestAttempts.first { it.exchangeId == exchangeId }
        assertEquals(ExchangeOutcome.PROVIDER_UNAVAILABLE, attempt.exchangeOutcome)
        assertEquals(500, attempt.httpStatusCode)
    }

    @Test
    fun `EvidenceAcquisitionProvenanceTest - model URL does not reduce gap`() = runBlocking {
        val profile = ResearchAllocationProfile(targetDistinctSources = 3)
        val evidence = AgentEvidence(
            id = "ev-unverified",
            kind = AgentEvidenceKind.DEEP_RESEARCH,
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
    fun `EvidenceAcquisitionProvenanceTest - verified fetch reduces gap`() = runBlocking {
        val profile = ResearchAllocationProfile(targetDistinctSources = 3)
        val url = "https://verified.com"
        val read = createSourceRead(url, "Substantial content", SourceReadProvenance.VERIFIED_FETCH)
        val evidence = AgentEvidence(
            id = "ev-verified",
            kind = AgentEvidenceKind.DEEP_RESEARCH,
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
    fun `SourcePublicationGateTest - rejected when source requirements unmet despite completion claim`() = runBlocking {
        val profile = ResearchAllocationProfile(targetDistinctSources = 3)
        val evidence = AgentEvidence(
            id = "ev-polished",
            kind = AgentEvidenceKind.MODEL_OUTPUT,
            title = "Final Answer",
            summary = "Summary",
            content = "This is a very polished and professional final answer.",
            sources = listOf(AgentSourceCitation("Hallucinated", "https://hallucinated.com"))
        )
        val goal = createTestGoal().copy(
            evidence = listOf(evidence),
            tasks = listOf(createTestTask(AgentCapability.SYNTHESIZE).copy(status = AgentTaskStatus.COMPLETED))
        )
        
        val decision = ResearchQualityGate.evaluateGoal(goal, allocation = profile)
        assertFalse("Goal should not pass quality gate with unmet source requirements", decision.passed)
        assertTrue(decision.reasons.any { it.contains("distinct research source(s) were preserved") })
    }

    private fun createTestGoal(complexity: ResearchComplexity = ResearchComplexity.LOW) = AgentGoal(
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

    private fun createTestTask(capability: AgentCapability) = AgentTask(
        id = "test-task",
        cycleId = "cycle",
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
