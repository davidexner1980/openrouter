package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class TerminalRecoveryReconciliationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: AgentStore
    private lateinit var tempDir: File

    @Before
    fun setup() {
        tempDir = tempFolder.newFolder("agent_store_livelock")
        store = AgentStore(tempDir)
    }

    @Test
    fun testTerminalRecoveryLivelockRepair() {
        // Create initial goal in FAILED_RETRYABLE with a terminal provider attempt and no authorization
        val goalId = "goal-" + UUID.randomUUID()
        val planId = "plan-" + UUID.randomUUID()
        val logicalRequestId = "recovery-$planId"
        val exchangeId = "ex-" + UUID.randomUUID()
        
        val plan = ResearchRecoveryPlan(
            id = planId,
            goalId = goalId,
            taskId = "task-1",
            inputExecutionFingerprint = "fp",
            diagnosis = ExecutionStallDiagnosis.REPEATED_CONTEXT,
            selectedTactic = EscalationTactic.SHIFT_SOURCE_FAMILY,
            status = RecoveryPlanStatus.FAILED_RETRYABLE,
            logicalProviderRequestId = logicalRequestId,
            proposal = null,
            proposalFingerprint = null,
            validationResult = null,
            failureClassification = null,
            failureMessage = null
        )
        
        val attempt = ProviderRequestAttempt(
            logicalRequestId = logicalRequestId,
            exchangeId = exchangeId,
            goalId = goalId,
            taskId = null,
            executionGeneration = 1,
            role = AgentTaskRole.PRIMARY_REASONING,
            parentOperationId = "parent-1",
            requestedModel = "model-1",
            payloadFingerprint = "payload-fp",
            wireAttemptOrdinal = 1,
            transportStage = ProviderTransportStage.RESPONSE_HEADERS_RECEIVED, // Meaning we reached provider
            deliveryCertainty = ProviderDeliveryCertainty.SENT_UNCONFIRMED,
            exchangeOutcome = ExchangeOutcome.TRANSPORT_FAILURE, // Terminal
            reconciliationClaimedAt = null,
            startedAt = System.currentTimeMillis() - 10000,
            finishedAt = System.currentTimeMillis() - 5000,
            safeDiagnosticSummary = "Failed"
        )
        
        val goal = AgentGoal(
            id = goalId,
            conversationId = "conv-1",
            userRequest = "Request",
            title = "Title",
            objective = "Objective",
            finalOutputDescription = "Description",
            status = AgentGoalStatus.RECOVERING,
            plannerModelId = "planner",
            executionModelId = "executor",
            tasks = listOf(AgentTask(id = "task-1", order = 0, title = "Task", instructions = "Inst", capability = AgentCapability.WEB_RESEARCH)),
            recoveryPlans = listOf(plan),
            activeRecoveryPlanId = plan.id,
            requestAttempts = listOf(attempt)
        )
        
        store.upsertGoal(goal)
        
        // 1. Invoke repair
        val result = store.repairTerminalRecoveryLivelockAtomic(goalId)
        
        // 2. Assert typed result
        assertTrue("Expected ReconciliationRequired, got $result", result is TerminalRecoveryRepairResult.ReconciliationRequired)
        
        // 3. Assert goal is updated to RECONCILIATION_REQUIRED
        val updatedGoal = store.loadSnapshot().goals.first { it.id == goalId }
        val updatedPlan = updatedGoal.recoveryPlans.first { it.id == planId }
        assertEquals(RecoveryPlanStatus.RECONCILIATION_REQUIRED, updatedPlan.status)
        
        // 4. Invoke repair again, should be AlreadyRepaired or NotApplicable
        val result2 = store.repairTerminalRecoveryLivelockAtomic(goalId)
        assertTrue("Expected AlreadyRepaired or NotApplicable, got $result2", result2 is TerminalRecoveryRepairResult.AlreadyRepaired || result2 is TerminalRecoveryRepairResult.NotApplicable)

        // Exact counts assertion:
        val finalGoal = store.loadSnapshot().goals.first { it.id == goalId }
        val attempts = finalGoal.requestAttempts.filter { it.logicalRequestId == logicalRequestId }
        assertEquals("initial provider dispatches", 1, attempts.size)
        
        val activeAttempts = attempts.count { it.exchangeOutcome == ExchangeOutcome.ACTIVE }
        assertEquals("blind redispatches", 0, activeAttempts)

        val authorizations = finalGoal.retryAuthorizations.filter { it.logicalRequestId == logicalRequestId }
        assertEquals("retry authorizations", 0, authorizations.size) // Because it was ReconciliationRequired

        val repairs = finalGoal.idempotencyRecords.filter { it.effectType == IdempotencyEffectType.SYSTEM_REPAIR }
        assertEquals("repair commits", 2, repairs.size)
        
        // Ensure no new tool executions or accounting created by the repair itself
        assertEquals("accounting applications", 0, finalGoal.toolExecutions.size)
    }
}
