package com.david.openassistant.agent

import org.json.JSONObject
import java.util.Locale

/**
 * A provider can accept OpenRouter's response_format field without actually
 * enforcing it. Keep the exact wire contract in the natural-language prompt
 * as well so a compatibility route is never asked to guess our schema.
 */
internal fun stepExecutionShapeContract(criteria: List<AgentAcceptanceCriterion>): String =
    stepShapeContract(
        criteria = criteria,
        workProductDescription = "the complete result for only the assigned milestone",
        claimInstruction = "Populate claims with material supported facts, inferences, recommendations, and uncertainty from the preserved evidence. Writing a grounded claim in this response is synthesis, not invention. Use an empty claims array only when the supplied evidence supports no claim; never use it to avoid completing a synthesis or correction.",
    )

internal fun stepRepairShapeContract(criteria: List<AgentAcceptanceCriterion>): String =
    stepShapeContract(
        criteria = criteria,
        workProductDescription = "the complete supplied work, preserved in substance",
        claimInstruction = "Preserve claims and citations present in the supplied work. Use an empty claims array rather than inventing claims or citations absent from the supplied response.",
    )

private fun stepShapeContract(
    criteria: List<AgentAcceptanceCriterion>,
    workProductDescription: String,
    claimInstruction: String,
): String {
    val criterionIds = criteria.joinToString(", ") { it.id }
    return buildString {
        appendLine("REQUIRED OUTPUT CONTRACT (exact top-level keys; no replacements or extra wrapper):")
        appendLine("{")
        appendLine("  \"work_product\": \"$workProductDescription\",")
        appendLine("  \"completion_score\": 0.0,")
        appendLine("  \"acceptance_checks\": [")
        appendLine("    {\"criterion_id\": \"criterion id\", \"status\": \"pass|partial|fail|not_evaluated\", \"score\": 0.0, \"explanation\": \"evidence-based reason\"}")
        appendLine("  ],")
        appendLine("  \"claims\": [")
        appendLine("    {\"id\": \"stable id\", \"text\": \"claim\", \"type\": \"fact|inference|recommendation|uncertainty\", \"confidence\": 0.0, \"supporting_evidence_ids\": [], \"source_urls\": []}")
        appendLine("  ],")
        appendLine("  \"unresolved_questions\": []")
        appendLine("}")
        appendLine("completion_score, every acceptance-check score, and every confidence value must be numbers from 0.0 through 1.0.")
        appendLine("Return exactly one acceptance_checks entry for each supplied criterion: ${criterionIds.ifBlank { "none" }}.")
        appendLine(claimInstruction)
    }.trim()
}

internal data class RecoveredStepAssessment(
    val checks: List<AgentAcceptanceCheck>,
    val completionScore: Double,
    val unresolvedQuestions: List<String>,
)

internal fun hasCanonicalStepWireShape(root: JSONObject): Boolean =
    root.opt("work_product") is String &&
        root.opt("completion_score") is Number &&
        root.optJSONArray("acceptance_checks") != null &&
        root.optJSONArray("claims") != null &&
        root.optJSONArray("unresolved_questions") != null

/**
 * Conservatively interprets only explicit grades from a provider's alternate
 * JSON shape. It never infers task completion from polished prose and never
 * creates claims, evidence, citations, or facts.
 */
internal fun recoverExplicitStepAssessment(
    repairContent: String,
    criteria: List<AgentAcceptanceCriterion>,
): RecoveredStepAssessment? {
    if (criteria.isEmpty()) return null
    val root = runCatching {
        JsonEnvelopeParser.requireEmbeddedObject(repairContent, "Agent milestone repair")
    }.getOrNull() ?: return null
    val canonicalChecks = root.arrayForNormalizedKey("acceptance_checks")
    val gradeObjects = listOfNotNull(
        root.objectForNormalizedKey("criteria_grades"),
        root.objectForNormalizedKey("criterion_grades"),
        root.objectForNormalizedKey("acceptance_criteria_grades"),
        root.objectForNormalizedKey("grading"),
        root.objectForNormalizedKey("grades"),
    )
    val acceptanceCriteria = root.objectForNormalizedKey("acceptance_criteria")

    var recognized = 0
    val checks = criteria.map { criterion ->
        val canonical = (0 until (canonicalChecks?.length() ?: 0))
            .asSequence()
            .mapNotNull { canonicalChecks?.optJSONObject(it) }
            .firstOrNull {
                it.valueForNormalizedKey("criterion_id")
                    ?.toString()
                    ?.trim()
                    ?.equals(criterion.id, ignoreCase = true) == true
            }
            ?.let(::assessmentFromObject)
        val directGrade = gradeObjects.asSequence()
            .mapNotNull { grades -> assessmentFromValue(grades.valueForNormalizedKey(criterion.id)) }
            .firstOrNull()
        val nestedGrade = acceptanceCriteria
            ?.valueForNormalizedKey(criterion.id)
            ?.let(::assessmentFromValue)
        val assessment = canonical ?: directGrade ?: nestedGrade
        if (assessment != null) recognized += 1
        AgentAcceptanceCheck(
            criterionId = criterion.id,
            status = assessment?.status ?: AgentAcceptanceCheckStatus.NOT_EVALUATED,
            score = assessment?.score ?: 0.0,
            explanation = assessment?.explanation
                ?: "The repair response did not explicitly grade this criterion in a recognized field.",
        )
    }
    if (recognized == 0) return null

    val totalWeight = criteria.sumOf { it.weight.coerceAtLeast(0.1) }
    val checksById = checks.associateBy { it.criterionId }
    val weightedScore = criteria.sumOf { criterion ->
        criterion.weight.coerceAtLeast(0.1) * (checksById[criterion.id]?.score ?: 0.0)
    }.div(totalWeight).coerceIn(0.0, 1.0)

    return RecoveredStepAssessment(
        checks = checks,
        completionScore = weightedScore,
        unresolvedQuestions = recoverUnresolvedQuestions(root),
    )
}

