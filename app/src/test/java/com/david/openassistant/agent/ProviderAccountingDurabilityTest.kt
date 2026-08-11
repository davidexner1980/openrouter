package com.david.openassistant.agent

import com.david.openassistant.data.diagnostics.DiagnosticEvent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class ProviderAccountingDurabilityTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: AgentStore
    private lateinit var baseDir: File

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("agent_store_test")
        store = AgentStore(baseDir = baseDir)
    }

    @Test
    fun testTransitionExchangeOutcomePersistsAccountingSummary() {
        val goalId = "goal-1"
        val exchangeId = "ex-1"
        val workerId = "worker-1"
        val now = System.currentTimeMillis()
        
        val attemptId = "att-1"
        val taskId = "task-1"
        val lease = AgentExecutionLease(
            workerId = workerId,
            ownerProcessSessionId = DiagnosticEvent.PROCESS_SESSION_ID,
            taskId = taskId,
            attemptId = attemptId,
            generation = 1,
            acquiredAt = now,
            heartbeatAt = now
        )

        val goal = AgentGoal(
            id = goalId,
            conversationId = "c1",
            userRequest = "r",
            title = "T",
            objective = "O",
            finalOutputDescription = "D",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "m",
            executionModelId = "m",
            tasks = listOf(AgentTask(id = taskId, order = 1, title = "T", instructions = "I", capability = AgentCapability.WEB_RESEARCH)),
            executionLease = lease
        )
        
        val attempt = ProviderRequestAttempt(
            exchangeId = exchangeId,
            goalId = goalId,
            parentOperationId = "op-1",
            executionGeneration = 1,
            requestedModel = "m",
            payloadFingerprint = "fp",
            exchangeOutcome = ExchangeOutcome.ACTIVE,
            transportStage = ProviderTransportStage.NOT_DISPATCHED,
            reconciliationClaimOwner = workerId,
            reconciliationClaimedAt = now,
            startedAt = now
        )
        
        val initialGoal = goal.copy(requestAttempts = listOf(attempt))
        
        // Use reflection to write goal directly if updateGoalAtomic is too complex to set up here
        val writeMethod = AgentStore::class.java.getDeclaredMethod("writeGoalLocked", AgentGoal::class.java, Boolean::class.javaPrimitiveType)
        writeMethod.isAccessible = true
        writeMethod.invoke(store, initialGoal, true)
        
        // 2. Transition outcome with summary
        val summary = AgentApiSummary(
            promptTokens = 100,
            completionTokens = 50,
            totalTokens = 150,
            costUsd = 0.002
        )
        
        val context = ProviderRequestContext.Mission(
            goalId = goalId,
            workerId = workerId,
            taskId = taskId,
            attemptId = attemptId,
            executionGeneration = 1,
            leaseGeneration = 1,
            acquiredAt = now,
            role = AgentTaskRole.PRIMARY_REASONING,
            operation = MissionOperation.EXECUTE_TASK,
            parentOperationId = "op-1",
            logicalRequestId = "logic-1"
        )
        
        val result = store.transitionExchangeOutcomeWithResultAtomic(
            goalId = goalId,
            exchangeId = exchangeId,
            newOutcome = ExchangeOutcome.RESPONSE_SUCCESS,
            context = context,
            summary = summary,
            statusCode = 200,
            providerResponseId = "resp-1"
        )
        
        assertTrue(result is TransitionOutcomeResult.Updated)
        val updatedAttemptInResult = (result as TransitionOutcomeResult.Updated).attempt
        assertEquals(100, updatedAttemptInResult.promptTokens)
        assertEquals(0.002, updatedAttemptInResult.costUsd!!, 0.000001)
        
        // 3. Reload from store to verify durability
        val reloadedStore = AgentStore(baseDir = baseDir)
        val snapshot = reloadedStore.loadSnapshot()
        val reloadedGoal = snapshot.goals.first { it.id == goalId }
        val reloadedAttempt = reloadedGoal.requestAttempts.first { it.exchangeId == exchangeId }
        
        assertEquals("Exchange outcome mismatch", ExchangeOutcome.RESPONSE_SUCCESS, reloadedAttempt.exchangeOutcome)
        assertEquals("Prompt tokens mismatch. JSON text: ${goalFileLocked(reloadedStore, goalId).readText()}", 100, reloadedAttempt.promptTokens)
        assertEquals("Completion tokens mismatch", 50, reloadedAttempt.completionTokens)
        assertEquals("Total tokens mismatch", 150, reloadedAttempt.totalTokens)
        assertEquals("Cost USD mismatch", 0.002, reloadedAttempt.costUsd!!, 0.000001)
        assertEquals("Provider response ID mismatch", "resp-1", reloadedAttempt.providerResponseId)
    }

    private fun goalFileLocked(store: AgentStore, goalId: String): File {
        val method = AgentStore::class.java.getDeclaredMethod("goalFileLocked", String::class.java)
        method.isAccessible = true
        return method.invoke(store, goalId) as File
    }
}
