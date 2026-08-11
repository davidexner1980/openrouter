package com.david.openassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AgentHeartbeatTest {

    @Test
    fun heartbeatKeepsLeaseAlive() {
        val baseDir = File.createTempFile("agent-store-test", "")
        baseDir.delete()
        baseDir.mkdirs()
        try {
            val store = AgentStore(baseDir)
            
            val goalId = "goal-1"
            val workerId = "worker-1"
            val taskId = "task-1"
            val attemptId = "attempt-1"
            val generation = 1
            
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
                    attemptId = attemptId,
                    generation = generation,
                    acquiredAt = System.currentTimeMillis() - 400_000L, // 6.6 mins ago
                    heartbeatAt = System.currentTimeMillis() - 400_000L
                ),
                leaseGeneration = generation
            )
            store.upsertGoal(goal)
            
            // Before heartbeat, it should be stale
            val staleGoal = store.loadSnapshot().goals.first { it.id == goalId }
            assertTrue("Lease should be stale before heartbeat", AgentLeasePolicy.isStale(staleGoal.executionLease))
            
            // Refresh heartbeat
            val result = store.refreshExecutionLease(
                goalId = goalId,
                workerId = workerId,
                attemptId = attemptId,
                leaseGeneration = generation,
                taskId = taskId
            )
            
            assertEquals(RefreshLeaseResult.Refreshed, result)
            
            // After heartbeat, it should NOT be stale
            val freshGoal = store.loadSnapshot().goals.first { it.id == goalId }
            assertTrue("Lease should NOT be stale after heartbeat", !AgentLeasePolicy.isStale(freshGoal.executionLease))
            assertTrue("Heartbeat timestamp should be recent", freshGoal.executionLease!!.heartbeatAt > System.currentTimeMillis() - 5000L)
        } finally {
            baseDir.deleteRecursively()
        }
    }

    @Test
    fun heartbeatRejectedIfLeaseLostToAnotherWorker() {
        val baseDir = File.createTempFile("agent-store-test", "")
        baseDir.delete()
        baseDir.mkdirs()
        try {
            val store = AgentStore(baseDir)
            
            val goalId = "goal-1"
            val worker1Id = "worker-1"
            val worker2Id = "worker-2"
            val taskId = "task-1"
            
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
                tasks = emptyList(),
                executionLease = AgentExecutionLease(
                    workerId = worker2Id, // worker-2 took the lease!
                    ownerProcessSessionId = com.david.openassistant.data.diagnostics.DiagnosticEvent.PROCESS_SESSION_ID,
                    taskId = taskId,
                    attemptId = "attempt-2",
                    generation = 2,
                    acquiredAt = System.currentTimeMillis(),
                    heartbeatAt = System.currentTimeMillis()
                ),
                leaseGeneration = 2
            )
            store.upsertGoal(goal)
            
            // worker-1 tries to refresh its old lease
            val result = store.refreshExecutionLease(
                goalId = goalId,
                workerId = worker1Id,
                attemptId = "attempt-1",
                leaseGeneration = 1,
                taskId = taskId
            )
            
            assertEquals(RefreshLeaseResult.LeaseLost, result)
        } finally {
            baseDir.deleteRecursively()
        }
    }
}
