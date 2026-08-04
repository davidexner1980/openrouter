package com.david.openassistant.agent

import java.util.Locale

/**
 * A verification correction is a replacement publication pass, not another
 * research pass. Keep uncertain material in the evidence trail, but do not
 * keep it in the graph that the verifier is being asked to publish.
 */
internal data class PublicationGraph(
    val claims: List<AgentClaim>,
    val evidenceLinks: List<AgentEvidenceLink>,
    val excludedClaimCount: Int,
)

/** A synthesis or correction becomes the only active publication claim set. */
internal fun replacesPublicationClaimGraph(capability: AgentCapability): Boolean =
    capability in setOf(AgentCapability.SYNTHESIZE, AgentCapability.CORRECT)

/**
 * Verification needs the research record, the decision framework, and the
 * newest publication candidate. Prior verifier critiques and superseded
 * synthesis/correction drafts waste context and can anchor later reviews to an
 * obsolete result.
 */
internal fun selectVerificationEvidence(goal: AgentGoal): List<AgentEvidence> {
    val reasonEvidenceIds = goal.tasks
        .asSequence()
        .filter { (it.capability == AgentCapability.REASON) && (it.status == AgentTaskStatus.COMPLETED) }
        .mapNotNull { it.outputEvidenceId }
        .toSet()
    val latestPublicationEvidenceId = goal.tasks
        .asSequence()
        .filter { replacesPublicationClaimGraph(it.capability) && (it.status == AgentTaskStatus.COMPLETED) }
        .sortedWith(compareBy<AgentTask> { it.order }.thenBy { it.finishedAt ?: Long.MIN_VALUE })
        .mapNotNull { it.outputEvidenceId }
        .lastOrNull()
        ?: goal.evidence.lastOrNull { it.kind == AgentEvidenceKind.MODEL_OUTPUT }?.id

    return goal.evidence.filter { evidence ->
        when (evidence.kind) {
            AgentEvidenceKind.WEB_RESEARCH,
            AgentEvidenceKind.DEEP_RESEARCH,
            AgentEvidenceKind.RESEARCH_HIT,
            AgentEvidenceKind.TOOL_RESULT,
            AgentEvidenceKind.CHECKPOINT,
                -> true

            AgentEvidenceKind.MODEL_OUTPUT ->
                evidence.id in reasonEvidenceIds || evidence.id == latestPublicationEvidenceId

            AgentEvidenceKind.PLAN,
            AgentEvidenceKind.VERIFICATION,
            AgentEvidenceKind.SYSTEM_EVENT,
                -> false
        }
    }
}

internal data class VerificationConvergenceSnapshot(
    val qualityScore: Double,
    val findingCodes: Set<String>,
    val excludedClaimCount: Int,
)

private const val VERIFICATION_CONVERGENCE_PREFIX = "VERIFICATION_CONVERGENCE_V1"
private const val MIN_MATERIAL_VERIFICATION_SCORE_GAIN = 0.01

internal fun verificationEvidenceContent(
    content: String,
    snapshot: VerificationConvergenceSnapshot,
    maximumCharacters: Int,
): String {
    val findings = snapshot.findingCodes.sorted().joinToString(",").ifBlank { "none" }
    val marker = buildString {
        append(VERIFICATION_CONVERGENCE_PREFIX)
        append("|quality=")
        append(String.format(Locale.US, "%.4f", snapshot.qualityScore.coerceIn(0.0, 1.0)))
        append("|excluded=")
        append(snapshot.excludedClaimCount.coerceAtLeast(0))
        append("|findings=")
        append(findings)
    }
    val bodyLimit = (maximumCharacters - marker.length - 2).coerceAtLeast(0)
    return buildString {
        append(content.take(bodyLimit).trimEnd())
        if (isNotEmpty()) appendLine().appendLine()
        append(marker)
    }.take(maximumCharacters.coerceAtLeast(0))
}

internal fun latestVerificationConvergenceSnapshot(
    evidence: List<AgentEvidence>,
): VerificationConvergenceSnapshot? = evidence
    .asReversed()
    .asSequence()
    .filter { it.kind == AgentEvidenceKind.VERIFICATION }
    .flatMap { it.content.lineSequence().toList().asReversed().asSequence() }
    .mapNotNull(::parseVerificationConvergenceSnapshot)
    .firstOrNull()

private fun parseVerificationConvergenceSnapshot(line: String): VerificationConvergenceSnapshot? {
    if (!line.startsWith("$VERIFICATION_CONVERGENCE_PREFIX|")) return null
    val fields = line.split('|').drop(1).mapNotNull { field ->
        val parts = field.split('=', limit = 2)
        if (parts.size == 2) parts[0] to parts[1] else null
    }.toMap()
    val quality = fields["quality"]?.toDoubleOrNull() ?: return null
    val excluded = fields["excluded"]?.toIntOrNull() ?: return null
    val findings = fields["findings"].orEmpty()
        .split(',')
        .map(String::trim)
        .filter { it.isNotBlank() && it != "none" }
        .toSet()
    return VerificationConvergenceSnapshot(
        qualityScore = quality.coerceIn(0.0, 1.0),
        findingCodes = findings,
        excludedClaimCount = excluded.coerceAtLeast(0),
    )
}

