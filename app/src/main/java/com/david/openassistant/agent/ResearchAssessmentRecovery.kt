package com.david.openassistant.agent

import java.net.URI
import java.util.Locale

/**
 * Recovers only provider self-grading metadata. It never relaxes the normal
 * source, diversity, content, factual-claim, or pass-role quality gates.
 */
internal fun recoverResearchAssessment(
    task: AgentTask,
    result: AgentStepResult,
    policy: AutonomyPolicy = AutonomyPolicy.DEFAULT,
    metadataWasRepaired: Boolean = false,
    completionFloor: Double = 0.72,
): AgentStepResult {
    if (task.capability !in setOf(AgentCapability.WEB_RESEARCH, AgentCapability.DEEP_RESEARCH)) {
        return result
    }
    if (result.acceptanceChecks.any { it.status == AgentAcceptanceCheckStatus.FAIL }) return result

    val providerOmittedAssessment = task.acceptanceCriteria.isNotEmpty() &&
        result.acceptanceChecks.all { it.status == AgentAcceptanceCheckStatus.NOT_EVALUATED }
    if (!providerOmittedAssessment && !metadataWasRepaired) return result
    if (task.acceptanceCriteria.any { !it.description.isRecoverableResearchCriterion() }) return result

    val deterministicDecision = ResearchQualityGate.evaluateStep(task, result, null, policy)
    if (!deterministicDecision.passed) return result

    val sourceUrls = result.sources.map { it.url }.distinct()
    val sourceDomains = sourceUrls.mapNotNull(::safeDomain).distinct()
    val facts = result.claims.count { it.type == AgentClaimType.FACT }
    val recoveredChecks = task.acceptanceCriteria.map { criterion ->
        result.acceptanceChecks.firstOrNull {
            it.criterionId == criterion.id && it.status != AgentAcceptanceCheckStatus.NOT_EVALUATED
        } ?: AgentAcceptanceCheck(
            criterionId = criterion.id,
            status = AgentAcceptanceCheckStatus.PARTIAL,
            score = completionFloor,
            explanation = buildString {
                append("Provider self-grading was unavailable; the deterministic research gate confirmed ")
                append("${sourceUrls.size} HTTPS sources across ${sourceDomains.size} domains, ")
                append("analyzed content, and $facts structured factual claim(s). ")
                append("This criterion is conservatively marked partial for milestone recovery.")
            },
        )
    }
    val recoveredScore = if (task.acceptanceCriteria.isEmpty()) {
        completionFloor
    } else {
        weightedAssessmentScore(task.acceptanceCriteria, recoveredChecks)
    }
    return result.copy(
        completionScore = maxOf(result.completionScore, recoveredScore, completionFloor).coerceIn(0.0, 1.0),
        acceptanceChecks = recoveredChecks,
    )
}

private fun String.isRecoverableResearchCriterion(): Boolean {
    val normalized = lowercase(Locale.US)
    return RESEARCH_PROTOCOL_CRITERION.containsMatchIn(normalized) &&
        !CONTENT_DELIVERABLE_CRITERION.containsMatchIn(normalized)
}

private fun weightedAssessmentScore(
    criteria: List<AgentAcceptanceCriterion>,
    checks: List<AgentAcceptanceCheck>,
): Double {
    if (criteria.isEmpty()) return 1.0
    val checksById = checks.associateBy { it.criterionId }
    val totalWeight = criteria.sumOf { it.weight.coerceAtLeast(0.1) }
    return criteria.sumOf { criterion ->
        criterion.weight.coerceAtLeast(0.1) * (checksById[criterion.id]?.score ?: 0.0)
    }.div(totalWeight).coerceIn(0.0, 1.0)
}

private fun safeDomain(url: String): String? = runCatching {
    URI(url).host?.lowercase(Locale.US)?.removePrefix("www.")
}.getOrNull()?.takeIf(String::isNotBlank)

private val RESEARCH_PROTOCOL_CRITERION = Regex(
    "\\b(source|evidence|finding|citation|https|url|uncertainty|landscape|" +
        "counterevidence|contradiction|limitation|alternative|disconfirm|primary|first[- ]party|" +
        "official|domain|query|full[- ]page|pdf|date|freshness|stale|gap)\\b",
)

private val CONTENT_DELIVERABLE_CRITERION = Regex(
    "\\b(named locations?|species|coordinates?|addresses?|prices?|costs?|ranking|ranked|" +
        "recommendations?|calculation|implementation|source code|table rows?|at least \\d+ (?:locations?|items?|results?))\\b",
)