private data class ExplicitAssessment(
    val status: AgentAcceptanceCheckStatus,
    val score: Double,
    val explanation: String,
)

private fun assessmentFromObject(value: JSONObject): ExplicitAssessment? {
    val rawStatus = listOf("status", "grade", "result", "assessment")
        .asSequence()
        .mapNotNull { key -> value.valueForNormalizedKey(key) }
        .map(Any::toString)
        .map(String::trim)
        .firstOrNull(String::isNotBlank)
        ?: return null
    val status = explicitStatus(rawStatus) ?: return null
    val score = value.valueForNormalizedKey("score")
        ?.toString()
        ?.toDoubleOrNull()
        ?.coerceIn(0.0, 1.0)
        ?: defaultScore(status)
    val explanation = value.valueForNormalizedKey("explanation")
        ?.toString()
        .orEmpty()
        .trim()
        .ifBlank { "Provider repair explicitly graded this criterion as '$rawStatus'." }
        .take(1_000)
    return ExplicitAssessment(status, score, explanation)
}

private fun assessmentFromValue(value: Any?): ExplicitAssessment? {
    if (value is JSONObject) return assessmentFromObject(value)
    val rawStatus = value?.toString()?.trim().orEmpty()
    val status = explicitStatus(rawStatus) ?: return null
    return ExplicitAssessment(
        status = status,
        score = defaultScore(status),
        explanation = "Provider repair explicitly graded this criterion as '$rawStatus'.",
    )
}

private fun explicitStatus(raw: String): AgentAcceptanceCheckStatus? {
    val normalized = raw.trim().lowercase(Locale.US)
        .replace(Regex("[^a-z_ -]"), "")
        .replace(Regex("[ _-]+"), " ")
        .trim()
    return when (normalized) {
        "pass", "passed", "met", "complete", "completed", "satisfied", "success" ->
            AgentAcceptanceCheckStatus.PASS
        "partial", "partially met", "partially satisfied", "incomplete" ->
            AgentAcceptanceCheckStatus.PARTIAL
        "fail", "failed", "unmet", "not met", "unsatisfied" ->
            AgentAcceptanceCheckStatus.FAIL
        "not evaluated", "unknown", "ungraded" -> AgentAcceptanceCheckStatus.NOT_EVALUATED
        else -> null
    }
}

private fun defaultScore(status: AgentAcceptanceCheckStatus): Double = when (status) {
    AgentAcceptanceCheckStatus.PASS -> 1.0
    AgentAcceptanceCheckStatus.PARTIAL -> 0.5
    AgentAcceptanceCheckStatus.FAIL,
    AgentAcceptanceCheckStatus.NOT_EVALUATED,
    -> 0.0
}

private fun recoverUnresolvedQuestions(root: JSONObject): List<String> {
    val keys = listOf(
        "unresolved_questions",
        "evidence_gaps",
        "current_evidence_gaps",
        "critical_gaps_for_subsequent_research",
    )
    return keys.asSequence()
        .mapNotNull { key -> root.arrayForNormalizedKey(key) }
        .flatMap { values ->
            (0 until values.length()).asSequence().mapNotNull { index ->
                values.optString(index).trim().takeIf(String::isNotBlank)
            }
        }
        .distinct()
        .take(10)
        .map { it.take(500) }
        .toList()
}

private fun JSONObject.valueForNormalizedKey(requestedKey: String): Any? {
    val normalized = requestedKey.normalizedJsonKey()
    val matchingKey = keys().asSequence().firstOrNull { it.normalizedJsonKey() == normalized }
        ?: return null
    return opt(matchingKey).takeUnless { it == null || it === JSONObject.NULL }
}

private fun JSONObject.objectForNormalizedKey(key: String): JSONObject? =
    valueForNormalizedKey(key) as? JSONObject

private fun JSONObject.arrayForNormalizedKey(key: String) =
    valueForNormalizedKey(key) as? org.json.JSONArray

private fun String.normalizedJsonKey(): String =
    lowercase(Locale.US).filter(Char::isLetterOrDigit)
