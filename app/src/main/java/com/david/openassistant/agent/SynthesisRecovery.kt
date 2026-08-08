package com.david.openassistant.agent

import java.util.Locale

internal const val MAX_SYNTHESIS_GAP_RECOVERY_PASSES = 3
internal const val SYNTHESIS_GAP_RESEARCH_PREFIX = "synthesis_gap_research_"
internal const val SYNTHESIS_GAP_ANALYSIS_PREFIX = "synthesis_gap_analysis_"
internal const val EPISTEMIC_BOUNDARY_EVENT_PREFIX = "EPISTEMIC_BOUNDARY_ACCEPTED:"

internal data class SynthesisGapDecision(
    val findings: List<String>,
    val requiresDeterministicAnalysis: Boolean,
    val qualifiesForBoundedPublication: Boolean,
) {
    val hasActionableGap: Boolean
        get() = findings.isNotEmpty()
}

internal data class SynthesisRecoveryMutation(
    val tasks: List<AgentTask>,
    val recoveryTaskIds: List<String>,
    val round: Int,
)

/**
 * Separates an evidence deficit from an ordinary writing/serialization defect.
 * Only a synthesis that is otherwise publication-ready may redirect execution
 * back into research; short, ungrounded, or malformed output is retried as
 * synthesis instead of spending another research pass.
 */
internal fun synthesisGapDecision(
    task: AgentTask,
    result: AgentStepResult,
    qualityReasons: List<String>,
): SynthesisGapDecision {
    if (task.capability != AgentCapability.SYNTHESIZE) {
        return SynthesisGapDecision(emptyList(), false, false)
    }

    val checksById = result.acceptanceChecks.associateBy { it.criterionId }
    val nonPassingChecks = task.acceptanceCriteria.mapNotNull { criterion ->
        val check = checksById[criterion.id] ?: return@mapNotNull null
        if (check.status == AgentAcceptanceCheckStatus.PASS) return@mapNotNull null
        criterion to check
    }
    val candidateFindings = buildList {
        nonPassingChecks.forEach { (criterion, check) ->
            val detail = buildString {
                append(criterion.description.trim())
                check.explanation.trim().takeIf(String::isNotBlank)?.let {
                    append(" — ")
                    append(it)
                }
            }
            if (isEvidenceBoundaryText(detail)) add(detail)
        }
        result.unresolvedQuestions
            .map(String::trim)
            .filter(String::isNotBlank)
            .filter(::isEvidenceBoundaryText)
            .forEach(::add)
    }
        .map { it.replace(Regex("\\s+"), " ").trim().take(MAX_SYNTHESIS_GAP_FINDING_CHARS) }
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase(Locale.US) }
        .take(MAX_SYNTHESIS_GAP_FINDINGS)

    val onlyAcceptanceGateFailed = qualityReasons.isNotEmpty() && qualityReasons.all { reason ->
        reason.contains("must pass every acceptance criterion", ignoreCase = true)
    }
    val groundedClaims = result.claims.filter { claim ->
        FactualClaimSupportPolicy.evaluate(claim, result.sourceReads) is FactualClaimSupportDecision.Supported
    }
    val otherwisePublicationReady =
        result.completionScore >= MIN_BOUNDED_SYNTHESIS_SCORE &&
            result.content.length >= ResearchQualityGate.MIN_SYNTHESIS_CONTENT_CHARS &&
            groundedClaims.size >= ResearchQualityGate.MIN_SYNTHESIS_CLAIMS &&
            groundedClaims.any { it.type == AgentClaimType.FACT } &&
            onlyAcceptanceGateFailed
    val everyDeficitIsTransparentPartial = nonPassingChecks.isNotEmpty() && nonPassingChecks.all { (_, check) ->
        check.status == AgentAcceptanceCheckStatus.PARTIAL &&
            check.score >= MIN_BOUNDED_CRITERION_SCORE &&
            isEvidenceBoundaryText(check.explanation)
    }
    val describesUncertainty = isEvidenceBoundaryText(
        buildString {
            appendLine(result.content)
            result.unresolvedQuestions.forEach(::appendLine)
            nonPassingChecks.forEach { (_, check) -> appendLine(check.explanation) }
        },
    )

    return SynthesisGapDecision(
        findings = candidateFindings.takeIf { otherwisePublicationReady }.orEmpty(),
        requiresDeterministicAnalysis = candidateFindings.any(::requiresDeterministicAnalysis),
        qualifiesForBoundedPublication = otherwisePublicationReady &&
            everyDeficitIsTransparentPartial &&
            describesUncertainty &&
            candidateFindings.isNotEmpty(),
    )
}

