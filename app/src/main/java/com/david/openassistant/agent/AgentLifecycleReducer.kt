package com.david.openassistant.agent

/**
 * Pure lifecycle mutations shared by the UI and tests.
 *
 * User intent wins races with network completion: active attempts are closed
 * before a goal is paused or cancelled, and a paused in-flight task is placed
 * back in the queue without consuming a model retry.
 */
object AgentLifecycleReducer {
    fun recoverInterruptedWork(
        goal: AgentGoal,
        now: Long = System.currentTimeMillis(),
    ): AgentGoal {
        val runningTaskIds = goal.tasks
            .filter { it.status == AgentTaskStatus.RUNNING }
            .mapTo(mutableSetOf()) { it.id }
        val verificationInterrupted = goal.status == AgentGoalStatus.VERIFYING
        if (runningTaskIds.isEmpty() && !verificationInterrupted) return goal

        return goal.copy(
            status = when (goal.status) {
                AgentGoalStatus.RUNNING,
                AgentGoalStatus.VERIFYING,
                -> AgentGoalStatus.QUEUED

                else -> goal.status
            },
            tasks = goal.tasks.map { task ->
                if (task.id in runningTaskIds) {
                    task.copy(
                        status = AgentTaskStatus.QUEUED,
                        attemptCount = (task.attemptCount - 1).coerceAtLeast(0),
                        lastError = "The previous worker was interrupted before it committed a result; the milestone was safely re-queued.",
                        finishedAt = now,
                    )
                } else {
                    task
                }
            },
            attempts = goal.attempts.map { attempt ->
                if (attempt.taskId in runningTaskIds && attempt.status == AgentAttemptStatus.RUNNING) {
                    attempt.copy(
                        status = AgentAttemptStatus.FAILED,
                        finishedAt = now,
                        error = "Worker interrupted before result commit.",
                    )
                } else {
                    attempt
                }
            },
            events = appendEvent(
                goal.events,
                when {
                    verificationInterrupted && runningTaskIds.isNotEmpty() ->
                        "Recovered interrupted verification and ${runningTaskIds.size} milestone(s); work was safely re-queued."
                    verificationInterrupted ->
                        "Recovered an interrupted verification pass; verification was safely re-queued."
                    else ->
                        "Recovered ${runningTaskIds.size} interrupted milestone(s) from the durable checkpoint."
                },
            ),
        )
    }

    fun pause(
        goal: AgentGoal,
        now: Long = System.currentTimeMillis(),
        reason: String = "Goal paused by the user.",
    ): AgentGoal {
        if (goal.status !in setOf(
                AgentGoalStatus.PLANNING,
                AgentGoalStatus.QUEUED,
                AgentGoalStatus.RUNNING,
                AgentGoalStatus.VERIFYING,
                AgentGoalStatus.WAITING_FOR_CREDENTIAL,
                AgentGoalStatus.WAITING_FOR_NETWORK,
            )
        ) {
            return goal
        }
        return goal.copy(
            status = AgentGoalStatus.PAUSED,
            tasks = goal.tasks.map { task ->
                if (task.status == AgentTaskStatus.RUNNING) {
                    task.copy(
                        status = AgentTaskStatus.QUEUED,
                        attemptCount = (task.attemptCount - 1).coerceAtLeast(0),
                        lastError = "Paused before the active worker committed its result.",
                        finishedAt = now,
                    )
                } else {
                    task
                }
            },
            attempts = closeRunningAttempts(goal.attempts, now, "Paused by the user before result commit."),
            events = appendEvent(goal.events, reason),
        )
    }

