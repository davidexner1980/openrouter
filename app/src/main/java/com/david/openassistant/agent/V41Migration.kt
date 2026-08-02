package com.david.openassistant.agent

import org.json.JSONObject

/**
 * Repairs stuck V41 missions that were paused by the identical-context guard.
 */
object V41Migration {

    fun isStuckV41(goal: AgentGoal): Boolean {
        if (goal.status != AgentGoalStatus.PAUSED) return false
        
        val identicalContextEvent = goal.events.any { it.message.contains("identical context fingerprint detected") }
        if (!identicalContextEvent) return false

        val hasBlockedTask = goal.tasks.any { it.status == AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE && it.failureClass == "STRUCTURED_SYNTHESIS_DEFICIT" }
        if (!hasBlockedTask) return false

        val danglingAttempt = goal.attempts.any { it.status == AgentAttemptStatus.RUNNING }
        if (!danglingAttempt) return false
        
        // Ledger proof: ensure no ProviderRequestAttempt exists for this mission's recent window that reached the wire
        val hasDispatchedRequest = goal.requestAttempts.any { 
            it.transportStage.ordinal >= ProviderTransportStage.REQUEST_BODY_STARTED.ordinal 
        }
        
        // In V41 stuck case, the task was suppressed BEFORE any provider request was created for that attempt
        return !hasDispatchedRequest || goal.attempts.lastOrNull()?.startedAt ?: 0 > goal.requestAttempts.lastOrNull()?.startedAt ?: 0
    }

    fun migrate(goal: AgentGoal): AgentGoal {
        if (goal.researchCycles.isNotEmpty()) return goal // Already migrated

        val baselineCycleId = "baseline-${goal.id.take(8)}"
        val baselineRevisionId = "root-revision-${goal.id.take(8)}"

        val baselineRevision = ObjectiveRevision(
            id = baselineRevisionId,
            ordinal = 0,
            rootObjectiveFingerprint = FingerprintUtils.computeRootObjectiveFingerprint(goal),
            operationalObjective = goal.objective,
            revisionFingerprint = "baseline"
        )

        val baselineCycle = ResearchCycle(
            id = baselineCycleId,
            ordinal = 0,
            status = ResearchCycleStatus.ACTIVE,
            objectiveRevisionId = baselineRevisionId,
            activatedAt = goal.createdAt
        )

        val repairedAttempts = goal.attempts.map { attempt ->
            if (attempt.status == AgentAttemptStatus.RUNNING && attempt.taskId != null) {
                attempt.copy(
                    status = AgentAttemptStatus.FAILED,
                    error = "V41 pre-dispatch suppression: closed during migration to V42.",
                    finishedAt = System.currentTimeMillis()
                )
            } else attempt
        }

        val repairedTasks = goal.tasks.map { task ->
            if (task.status == AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE && task.failureClass == "STRUCTURED_SYNTHESIS_DEFICIT") {
                task.copy(
                    status = AgentTaskStatus.QUEUED,
                    failureClass = null,
                    cycleId = baselineCycleId,
                    attemptCount = (task.attemptCount - 1).coerceAtLeast(0) // Correct pre-dispatch increment
                )
            } else {
                task.copy(cycleId = baselineCycleId)
            }
        }

        return goal.copy(
            researchCycles = listOf(baselineCycle),
            objectiveRevisions = listOf(baselineRevision),
            activeResearchCycleId = baselineCycleId,
            attempts = repairedAttempts,
            tasks = repairedTasks,
            events = goal.events + AgentEvent(message = "Migrated legacy V41 mission to V42 adaptive recovery framework. Established baseline research cycle and repaired dangling attempts.")
        )
    }
}
