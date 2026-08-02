package com.david.openassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AgentLeaseTest {

    @Test
    fun leaseAcquisitionSucceedsWhenNoLeaseExists() {
        val goalId = UUID.randomUUID().toString()
        val workerId = "worker-1"
        var goal = AgentGoal(
            id = goalId,
            conversationId = "conv",
            userRequest = "test",
            title = "test",
            objective = "test",
            finalOutputDescription = "test",
            status = AgentGoalStatus.QUEUED,
            plannerModelId = "model",
            executionModelId = "model",
            tasks = emptyList()
        )

        // Simulate the logic in AgentGoalWorker
        val now = System.currentTimeMillis()
        val existing = goal.executionLease
        
        assertTrue(existing == null)
        
        goal = goal.copy(
            executionLease = AgentExecutionLease(
                workerId = workerId,
                ownerProcessSessionId = "test-session",
                taskId = "none",
                attemptId = "attempt-1",
                generation = 1,
                acquiredAt = now,
                heartbeatAt = now
            )
        )
        
        assertNotNull(goal.executionLease)
        assertEquals(workerId, goal.executionLease?.workerId)
    }

    @Test
    fun leaseAcquisitionFailsWhenActiveLeaseExists() {
        val now = System.currentTimeMillis()
        val goal = AgentGoal(
            id = "goal-1",
            conversationId = "conv",
            userRequest = "test",
            title = "test",
            objective = "test",
            finalOutputDescription = "test",
            status = AgentGoalStatus.QUEUED,
            plannerModelId = "model",
            executionModelId = "model",
            tasks = emptyList(),
            executionLease = AgentExecutionLease(
                workerId = "worker-1",
                ownerProcessSessionId = "test-session",
                taskId = "none",
                attemptId = "attempt-1",
                generation = 1,
                acquiredAt = now,
                heartbeatAt = now
            )
        )

        val newWorkerId = "worker-2"
        val existing = goal.executionLease
        val isStale = existing != null && (now - existing.heartbeatAt > 300_000L)
        
        val acquired = existing == null || isStale || existing.workerId == newWorkerId
        assertFalse(acquired)
    }

    @Test
    fun staleLeaseCanBeRecovered() {
        val longAgo = System.currentTimeMillis() - 600_000L // 10 mins ago
        var goal = AgentGoal(
            id = "goal-1",
            conversationId = "conv",
            userRequest = "test",
            title = "test",
            objective = "test",
            finalOutputDescription = "test",
            status = AgentGoalStatus.QUEUED,
            plannerModelId = "model",
            executionModelId = "model",
            tasks = emptyList(),
            executionLease = AgentExecutionLease(
                workerId = "worker-1",
                ownerProcessSessionId = "test-session",
                taskId = "none",
                attemptId = "attempt-1",
                generation = 1,
                acquiredAt = longAgo,
                heartbeatAt = longAgo
            )
        )

        val newWorkerId = "worker-2"
        val now = System.currentTimeMillis()
        val existing = goal.executionLease
        val isStale = existing != null && (now - existing.heartbeatAt > 300_000L)
        
        assertTrue(isStale)
        
        if (existing == null || isStale || existing.workerId == newWorkerId) {
            goal = goal.copy(
                executionLease = AgentExecutionLease(
                    workerId = newWorkerId,
                    ownerProcessSessionId = "test-session",
                    taskId = "none",
                    attemptId = "attempt-2",
                    generation = (existing?.generation ?: 0) + 1,
                    acquiredAt = now,
                    heartbeatAt = now
                )
            )
        }
        
        assertEquals(newWorkerId, goal.executionLease?.workerId)
        assertEquals(2, goal.executionLease?.generation)
    }

    @Test
    fun resultCommitBlockedByWrongLease() {
        val goalId = "goal-1"
        val taskId = "task-1"
        val worker1Id = "worker-1"
        val attempt1Id = "attempt-1"
        
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
            attempts = listOf(AgentAttempt(id = attempt1Id, taskId = taskId, status = AgentAttemptStatus.RUNNING, startedAt = 0, modelId = "m")),
            executionLease = AgentExecutionLease(
                workerId = "worker-2", // Different worker took the lease!
                ownerProcessSessionId = "test-session",
                taskId = taskId,
                attemptId = "attempt-2",
                generation = 2,
                acquiredAt = 0,
                heartbeatAt = 0
            )
        )

        val ownership1 = ExecutionOwnership(
            workerId = worker1Id,
            leaseAttemptId = "attempt-1",
            executionGeneration = 1,
            taskId = taskId,
        )
        val canCommit = canCommitMilestoneResult(goal, taskId, attempt1Id, ownership1)
        assertFalse(canCommit)
    }
}