internal fun completedSynthesisGapRecoveryPasses(goal: AgentGoal): Int = goal.tasks.count { task ->
    task.id.startsWith(SYNTHESIS_GAP_RESEARCH_PREFIX) && task.status == AgentTaskStatus.COMPLETED
}

internal fun AgentGoal.hasEpistemicallyBoundedConclusion(): Boolean = events.any { event ->
    event.message.startsWith(EPISTEMIC_BOUNDARY_EVENT_PREFIX)
}

/**
 * Inserts recovery immediately before the synthesis milestone, keeping every
 * dependency backward-pointing. Later tasks retain their relative order.
 */
internal fun insertSynthesisGapRecovery(
    goal: AgentGoal,
    synthesisTaskId: String,
    decision: SynthesisGapDecision,
    preciseFailure: String,
    now: Long = System.currentTimeMillis(),
): SynthesisRecoveryMutation {
    require(decision.hasActionableGap) { "Synthesis recovery requires a concrete evidence gap." }
    val synthesis = goal.tasks.firstOrNull { it.id == synthesisTaskId }
        ?: throw IllegalArgumentException("The synthesis task is missing from the goal.")
    require(synthesis.capability == AgentCapability.SYNTHESIZE) {
        "Only a synthesis task can receive synthesis-gap recovery."
    }

    val round = completedSynthesisGapRecoveryPasses(goal) + 1
    require(round <= MAX_SYNTHESIS_GAP_RECOVERY_PASSES) {
        "The bounded synthesis-gap recovery budget is exhausted."
    }
    val existingIds = goal.tasks.mapTo(mutableSetOf()) { it.id }
    val researchId = uniqueRecoveryTaskId("$SYNTHESIS_GAP_RESEARCH_PREFIX$round", existingIds)
    existingIds += researchId
    val completedPredecessors = goal.tasks
        .asSequence()
        .filter { it.status == AgentTaskStatus.COMPLETED && it.order < synthesis.order }
        .map { it.id }
        .toList()
    val researchDependencies = (synthesis.dependsOn + completedPredecessors).distinct()
    val findings = decision.findings.take(MAX_SYNTHESIS_GAP_FINDINGS)
    val hostedAnalysisAvailable = !goal.executionModelId.isFreeOnlyExecutionRoute()
    val researchTask = AgentTask(
        id = researchId,
        cycleId = goal.activeResearchCycleId,
        order = synthesis.order,
        title = "Gap closure: synthesis evidence escape $round",
        instructions = buildString {
            appendLine("The last synthesis reached a concrete evidence boundary. Investigate only the unresolved questions below; do not repeat completed research angles.")
            findings.forEach { appendLine("- $it") }
            appendLine()
            appendLine("Derive at least three new query angles from the exact missing identifiers, datasets, methods, entities, dates, or measurements. Follow newly discovered citations and terminology into full sources.")
            appendLine("If a direct page is blocked, paywalled, binary, or missing, pursue official metadata endpoints, alternate HTML or text representations, APIs, machine-readable services, archives, mirrors, and the source's cited upstream records.")
            if (decision.requiresDeterministicAnalysis && !hostedAnalysisAvailable) {
                appendLine("The selected free-only execution route cannot depend on the hosted code workbench. Pursue official GIS/REST or other machine-readable endpoints directly, preserve exact request parameters and returned values, and treat an unavailable computation as an explicit evidence boundary rather than a reason to stop.")
            }
            appendLine("For each gap, either close it with precise evidence or document the exact access or evidentiary boundary, the alternate paths attempted, and how that boundary changes the answer.")
        }.trim(),
        capability = AgentCapability.DEEP_RESEARCH,
        dependsOn = researchDependencies,
        status = AgentTaskStatus.QUEUED,
        weight = 2.5,
        acceptanceCriteria = findings.mapIndexed { index, finding ->
            AgentAcceptanceCriterion(
                id = "${researchId}_gap_${index + 1}",
                description = "Resolve or explicitly bound this synthesis evidence gap after pursuing alternate sources: $finding",
                weight = 1.0,
            )
        },
    )

    val recoveryTasks = mutableListOf(researchTask)
    var finalRecoveryId = researchId
    if (decision.requiresDeterministicAnalysis && hostedAnalysisAvailable) {
        val analysisId = uniqueRecoveryTaskId("$SYNTHESIS_GAP_ANALYSIS_PREFIX$round", existingIds)
        val analysisTask = AgentTask(
            id = analysisId,
            cycleId = goal.activeResearchCycleId,
            order = synthesis.order + 1,
            title = "Analyze the unresolved synthesis evidence deterministically",
            instructions = buildString {
                appendLine("Use the newly preserved evidence to perform the missing deterministic operation behind these unresolved synthesis gaps:")
                findings.forEach { appendLine("- $it") }
                appendLine()
                appendLine("Use sandbox_workbench when extraction, code, GIS, PDF, dataset, API, statistical, or reproducible computation is required. Enable its web access when the selected route permits it; otherwise supply the preserved data as input.")
                appendLine("Record exact inputs, source URLs, code or method, coordinate systems or units when relevant, validation checks, outputs, and remaining limitations. Do not substitute prose for a real tool execution.")
            }.trim(),
            capability = AgentCapability.TOOL_USE,
            dependsOn = (researchDependencies + researchId).distinct(),
            status = AgentTaskStatus.QUEUED,
            weight = 2.5,
            acceptanceCriteria = listOf(
                AgentAcceptanceCriterion(
                    id = "${analysisId}_executed",
                    description = "A real deterministic tool executes the missing analysis or extraction against preserved inputs.",
                    weight = 1.5,
                ),
                AgentAcceptanceCriterion(
                    id = "${analysisId}_reproducible",
                    description = "The result records reproducible inputs, method, output, validation, provenance, and limitations.",
                    weight = 1.0,
                ),
            ),
        )
        recoveryTasks += analysisTask
        finalRecoveryId = analysisId
    }

    val insertionSize = recoveryTasks.size
    val resumedSynthesis = synthesis.copy(
        order = synthesis.order + insertionSize,
        dependsOn = (synthesis.dependsOn + finalRecoveryId).distinct(),
        status = AgentTaskStatus.FAILED,
        attemptCount = 0,
        lastError = buildString {
            append("Synthesis exposed an unresolved evidence boundary. Recovery round $round was inserted before another synthesis attempt. ")
            append(preciseFailure.trim().take(MAX_SYNTHESIS_FAILURE_CONTEXT_CHARS))
        }.trim().take(2_000),
        finishedAt = now,
    )
    val updatedTasks = buildList {
        goal.tasks.forEach { task ->
            when {
                task.id == synthesis.id -> {
                    addAll(recoveryTasks)
                    add(resumedSynthesis)
                }
                task.order > synthesis.order -> add(task.copy(order = task.order + insertionSize))
                else -> add(task)
            }
        }
    }.sortedBy { it.order }

    return SynthesisRecoveryMutation(
        tasks = updatedTasks,
        recoveryTaskIds = recoveryTasks.map { it.id },
        round = round,
    )
}