internal fun hasVerificationConvergenceStalled(
    previous: VerificationConvergenceSnapshot?,
    current: VerificationConvergenceSnapshot,
): Boolean = previous != null &&
    current.findingCodes.isNotEmpty() &&
    current.findingCodes == previous.findingCodes &&
    current.qualityScore < previous.qualityScore + MIN_MATERIAL_VERIFICATION_SCORE_GAIN &&
    current.excludedClaimCount >= previous.excludedClaimCount

/** Removes verdict metadata that cannot tell a correction worker what to fix. */
internal fun actionableVerificationFindings(
    missingRequirements: List<String>,
    integrityReasons: List<String>,
    maximumFindings: Int = 12,
): List<String> = (missingRequirements + integrityReasons.filterNot { reason ->
    reason.startsWith("The independent verifier") ||
        reason.startsWith("Quality score") ||
        reason.startsWith("The verifier still reports")
})
    .map(String::trim)
    .filter(String::isNotBlank)
    .distinctBy { it.lowercase(Locale.US).replace(Regex("\\s+"), " ") }
    .take(maximumFindings.coerceAtLeast(0))

internal fun compactPublicationGraph(
    claims: List<AgentClaim>,
    evidenceLinks: List<AgentEvidenceLink>,
): PublicationGraph {
    val retainedClaims = claims.filter(::isPublicationCandidate)
    val retainedClaimIds = retainedClaims.mapTo(mutableSetOf()) { it.id }
    return PublicationGraph(
        claims = retainedClaims,
        evidenceLinks = evidenceLinks
            .filter { it.claimId in retainedClaimIds }
            .distinctBy { Triple(it.claimId, it.evidenceId, it.relation) },
        excludedClaimCount = claims.size - retainedClaims.size,
    )
}

/**
 * Facts must be fully supported before publication. Partially supported
 * inferences, recommendations, and explicit uncertainty may remain visible,
 * but unsupported or contradicted material stays only in preserved evidence.
 */
private fun isPublicationCandidate(claim: AgentClaim): Boolean = when {
    claim.support in setOf(AgentClaimSupport.UNSUPPORTED, AgentClaimSupport.CONTRADICTED) -> false
    claim.type == AgentClaimType.FACT -> claim.support == AgentClaimSupport.SUPPORTED
    else -> true
}

internal fun hasPublishableCorrectionClaims(claims: List<AgentClaim>): Boolean =
    claims.any(::isPublicationCandidate)

internal const val MAX_VERIFICATION_CORRECTION_PASSES = 3

internal fun canQueueVerificationCorrection(completedCorrectionPasses: Int): Boolean =
    completedCorrectionPasses < MAX_VERIFICATION_CORRECTION_PASSES

internal fun hasExhaustedCorrectionAttemptWindow(
    task: AgentTask,
    qualityAccepted: Boolean,
): Boolean = !qualityAccepted &&
    task.capability == AgentCapability.CORRECT &&
    task.attemptCount >= MAX_CORRECTION_MILESTONE_ATTEMPTS

internal data class VerificationRecoveryRoute(
    val capability: AgentCapability,
    val taskIdPrefix: String,
    val title: String,
    val evidenceOnly: Boolean,
    val targetFindings: List<String>,
) {
    val targetFinding: String?
        get() = targetFindings.firstOrNull()
}

/**
 * Content and citation failures are corrected from durable evidence. A true
 * deterministic research deficit gets the smallest complete bundle of missing
 * research roles. A bundle counts as one bounded correction pass, while every
 * role remains a distinct milestone so the deterministic research gate can
 * recognize discovery, primary, contradiction, and gap-closure work.
 */
