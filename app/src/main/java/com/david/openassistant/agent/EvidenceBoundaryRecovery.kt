package com.david.openassistant.agent

import java.net.URI
import java.util.Locale

/**
 * A focused recovery pass can succeed by proving that the requested record is
 * not available after a real alternate-angle search. Treating that honest
 * negative result as zero progress creates an expensive loop and pressures the
 * model to invent a consensus, measurement, or dataset that does not exist.
 */
internal fun acceptsBoundedResearchRecovery(
    task: AgentTask,
    result: AgentStepResult,
    policy: AutonomyPolicy = AutonomyPolicy.DEFAULT,
): Boolean {
    if (task.capability != AgentCapability.DEEP_RESEARCH) return false
    if (!isFocusedEvidenceRecoveryTask(task)) return false
    if (task.acceptanceCriteria.isEmpty()) return false

    val checksById = result.acceptanceChecks.associateBy { it.criterionId }
    val checks = task.acceptanceCriteria.map { criterion ->
        checksById[criterion.id] ?: return false
    }
    val nonPassing = checks.filter { it.status != AgentAcceptanceCheckStatus.PASS }
    if (nonPassing.isEmpty()) return false
    if (nonPassing.any { check ->
            check.status == AgentAcceptanceCheckStatus.NOT_EVALUATED ||
                !isEvidenceBoundaryText(check.explanation)
        }
    ) {
        return false
    }

    val sources = result.sources
        .map { it.url.trim() }
        .filter(String::isNotBlank)
        .distinct()
    if (sources.size < MIN_BOUNDED_RECOVERY_SOURCES) return false
    if (sources.any { !it.startsWith("https://") }) return false
    val domains = sources.mapNotNull { source ->
        runCatching { URI(source).host?.lowercase(Locale.US)?.removePrefix("www.") }.getOrNull()
    }.filter(String::isNotBlank).distinct()
    if (domains.size < MIN_BOUNDED_RECOVERY_DOMAINS) return false

    val auditedSearches = successfulResearchSearchCount(result.toolExecutions)
    val searchRequests = maxOf(result.summary.webSearchRequests ?: 0, auditedSearches)
    if (searchRequests < policy.minimumSearchQueriesPerResearchPass) return false
    val readUnits = successfulResearchReadAccounting(result.toolExecutions).equivalentReadUnits
    if (readUnits < MIN_BOUNDED_RECOVERY_READ_UNITS) return false
    if (result.content.length < ResearchQualityGate.MIN_DEEP_RESEARCH_CONTENT_CHARS) return false
    if (result.claims.count { it.type == AgentClaimType.FACT } < ResearchQualityGate.MIN_DEEP_RESEARCH_FACTS) {
        return false
    }

    val boundaryRecord = buildString {
        appendLine(result.content)
        result.unresolvedQuestions.forEach(::appendLine)
        nonPassing.forEach { appendLine(it.explanation) }
    }
    return isEvidenceBoundaryText(boundaryRecord)
}

internal fun isFocusedEvidenceRecoveryTask(task: AgentTask): Boolean =
    task.id.startsWith("verification_") ||
        task.id.startsWith(SYNTHESIS_GAP_RESEARCH_PREFIX)

internal fun boundedResearchRecoveryEventMessage(task: AgentTask): String =
    "$EPISTEMIC_BOUNDARY_EVENT_PREFIX Focused recovery '${task.title}' completed a documented " +
        "alternate-angle search and established that one or more requested records, measurements, " +
        "or consensus claims are not available. The next publication pass must revise the conclusion " +
        "to the strongest supportable answer and state this boundary explicitly."

private const val MIN_BOUNDED_RECOVERY_SOURCES = 3
private const val MIN_BOUNDED_RECOVERY_DOMAINS = 2
private const val MIN_BOUNDED_RECOVERY_READ_UNITS = 2