internal fun boundedSynthesisEventMessage(task: AgentTask, decision: SynthesisGapDecision): String = buildString {
    append(EPISTEMIC_BOUNDARY_EVENT_PREFIX)
    append(" Synthesis '")
    append(task.title)
    append("' produced a grounded best-supported conclusion after ")
    append(MAX_SYNTHESIS_GAP_RECOVERY_PASSES)
    append(" alternate-angle recovery pass(es). Remaining uncertainty is published explicitly instead of being converted into false certainty or terminal failure. Bounded gaps: ")
    append(decision.findings.joinToString(" | ").take(1_200))
}

/**
 * A verifier may accept a bounded conclusion only after the runtime's explicit
 * recovery marker and only when every non-passing item is itself an evidence
 * boundary. Citation, formatting, support, and missing-answer defects never use
 * this path.
 */
internal fun acceptsEpistemicallyBoundedVerification(
    goal: AgentGoal,
    verification: AgentVerificationResult,
): Boolean {
    if (!goal.hasEpistemicallyBoundedConclusion()) return false
    if (verification.qualityScore < MIN_BOUNDED_VERIFICATION_SCORE || verification.finalAnswer.isBlank()) return false
    if (verification.missingRequirements.any { requirement ->
            !isEvidenceBoundaryText(requirement) &&
                !isEvidenceContingentRequirementText(requirement)
        }
    ) {
        return false
    }
    val checksById = verification.acceptanceChecks.associateBy { it.criterionId }
    return goal.acceptanceCriteria.all { criterion ->
        val check = checksById[criterion.id] ?: return@all false
        when (check.status) {
            AgentAcceptanceCheckStatus.PASS -> check.score >= MIN_PASSED_CRITERION_SCORE
            AgentAcceptanceCheckStatus.PARTIAL ->
                check.score >= MIN_BOUNDED_CRITERION_SCORE && isEvidenceBoundaryText(check.explanation)
            AgentAcceptanceCheckStatus.FAIL ->
                check.score >= MIN_BOUNDED_FAILED_CRITERION_SCORE && isEvidenceBoundaryText(check.explanation)
            AgentAcceptanceCheckStatus.NOT_EVALUATED -> false
        }
    }
}

