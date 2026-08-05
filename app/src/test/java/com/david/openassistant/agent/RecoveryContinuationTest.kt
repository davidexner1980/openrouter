package com.david.openassistant.agent

import com.david.openassistant.data.diagnostics.RuntimeDiagnostics
import com.david.openassistant.data.openrouter.OpenRouterModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class RecoveryContinuationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: AgentStore
    private lateinit var diagnostics: RuntimeDiagnostics
    private lateinit var client: FakeAgentOpenRouterClient
    private lateinit var planner: AgentPlanner
    private lateinit var goalId: String
    private lateinit var taskId: String
    private lateinit var workerId: String

    @Before
    fun setup() {
        val baseDir = tempFolder.newFolder("agent_store_test")
        store = AgentStore(baseDir = baseDir)

        val diagDir = tempFolder.newFolder("diagnostics")
        diagnostics = RuntimeDiagnostics(null, diagDir, null)
        
        client = FakeAgentOpenRouterClient()
        planner = AgentPlanner(client, store, diagnostics)
        
        goalId = "goal-${UUID.randomUUID()}"
        taskId = "task-1"
        workerId = "worker-1"
    }

    @Test
    fun testSuccessfulRecoveryProposalTriggersContinuation() = runBlocking {
        val planId = "plan-1"
        val inputFp = "fp-input"
        val ticket = PlanningTicket(goalId, workerId, "session-1", 1, "attempt-1", System.currentTimeMillis())
        
        val plan = ResearchRecoveryPlan(
            id = planId,
            goalId = goalId,
            taskId = taskId,
            inputExecutionFingerprint = inputFp,
            diagnosis = ExecutionStallDiagnosis.REPEATED_CONTEXT,
            selectedTactic = EscalationTactic.REBUILD_QUERY_PORTFOLIO,
            status = RecoveryPlanStatus.PREPARED,
            logicalProviderRequestId = null,
            proposal = null,
            proposalFingerprint = null,
            validationResult = null,
            failureClassification = null,
            failureMessage = null
        )
        
        val goal = AgentGoal(
            id = goalId,
            conversationId = "conv-1",
            userRequest = "Objective",
            title = "Title",
            objective = "Objective",
            finalOutputDescription = "Desc",
            status = AgentGoalStatus.RECOVERING,
            plannerModelId = "model",
            executionModelId = "model",
            tasks = listOf(
                AgentTask(id = taskId, order = 0, title = "Task", instructions = "Inst", capability = AgentCapability.WEB_RESEARCH)
            ),
            recoveryPlans = listOf(plan),
            activeRecoveryPlanId = planId,
            leaseGeneration = 1,
            executionLease = AgentExecutionLease(workerId, "session-1", "none", "attempt-1", 1, System.currentTimeMillis(), System.currentTimeMillis()),
            objectiveContract = ObjectiveContract(1, "Title", listOf("Objective"), null, "Desc", "GENERAL", "hash")
        )
        
        store.upsertGoal(goal, true)
        
        val proposal = RecoveryProposal(
            revisedInvestigationInterpretation = "new strategy",
            specificUnresolvedGap = "gap",
            selectedSourceFamilyShift = null,
            evidenceTargets = listOf("target1"),
            falsifiers = emptyList(),
            newQueryPortfolio = listOf("query1"),
            followUpRule = null,
            rationale = "rationale",
            expectedNoveltyDimensions = listOf("strategy")
        )
        
        client.nextRecoveryProposal = RecoveryProposalGenerationResult.ProposalAvailable(proposal, AgentApiSummary(responseId = "resp-1", totalTokens = 50), "exchange-1", false)

        // 1. Initial fingerprint
        val initialFp = ContinuationSchedulingPolicy.fingerprint(goal)

        // 2. Generate proposal
        val outcome = planner.generateRecoveryProposal("key", goal, plan, ticket)
        assertEquals(WorkerOutcome.CONTINUE, outcome)

        // 3. Verify durable state
        val updatedGoal = store.loadSnapshot().goals.first { it.id == goalId }
        val updatedPlan = updatedGoal.recoveryPlans.first { it.id == planId }
        assertEquals(RecoveryPlanStatus.READY_TO_COMMIT, updatedPlan.status)
        assertNotNull(updatedPlan.proposal)
        assertEquals(proposal.revisedInvestigationInterpretation, updatedPlan.proposal?.revisedInvestigationInterpretation)

        // 4. Verify fingerprint change
        val nextFp = ContinuationSchedulingPolicy.fingerprint(updatedGoal)
        assertNotEquals("Fingerprint must change when plan status moves to READY_TO_COMMIT", initialFp, nextFp)

        // 5. Verify schedulability
        assertTrue("Mission must be schedulable after recovery progress", ContinuationSchedulingPolicy.isSchedulable(updatedGoal, goal))
    }

    @Test
    fun testReadyToCommitToCommittedChangesFingerprint() = runBlocking {
        val planId = "plan-1"
        val inputFp = "fp-input"
        val ticket = PlanningTicket(goalId, workerId, "session-1", 1, "attempt-1", System.currentTimeMillis())
        
        val proposal = RecoveryProposal(
            revisedInvestigationInterpretation = "new strategy",
            specificUnresolvedGap = "gap",
            selectedSourceFamilyShift = null,
            evidenceTargets = listOf("target1"),
            falsifiers = emptyList(),
            newQueryPortfolio = listOf("query1"),
            followUpRule = null,
            rationale = "rationale",
            expectedNoveltyDimensions = listOf("strategy")
        )
        
        val plan = ResearchRecoveryPlan(
            id = planId,
            goalId = goalId,
            taskId = taskId,
            inputExecutionFingerprint = inputFp,
            diagnosis = ExecutionStallDiagnosis.REPEATED_CONTEXT,
            selectedTactic = EscalationTactic.REBUILD_QUERY_PORTFOLIO,
            status = RecoveryPlanStatus.READY_TO_COMMIT,
            logicalProviderRequestId = null,
            proposal = proposal,
            proposalFingerprint = "fp-prop",
            validationResult = null,
            failureClassification = null,
            failureMessage = null
        )
        
        val goal = AgentGoal(
            id = goalId,
            conversationId = "conv-1",
            userRequest = "Objective",
            title = "Title",
            objective = "Objective",
            finalOutputDescription = "Desc",
            status = AgentGoalStatus.RECOVERING,
            plannerModelId = "model",
            executionModelId = "model",
            tasks = listOf(
                AgentTask(id = taskId, order = 0, title = "Task", instructions = "Inst", capability = AgentCapability.WEB_RESEARCH)
            ),
            recoveryPlans = listOf(plan),
            activeRecoveryPlanId = planId,
            leaseGeneration = 1,
            executionLease = AgentExecutionLease(workerId, "session-1", "none", "attempt-1", 1, System.currentTimeMillis(), System.currentTimeMillis())
        )
        
        store.upsertGoal(goal, true)
        
        val fpAtReady = ContinuationSchedulingPolicy.fingerprint(goal)
        
        // Commit
        val outcome = planner.commitRecoveryEffect(goal, plan, ticket)
        assertEquals(WorkerOutcome.CONTINUE, outcome)
        
        val committedGoal = store.loadSnapshot().goals.first { it.id == goalId }
        val committedPlan = committedGoal.recoveryPlans.first { it.id == planId }
        assertEquals(RecoveryPlanStatus.COMMITTED, committedPlan.status)
        assertEquals(AgentGoalStatus.QUEUED, committedGoal.status)
        
        val fpAtCommitted = ContinuationSchedulingPolicy.fingerprint(committedGoal)
        assertNotEquals("Fingerprint must change when plan status moves to COMMITTED", fpAtReady, fpAtCommitted)
    }

    @Test
    fun testPendingClaimPreventsDuplicateContinuation() = runBlocking {
        val goal = AgentGoal(
            id = goalId,
            conversationId = "conv-1",
            userRequest = "Objective",
            title = "Title",
            objective = "Objective",
            finalOutputDescription = "Desc",
            status = AgentGoalStatus.RECOVERING,
            plannerModelId = "model",
            executionModelId = "model",
            tasks = emptyList()
        )
        store.upsertGoal(goal, true)
        
        val previousGoal = goal.copy(status = AgentGoalStatus.QUEUED)
        
        val currentFp = ContinuationSchedulingPolicy.fingerprint(goal)
        
        // 1. Initially schedulable
        assertTrue(ContinuationSchedulingPolicy.isSchedulable(goal, previousGoal))
        
        // 2. Add PENDING claim
        val updatedGoal = goal.copy(
            activeContinuationSchedulingClaim = ContinuationSchedulingClaim(
                goalId = goalId,
                continuationFingerprint = currentFp,
                claimantGeneration = 1,
                workName = "test",
                state = ContinuationSchedulingState.PENDING
            )
        )
        
        // 3. Now NOT schedulable
        assertFalse("Pending claim for same fingerprint should prevent duplicate scheduling", ContinuationSchedulingPolicy.isSchedulable(updatedGoal, previousGoal))
    }

    class FakeAgentOpenRouterClient : AgentOpenRouterClient() {
        var nextRecoveryProposal: RecoveryProposalGenerationResult? = null
        
        override suspend fun createResearchRecoveryProposal(
            apiKey: String,
            modelId: String,
            goal: AgentGoal,
            plan: ResearchRecoveryPlan,
            evidence: List<AgentEvidence>,
            freeOnly: Boolean,
            requestContext: ProviderRequestContext.Mission
        ): RecoveryProposalGenerationResult {
            return nextRecoveryProposal ?: throw IllegalStateException("Next proposal not set")
        }
    }
}
