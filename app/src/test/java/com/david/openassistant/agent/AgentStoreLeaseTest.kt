package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class AgentStoreLeaseTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var store: AgentStore
    private val goalId = "goal-1"
    private val workerId = "worker-1"

    @Before
    fun setup() {
        val root = tempFolder.newFolder()
        store = AgentStore(root)
        
        val goal = AgentGoal(
            id = goalId,
            conversationId = "conv-1",
            userRequest = "test request",
            title = "Title",
            objective = "objective",
            finalOutputDescription = "output",
            status = AgentGoalStatus.QUEUED,
            plannerModelId = "model-1",
            executionModelId = "model-1",
            tasks = emptyList()
        )
        store.upsertGoal(goal)
    }

    @Test
    fun acquireLeaseAtomic_AcquiresInitialLease() {
        val result = store.acquireLeaseAtomic(goalId, workerId, "task-1")
        assertTrue(result is LeaseAcquisitionResult.Acquired)
        val goal = (result as LeaseAcquisitionResult.Acquired).goal
        assertNotNull(goal.executionLease)
        assertEquals(workerId, goal.executionLease?.workerId)
        assertEquals("task-1", goal.executionLease?.taskId)
        assertEquals(1, goal.leaseGeneration)
    }

    @Test
    fun acquireLeaseAtomic_ReclaimsOrphanedLease() {
        // Manually inject a lease from a "different" process session
        store.updateGoal(goalId) { current ->
            current.copy(
                executionLease = AgentExecutionLease(
                    workerId = "old-worker",
                    ownerProcessSessionId = "old-session",
                    taskId = "task-1",
                    attemptId = "old-attempt",
                    generation = 1,
                    acquiredAt = System.currentTimeMillis(),
                    heartbeatAt = System.currentTimeMillis()
                ),
                leaseGeneration = 1
            )
        }

        val result = store.acquireLeaseAtomic(goalId, workerId, "task-2")
        assertTrue(result is LeaseAcquisitionResult.OrphanReclaimed)
        val goal = (result as LeaseAcquisitionResult.OrphanReclaimed).goal
        assertEquals(workerId, goal.executionLease?.workerId)
        assertEquals(2, goal.leaseGeneration)
        assertEquals(com.david.openassistant.data.diagnostics.DiagnosticEvent.PROCESS_SESSION_ID, goal.executionLease?.ownerProcessSessionId)
    }

    @Test
    fun acquireLeaseAtomic_ContendsWhenLiveOwnerPresent() {
        // Acquire lease in current session
        store.acquireLeaseAtomic(goalId, "worker-original", "task-1")

        // Try to acquire with different worker in same session
        val result = store.acquireLeaseAtomic(goalId, "worker-contender", "task-2")
        assertTrue(result is LeaseAcquisitionResult.LiveOwnerPresent)
    }

    @Test
    fun acquireLeaseAtomic_ReentrantAcquisition() {
        store.acquireLeaseAtomic(goalId, workerId, "task-1")
        
        val result = store.acquireLeaseAtomic(goalId, workerId, "task-1")
        assertTrue(result is LeaseAcquisitionResult.Acquired)
        val goal = (result as LeaseAcquisitionResult.Acquired).goal
        assertEquals(2, goal.leaseGeneration) // Generation increments even if re-entrant? 
        // Based on implementation, it does.
    }

    @Test
    fun releaseLeaseAtomic_ReleasesOwnLease() {
        store.acquireLeaseAtomic(goalId, workerId, "task-1")
        val released = store.releaseLeaseAtomic(goalId, workerId)
        assertTrue(released)
        
        val snapshot = store.loadSnapshot()
        assertNull(snapshot.goals.first().executionLease)
    }

    @Test
    fun releaseLeaseAtomic_FailsToReleaseOthersLease() {
        // Manually inject lease from another session
        store.updateGoal(goalId) { current ->
            current.copy(
                executionLease = AgentExecutionLease(
                    workerId = workerId,
                    ownerProcessSessionId = "other-session",
                    taskId = "task-1",
                    attemptId = "attempt-1",
                    generation = 1,
                    acquiredAt = System.currentTimeMillis(),
                    heartbeatAt = System.currentTimeMillis()
                )
            )
        }

        val released = store.releaseLeaseAtomic(goalId, workerId)
        assertFalse(released)
        
        val snapshot = store.loadSnapshot()
        assertNotNull(snapshot.goals.first().executionLease)
    }
}
