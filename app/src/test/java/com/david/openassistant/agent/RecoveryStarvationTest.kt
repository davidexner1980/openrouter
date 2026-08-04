package com.david.openassistant.agent

import com.david.openassistant.data.diagnostics.RuntimeDiagnostics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okhttp3.OkHttpClient
import java.util.UUID

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
    fun testRecoveryHasPriorityOverTaskExecution() = runBlocking {
        val task = createTestTask(status = AgentTaskStatus.QUEUED)
        val planId = "plan-1"
        val plan = createPreparedPlan(planId)

        val goal = createTestGoal(
            status = AgentGoalStatus.RECOVERING,
            tasks = listOf(task),
            recoveryPlans = listOf(plan),
            activeRecoveryPlanId = planId
        )
        store.upsertGoal(goal, true)
        
        val goalSnapshot = store.loadSnapshot().goals.first { it.id == goalId }
        
        // Use the same logic as executeGoalWorker
        val activeRecoveryId = goalSnapshot.activeRecoveryPlanId
        val activeRecoveryPlan = if (activeRecoveryId != null) goalSnapshot.recoveryPlans.firstOrNull { it.id == activeRecoveryId } else null
        val hasActiveRecovery = goalSnapshot.status == AgentGoalStatus.RECOVERING || (activeRecoveryPlan != null && activeRecoveryPlan.status.isNonTerminal())
        
        assertTrue("Should detect active recovery", hasActiveRecovery)

        val allocationProfile = AgentResearchAllocator.profileForGoal(goalSnapshot)
        val taskSelection = if (hasActiveRecovery) {
            AllocatedTaskSelection(null, "Active recovery priority.")
        } else {
            AgentResearchAllocator.chooseNextTask(goalSnapshot, allocationProfile)
        }
        
        assertNull("Task should NOT be selected when recovery has priority", taskSelection.taskId)

        val workerId = "worker-1"
        val acquisition = when {
            goalSnapshot.status == AgentGoalStatus.PLANNING -> store.acquirePlanningLeaseAtomic(goalId, workerId)
            hasActiveRecovery -> store.acquirePlanningLeaseAtomic(goalId, workerId)
            taskSelection.taskId != null -> store.acquireTaskLeaseAtomic(goalId, workerId, taskSelection.taskId)
            else -> LeaseAcquisitionResult.Rejected("none")
        }
        
        assertTrue("Should have acquired a lease", acquisition is LeaseAcquisitionResult.Acquired)
        val ticket = (acquisition as LeaseAcquisitionResult.Acquired).ticket
        assertTrue("Should have acquired PlanningTicket", ticket is PlanningTicket)
    }

    @Test
    fun testTaskLeaseRejectionDuringActiveRecovery() = runBlocking {
        val task = createTestTask()
        val planId = "plan-1"
        val plan = createPreparedPlan(planId)
        val goal = createTestGoal(
            status = AgentGoalStatus.RECOVERING,
            tasks = listOf(task),
            recoveryPlans = listOf(plan),
            activeRecoveryPlanId = planId
        )
        store.upsertGoal(goal, true)

        val acquisition = store.acquireTaskLeaseAtomic(goalId, "worker-1", taskId)
        assertTrue("Should be rejected", acquisition is LeaseAcquisitionResult.Rejected)
        assertEquals("ACTIVE_RECOVERY_OWNS_EXECUTION", (acquisition as LeaseAcquisitionResult.Rejected).reason)
        
        val finalGoal = store.loadSnapshot().goals.first { it.id == goalId }
        assertEquals("Generation should not increment", 0, finalGoal.leaseGeneration)
        assertNull("Lease should be null", finalGoal.executionLease)
    }

    @Test
    fun testWatchdogPreservesRecoveringStatus() = runBlocking {
        val goal = createTestGoal(status = AgentGoalStatus.RECOVERING)
        store.upsertGoal(goal, true)
        
        // Simulate watchdog recovery logic from MissionRecoveryWorker.kt
        store.updateGoal(goalId) { current ->
            if (current.status.isActivePhase()) {
                val recovered = AgentLifecycleReducer.recoverInterruptedWork(current)
                // Watchdog should preserve RECOVERING
                recovered.copy(
                    events = appendEvent(recovered.events, "Watchdog recovered an active goal.")
                )
            } else current
        }
        
        val finalGoal = store.loadSnapshot().goals.first { it.id == goalId }
        assertEquals("Watchdog should preserve RECOVERING status", AgentGoalStatus.RECOVERING, finalGoal.status)
    }
    
    @Test
    fun testIdenticalContextDoesNotDuplicatePreparationEvent() = runBlocking {
        val currentFingerprint = "fp-1"
        val task = createTestTask(attemptCount = 1).copy(lastRequestFingerprint = currentFingerprint)
        
        val planId = ResearchRecoveryEngine.generatePlanIdentity(goalId, taskId, currentFingerprint, ExecutionStallDiagnosis.REPEATED_CONTEXT, EscalationTactic.REBUILD_QUERY_PORTFOLIO)
        val plan = createPreparedPlan(planId).copy(inputExecutionFingerprint = currentFingerprint)
        
        val eventText = "Identical context detected. Prepared adaptive recovery tactic: REBUILD_QUERY_PORTFOLIO."
        val goal = createTestGoal(
            status = AgentGoalStatus.RUNNING,
            tasks = listOf(task),
            recoveryPlans = listOf(plan),
            activeRecoveryPlanId = planId,
            events = listOf(AgentEvent(message = eventText))
        )
        store.upsertGoal(goal, true)
        
        val executor = createTestExecutor()
        
        // Bypass priority to acquire task ticket for testing executor idempotency
        store.updateGoal(goalId) { it.copy(status = AgentGoalStatus.RUNNING, activeRecoveryPlanId = null) }
        val acq = store.acquireTaskLeaseAtomic(goalId, "worker-1", taskId) as LeaseAcquisitionResult.Acquired
        store.updateGoal(goalId) { it.copy(status = AgentGoalStatus.RUNNING, activeRecoveryPlanId = planId) }
        
        val ticket = acq.ticket as TaskExecutionTicket
        executor.executeOneTask("api-key", acq.goal, task, ticket, emptyList())
        
        val finalGoal = store.loadSnapshot().goals.first { it.id == goalId }
        val recoveryEvents = finalGoal.events.count { it.message == eventText }
        assertEquals("Should not duplicate recovery preparation event", 1, recoveryEvents)
    }

    @Test
    fun testStructuralRepairIdempotency() = runBlocking {
        val currentFingerprint = "fp-1"
        val task = createTestTask(attemptCount = 1).copy(lastRequestFingerprint = currentFingerprint)
        val planId = "plan-1"
        val plan = createPreparedPlan(planId).copy(inputExecutionFingerprint = currentFingerprint)
        
        val suppressionMessage = "Identical context detected. Prepared adaptive recovery tactic: REBUILD_QUERY_PORTFOLIO."
        val goal = createTestGoal(
            status = AgentGoalStatus.RECOVERING,
            tasks = listOf(task),
            recoveryPlans = listOf(plan),
            activeRecoveryPlanId = planId,
            events = listOf(
                AgentEvent(message = suppressionMessage),
                AgentEvent(message = suppressionMessage),
                AgentEvent(message = "Acquired lease.")
            )
        )
        store.upsertGoal(goal, true)
        
        assertTrue("First repair should succeed", store.repairRecoveryStarvationAtomic(goalId))
        assertFalse("Second repair should be a no-op", store.repairRecoveryStarvationAtomic(goalId))
        
        val finalGoal = store.loadSnapshot().goals.first { it.id == goalId }
        assertEquals("Should only have one repair event", 1, finalGoal.events.count { it.message.contains("structural repair") })
    }

    @Test
    fun testIdempotentRecoveryProposalGeneration() = runBlocking {
        val task = createTestTask()
        val planId = "plan-1"
        val plan = createPreparedPlan(planId)
        val goal = createTestGoal(
            status = AgentGoalStatus.RECOVERING,
            tasks = listOf(task),
            recoveryPlans = listOf(plan),
            activeRecoveryPlanId = planId
        )
        store.upsertGoal(goal, true)
        
        val mockClient = MockClient(store, diagnostics)
        val planner = AgentPlanner(mockClient, store, diagnostics)
        val ticket = store.acquirePlanningLeaseAtomic(goalId, "worker-1") as LeaseAcquisitionResult.Acquired
        val planningTicket = ticket.ticket as PlanningTicket

        // Boundary 1: PREPARED -> GENERATING (and READY_TO_COMMIT if call succeeds)
        planner.generateRecoveryProposal("key", ticket.goal, plan, planningTicket)
        assertEquals(1, mockClient.callCount)
        
        val goalAfter = store.loadSnapshot().goals.first { it.id == goalId }
        val planAfter = goalAfter.recoveryPlans.first { it.id == planId }
        assertEquals(RecoveryPlanStatus.READY_TO_COMMIT, planAfter.status)
        assertNotNull(planAfter.proposal)
        
        // Boundary 2: Re-run should NOT call provider again
        planner.generateRecoveryProposal("key", goalAfter, planAfter, planningTicket)
        assertEquals("Should not call provider again if proposal exists", 1, mockClient.callCount)
    }

    @Test
    fun testMismatchedFingerprintCannotCommitProposal() = runBlocking {
        val task = createTestTask()
        val planId = "plan-1"
        val plan = createPreparedPlan(planId).copy(inputExecutionFingerprint = "fp1")
        val goal = createTestGoal(
            status = AgentGoalStatus.RECOVERING,
            tasks = listOf(task),
            recoveryPlans = listOf(plan),
            activeRecoveryPlanId = planId
        )
        store.upsertGoal(goal, true)
        
        val planner = AgentPlanner(createTestClient(), store, diagnostics)
        val ticket = store.acquirePlanningLeaseAtomic(goalId, "worker-1") as LeaseAcquisitionResult.Acquired
        val planningTicket = ticket.ticket as PlanningTicket
        
        val proposal = createTestProposal()
        val planWithProposal = plan.copy(status = RecoveryPlanStatus.READY_TO_COMMIT, proposal = proposal, inputExecutionFingerprint = "MISMATCH")
        
        val outcome = planner.commitRecoveryEffect(goal, planWithProposal, planningTicket)
        assertEquals("Should fail due to fingerprint mismatch", WorkerOutcome.FAIL, outcome)
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

    private fun createTestTask(
        status: AgentTaskStatus = AgentTaskStatus.QUEUED,
        attemptCount: Int = 0
    ) = AgentTask(
        id = taskId,
        order = 0,
        title = "Task",
        instructions = "Instructions",
        capability = AgentCapability.WEB_RESEARCH,
        status = status,
        attemptCount = attemptCount
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

    private class MockClient(
        store: AgentStore,
        diagnostics: RuntimeDiagnostics
    ) : AgentOpenRouterClient(
        toolRuntime = null,
        autonomyPolicy = AutonomyPolicy(),
        client = OkHttpClient(),
        researchMonitor = null,
        diagnostics = diagnostics,
        store = store
    ) {
        var callCount = 0
        override suspend fun createResearchRecoveryProposal(
            apiKey: String,
            modelId: String,
            goal: AgentGoal,
            plan: ResearchRecoveryPlan,
            evidence: List<AgentEvidence>,
            freeOnly: Boolean,
            requestContext: ProviderRequestContext.Mission
        ): Pair<RecoveryProposal, AgentApiSummary> {
            callCount++
            return RecoveryProposal(
                revisedInvestigationInterpretation = "new strategy",
                specificUnresolvedGap = "gap",
                selectedSourceFamilyShift = null,
                evidenceTargets = emptyList(),
                falsifiers = emptyList(),
                newQueryPortfolio = listOf("query1"),
                followUpRule = null,
                rationale = "rationale",
                expectedNoveltyDimensions = listOf("strategy")
            ) to AgentApiSummary()
        }
    }

    private fun createTestClient() = AgentOpenRouterClient(
        toolRuntime = null,
        autonomyPolicy = AutonomyPolicy(),
        client = OkHttpClient(),
        researchMonitor = null,
        diagnostics = diagnostics,
        store = store
    )

    private fun createTestExecutor() = AgentTaskExecutor(
        client = createTestClient(),
        store = store,
        diagnostics = diagnostics,
        autonomyPolicy = AutonomyPolicy()
    )
}
