package com.david.openassistant.agent

/**
 * Pure deterministic engine for research recovery.
 * Responsible for diagnosing stalls, selecting tactics, and validating recovery novelty.
 */
object ResearchRecoveryEngine {

    /**
     * Identifies the precise stall diagnosis based on the goal, task, and execution context.
     */
    fun diagnoseStall(
        goal: AgentGoal,
        task: AgentTask,
        isFree: Boolean,
        qualityAccepted: Boolean
    ): ExecutionStallDiagnosis {
        if (qualityAccepted) return ExecutionStallDiagnosis.NONE
        if (!isFree) return ExecutionStallDiagnosis.NONE

        val consecutiveStalls = task.consecutiveNoProgressCount
        if (consecutiveStalls < 2) return ExecutionStallDiagnosis.NONE

        val lastFingerprint = task.lastMaterialProgressFingerprint
        val currentFingerprint = task.progressFingerprint
        val repeatedContext = lastFingerprint != null && lastFingerprint == currentFingerprint

        // Check for duplicate query portfolio stagnation
        val queries = task.recentQueryFingerprints
        val duplicateQueries = queries.size >= 2 && queries.distinct().size == 1

        return when {
            repeatedContext -> ExecutionStallDiagnosis.REPEATED_CONTEXT
            duplicateQueries -> ExecutionStallDiagnosis.DUPLICATE_QUERY_PORTFOLIO
            consecutiveStalls >= 5 -> ExecutionStallDiagnosis.STALE_RESEARCH_STRATEGY
            consecutiveStalls >= 4 -> ExecutionStallDiagnosis.UNRESOLVED_GAP_STAGNATION
            consecutiveStalls >= 3 -> ExecutionStallDiagnosis.SOURCE_HOMOGENEITY
            else -> ExecutionStallDiagnosis.PROGRESS_STALL
        }
    }

    /**
     * Selects the next applicable tactic that hasn't been exhausted for the current context.
     */
    fun selectTactic(
        goal: AgentGoal,
        task: AgentTask,
        diagnosis: ExecutionStallDiagnosis
    ): EscalationTactic {
        val attemptedTactics = goal.recoveryPlans
            .filter { it.taskId == task.id && it.status == RecoveryPlanStatus.COMMITTED }
            .map { it.selectedTactic }
            .toSet()

        val candidates = when (diagnosis) {
            ExecutionStallDiagnosis.REPEATED_CONTEXT -> listOf(
                EscalationTactic.REBUILD_QUERY_PORTFOLIO,
                EscalationTactic.FOLLOW_RELEVANT_LINKS,
                EscalationTactic.SHIFT_SOURCE_FAMILY
            )
            ExecutionStallDiagnosis.DUPLICATE_QUERY_PORTFOLIO -> listOf(
                EscalationTactic.REBUILD_QUERY_PORTFOLIO,
                EscalationTactic.DECOMPOSE_UNRESOLVED_GAP,
                EscalationTactic.SEARCH_CONTRADICTING_EVIDENCE
            )
            ExecutionStallDiagnosis.SOURCE_HOMOGENEITY -> listOf(
                EscalationTactic.SHIFT_SOURCE_FAMILY,
                EscalationTactic.SEARCH_PRIMARY_RECORDS,
                EscalationTactic.FOLLOW_CITATIONS
            )
            ExecutionStallDiagnosis.UNRESOLVED_GAP_STAGNATION -> listOf(
                EscalationTactic.DECOMPOSE_UNRESOLVED_GAP,
                EscalationTactic.RESOLVE_ENTITIES,
                EscalationTactic.RE_EVALUATE_ASSUMPTIONS
            )
            ExecutionStallDiagnosis.STALE_RESEARCH_STRATEGY -> listOf(
                EscalationTactic.REVISE_OPERATIONAL_OBJECTIVE,
                EscalationTactic.CHANGE_TEMPORAL_SCOPE,
                EscalationTactic.CHANGE_GEOGRAPHIC_SCOPE
            )
            else -> listOf(EscalationTactic.REFORMULATE_QUERY)
        }

        val withinCycleTactic = candidates.firstOrNull { it !in attemptedTactics }
        if (withinCycleTactic != null) return withinCycleTactic

        // V42.4: Removed fixed cycleCount < 3 limit.
        // If all within-cycle tactics are exhausted, we attempt cycle advancement.
        // Exhaustion will be determined by the novelty and fidelity of the resulting proposal.
        return EscalationTactic.CYCLE_ADVANCE
    }

    private fun ExecutionStallDiagnosis.toTactic(): EscalationTactic = when(this) {
        ExecutionStallDiagnosis.SHALLOW_RESEARCH_STALL -> EscalationTactic.FOLLOW_RELEVANT_LINKS
        else -> EscalationTactic.REFORMULATE_QUERY
    }

    /**
     * Generates a versioned deterministic identity for a recovery plan.
     */
    fun generatePlanIdentity(
        goalId: String,
        taskId: String,
        inputFingerprint: String,
        diagnosis: ExecutionStallDiagnosis,
        tactic: EscalationTactic
    ): String {
        val raw = "v1:plan:$goalId:$taskId:$inputFingerprint:${diagnosis.name}:${tactic.name}"
        return FingerprintUtils.hash(raw)
    }

    /**
     * Validates material novelty of a proposal against previous attempts.
     */
    fun validateNovelty(
        proposal: RecoveryProposal,
        previousPlans: List<ResearchRecoveryPlan>
    ): Boolean {
        val currentFingerprint = FingerprintUtils.calculateProposalFingerprint(proposal)
        
        // Reject if fingerprint matches any committed or ready proposal
        val isDuplicate = previousPlans.any { plan ->
            plan.proposalFingerprint == currentFingerprint && 
            (plan.status == RecoveryPlanStatus.COMMITTED || plan.status == RecoveryPlanStatus.READY_TO_COMMIT)
        }
        if (isDuplicate) return false

        // V42.4: Material novelty evaluation
        val committedProposals = previousPlans
            .filter { it.status == RecoveryPlanStatus.COMMITTED }
            .mapNotNull { it.proposal }

        val previousQueries = committedProposals
            .flatMap { it.newQueryPortfolio }
            .map { it.lowercase().trim() }
            .toSet()

        val newQueries = proposal.newQueryPortfolio.map { it.lowercase().trim() }
        val hasNovelQuery = newQueries.any { it !in previousQueries && it.isNotEmpty() }

        val previousGaps = committedProposals.map { it.specificUnresolvedGap.lowercase().trim() }.toSet()
        val hasNovelGap = proposal.specificUnresolvedGap.lowercase().trim() !in previousGaps

        val previousInterpretations = committedProposals.map { it.revisedInvestigationInterpretation.lowercase().trim() }.toSet()
        val hasNovelInterpretation = proposal.revisedInvestigationInterpretation.lowercase().trim() !in previousInterpretations

        val novelSourceShift = proposal.selectedSourceFamilyShift != null && 
                committedProposals.none { it.selectedSourceFamilyShift == proposal.selectedSourceFamilyShift }

        return hasNovelQuery || hasNovelGap || hasNovelInterpretation || novelSourceShift
    }

    fun generateCycleIdentity(goalId: String, ordinal: Int): String {
        val raw = "v1:cycle:$goalId:$ordinal"
        return FingerprintUtils.hash(raw)
    }

    fun generateRevisionIdentity(goalId: String, ordinal: Int): String {
        val raw = "v1:revision:$goalId:$ordinal"
        return FingerprintUtils.hash(raw)
    }
}