internal fun isSynthesisGapAnalysisTask(task: AgentTask): Boolean =
    task.id.startsWith(SYNTHESIS_GAP_ANALYSIS_PREFIX)

/**
 * A hosted analysis provider can itself be the blocked path. After its bounded
 * tool window is exhausted, change capability and method instead of retrying
 * the same unavailable tool or failing the mission.
 */
internal fun AgentTask.rerouteUnavailableSynthesisAnalysis(
    preciseFailure: String,
    now: Long = System.currentTimeMillis(),
): AgentTask {
    require(isSynthesisGapAnalysisTask(this) && capability == AgentCapability.TOOL_USE) {
        "Only a synthesis-gap tool analysis can use the alternate-source fallback."
    }
    return copy(
        title = "Gap closure: deterministic-analysis alternate source",
        instructions = buildString {
            appendLine("The hosted deterministic workbench remained unavailable after its bounded attempt window. Do not call it again and do not stop the mission.")
            appendLine("Use a new deep-research angle to approximate or replace the missing operation with official machine-readable APIs, precomputed government or first-party datasets, documented query endpoints, alternate text/HTML representations, published tables, archival records, or independently reproduced results.")
            appendLine("Preserve exact URLs, request parameters, identifiers, coordinates, units, datums, methods, and returned values. Seek independent corroboration and contradictions.")
            appendLine("If the operation still cannot be reproduced, document every attempted alternate path and the precise evidence boundary so synthesis can deliver the strongest honest answer instead of failing.")
            appendLine()
            appendLine("Unavailable-tool context: ${preciseFailure.trim().take(MAX_SYNTHESIS_FAILURE_CONTEXT_CHARS)}")
        }.trim(),
        capability = AgentCapability.DEEP_RESEARCH,
        status = AgentTaskStatus.FAILED,
        attemptCount = 0,
        lastError = preciseFailure.trim().take(2_000).ifBlank {
            "The hosted deterministic workbench was unavailable."
        },
        acceptanceCriteria = listOf(
            AgentAcceptanceCriterion(
                id = "${id}_alternate_path",
                description = "Pursue multiple official or independently reproducible alternate sources for the unavailable deterministic operation.",
                weight = 1.5,
            ),
            AgentAcceptanceCriterion(
                id = "${id}_reproducible_trace",
                description = "Preserve the exact endpoint, inputs, identifiers, method, output, validation, provenance, and any residual evidence boundary.",
                weight = 1.0,
            ),
        ),
        acceptanceChecks = emptyList(),
        progressScore = 0.0,
        startedAt = null,
        finishedAt = now,
        outputEvidenceId = null,
    )
}

internal fun automaticSynthesisAnalysisFallbackMessage(task: AgentTask, reason: String): String =
    "Synthesis-gap analysis '${task.title}' completed its $MAX_REQUIRED_TOOL_MILESTONE_ATTEMPTS-attempt " +
        "hosted-tool window without a verified execution. Preserved work remains available. The milestone " +
        "was rerouted automatically to deep research using official APIs, precomputed datasets, published " +
        "tables, archives, and independently reproduced results. Last tool deficiency: " +
        reason.trim().take(800)

private fun uniqueRecoveryTaskId(preferred: String, existingIds: Set<String>): String =
    generateSequence(preferred) { current -> "${current}_x" }
        .first { candidate -> candidate !in existingIds }

