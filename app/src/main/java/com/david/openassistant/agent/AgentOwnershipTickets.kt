package com.david.openassistant.agent

/**
 * Common fields for all ownership tickets.
 */
sealed interface AgentOwnershipTicket {
    val goalId: String
    val taskId: String?
    val workerId: String
    val ownerProcessSessionId: String
    val leaseGeneration: Int // Transient fencing
    val executionGeneration: Int // Stable mission epoch
    val attemptId: String
    val acquiredAt: Long
}

/**
 * Ticket for goal-scoped operations like planning.
 */
data class PlanningTicket(
    override val goalId: String,
    override val workerId: String,
    override val ownerProcessSessionId: String,
    override val leaseGeneration: Int,
    override val executionGeneration: Int,
    override val attemptId: String,
    override val acquiredAt: Long,
) : AgentOwnershipTicket {
    override val taskId: String? = null
}

/**
 * Ticket for task-bound execution.
 * Requires a non-blank `taskId`.
 */
data class TaskExecutionTicket(
    override val goalId: String,
    val taskIdentity: String, // Explicit non-null task ID
    override val workerId: String,
    override val ownerProcessSessionId: String,
    override val leaseGeneration: Int,
    override val executionGeneration: Int,
    override val attemptId: String,
    override val acquiredAt: Long,
) : AgentOwnershipTicket {
    override val taskId: String = taskIdentity

    init {
        require(taskIdentity.isNotBlank()) { "TaskExecutionTicket requires a non-blank task ID." }
        require(taskIdentity != "none") { "TaskExecutionTicket cannot use the reserved 'none' task ID." }
    }
}

sealed class TicketValidationResult {
    object Valid : TicketValidationResult()
    data class Mismatch(
        val reason: String,
        val field: String,
        val expected: String?,
        val actual: String?,
    ) : TicketValidationResult()
    object LeaseMissing : TicketValidationResult()
    object LeaseExpired : TicketValidationResult()
}
