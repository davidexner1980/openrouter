package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID

class AgentLeaseValidationTest {

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
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "model-1",
            executionModelId = "model-1",
            tasks = listOf(
                AgentTask(id = "task-1", order = 0, title = "Task 1", instructions = "do it", capability = AgentCapability.REASON)
            )
        )
        store.upsertGoal(goal)
    }

    @Test
    fun validateTicket_ValidTicketPasses() {
        val result = store.acquireTaskLeaseAtomic(goalId, workerId, "task-1")
        assertTrue(result is LeaseAcquisitionResult.Acquired)
        val ticket = (result as LeaseAcquisitionResult.Acquired).ticket

        val validation = store.validateTicket(ticket)
        assertTrue(validation is TicketValidationResult.Valid)
    }

    @Test
    fun validateTicket_WorkerMismatchFails() {
        val result = store.acquireTaskLeaseAtomic(goalId, workerId, "task-1")
        val ticket = (result as LeaseAcquisitionResult.Acquired).ticket as TaskExecutionTicket
        
        val invalidTicket = ticket.copy(workerId = "other-worker")
        val validation = store.validateTicket(invalidTicket)
        assertTrue(validation is TicketValidationResult.Mismatch)
        assertEquals("WORKER_MISMATCH", (validation as TicketValidationResult.Mismatch).reason)
    }

    @Test
    fun validateTicket_SessionMismatchFails() {
        val result = store.acquireTaskLeaseAtomic(goalId, workerId, "task-1")
        val ticket = (result as LeaseAcquisitionResult.Acquired).ticket as TaskExecutionTicket
        
        val invalidTicket = ticket.copy(ownerProcessSessionId = "other-session")
        val validation = store.validateTicket(invalidTicket)
        assertTrue(validation is TicketValidationResult.Mismatch)
        assertEquals("PROCESS_SESSION_MISMATCH", (validation as TicketValidationResult.Mismatch).reason)
    }

    @Test
    fun validateTicket_TaskMismatchFails() {
        val result = store.acquireTaskLeaseAtomic(goalId, workerId, "task-1")
        val ticket = (result as LeaseAcquisitionResult.Acquired).ticket as TaskExecutionTicket
        
        // This is exactly what happened in V36: re-acquisition with null (none) taskId
        val invalidTicket = ticket.copy(taskIdentity = "other-task")
        val validation = store.validateTicket(invalidTicket)
        assertTrue(validation is TicketValidationResult.Mismatch)
        assertEquals("TASK_MISMATCH", (validation as TicketValidationResult.Mismatch).reason)
    }

    @Test
    fun validateTicket_GenerationMismatchFails() {
        val result = store.acquireTaskLeaseAtomic(goalId, workerId, "task-1")
        val ticket = (result as LeaseAcquisitionResult.Acquired).ticket as TaskExecutionTicket
        
        // Advance generation
        store.acquireTaskLeaseAtomic(goalId, workerId, "task-1")
        
        val validation = store.validateTicket(ticket)
        assertTrue(validation is TicketValidationResult.Mismatch)
        assertEquals("LEASE_GENERATION_MISMATCH", (validation as TicketValidationResult.Mismatch).reason)
    }

    @Test
    fun validateTicket_AttemptMismatchFails() {
        val result = store.acquireTaskLeaseAtomic(goalId, workerId, "task-1")
        val ticket = (result as LeaseAcquisitionResult.Acquired).ticket as TaskExecutionTicket
        
        val invalidTicket = ticket.copy(attemptId = "other-attempt")
        val validation = store.validateTicket(invalidTicket)
        assertTrue(validation is TicketValidationResult.Mismatch)
        assertEquals("ATTEMPT_MISMATCH", (validation as TicketValidationResult.Mismatch).reason)
    }

    @Test
    fun validateTicket_LeaseMissingFails() {
        val result = store.acquireTaskLeaseAtomic(goalId, workerId, "task-1")
        val ticket = (result as LeaseAcquisitionResult.Acquired).ticket
        
        store.updateGoalAtomic(goalId, ticket) { it.copy(executionLease = null) }
        
        val validation = store.validateTicket(ticket)
        assertTrue(validation is TicketValidationResult.LeaseMissing)
    }
}