internal fun selectVerificationRecoveryRoutes(
    researchGateReasons: List<String>,
    verificationMissingRequirements: List<String> = emptyList(),
): List<VerificationRecoveryRoute> {
    val verifierResearchDeficits = verificationMissingRequirements
        .map(String::trim)
        .filter(String::isNotBlank)
        .filter(::verificationFindingRequiresResearch)
    if (researchGateReasons.isEmpty() && verifierResearchDeficits.isEmpty()) {
        return listOf(
            VerificationRecoveryRoute(
                capability = AgentCapability.CORRECT,
                taskIdPrefix = "correction",
                title = "Correct verification failures",
                evidenceOnly = true,
                targetFindings = emptyList(),
            ),
        )
    }

    val unmatched = (researchGateReasons + verifierResearchDeficits)
        .distinctBy { it.lowercase(Locale.US).replace(Regex("\\s+"), " ") }
        .toMutableList()
    val routes = mutableListOf<VerificationRecoveryRoute>()

    fun addRoleRoute(
        taskIdPrefix: String,
        title: String,
        matches: (String) -> Boolean,
    ) {
        val findings = unmatched.filter(matches)
        if (findings.isEmpty()) return
        routes += VerificationRecoveryRoute(
            capability = AgentCapability.DEEP_RESEARCH,
            taskIdPrefix = taskIdPrefix,
            title = title,
            evidenceOnly = false,
            targetFindings = findings,
        )
        unmatched.removeAll(findings.toSet())
    }

    addRoleRoute(
        taskIdPrefix = "verification_discovery_recovery",
        title = "Research discovery recovery: map the evidence landscape",
    ) { reason ->
        val normalized = reason.lowercase(Locale.US)
        "evidence-discovery pass" in normalized ||
            DISCOVERY_RECOVERY_FINDING_PATTERN.containsMatchIn(normalized)
    }
    addRoleRoute(
        taskIdPrefix = "verification_primary_recovery",
        title = "Research primary-source recovery",
    ) { reason ->
        val normalized = reason.lowercase(Locale.US)
        "primary-source verification pass" in normalized ||
            "primary-source pass" in normalized ||
            "primary or first-party source use" in normalized ||
            (
                PRIMARY_RECOVERY_FINDING_PATTERN.containsMatchIn(normalized) &&
                    !CONTRADICTION_RECOVERY_FINDING_PATTERN.containsMatchIn(normalized)
            )
    }
    addRoleRoute(
        taskIdPrefix = "verification_contradiction_recovery",
        title = "Research contradiction and disconfirmation recovery",
    ) { reason ->
        val normalized = reason.lowercase(Locale.US)
        "contradiction or disconfirmation pass" in normalized ||
            "contradiction pass" in normalized ||
            "counterevidence" in normalized ||
            CONTRADICTION_RECOVERY_FINDING_PATTERN.containsMatchIn(normalized)
    }
    addRoleRoute(
        taskIdPrefix = "verification_gap_closure",
        title = "Research gap closure and freshness audit recovery",
    ) { reason ->
        val normalized = reason.lowercase(Locale.US)
        "evidence-gap and freshness audit pass" in normalized ||
            "freshness" in normalized ||
            "stale claim" in normalized ||
            GAP_RECOVERY_FINDING_PATTERN.containsMatchIn(normalized)
    }

    if (routes.isEmpty()) {
        routes += VerificationRecoveryRoute(
            capability = AgentCapability.DEEP_RESEARCH,
            taskIdPrefix = "verification_research_recovery",
            title = "Research source recovery",
            evidenceOnly = false,
            targetFindings = unmatched.take(MAX_RECOVERY_FINDINGS_PER_TASK),
        )
    } else if (unmatched.isNotEmpty()) {
        val lastIndex = routes.lastIndex
        routes[lastIndex] = routes[lastIndex].copy(
            targetFindings = (routes[lastIndex].targetFindings + unmatched)
                .distinct()
                .take(MAX_RECOVERY_FINDINGS_PER_TASK),
        )
    }

    val focusedResearchRoutes = routes.take(MAX_RECOVERY_RESEARCH_TASKS_PER_PASS)
    val publicationCorrection = VerificationRecoveryRoute(
        capability = AgentCapability.CORRECT,
        taskIdPrefix = "correction",
        title = "Synthesize recovered evidence and correct verification failures",
        evidenceOnly = true,
        targetFindings = verificationMissingRequirements,
    )
    return focusedResearchRoutes + publicationCorrection
}

/** Compatibility shim for persisted 1.7.6 diagnostics and focused harnesses. */
internal fun selectVerificationRecoveryRoute(
    researchGateReasons: List<String>,
): VerificationRecoveryRoute = selectVerificationRecoveryRoutes(researchGateReasons).first()

/**
 * A verifier can identify missing evidence even when aggregate pass and source
 * counts are technically satisfied. Route only findings that explicitly call
 * for absent sources, records, measurements, coverage, or triangulation back
 * into research. Editorial changes and removal of unsupported prose remain an
 * correction.
 */
internal fun verificationFindingRequiresResearch(finding: String): Boolean {
    val normalized = finding.lowercase(Locale.US).replace(Regex("\\s+"), " ").trim()
    if (normalized.isBlank()) return false
    if (EDITORIAL_ONLY_FINDING_PATTERN.containsMatchIn(normalized)) return false
    return RESEARCH_DEFICIT_FINDING_PATTERN.containsMatchIn(normalized)
}

