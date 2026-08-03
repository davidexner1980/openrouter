package com.david.openassistant.agent

import java.util.Locale

/**
 * Auditor for detecting semantic drift from the original user objective.
 * [PB-001] Mission recovery must preserve evidence and provenance.
 */
object AgentDriftAuditor {

    data class DriftReport(
        val isDrifted: Boolean,
        val driftSeverity: Double,
        val missingAnchors: List<String>,
        val explanation: String
    ) {
        companion object {
            val STABLE = DriftReport(false, 0.0, emptyList(), "No material drift detected.")
        }
    }

    fun evaluateDrift(
        contract: ObjectiveContract?,
        plan: AgentPlanDraft
    ): DriftReport {
        if (contract == null) return DriftReport.STABLE
        
        val anchors = contract.strongAnchors
        if (anchors.isEmpty()) return DriftReport.STABLE

        val planText = buildString {
            append(plan.title)
            append(" ")
            append(plan.objective)
            append(" ")
            plan.tasks.forEach {
                append(it.title)
                append(" ")
                append(it.instructions)
                append(" ")
            }
        }.lowercase(Locale.US)

        val missingAnchors = anchors.filter { anchor ->
            !planText.hasAnchor(anchor)
        }

        val driftSeverity = if (anchors.isEmpty()) 0.0 else missingAnchors.size.toDouble() / anchors.size.toDouble()
        val isDrifted = driftSeverity >= 0.3 // Drift if > 30% of anchors are lost

        return DriftReport(
            isDrifted = isDrifted,
            driftSeverity = driftSeverity,
            missingAnchors = missingAnchors,
            explanation = if (isDrifted) {
                "Plan has drifted from root objective. Missing anchors: ${missingAnchors.joinToString(", ")}"
            } else {
                "Plan remains aligned with root objective."
            }
        )
    }

    fun evaluateRecoveryDrift(
        contract: ObjectiveContract?,
        proposal: RecoveryProposal
    ): DriftReport {
        if (contract == null) return DriftReport.STABLE
        
        val anchors = contract.strongAnchors
        if (anchors.isEmpty()) return DriftReport.STABLE

        val proposalText = buildString {
            append(proposal.revisedInvestigationInterpretation)
            append(" ")
            append(proposal.specificUnresolvedGap)
            append(" ")
            proposal.evidenceTargets.forEach { append(it).append(" ") }
            proposal.newQueryPortfolio.forEach { append(it).append(" ") }
        }.lowercase(Locale.US)

        val missingAnchors = anchors.filter { anchor ->
            !proposalText.hasAnchor(anchor)
        }

        val driftSeverity = if (anchors.isEmpty()) 0.0 else missingAnchors.size.toDouble() / anchors.size.toDouble()
        val isDrifted = driftSeverity >= 0.5 // Recovery is allowed to be slightly more focused but not disconnected

        return DriftReport(
            isDrifted = isDrifted,
            driftSeverity = driftSeverity,
            missingAnchors = missingAnchors,
            explanation = if (isDrifted) {
                "Recovery proposal has drifted from root objective. Missing anchors: ${missingAnchors.joinToString(", ")}"
            } else {
                "Recovery proposal remains aligned with root objective."
            }
        )
    }

    private fun String.hasAnchor(anchor: String): Boolean {
        val normalizedAnchor = anchor.lowercase(Locale.US).trim()
        if (normalizedAnchor.isEmpty()) return true
        
        // Match word boundaries to avoid false positives (e.g. "task" matching "multitasking")
        val escaped = Regex.escape(normalizedAnchor)
        val regex = Regex("(^|[^a-z0-9])$escaped([^a-z0-9]|$)")
        return regex.containsMatchIn(this)
    }
}
