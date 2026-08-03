package com.david.openassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class AgentExchangeRaceTest {

    @Test
    fun responseDiscardedIfLeaseLostDuringWait() {
        val baseDir = File.createTempFile("agent-store-test", "")
        baseDir.delete()
        baseDir.mkdirs()
        try {
            val store = AgentStore(baseDir)
            val goalId = "goal-1"
            val workerId = "worker-1"
            val taskId = "task-1"
            val exchangeId = "exchange-1"
            val generation = 1
            
            // 1. Worker 1 starts with a goal and a lease
            val goal = AgentGoal(
                id = goalId,
                conversationId = "conv",
                userRequest = "test",
                title = "test",
                objective = "test",
                finalOutputDescription = "test",
                status = AgentGoalStatus.RUNNING,
                plannerModelId = "model",
                executionModelId = "model",
                tasks = listOf(AgentTask(id = taskId, order = 0, title = "t", instructions = "i", capability = AgentCapability.REASON, status = AgentTaskStatus.RUNNING)),
                executionLease = AgentExecutionLease(
                    workerId = workerId,
                    ownerProcessSessionId = com.david.openassistant.data.diagnostics.DiagnosticEvent.PROCESS_SESSION_ID,
                    taskId = taskId,
                    attemptId = "attempt-1",
                    generation = generation,
                    acquiredAt = System.currentTimeMillis(),
                    heartbeatAt = System.currentTimeMillis()
                ),
                leaseGeneration = generation
            )
            store.upsertGoal(goal)
            
            val context = ProviderRequestContext.Mission(
                goalId = goalId,
                workerId = workerId,
                taskId = taskId,
                attemptId = "attempt-1",
                executionGeneration = generation,
                acquiredAt = System.currentTimeMillis(),
                role = AgentTaskRole.PRIMARY_REASONING,
                operation = MissionOperation.EXECUTE_TASK,
                parentOperationId = "op-1"
            )
            
            // 2. Worker 1 creates an active request attempt
            val attempt = ProviderRequestAttempt(
                exchangeId = exchangeId,
                parentOperationId = "op-1",
                goalId = goalId,
                taskId = taskId,
                executionGeneration = generation,
                requestedModel = "model",
                payloadFingerprint = "fp",
                exchangeOutcome = ExchangeOutcome.ACTIVE,
                startedAt = System.currentTimeMillis()
            )
            val createResult = store.createActiveRequestAttempt(goalId, attempt, context)
            assertEquals(CreateAttemptResult.Created, createResult)
            
            // 3. Worker 2 steals the lease (simulating process recovery or timeout)
            val worker2Id = "worker-2"
            store.updateGoal(goalId) { current ->
                current.copy(
                    leaseGeneration = 2,
                    executionLease = AgentExecutionLease(
                        workerId = worker2Id,
                        ownerProcessSessionId = com.david.openassistant.data.diagnostics.DiagnosticEvent.PROCESS_SESSION_ID,
                        taskId = taskId,
                        attemptId = "attempt-2",
                        generation = 2,
                        acquiredAt = System.currentTimeMillis(),
                        heartbeatAt = System.currentTimeMillis()
                    )
                )
            }
            
            // 4. Worker 1 (the original) tries to finish its exchange
            val transitionResult = store.transitionExchangeOutcome(
                goalId = goalId,
                exchangeId = exchangeId,
                newOutcome = ExchangeOutcome.RESPONSE_SUCCESS,
                context = context,
                statusCode = 200
            )
            
            // 5. Must be rejected due to ownership loss
            assertEquals(TransitionOutcomeResult.InvalidLeaseOrGoalState, transitionResult)
        } finally {
            baseDir.deleteRecursively()
        }
    }
}