    fun resume(
        goal: AgentGoal,
        reason: ResumeReason = ResumeReason.USER_RESUME,
        message: String = "Goal resumed by the user.",
    ): AgentGoal {
        if (goal.status !in setOf(
                AgentGoalStatus.PAUSED,
                AgentGoalStatus.FAILED,
                AgentGoalStatus.BLOCKED,
                AgentGoalStatus.WAITING_FOR_CREDENTIAL,
                AgentGoalStatus.WAITING_FOR_NETWORK,
                AgentGoalStatus.PLANNING,
                AgentGoalStatus.QUEUED,
                AgentGoalStatus.RUNNING,
                AgentGoalStatus.VERIFYING,
                AgentGoalStatus.REQUIRES_USER_CLARIFICATION,
            )
        ) return goal
        val resumedStatus = if (goal.tasks.isEmpty()) AgentGoalStatus.PLANNING else AgentGoalStatus.QUEUED
        val restartsCorrectionWindow = goal.status in setOf(AgentGoalStatus.PAUSED, AgentGoalStatus.FAILED, AgentGoalStatus.BLOCKED)
        return goal.copy(
            status = resumedStatus,
            lastResumeReason = reason,
            tasks = goal.tasks.map { task ->
                when (task.status) {
                    AgentTaskStatus.FAILED -> task.copy(
                        status = AgentTaskStatus.QUEUED,
                        taskGeneration = task.taskGeneration + 1,
                        attemptCount = if (
                            (goal.status == AgentGoalStatus.FAILED || goal.status == AgentGoalStatus.WAITING_FOR_NETWORK) &&
                            (
                                task.capability in setOf(
                                    AgentCapability.CORRECT,
                                    AgentCapability.TOOL_USE,
                                    AgentCapability.TOOL_CREATE,
                                ) ||
                                    task.capability in AgentCapability.EVIDENCE_BOUNDED_CAPABILITIES ||
                                    task.capability in AgentCapability.RESEARCH_CAPABILITIES
                            )
                        ) {
                            0
                        } else {
                            task.attemptCount
                        },
                        lastError = null,
                    )
                    AgentTaskStatus.RUNNING -> task.copy(
                        status = AgentTaskStatus.QUEUED,
                        attemptCount = (task.attemptCount - 1).coerceAtLeast(0),
                    )
                    else -> task
                }
            },
            verificationCorrectionStreak = if (restartsCorrectionWindow) 0 else goal.verificationCorrectionStreak,
            terminalResultDelivered = if (goal.status == AgentGoalStatus.FAILED) false else goal.terminalResultDelivered,
            error = null,
            events = appendEvent(goal.events, message),
        )
    }

    fun cancel(
        goal: AgentGoal,
        now: Long = System.currentTimeMillis(),
        reason: String = "Goal cancelled by the user.",
    ): AgentGoal {
        if (goal.status.isFinalTerminalStatus()) return goal
        return goal.copy(
            status = AgentGoalStatus.CANCELLED,
            tasks = goal.tasks.map { task ->
                if (task.status in setOf(AgentTaskStatus.PLANNED, AgentTaskStatus.QUEUED, AgentTaskStatus.RUNNING)) {
                    task.copy(
                        status = AgentTaskStatus.CANCELLED,
                        finishedAt = task.finishedAt ?: now,
                    )
                } else {
                    task
                }
            },
            attempts = closeRunningAttempts(goal.attempts, now, reason),
            events = appendEvent(goal.events, reason),
            error = null,
        )
    }

    fun finalize(
        goal: AgentGoal,
        now: Long = System.currentTimeMillis(),
        reason: String = "Finalizing mission and creating report.",
    ): AgentGoal {
        if (goal.status.isFinalTerminalStatus() || goal.status in setOf(AgentGoalStatus.FINALIZING, AgentGoalStatus.CANCELLING)) {
            return goal
        }
        return goal.copy(
            status = AgentGoalStatus.CANCELLING,
            attempts = closeRunningAttempts(goal.attempts, now, reason),
            events = appendEvent(goal.events, reason),
        )
    }

    private fun closeRunningAttempts(
        attempts: List<AgentAttempt>,
        now: Long,
        reason: String,
    ): List<AgentAttempt> = attempts.map { attempt ->
        if (attempt.status == AgentAttemptStatus.RUNNING) {
            attempt.copy(
                status = AgentAttemptStatus.FAILED,
                finishedAt = now,
                error = reason,
            )
        } else {
            attempt
        }
    }
}
