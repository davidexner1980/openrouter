package com.david.openassistant.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecoveryFingerprintContractTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: AgentStore
    private val goalId = "goal-1"
    private val taskId = "task-1"

    @Before
    fun setup() {
        store = AgentStore(baseDir = tempFolder.newFolder("agent_store_test"))
    }

    @Test
    fun testRecoveryPlanCreationWithFingerprints() = runBlocking {
        val goal = AgentGoal(
            id = goalId,
            conversationId = "conv-1",
            userRequest = "Original Request",
            title = "Title",
            objective = "Initial Objective",
            finalOutputDescription = "Desc",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "m",
            executionModelId = "m",
            tasks = listOf(AgentTask(id = taskId, order = 0, title = "T", instructions = "I", capability = AgentCapability.WEB_RESEARCH)),
            executionGeneration = 1
        )
        store.upsertGoal(goal, true)

        val rootFp = FingerprintUtils.calculateRootObjectiveFingerprint(goal)
        val execFp = FingerprintUtils.calculateExecutionFingerprint(goal, goal.tasks[0])

        val plan = ResearchRecoveryPlan(
            id = "plan-1",
            goalId = goalId,
            taskId = taskId,
            inputExecutionFingerprint = execFp,
            inputObjectiveFingerprint = rootFp,
            triggerExecutionFingerprint = execFp,
            version = 2,
            diagnosis = ExecutionStallDiagnosis.PROGRESS_STALL,
            selectedTactic = EscalationTactic.REFORMULATE_QUERY,
            status = RecoveryPlanStatus.PREPARED,
            logicalProviderRequestId = null,
            proposal = null,
            proposalFingerprint = null,
            validationResult = null,
            failureClassification = null,
            failureMessage = null
        )

        val ticket = PlanningTicket(goalId, "worker-1", "sid-1", 1, 1, "att-1", System.currentTimeMillis())
        val created = store.createRecoveryPlanAtomic(ticket, plan)
        assertTrue(created)

        val reloaded = store.loadSnapshot().goals.first { it.id == goalId }
        val savedPlan = reloaded.recoveryPlans.first { it.id == plan.id }
        
        assertEquals(rootFp, savedPlan.inputObjectiveFingerprint)
        assertEquals(execFp, savedPlan.triggerExecutionFingerprint)
        assertEquals(2, savedPlan.version)
    }

    @Test
    fun testLegacyPlanMigration() = runBlocking {
        // Create a V1 plan in the store (simulated)
        val legacyPlan = ResearchRecoveryPlan(
            id = "legacy-plan",
            goalId = goalId,
            taskId = taskId,
            inputExecutionFingerprint = "old-exec-fp",
            version = 1,
            diagnosis = ExecutionStallDiagnosis.PROGRESS_STALL,
            selectedTactic = EscalationTactic.REFORMULATE_QUERY,
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
            userRequest = "Original Request",
            title = "Title",
            objective = "Initial Objective",
            finalOutputDescription = "Desc",
            status = AgentGoalStatus.QUEUED,
            plannerModelId = "m",
            executionModelId = "m",
            tasks = listOf(AgentTask(id = taskId, order = 0, title = "T", instructions = "I", capability = AgentCapability.WEB_RESEARCH, cycleId = "cycle-1")),
            recoveryPlans = listOf(legacyPlan),
            activeResearchCycleId = "cycle-1",
            researchCycles = listOf(ResearchCycle(id = "cycle-1", ordinal = 1, parentCycleId = null, status = ResearchCycleStatus.ACTIVE, objectiveRevisionId = "rev-1", triggerDiagnosis = ExecutionStallDiagnosis.NONE, selectedAdvancementTactic = EscalationTactic.NONE, strategyFingerprint = "fp", queryPortfolioFingerprint = "fp", acceptedEvidenceFingerprint = "fp", unresolvedGapFingerprint = "fp", learningSummary = null))
        )
        
        // This will trigger migration in validateAndRepairInvariants on load (via upsert)
        store.upsertGoal(goal, true)

        val reloaded = store.loadSnapshot().goals.first { it.id == goalId }
        val migratedPlan = reloaded.recoveryPlans.first { it.id == legacyPlan.id }
        
        assertEquals(2, migratedPlan.version)
        assertEquals(FingerprintUtils.calculateRootObjectiveFingerprint(reloaded), migratedPlan.inputObjectiveFingerprint)
        assertEquals("old-exec-fp", migratedPlan.triggerExecutionFingerprint)
        
        assertTrue(reloaded.idempotencyRecords.any { it.key == "v43-recovery-migration:$goalId" })
    }
}
