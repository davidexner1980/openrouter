package com.david.openassistant.agent

import java.net.URI
import java.util.Locale

/**
 * Deterministic, side-effect free recovery engine for research stalls.
 * Diagnoses why progress stopped and selects an untried recovery tactic.
 */
object ResearchRecoveryEngine {

    data class RecoveryDecision(
        val diagnosis: ExecutionStallDiagnosis,
        val tactic: EscalationTactic,
        val kind: RecoveryKind,
        val explanation: String
    )

    fun diagnoseAndSelectTactic(
        goal: AgentGoal,
        activeCycle: ResearchCycle,
        task: AgentTask,
        currentFingerprint: String
    ): RecoveryDecision? {
        val diagnosis = refineDiagnosis(goal, activeCycle, task, currentFingerprint)
        if (diagnosis == ExecutionStallDiagnosis.NONE) return null

        val attemptedTactics = activeCycle.learningSummary?.attemptedTactics?.toSet() ?: emptySet()

        // Recovery Hierarchy
        val selection = when (diagnosis) {
            ExecutionStallDiagnosis.REPEATED_CONTEXT -> selectFromHierarchy(
                attemptedTactics,
                listOf(
                    EscalationTactic.REBUILD_QUERY_PORTFOLIO,
                    EscalationTactic.SHIFT_SOURCE_FAMILY,
                    EscalationTactic.FOLLOW_CITATIONS,
                    EscalationTactic.DECOMPOSE_UNRESOLVED_GAP,
                    EscalationTactic.REVISE_OPERATIONAL_OBJECTIVE
                )
            )
            ExecutionStallDiagnosis.DUPLICATE_QUERY_PORTFOLIO -> selectFromHierarchy(
                attemptedTactics,
                listOf(
                    EscalationTactic.REBUILD_QUERY_PORTFOLIO,
                    EscalationTactic.RE_EVALUATE_ASSUMPTIONS,
                    EscalationTactic.REVISE_OPERATIONAL_OBJECTIVE
                )
            )
            ExecutionStallDiagnosis.SOURCE_HOMOGENEITY -> selectFromHierarchy(
                attemptedTactics,
                listOf(
                    EscalationTactic.SHIFT_SOURCE_FAMILY,
                    EscalationTactic.SEARCH_PRIMARY_RECORDS,
                    EscalationTactic.FOLLOW_CITATIONS
                )
            )
            ExecutionStallDiagnosis.UNRESOLVED_GAP_STAGNATION -> selectFromHierarchy(
                attemptedTactics,
                listOf(
                    EscalationTactic.DECOMPOSE_UNRESOLVED_GAP,
                    EscalationTactic.SMALLEST_MISSING_FACT,
                    EscalationTactic.REVISE_OPERATIONAL_OBJECTIVE
                )
            )
            ExecutionStallDiagnosis.ENTITY_AMBIGUITY -> selectFromHierarchy(
                attemptedTactics,
                listOf(EscalationTactic.RESOLVE_ENTITIES)
            )
            ExecutionStallDiagnosis.TEMPORAL_SCOPE_MISMATCH -> selectFromHierarchy(
                attemptedTactics,
                listOf(EscalationTactic.CHANGE_TEMPORAL_SCOPE)
            )
            ExecutionStallDiagnosis.NO_NEW_ACCEPTED_EVIDENCE -> selectFromHierarchy(
                attemptedTactics,
                listOf(
                    EscalationTactic.SEARCH_CONTRADICTING_EVIDENCE,
                    EscalationTactic.ALTERNATIVE_DISCOVERY_ADAPTER,
                    EscalationTactic.REVISE_OPERATIONAL_OBJECTIVE
                )
            )
            else -> selectFromHierarchy(
                attemptedTactics,
                listOf(EscalationTactic.REBUILD_QUERY_PORTFOLIO, EscalationTactic.REVISE_OPERATIONAL_OBJECTIVE)
            )
        }

        return selection?.let { (tactic, kind) ->
            RecoveryDecision(
                diagnosis = diagnosis,
                tactic = tactic,
                kind = kind,
                explanation = "Diagnosed $diagnosis; selected tactic $tactic."
            )
        }
    }

    private fun refineDiagnosis(
        goal: AgentGoal,
        activeCycle: ResearchCycle,
        task: AgentTask,
        currentFingerprint: String
    ): ExecutionStallDiagnosis {
        if (task.lastRequestFingerprint == currentFingerprint) {
            return ExecutionStallDiagnosis.REPEATED_CONTEXT
        }
        
        // Query Portfolio Duplication check
        val currentQueries = task.queryFingerprints
        val previousQueriesFp = activeCycle.queryPortfolioFingerprint
        if (currentQueries.isNotEmpty() && FingerprintUtils.computeQueryPortfolioFingerprint(currentQueries) == previousQueriesFp) {
            return ExecutionStallDiagnosis.DUPLICATE_QUERY_PORTFOLIO
        }

        // Source Homogeneity check
        val taskEvidence = goal.evidence.filter { it.taskId == task.id }
        val hosts = taskEvidence.flatMap { it.sources.mapNotNull { s -> runCatching { URI(s.url).host }.getOrNull() } }.distinct()
        if (task.attemptCount >= 1 && hosts.size < 2 && task.capability in AgentCapability.RESEARCH_CAPABILITIES) {
            return ExecutionStallDiagnosis.SOURCE_HOMOGENEITY
        }

        // No New Accepted Evidence check
        val lastEvidenceFp = activeCycle.acceptedEvidenceFingerprint
        val currentEvidenceFp = FingerprintUtils.computeAcceptedEvidenceFingerprint(goal.evidence)
        if (lastEvidenceFp == currentEvidenceFp && task.attemptCount >= 1) {
            if (task.consecutiveNoProgressCount >= 2) {
                return ExecutionStallDiagnosis.NO_NEW_ACCEPTED_EVIDENCE
            }
        }

        return ExecutionStallDiagnosis.NONE
    }

    private fun selectFromHierarchy(
        attempted: Set<EscalationTactic>,
        hierarchy: List<EscalationTactic>
    ): Pair<EscalationTactic, RecoveryKind>? {
        for (tactic in hierarchy) {
            if (tactic !in attempted) {
                val kind = if (tactic == EscalationTactic.REVISE_OPERATIONAL_OBJECTIVE) {
                    RecoveryKind.CYCLE_ADVANCE
                } else {
                    RecoveryKind.TACTIC_PIVOT
                }
                return tactic to kind
            }
        }
        // If everything in hierarchy attempted, fall back to cycle advance if not already done
        if (EscalationTactic.REVISE_OPERATIONAL_OBJECTIVE !in attempted) {
            return EscalationTactic.REVISE_OPERATIONAL_OBJECTIVE to RecoveryKind.CYCLE_ADVANCE
        }
        return null
    }

    /**
     * Enforces the Material Novelty Gate.
     * Rejects changes that are merely cosmetic.
     */
    fun isNovel(
        proposal: RecoveryProposal,
        activeCycle: ResearchCycle
    ): Boolean {
        val newStrategyFp = FingerprintUtils.computeStrategyFingerprint(proposal.strategyJson)
        if (newStrategyFp == activeCycle.strategyFingerprint && activeCycle.strategyFingerprint != null) {
            return false
        }

        val newQueryFp = FingerprintUtils.computeQueryPortfolioFingerprint(proposal.queryPortfolio)
        if (newQueryFp == activeCycle.queryPortfolioFingerprint && activeCycle.queryPortfolioFingerprint != null) {
            return false
        }

        return true
    }
}
