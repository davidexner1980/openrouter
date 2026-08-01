package com.david.openassistant.agent

/**
 * Planners sometimes turn a desirable evidence type into an assertion that it
 * must exist. Preserve the demanding search target while making an honest,
 * documented absence a valid outcome.
 */
internal fun boundEvidenceContingentPlanCriteria(draft: AgentPlanDraft): AgentPlanDraft = draft.copy(
    acceptanceCriteria = draft.acceptanceCriteria.map(::boundEvidenceContingentCriterion),
    tasks = draft.tasks.map { task ->
        task.copy(acceptanceCriteria = task.acceptanceCriteria.map(::boundEvidenceContingentCriterion))
    },
)

private fun boundEvidenceContingentCriterion(
    criterion: AgentAcceptanceCriterion,
): AgentAcceptanceCriterion {
    val description = criterion.description.trim()
    if (!EVIDENCE_EXISTENCE_ASSUMPTION_PATTERN.containsMatchIn(description)) return criterion
    if (EVIDENCE_BOUNDARY_ALTERNATIVE_PATTERN.containsMatchIn(description)) return criterion
    return criterion.copy(
        description = (
            "$description If that evidence does not exist or cannot be located after documented " +
                "alternate-angle research, explicitly establish the evidence boundary, revise the " +
                "conclusion to the strongest supportable answer, and do not invent the missing record."
            ).take(1_000),
    )
}

private val EVIDENCE_EXISTENCE_ASSUMPTION_PATTERN = Regex(
    "\\b(?:consensus|controlled (?:test|comparison|trial|experiment)|head-to-head|" +
        "(?:specific|exact|measured|quantitative) .{0,80}(?:metrics?|measurements?|datasets?|data)|" +
        "at least \\d+ .{0,60}(?:independent |high-quality |expert )?(?:sources|studies|reviews|reviewers|experts))\\b",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private val EVIDENCE_BOUNDARY_ALTERNATIVE_PATTERN = Regex(
    "\\b(?:if (?:that|the|such) evidence (?:does not exist|cannot be located)|" +
        "evidence boundary|strongest supportable answer|do not invent)\\b",
    RegexOption.IGNORE_CASE,
)
