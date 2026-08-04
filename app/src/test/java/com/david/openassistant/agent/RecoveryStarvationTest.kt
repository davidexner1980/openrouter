package com.david.openassistant.agent

import com.david.openassistant.data.diagnostics.DiagnosticEvent
import com.david.openassistant.data.diagnostics.RuntimeDiagnostics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID
import org.json.JSONObject

class RecoveryStarvationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: AgentStore
    private lateinit var diagnostics: RuntimeDiagnostics
    private lateinit var goalId: String
    private lateinit var taskId: String

    @Before
    fun setup() {
        val baseDir = tempFolder.newFolder("agent_store_test")
        store = AgentStore(baseDir = baseDir)

        val diagDir = tempFolder.newFolder("diagnostics")
        diagnostics = RuntimeDiagnostics(null, diagDir, null)
        
        goalId = "goal-${UUID.randomUUID()}"
        taskId = "task-${UUID.randomUUID()}"
    }

    @Test
    fun testCrossGenerationReconciliation() = runBlocking {
        val logicalId = "logical-1"
        val operation = MissionOperation.RECOVERY_PROPOSAL
        val fingerprint = "fp-1"
        
        val ticketGen1 = PlanningTicket(goalId, "worker-1", "session-1", 1, "attempt-1", System.currentTimeMillis())
        val planId = "plan-1"
        
        val goal = createTestGoal(status = AgentGoalStatus.RECOVERING, activeRecoveryPlanId = planId, recoveryPlans = listOf(createPreparedPlan(planId)))
        store.upsertGoal(goal, true)
        store.updateGoalAtomic(goalId, null) { it.copy(executionLease = AgentExecutionLease("worker-1", "session-1", "none", "attempt-1", 1, System.currentTimeMillis(), System.currentTimeMillis())) }

        // Generation 1 claims
        val res1 = store.claimOrReconcileProviderRequestAtomic(
            goalId = goalId,
            logicalRequestId = logicalId,
            operation = operation,
            payloadFingerprint = fingerprint,
            ticket = ticketGen1,
            recoveryPlanId = planId
        )
        if (res1 !is ReconciliationResult.NewDispatchClaimed) {
            println("DEBUG: res1 is $res1")
        }
        assertTrue(res1 is ReconciliationResult.NewDispatchClaimed)
        val exchangeId = (res1 as ReconciliationResult.NewDispatchClaimed).attempt.exchangeId
        
        // Simulate process death and new generation
        val ticketGen2 = PlanningTicket(goalId, "worker-2", "session-2", 2, "attempt-2", System.currentTimeMillis())
        store.updateGoalAtomic(goalId, null) { it.copy(
            leaseGeneration = 2,
            executionLease = AgentExecutionLease("worker-2", "session-2", "none", "attempt-2", 2, System.currentTimeMillis(), System.currentTimeMillis())
        ) }
        
        // Generation 2 reconciles
        val res2 = store.claimOrReconcileProviderRequestAtomic(
            goalId = goalId,
            logicalRequestId = logicalId,
            operation = operation,
            payloadFingerprint = fingerprint,
            ticket = ticketGen2,
            recoveryPlanId = planId
        )
        assertTrue("Generation 2 should claim takeover retry", res2 is ReconciliationResult.NewDispatchClaimed)
        val newExchangeId = (res2 as ReconciliationResult.NewDispatchClaimed).attempt.exchangeId
        assertNotEquals(exchangeId, newExchangeId)
        assertEquals(2, res2.attempt.wireAttemptOrdinal)
    }

    @Test
    fun testLogicalIdentityConflict() = runBlocking {
        val logicalId = "logical-1"
        val operation = MissionOperation.RECOVERY_PROPOSAL
        val ticket = PlanningTicket(goalId, "worker-1", "session-1", 1, "attempt-1", System.currentTimeMillis())
        val planId = "plan-1"
        
        val goal = createTestGoal(status = AgentGoalStatus.RECOVERING, activeRecoveryPlanId = planId, recoveryPlans = listOf(createPreparedPlan(planId)))
        store.upsertGoal(goal, true)
        store.updateGoalAtomic(goalId, null) { it.copy(executionLease = AgentExecutionLease("worker-1", "session-1", "none", "attempt-1", 1, System.currentTimeMillis(), System.currentTimeMillis())) }

        store.claimOrReconcileProviderRequestAtomic(
            goalId = goalId,
            logicalRequestId = logicalId,
            operation = operation,
            payloadFingerprint = "fp-original",
            ticket = ticket,
            recoveryPlanId = planId
        )
        
        val res = store.claimOrReconcileProviderRequestAtomic(
            goalId = goalId,
            logicalRequestId = logicalId,
            operation = operation,
            payloadFingerprint = "fp-CHANGED",
            ticket = ticket,
            recoveryPlanId = planId
        )
        assertTrue("Should reject different payload for same logical ID", res is ReconciliationResult.LogicalIdentityConflict)
    }

    @Test
    fun testOwnershipMismatch() = runBlocking {
        val logicalId = "logical-1"
        val operation = MissionOperation.RECOVERY_PROPOSAL
        val ticket2 = PlanningTicket(goalId, "worker-2", "session-1", 1, "attempt-1", System.currentTimeMillis())
        val planId = "plan-1"
        
        val goal = createTestGoal(status = AgentGoalStatus.RECOVERING, activeRecoveryPlanId = planId, recoveryPlans = listOf(createPreparedPlan(planId)))
        store.upsertGoal(goal, true)
        store.updateGoalAtomic(goalId, null) { it.copy(executionLease = AgentExecutionLease("worker-1", "session-1", "none", "attempt-1", 1, System.currentTimeMillis(), System.currentTimeMillis())) }

        val res = store.claimOrReconcileProviderRequestAtomic(
            goalId = goalId,
            logicalRequestId = logicalId,
            operation = operation,
            payloadFingerprint = "fp",
            ticket = ticket2,
            recoveryPlanId = planId
        )
        assertTrue("Should reject if worker doesn't own the lease", res is ReconciliationResult.OwnershipMismatch)
    }

    @Test
    fun testSuccessfulResponseSurvivesProcessDeath() = runBlocking {
        val planId = "plan-1"
        val logicalId = "recovery-plan-1"
        val operation = MissionOperation.RECOVERY_PROPOSAL
        val ticket = PlanningTicket(goalId, "worker-1", DiagnosticEvent.PROCESS_SESSION_ID, 1, "attempt-1", System.currentTimeMillis())
        
        val goal = createTestGoal(status = AgentGoalStatus.RECOVERING, activeRecoveryPlanId = planId, recoveryPlans = listOf(createPreparedPlan(planId)))
        store.upsertGoal(goal, true)
        store.updateGoalAtomic(goalId, null) { it.copy(executionLease = AgentExecutionLease("worker-1", DiagnosticEvent.PROCESS_SESSION_ID, "none", "attempt-1", 1, System.currentTimeMillis(), System.currentTimeMillis())) }

        val claim = store.claimOrReconcileProviderRequestAtomic(
            goalId = goalId,
            logicalRequestId = logicalId,
            operation = operation,
            payloadFingerprint = "fp",
            ticket = ticket,
            recoveryPlanId = planId
        ) as ReconciliationResult.NewDispatchClaimed
        val exchangeId = claim.attempt.exchangeId
        
        // DESIGN A: Atomically persist proposal then mark success
        val proposal = createTestProposal()
        val summary = AgentApiSummary(responseId = "resp-1", totalTokens = 100)
        
        store.transitionRecoveryPlanAtomic(ticket, planId, RecoveryPlanStatus.PREPARED, RecoveryPlanStatus.GENERATING, "fp1") { g, _ ->
            g.copy(recoveryPlans = g.recoveryPlans.map { 
                if (it.id == planId) it.copy(proposal = proposal, accountingSummary = summary) else it 
            })
        }
        
        val context = ProviderRequestContext.Mission(goalId, "worker-1", null, "attempt-1", 1, ticket.acquiredAt, operation = operation, parentOperationId = logicalId, recoveryPlanId = planId)
        store.transitionExchangeOutcomeWithResultAtomic(goalId, exchangeId, ExchangeOutcome.RESPONSE_SUCCESS, context)
        
        // Simulate restart
        val ticket2 = PlanningTicket(goalId, "worker-2", DiagnosticEvent.PROCESS_SESSION_ID, 2, "attempt-2", System.currentTimeMillis())
        store.updateGoalAtomic(goalId, null) { it.copy(
            leaseGeneration = 2,
            executionLease = AgentExecutionLease("worker-2", DiagnosticEvent.PROCESS_SESSION_ID, "none", "attempt-2", 2, System.currentTimeMillis(), System.currentTimeMillis())
        ) }
        
        val recon = store.claimOrReconcileProviderRequestAtomic(
            goalId = goalId,
            logicalRequestId = logicalId,
            operation = operation,
            payloadFingerprint = "fp",
            ticket = ticket2,
            recoveryPlanId = planId
        )
        assertTrue(recon is ReconciliationResult.ExistingSuccessfulResultAvailable)
        val res = recon as ReconciliationResult.ExistingSuccessfulResultAvailable
        assertEquals(proposal, res.proposal)
        assertEquals(summary, res.summary)
    }

    @Test
    fun testTwoPhaseScheduling() = runBlocking {
        val fingerprint = "fp-sched-1"
        val workName = "work-1"
        val gen = 1
        
        val goal = createTestGoal()
        store.upsertGoal(goal, true)
        
        // 1. Claim PENDING
        val claim = store.claimContinuationAtomic(goalId, fingerprint, gen, workName)
        assertNotNull(claim)
        assertEquals(ContinuationSchedulingState.PENDING, claim!!.state)
        
        // 2. Duplicate claim for same gen/fp should return existing PENDING
        val claim2 = store.claimContinuationAtomic(goalId, fingerprint, gen, workName)
        assertEquals(claim.claimId, claim2!!.claimId)
        
        // 3. Confirm CONFIRMED_ACTIVE
        val workId = UUID.randomUUID().toString()
        assertTrue(store.confirmContinuationAtomic(goalId, claim.claimId, ContinuationSchedulingState.CONFIRMED_ACTIVE, workId))
        
        val finalGoal = store.loadSnapshot().goals.first { it.id == goalId }
        assertEquals(ContinuationSchedulingState.CONFIRMED_ACTIVE, finalGoal.activeContinuationSchedulingClaim!!.state)
        assertEquals(workId, finalGoal.activeContinuationSchedulingClaim.workId)
    }

    @Test
    fun testGoalBoundRecoveryContext() = runBlocking {
        val planId = "plan-1"
        val ticket = PlanningTicket(goalId, "worker-1", "session-1", 1, "attempt-1", System.currentTimeMillis())
        
        val context = ProviderRequestContext.Mission(
            goalId = goalId,
            workerId = ticket.workerId,
            taskId = null, // Mandatory for goal-bound
            attemptId = ticket.attemptId,
            executionGeneration = ticket.generation,
            acquiredAt = ticket.acquiredAt,
            role = AgentTaskRole.PRIMARY_REASONING,
            operation = MissionOperation.RECOVERY_PROPOSAL,
            parentOperationId = "parent",
            recoveryPlanId = planId
        )
        
        val derivedTicket = context.toTicket(context.acquiredAt)
        assertTrue("Goal-bound recovery must yield PlanningTicket", derivedTicket is PlanningTicket)
        assertNull("Goal-bound recovery taskId must be null", (derivedTicket as PlanningTicket).taskId)
        
        // Test forChildOperation normalization
        val child = context.forChildOperation(MissionOperation.CYCLE_ADVANCE, AgentTaskRole.PRIMARY_REASONING)
        assertNull("forChildOperation must normalize taskId to null for goal-bound operations", child.taskId)
    }

    private fun createTestGoal(
        status: AgentGoalStatus = AgentGoalStatus.QUEUED,
        tasks: List<AgentTask> = emptyList(),
        recoveryPlans: List<ResearchRecoveryPlan> = emptyList(),
        activeRecoveryPlanId: String? = null,
        events: List<AgentEvent> = emptyList()
    ) = AgentGoal(
        id = goalId,
        conversationId = "conv-1",
        userRequest = "Objective",
        title = "Title",
        objective = "Objective",
        finalOutputDescription = "Desc",
        status = status,
        plannerModelId = "model",
        executionModelId = "model",
        tasks = tasks,
        recoveryPlans = recoveryPlans,
        activeRecoveryPlanId = activeRecoveryPlanId,
        events = events
    )

    private fun createPreparedPlan(id: String) = ResearchRecoveryPlan(
        id = id,
        goalId = goalId,
        taskId = taskId,
        inputExecutionFingerprint = "fp1",
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

    private fun createTestProposal() = RecoveryProposal(
        revisedInvestigationInterpretation = "new strategy",
        specificUnresolvedGap = "gap",
        selectedSourceFamilyShift = null,
        evidenceTargets = emptyList(),
        falsifiers = emptyList(),
        newQueryPortfolio = listOf("query1"),
        followUpRule = null,
        rationale = "rationale",
        expectedNoveltyDimensions = listOf("strategy")
    )
}