internal const val MAX_RECOVERY_RESEARCH_TASKS_PER_PASS = 4
internal const val MAX_RECOVERY_TASKS_PER_PASS = MAX_RECOVERY_RESEARCH_TASKS_PER_PASS + 1
private const val MAX_RECOVERY_FINDINGS_PER_TASK = 12

private val RESEARCH_DEFICIT_FINDING_PATTERN = Regex(
    "\\b(?:exact|dated|current|recent|fresh|primary|official|independent|reliable|credible|" +
        "source|sources|citation|citations|record|records|document|documents|dataset|datasets|" +
        "data|metric|metrics|methodology|methodologies|measurement|measurements|quantitative|" +
        "poll|polling|survey|surveys|statistics|statistical|evidence|triangulat(?:e|ed|ion)|" +
        "documented|case-level|biograph(?:y|ical)|career|chronology|coverage|analysis|" +
        "institution|institutional|mechanism|structure|authority|ideology|governing style|" +
        "policy|policies|rights|military|succession|public image)\\b",
)
private val EDITORIAL_ONLY_FINDING_PATTERN = Regex(
    "^(?:remove|removal|omit|delete|reword|qualify|correction of|correct the wording|" +
        "fix the wording|define|definitions? of|add a glossary|" +
        "disambiguate)\\b",
)
private val DISCOVERY_RECOVERY_FINDING_PATTERN = Regex(
    "\\b(?:biograph(?:y|ical)|career|chronology|background|history|institutional|institution|" +
        "structure|mechanism|relationship|context|ideology|governing style|personnel|succession)\\b",
)
private val PRIMARY_RECOVERY_FINDING_PATTERN = Regex(
    "\\b(?:exact (?:url|urls|page|pages)|citation|citations|primary|official|constitution|law|laws|" +
        "legal|record|records|document|documents|filing|filings|roster|source list|provenance)\\b",
)
private val CONTRADICTION_RECOVERY_FINDING_PATTERN = Regex(
    "\\b(?:triangulat(?:e|ed|ion)|contradict(?:ion|ory)|counterevidence|denial|denials|" +
        "allegation|allegations|controvers(?:y|ies)|dispute|disputed|rights evidence|" +
        "competing interpretation|competing interpretations)\\b",
)
private val GAP_RECOVERY_FINDING_PATTERN = Regex(
    "\\b(?:dated|current|recent|fresh|quantitative|dataset|datasets|data|metric|metrics|" +
        "methodology|measurement|measurements|poll|polling|survey|surveys|statistics|" +
        "outcome|outcomes|implementation|coverage|domain|domains|public image)\\b",
)

internal fun verificationResponseStatus(result: AgentVerificationResult): String = when {
    result.structuredOutputRepaired -> "REPAIRED"
    result.summary.startsWith("The verification response could not be parsed safely") -> "UNPARSEABLE"
    else -> "STRUCTURED"
}

/** Safe, content-free categories for diagnostics and support traces. */
internal fun verificationFindingCodes(reasons: List<String>): Set<String> = reasons.mapTo(linkedSetOf()) { reason ->
    when {
        reason.startsWith("The independent verifier") -> "verifier_rejected"
        reason.startsWith("Quality score") -> "quality_below_threshold"
        reason.startsWith("The verifier returned no usable final answer") -> "missing_final_answer"
        reason.startsWith("One or more planned tasks") -> "incomplete_task"
        reason.startsWith("The verifier still reports") -> "missing_requirements"
        reason.startsWith("Acceptance criterion '") -> "criterion_not_evaluated"
        reason.startsWith("Acceptance criterion did not pass") -> "criterion_failed"
        reason.startsWith("Acceptance criterion is below threshold") -> "criterion_below_threshold"
        reason.contains("no structured claims") -> "missing_structured_claims"
        reason.contains("unknown evidence record") -> "unknown_evidence_reference"
        reason.contains("unpreserved source URL") -> "unpreserved_source_reference"
        reason.startsWith("Claim is contradicted") -> "contradicted_claim"
        reason.startsWith("A factual claim is not fully supported") -> "fact_not_supported"
        reason.startsWith("A factual claim has no preserved evidence") -> "fact_missing_evidence"
        reason.contains("has no preserved source URL") -> "fact_missing_source"
        reason.contains("no evidence record containing its cited source") -> "fact_source_mismatch"
        reason.contains("missing a SUPPORTS") -> "fact_missing_support_link"
        reason.contains("derived measurement inconsistency") -> "inconsistent_derived_measurement"
        reason.startsWith("Structured claim IDs are not unique") -> "duplicate_claim_id"
        reason.startsWith("Only ") || reason.startsWith("Research ") ||
            reason.startsWith("The plan ") || reason.startsWith("The preserved evidence") ||
            reason.startsWith("OpenRouter reported") -> "research_quality"
        else -> "other"
    }
}
