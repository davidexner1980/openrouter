package com.david.openassistant.agent

/**
 * Policy for determining when a mission continuation should be enqueued.
 * Complies with RECOVERY-PROPOSAL-COMMIT-AND-CONTINUATION-TRUTH.
 */
internal object ContinuationSchedulingPolicy {

    /**
     * Computes a deep fingerprint of the goal state relevant for continuation.
     * Includes active recovery plan details to ensure intermediate progress is detected.
     */
    fun fingerprint(goal: AgentGoal): String {
        val activePlan = goal.activeRecoveryPlanId
            ?.let { activeId -> goal.recoveryPlans.firstOrNull { it.id == activeId } }

        val raw = buildString {
            append(goal.status.name)
            append(":")
            append(goal.activeResearchCycleId ?: "none")
            append(":")
            append(goal.tasks.count { it.status == AgentTaskStatus.COMPLETED })
            append(":")
            append(goal.nextRunnableTask(skipCooldowns = true)?.id ?: "none")
            append(":")
            append(goal.isReadyForVerification)
            append(":")
            
            // RECOVERY-PROPOSAL-COMMIT-AND-CONTINUATION-TRUTH: include active plan details
            append(activePlan?.id ?: "none")
            append(":")
            append(activePlan?.status?.name ?: "none")
            append(":")
            append(activePlan?.proposalFingerprint ?: "none")
            append(":")
            append(activePlan?.selectedTactic?.name ?: "none")
        }
        return FingerprintUtils.hash(raw)
    }

    /**
     * Determines if a continuation is required based on state changes.
     */
    fun isSchedulable(current: AgentGoal, previous: AgentGoal): Boolean {
        // PROHIBIT enqueue for inactive or terminal missions
        if (current.status.isInactive()) return false
        if (current.status == AgentGoalStatus.PAUSED || current.status == AgentGoalStatus.CANCELLED) return false

        val currentFingerprint = fingerprint(current)
        val previousFingerprint = fingerprint(previous)

        // Only enqueue if the state has materially changed
        if (currentFingerprint == previousFingerprint && current.status == previous.status) {
            return false
        }

        // OWNERSHIP-AWARE: Check if a continuation is already pending or confirmed
        val activeClaim = current.activeContinuationSchedulingClaim
        if (activeClaim != null) {
            val state = activeClaim.state
            if (activeClaim.continuationFingerprint == currentFingerprint && 
                (state == ContinuationSchedulingState.PENDING || state == ContinuationSchedulingState.CONFIRMED_ACTIVE || state == ContinuationSchedulingState.REUSED_ACTIVE)) {
                return false
            }
        }

        return true
    }
}
