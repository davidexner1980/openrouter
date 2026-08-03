package com.david.openassistant.agent

/**
 * Centralized transition policy for durable agent goals.
 *
 * Models may propose work, but only application code is allowed to move a goal
 * through this state machine. Same-state writes are allowed so task/evidence
 * updates can be persisted without inventing a new lifecycle transition.
 */
object AgentStateMachine {
    private val allowedTransitions: Map<AgentGoalStatus, Set<AgentGoalStatus>> = mapOf(
        AgentGoalStatus.PLANNING to setOf(
            AgentGoalStatus.FINALIZING,
            AgentGoalStatus.CANCELLING,
            AgentGoalStatus.QUEUED,
            AgentGoalStatus.WAITING_FOR_CREDENTIAL,
            AgentGoalStatus.WAITING_FOR_NETWORK,
            AgentGoalStatus.PAUSED,
            AgentGoalStatus.CANCELLED,
            AgentGoalStatus.FAILED,
            AgentGoalStatus.BLOCKED,
            AgentGoalStatus.INSUFFICIENT_CURRENT_DATA,
            AgentGoalStatus.RESEARCH_CYCLES_EXHAUSTED,
            AgentGoalStatus.RECOVERING,
        ),
        AgentGoalStatus.QUEUED to setOf(
            AgentGoalStatus.RUNNING,
            AgentGoalStatus.VERIFYING,
            AgentGoalStatus.WAITING_FOR_CREDENTIAL,
            AgentGoalStatus.WAITING_FOR_NETWORK,
            AgentGoalStatus.PAUSED,
            AgentGoalStatus.CANCELLED,
            AgentGoalStatus.FAILED,
            AgentGoalStatus.FINALIZING,
            AgentGoalStatus.CANCELLING,
            AgentGoalStatus.BLOCKED,
            AgentGoalStatus.RESEARCH_CYCLES_EXHAUSTED,
            AgentGoalStatus.RECOVERING,
        ),
        AgentGoalStatus.RUNNING to setOf(
            AgentGoalStatus.QUEUED,
            AgentGoalStatus.VERIFYING,
            AgentGoalStatus.WAITING_FOR_CREDENTIAL,
            AgentGoalStatus.WAITING_FOR_NETWORK,
            AgentGoalStatus.PAUSED,
            AgentGoalStatus.CANCELLED,
            AgentGoalStatus.FAILED,
            AgentGoalStatus.FINALIZING,
            AgentGoalStatus.CANCELLING,
            AgentGoalStatus.BLOCKED,
            AgentGoalStatus.RESEARCH_CYCLES_EXHAUSTED,
            AgentGoalStatus.RECOVERING,
        ),
        AgentGoalStatus.VERIFYING to setOf(
            AgentGoalStatus.QUEUED,
            AgentGoalStatus.WAITING_FOR_CREDENTIAL,
            AgentGoalStatus.WAITING_FOR_NETWORK,
            AgentGoalStatus.COMPLETED,
            AgentGoalStatus.COMPLETED_WITH_STRONG_EVIDENCE,
            AgentGoalStatus.COMPLETED_WITH_QUALIFICATIONS,
            AgentGoalStatus.INSUFFICIENT_CURRENT_DATA,
            AgentGoalStatus.CONFLICTING_PRIMARY_SOURCES,
            AgentGoalStatus.PAUSED,
            AgentGoalStatus.CANCELLED,
            AgentGoalStatus.FAILED,
            AgentGoalStatus.FINALIZING,
            AgentGoalStatus.CANCELLING,
            AgentGoalStatus.BLOCKED,
            AgentGoalStatus.BLOCKED_WITH_PARTIAL_EVIDENCE,
            AgentGoalStatus.RECOVERING,
        ),
        AgentGoalStatus.RECOVERING to setOf(
            AgentGoalStatus.QUEUED,
            AgentGoalStatus.WAITING_FOR_NETWORK,
            AgentGoalStatus.WAITING_FOR_CREDENTIAL,
            AgentGoalStatus.REQUIRES_USER_CLARIFICATION,
            AgentGoalStatus.BLOCKED_NEEDS_ACTION,
            AgentGoalStatus.RESEARCH_CYCLES_EXHAUSTED,
            AgentGoalStatus.FAILED,
            AgentGoalStatus.CANCELLING,
            AgentGoalStatus.CANCELLED,
            AgentGoalStatus.FINALIZING,
        ),
        AgentGoalStatus.WAITING_FOR_CREDENTIAL to setOf(
            AgentGoalStatus.PLANNING,
            AgentGoalStatus.QUEUED,
            AgentGoalStatus.CANCELLED,
            AgentGoalStatus.FINALIZING,
            AgentGoalStatus.CANCELLING,
            AgentGoalStatus.BLOCKED,
        ),
        AgentGoalStatus.WAITING_FOR_NETWORK to setOf(
            AgentGoalStatus.PLANNING,
            AgentGoalStatus.QUEUED,
            AgentGoalStatus.PAUSED,
            AgentGoalStatus.CANCELLED,
            AgentGoalStatus.FAILED,
            AgentGoalStatus.BLOCKED,
            AgentGoalStatus.FINALIZING,
            AgentGoalStatus.CANCELLING,
        ),
        AgentGoalStatus.FAILED to setOf(
            AgentGoalStatus.PLANNING,
            AgentGoalStatus.QUEUED,
            AgentGoalStatus.WAITING_FOR_CREDENTIAL,
            AgentGoalStatus.WAITING_FOR_NETWORK,
            AgentGoalStatus.CANCELLED,
            AgentGoalStatus.FINALIZING,
            AgentGoalStatus.CANCELLING,
            AgentGoalStatus.BLOCKED,
        ),
        AgentGoalStatus.PAUSED to setOf(
            AgentGoalStatus.PLANNING,
            AgentGoalStatus.QUEUED,
            AgentGoalStatus.WAITING_FOR_NETWORK,
            AgentGoalStatus.CANCELLED,
            AgentGoalStatus.FINALIZING,
            AgentGoalStatus.CANCELLING,
            AgentGoalStatus.BLOCKED,
        ),
        AgentGoalStatus.BLOCKED to setOf(
            AgentGoalStatus.PLANNING,
            AgentGoalStatus.QUEUED,
            AgentGoalStatus.WAITING_FOR_NETWORK,
            AgentGoalStatus.CANCELLED,
            AgentGoalStatus.FINALIZING,
            AgentGoalStatus.CANCELLING,
        ),
        AgentGoalStatus.CANCELLING to setOf(
            AgentGoalStatus.FINALIZING,
            AgentGoalStatus.CANCELLED,
            AgentGoalStatus.FAILED,
        ),
        AgentGoalStatus.FINALIZING to setOf(
            AgentGoalStatus.COMPLETED,
            AgentGoalStatus.COMPLETED_WITH_STRONG_EVIDENCE,
            AgentGoalStatus.COMPLETED_WITH_QUALIFICATIONS,
            AgentGoalStatus.CANCELLED,
            AgentGoalStatus.FAILED,
        ),
        AgentGoalStatus.COMPLETED to emptySet(),
        AgentGoalStatus.COMPLETED_WITH_STRONG_EVIDENCE to emptySet(),
        AgentGoalStatus.COMPLETED_WITH_QUALIFICATIONS to emptySet(),
        AgentGoalStatus.BLOCKED_WITH_PARTIAL_EVIDENCE to emptySet(),
        AgentGoalStatus.INSUFFICIENT_CURRENT_DATA to emptySet(),
        AgentGoalStatus.CONFLICTING_PRIMARY_SOURCES to emptySet(),
        AgentGoalStatus.RESEARCH_CYCLES_EXHAUSTED to emptySet(),
        AgentGoalStatus.REQUIRES_USER_CLARIFICATION to setOf(
            AgentGoalStatus.PLANNING,
            AgentGoalStatus.QUEUED,
            AgentGoalStatus.PAUSED,
            AgentGoalStatus.CANCELLED,
            AgentGoalStatus.FINALIZING,
            AgentGoalStatus.CANCELLING,
            AgentGoalStatus.BLOCKED,
        ),
        AgentGoalStatus.CANCELLED to emptySet(),
        AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION to emptySet(),
        AgentGoalStatus.REJECTED to emptySet(),
        AgentGoalStatus.BLOCKED_NEEDS_ACTION to setOf(
            AgentGoalStatus.QUEUED,
            AgentGoalStatus.CANCELLED,
            AgentGoalStatus.FINALIZING,
            AgentGoalStatus.CANCELLING,
        ),
        AgentGoalStatus.WAITING_FOR_USER to setOf(
            AgentGoalStatus.QUEUED,
            AgentGoalStatus.CANCELLED,
            AgentGoalStatus.FINALIZING,
            AgentGoalStatus.CANCELLING,
        ),
        AgentGoalStatus.RESEARCHING to setOf(AgentGoalStatus.QUEUED), // Intermediate states
        AgentGoalStatus.RETRIEVING to setOf(AgentGoalStatus.QUEUED),
        AgentGoalStatus.EXTRACTING to setOf(AgentGoalStatus.QUEUED),
        AgentGoalStatus.VALIDATING to setOf(AgentGoalStatus.QUEUED),
        AgentGoalStatus.SYNTHESIZING to setOf(AgentGoalStatus.QUEUED),
    )


    fun canTransition(from: AgentGoalStatus, to: AgentGoalStatus): Boolean {
        if (from == to) return true
        // Allow finalizing or cancelling any non-terminal mission
        if ((to == AgentGoalStatus.FINALIZING || to == AgentGoalStatus.CANCELLING) && !from.isFinalTerminalStatus()) {
            return true
        }
        if (to == AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION && !from.isFinalTerminalStatus()) {
            return true
        }
        val allowed = allowedTransitions[from] ?: return false
        return (to in allowed) || allowed.any { it.name == to.name }
    }

    fun requireTransition(from: AgentGoalStatus, to: AgentGoalStatus) {
        require(canTransition(from, to)) {
            "Illegal agent goal transition: $from -> $to"
        }
    }
}