private fun String.isFreeOnlyExecutionRoute(): Boolean =
    equals("openrouter/free", ignoreCase = true) || endsWith(":free", ignoreCase = true)

internal fun isEvidenceBoundaryText(value: String): Boolean =
    EVIDENCE_BOUNDARY_PATTERN.containsMatchIn(value.lowercase(Locale.US))

private fun requiresDeterministicAnalysis(value: String): Boolean =
    DETERMINISTIC_ANALYSIS_PATTERN.containsMatchIn(value.lowercase(Locale.US))

private fun isEvidenceContingentRequirementText(value: String): Boolean =
    EVIDENCE_CONTINGENT_REQUIREMENT_PATTERN.containsMatchIn(value.lowercase(Locale.US))

private const val MAX_SYNTHESIS_GAP_FINDINGS = 6
private const val MAX_SYNTHESIS_GAP_FINDING_CHARS = 700
private const val MAX_SYNTHESIS_FAILURE_CONTEXT_CHARS = 900
private const val MIN_BOUNDED_SYNTHESIS_SCORE = 0.80
private const val MIN_BOUNDED_VERIFICATION_SCORE = 0.65
private const val MIN_BOUNDED_CRITERION_SCORE = 0.35
private const val MIN_BOUNDED_FAILED_CRITERION_SCORE = 0.20
private const val MIN_PASSED_CRITERION_SCORE = 0.75

private val EVIDENCE_BOUNDARY_PATTERN = Regex(
    "\\b(unresolved|uncertain(?:ty)?|unverified|not (?:yet )?(?:verified|confirmed|established|determined)|" +
        "cannot (?:be )?(?:verified|confirmed|established|determined|asserted)|inconclusive|best[- ]available|" +
        "evidence (?:does not|cannot) (?:conclusively )?(?:show|establish|confirm|determine)|" +
        "data (?:does not|cannot) (?:conclusively )?(?:show|establish|confirm|determine)|" +
        "no (?:spatial |deterministic |independent |direct |controlled |matched )?(?:analysis|measurement|record|dataset|source|comparison|test|consensus) (?:was|is|has been|exists|could be)|" +
        "(?:found|located) no (?:qualifying |matching |independent |controlled )?(?:analysis|measurement|record|dataset|source|comparison|test|consensus)|" +
        "no .{0,80}(?:was|were|could be) (?:found|located|identified|published|reported)|" +
        "(?:absence|lack) of (?:a |an |the )?(?:qualifying |matching |independent |controlled )?(?:analysis|measurement|record|dataset|source|comparison|test|consensus)|" +
        "(?:analysis|measurement|record|dataset|source|comparison|test|consensus) (?:is|was|remains) (?:absent|unavailable|unpublished)|" +
        "(?:supplies|provides|contains|reports) no .{0,80}(?:metric|measurement|velocity|dispersion|comparison|test|data|consensus)|" +
        "no (?:available )?.{0,80}(?:metric|measurement|velocity|dispersion|comparison|test|dataset|consensus)|" +
        "missing (?:data|dataset|measurement|record|analysis|datum|coordinates?|boundary|method|metadata)|" +
        "not (?:published|disclosed|available|accessible)|access (?:barrier|limitation|blocked)|" +
        "pending (?:analysis|verification|measurement|access)|requires? (?:a |an )?(?:gis |spatial |deterministic |independent )?(?:analysis|overlay|extraction|measurement|verification)|" +
        "evidence gap|data gap|research boundary|precision limit|boundary inclusion|natural[- ]ground status)\\b",
)

private val DETERMINISTIC_ANALYSIS_PATTERN = Regex(
    "\\b(gis|geospatial|spatial|overlay|dem|digital elevation|raster|vector|shapefile|coordinate system|" +
        "clip(?:ping)?|extract(?:ion)?|calculate|calculation|compute|computation|parse|pdf|dataset|data set|" +
        "api|json|csv|table|statistical|statistics|convert|conversion|code|script|simulation|reproduce|reproducible)\\b",
)

private val EVIDENCE_CONTINGENT_REQUIREMENT_PATTERN = Regex(
    "\\b(?:consensus|controlled (?:test|comparison|trial|experiment)|head-to-head|" +
        "(?:specific|exact|measured|quantitative) .{0,80}(?:metrics?|measurements?|datasets?|data)|" +
        "metric-by-metric comparison|stronger independent evidence)\\b",
)
