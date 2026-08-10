package com.david.openassistant.agent

import com.david.openassistant.data.diagnostics.RuntimeDiagnostics
import com.david.openassistant.domain.tools.AutonomousToolRuntime
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.*

class V42A_StabilizationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: AgentStore
    private lateinit var diagnostics: RuntimeDiagnostics
    private lateinit var client: AgentOpenRouterClient
    private lateinit var toolRuntime: AutonomousToolRuntime
    private lateinit var executor: AgentTaskExecutor

    private val goalId = "test-goal"
    private val workerId = "test-worker"
    private val apiKey = "sk-or-test"

    @Before
    fun setUp() {
        MockKAnnotations.init(this)
        val baseDir = tempFolder.newFolder("agent_store")
        store = AgentStore(baseDir = baseDir)
        diagnostics = mockk(relaxed = true)
        toolRuntime = mockk(relaxed = true)
        
        client = AgentOpenRouterClient(
            store = store,
            diagnostics = diagnostics,
            toolRuntime = toolRuntime
        )

        executor = AgentTaskExecutor(
            client = client,
            store = store,
            diagnostics = diagnostics,
            autonomyPolicy = AutonomyPolicy.DEFAULT
        )
    }

    @Test
    fun `ResearchEvidenceCapabilityGateTest - blocks when web unavailable and gap exists`() = runBlocking {
        val goal = createTestGoal(complexity = ResearchComplexity.HIGH) 
        val task = createTestTask(AgentCapability.CORRECT)
        store.upsertGoal(goal.copy(tasks = listOf(task)))

        val ticket = TaskExecutionTicket(
            goalId = goal.id,
            taskIdentity = task.id,
            workerId = workerId,
            ownerProcessSessionId = "session",
            leaseGeneration = 1,
            executionGeneration = 1,
            attemptId = UUID.randomUUID().toString(),
            acquiredAt = System.currentTimeMillis()
        )

        every { toolRuntime.isNetworkAvailable() } returns false
        every { toolRuntime.isPublicWebConfigured() } returns false
        
        val outcome = executor.executeOneTask(apiKey, goal, task, ticket)

        assertEquals(WorkerOutcome.DONE, outcome)
        val updatedGoal = store.loadSnapshot().goals.first()
        assertEquals(AgentGoalStatus.BLOCKED_NEEDS_ACTION, updatedGoal.status)
        assertTrue(updatedGoal.error!!.contains("Web Search required but unavailable"))
    }

    @Test
    fun `ResearchEvidenceCapabilityGateTest - allows synthesis when enough evidence exists`() = runBlocking {
        val sources = (1..10).map { i ->
            val url = "https://example.com/$i"
            createSourceRead(url, "Substantial content for source $i", SourceReadProvenance.VERIFIED_FETCH)
        }
        
        val goal = createTestGoal(complexity = ResearchComplexity.HIGH).copy(
            sourceReads = sources
        )
        
        val evidence = AgentEvidence(
            id = UUID.randomUUID().toString(),
            kind = AgentEvidenceKind.DEEP_RESEARCH,
            title = "Research",
            summary = "Summary",
            content = "Content",
            sources = sources.map { AgentSourceCitation(it.url, it.url) }
        )
        
        val task = createTestTask(AgentCapability.SYNTHESIZE)
        store.upsertGoal(goal.copy(tasks = listOf(task), evidence = listOf(evidence)))

        val ticket = TaskExecutionTicket(
            goalId = goal.id,
            taskIdentity = task.id,
            workerId = workerId,
            ownerProcessSessionId = "session",
            leaseGeneration = 1,
            executionGeneration = 1,
            attemptId = UUID.randomUUID().toString(),
            acquiredAt = System.currentTimeMillis()
        )

        every { toolRuntime.isNetworkAvailable() } returns false
        coEvery { client.executeTask(any(), any(), any(), any(), any(), any(), any(), any()) } returns mockk(relaxed = true)

        executor.executeOneTask(apiKey, goal, task, ticket)

        val updatedGoal = store.loadSnapshot().goals.first()
        assertNotEquals(AgentGoalStatus.BLOCKED_NEEDS_ACTION, updatedGoal.status)
    }

    @Test
    fun `EvidenceAcquisitionProvenanceTest - model URL does not reduce gap`() = runBlocking {
        val profile = ResearchAllocationProfile(targetDistinctSources = 3)
        val evidence = AgentEvidence(
            id = UUID.randomUUID().toString(),
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
            id = UUID.randomUUID().toString(),
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
            id = UUID.randomUUID().toString(),
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
