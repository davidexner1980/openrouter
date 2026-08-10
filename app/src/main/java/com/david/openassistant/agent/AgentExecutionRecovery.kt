package com.david.openassistant.agent

import java.util.Locale

internal enum class AgentExecutionProfile {
    FULL,
    FOCUSED_TOOL,
    EVIDENCE_BOUNDED_RESPONSE,
    COMPATIBILITY_RESPONSE,
    CHECKPOINT_COMPLETION,
    ANGLE_SWITCH_RECOVERY,
}

internal data class AgentExecutionStrategy(
    val profile: AgentExecutionProfile,
    val allowsInteractiveTools: Boolean,
    val reuseCheckpointSources: Boolean,
    val explanation: String,
)

/**
 * Changes the shape of repeated work instead of sending the same expensive
 * request forever. Research still gets a deterministic public-web bootstrap;
 * reasoning, synthesis, correction, and verification stay evidence-bounded;
 * and tool milestones are never stripped of the tools they are meant to use.
 */
internal fun selectAgentExecutionStrategy(
    goal: AgentGoal,
    task: AgentTask,
): AgentExecutionStrategy {
    val currentTask = goal.tasks.firstOrNull { it.id == task.id } ?: task
    val completedAttempts = goal.attempts
        .filter { (it.taskId == task.id) && (it.status != AgentAttemptStatus.RUNNING) }

    if (task.capability.isToolCapability()) {
        val consecutiveMissingToolAttempts = completedAttempts.countConsecutiveMissingToolFailures()
        if ((consecutiveMissingToolAttempts > 0) || task.lastError.isMissingRequiredToolFailure()) {
            return FOCUSED_TOOL_EXECUTION_STRATEGY
        }
        return FULL_EXECUTION_STRATEGY
    }

    if (task.capability == AgentCapability.CORRECT) {
        return evidenceBoundedCorrectionStrategy
    }

    if (task.capability in AgentCapability.STRUCTURED_RESULT_CAPABILITIES) {
        return evidenceBoundedStrategy(task.capability)
    }

    val consecutiveProviderFailures = completedAttempts.countConsecutiveProviderFailures()
    val taskEvidence = goal.evidence.filter { it.taskId == task.id }
    val hasUsefulCheckpoint = isUsefulCheckpoint(taskEvidence)
    
    val lastCompletedAttempt = completedAttempts.lastOrNull()
    val lastAttemptUsedNoNewSearches = (lastCompletedAttempt != null) &&
        ((lastCompletedAttempt.webSearchRequests ?: 0) == 0) &&
        ((lastCompletedAttempt.totalTokens ?: 0) > 0)

    if (
        (currentTask.attemptCount >= 1) &&
        hasUsefulCheckpoint &&
        (!lastAttemptUsedNoNewSearches) &&
        (task.capability in AgentCapability.RESEARCH_CAPABILITIES)
    ) {
        return checkpointCompletionStrategy
    }

    if (
        (currentTask.attemptCount >= 2) &&
        (task.capability in AgentCapability.RESEARCH_CAPABILITIES) &&
        (!lastAttemptUsedNoNewSearches)
    ) {
        return angleSwitchRecoveryStrategy(currentTask.attemptCount)
    }

    if (consecutiveProviderFailures >= 2) {
        return compatibilityResponseStrategy
    }

    return FULL_EXECUTION_STRATEGY
}

private fun AgentCapability.isToolCapability(): Boolean =
    (this == AgentCapability.TOOL_USE) || (this == AgentCapability.TOOL_CREATE)

private fun List<AgentAttempt>.countConsecutiveMissingToolFailures(): Int =
    asReversed().asSequence()
        .takeWhile { it.status == AgentAttemptStatus.FAILED && it.error.isMissingRequiredToolFailure() }
        .count()

private fun List<AgentAttempt>.countConsecutiveProviderFailures(): Int =
    asReversed().asSequence()
        .takeWhile { it.status == AgentAttemptStatus.FAILED && it.isProviderInfrastructureFailure() }
        .count()

private fun isUsefulCheckpoint(taskEvidence: List<AgentEvidence>): Boolean {
    val preservedSources = taskEvidence.asSequence()
        .flatMap { it.sources }
        .distinctBy { it.url }
        .toList()
    return (preservedSources.size >= MIN_CHECKPOINT_SOURCES) &&
        (taskEvidence.sumOf { it.content.length.coerceAtMost(MAX_EVIDENCE_TRUNCATION) } >= MIN_CHECKPOINT_CONTENT_LENGTH)
}

private val evidenceBoundedCorrectionStrategy = AgentExecutionStrategy(
    profile = AgentExecutionProfile.COMPATIBILITY_RESPONSE,
    allowsInteractiveTools = true,
    reuseCheckpointSources = false,
    explanation = "Use preserved evidence first. Search, fetch, calculate, inspect, or use another tool whenever additional evidence is needed to resolve a verification finding.",
)

private fun evidenceBoundedStrategy(capability: AgentCapability) = AgentExecutionStrategy(
    profile = AgentExecutionProfile.EVIDENCE_BOUNDED_RESPONSE,
    allowsInteractiveTools = true,
    reuseCheckpointSources = false,
    explanation = when (capability) {
        AgentCapability.REASON ->
            "Focus on the assigned reasoning problem. Use searches or tools when facts, definitions, calculations, or current information are required."
        AgentCapability.SYNTHESIZE ->
            "Integrate preserved evidence. Gather additional evidence when a material gap prevents a grounded result."
        AgentCapability.VERIFY ->
            "Evaluate the supplied work and independently investigate unresolved, contradictory, stale, or weakly supported claims when necessary."
        else -> "Use preserved evidence first while retaining access to operational tools for unresolved gaps."
    },
)

private val checkpointCompletionStrategy = AgentExecutionStrategy(
    profile = AgentExecutionProfile.CHECKPOINT_COMPLETION,
    allowsInteractiveTools = true,
    reuseCheckpointSources = true,
    explanation = "Reuse the preserved research checkpoint to avoid duplicate work, while retaining the ability to search or use another tool for unresolved gaps.",
)

private fun angleSwitchRecoveryStrategy(attemptCount: Int) = AgentExecutionStrategy(
    profile = AgentExecutionProfile.ANGLE_SWITCH_RECOVERY,
    allowsInteractiveTools = true,
    reuseCheckpointSources = false,
    explanation = "ANGLE SWITCH: The previous direct investigation hit a wall after $attemptCount attempts. This retry must abandon the direct path and use a radically different lateral angle (e.g. pivoting to community consensus, forensic clues, or alternate-standard documentation) to triangulate the result.",
)

private val compatibilityResponseStrategy = AgentExecutionStrategy(
    profile = AgentExecutionProfile.COMPATIBILITY_RESPONSE,
    allowsInteractiveTools = true,
    reuseCheckpointSources = false,
    explanation = "Two provider attempts ended without usable output, so this retry uses a smaller request shape while retaining access to operational tools.",
)

internal const val MAX_REQUIRED_TOOL_MILESTONE_ATTEMPTS = 6
internal const val MAX_EVIDENCE_BOUNDED_MILESTONE_ATTEMPTS = 4
internal const val MAX_RESEARCH_MILESTONE_ATTEMPTS = 6
internal const val MAX_GLOBAL_AUTOMATIC_RESEARCH_REOPENS = 4
internal const val MAX_GLOBAL_AUTOMATIC_CORRECTION_REOPENS = 3
internal const val MAX_GLOBAL_AUTOMATIC_STRUCTURED_REOPENS = 2
internal const val MAX_CORRECTION_MILESTONE_ATTEMPTS = 6

private const val MAX_ERROR_LENGTH = 2_000
private const val MAX_REASON_LENGTH = 800
private const val MIN_CHECKPOINT_SOURCES = 2
private const val MIN_CHECKPOINT_CONTENT_LENGTH = 400
private const val MAX_EVIDENCE_TRUNCATION = 16_000

internal fun hasExhaustedEvidenceBoundedAttemptWindow(
    task: AgentTask,
    qualityAccepted: Boolean,
): Boolean = !qualityAccepted &&
    task.capability in AgentCapability.STRUCTURED_RESULT_CAPABILITIES &&
    task.attemptCount >= MAX_EVIDENCE_BOUNDED_MILESTONE_ATTEMPTS

internal fun hasExhaustedResearchAttemptWindow(
    task: AgentTask,
    qualityAccepted: Boolean,
): Boolean = !qualityAccepted &&
    task.capability in AgentCapability.RESEARCH_CAPABILITIES &&
    task.attemptCount >= MAX_RESEARCH_MILESTONE_ATTEMPTS

internal fun localAttemptWindowLimit(capability: AgentCapability): Int? = when (capability) {
    in AgentCapability.RESEARCH_CAPABILITIES -> MAX_RESEARCH_MILESTONE_ATTEMPTS
    in AgentCapability.STRUCTURED_RESULT_CAPABILITIES -> MAX_EVIDENCE_BOUNDED_MILESTONE_ATTEMPTS
    AgentCapability.CORRECT -> MAX_CORRECTION_MILESTONE_ATTEMPTS
    AgentCapability.TOOL_USE, AgentCapability.TOOL_CREATE ->
        MAX_REQUIRED_TOOL_MILESTONE_ATTEMPTS
    else -> null
}

/**
 * Opens another bounded research window without discarding the strongest
 * checkpoint. The per-window counter prevents a hot loop; WorkManager backoff
 * still controls when the next window begins, while the complete attempt
 * history remains available for route and execution-profile diversification.
 */
internal fun AgentTask.reopenAutomaticResearchWindow(
    preciseFailure: String,
    madeMeaningfulProgress: Boolean,
    now: Long = System.currentTimeMillis(),
): AgentTask {
    val maxGlobalReopens = MAX_GLOBAL_AUTOMATIC_RESEARCH_REOPENS
    if (globalAutomaticWindowReopenCount >= maxGlobalReopens) {
        val progressNote = if (madeMeaningfulProgress) {
            "The final window added useful evidence, but the acceptance gate is still unresolved."
        } else {
            "The final window added no new accepted evidence or resolved criteria."
        }
        return copy(
            status = AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE,
            lastError = "Exhausted $maxGlobalReopens automatic research reopen windows. $progressNote " +
                "Preserved evidence remains available. Last deficiency: $preciseFailure",
            finishedAt = now,
        )
    }
    return reopenAutomaticWindow(
        capabilityFilter = { it in AgentCapability.RESEARCH_CAPABILITIES },
        preciseFailure = preciseFailure,
        errorMessageIfBlank = "The previous research window did not reach verified completion.",
        now = now,
        incrementGlobal = true,
    )
}

internal fun automaticResearchRecoveryMessage(task: AgentTask, reason: String): String {
    val preciseReason = reason.trim().take(MAX_REASON_LENGTH).ifBlank {
        "The previous window did not produce a result that passed the deterministic research gate."
    }
    return "Research milestone '${task.title}' completed its $MAX_RESEARCH_MILESTONE_ATTEMPTS-attempt local " +
        "safety window without verified completion. Preserved sources, claims, and checkpoints remain " +
        "available. A new automatic recovery window will retry after backoff using newly reasoned query angles and all operational tools. Last deficiency: $preciseReason"
}

/**
 * Opens a fresh correction window after the local safety window
 * ends. The publication graph and strongest partial correction stay durable;
 * only the per-window attempt counter is reset. This prevents one late
 * provider timeout from turning a nearly complete mission into a terminal
 * user-visible failure.
 */
internal fun AgentTask.reopenAutomaticCorrectionWindow(
    preciseFailure: String,
    now: Long = System.currentTimeMillis(),
): AgentTask {
    val maxGlobalReopens = MAX_GLOBAL_AUTOMATIC_CORRECTION_REOPENS
    if (globalAutomaticWindowReopenCount >= maxGlobalReopens) {
        return copy(
            status = AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE,
            lastError = "Exhausted $maxGlobalReopens automatic correction reopen windows. Preserved evidence and partial work remain available. Last deficiency: $preciseFailure",
            finishedAt = now,
        )
    }
    return reopenAutomaticWindow(
        capabilityFilter = { it == AgentCapability.CORRECT },
        preciseFailure = preciseFailure,
        errorMessageIfBlank = "The previous correction window did not pass the publication gate.",
        now = now,
        incrementGlobal = true
    )
}

internal fun automaticCorrectionRecoveryMessage(task: AgentTask, reason: String): String {
    val preciseReason = reason.trim().take(MAX_REASON_LENGTH).ifBlank {
        "The previous correction window did not pass every deterministic publication check."
    }
    return "Correction milestone '${task.title}' completed its $MAX_CORRECTION_MILESTONE_ATTEMPTS-attempt " +
        "local safety window without a publishable result. Preserved evidence, claims, and the strongest " +
        "partial correction remain available. A new automatic correction window will retry after backoff " +
        "with all operational tools. Last deficiency: $preciseReason"
}

internal fun AgentTask.reopenAutomaticEvidenceBoundedWindow(
    preciseFailure: String,
    now: Long = System.currentTimeMillis(),
): AgentTask {
    val maxGlobalReopens = MAX_GLOBAL_AUTOMATIC_STRUCTURED_REOPENS
    if (globalAutomaticWindowReopenCount >= maxGlobalReopens) {
        return copy(
            status = AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE,
            lastError = "Exhausted $maxGlobalReopens automatic evidence-bounded reopen windows. Preserved evidence remains available. Last deficiency: $preciseFailure",
            finishedAt = now,
        )
    }
    return reopenAutomaticWindow(
        capabilityFilter = { it in AgentCapability.STRUCTURED_RESULT_CAPABILITIES },
        preciseFailure = preciseFailure,
        errorMessageIfBlank = "The previous evidence-bounded window did not pass its completion gate.",
        now = now,
        incrementGlobal = true
    )
}

internal fun automaticEvidenceBoundedRecoveryMessage(task: AgentTask, reason: String): String {
    val preciseReason = reason.trim().take(MAX_REASON_LENGTH).ifBlank {
        "The previous window did not pass its deterministic completion gate."
    }
    val label = task.capability.wireName.replace('_', ' ').replaceFirstChar(Char::uppercase)
    return "$label milestone '${task.title}' completed its $MAX_EVIDENCE_BOUNDED_MILESTONE_ATTEMPTS-attempt " +
        "local safety window without verified completion. Its strongest evidence and checkpoint remain " +
        "available. A new automatic window will retry after backoff using all operational tools. " +
        "Last deficiency: $preciseReason"
}

private fun AgentTask.reopenAutomaticWindow(
    capabilityFilter: (AgentCapability) -> Boolean,
    preciseFailure: String,
    errorMessageIfBlank: String,
    now: Long,
    incrementGlobal: Boolean = false,
): AgentTask {
    require(capabilityFilter(capability)) {
        "This milestone capability ($capability) cannot open an automatic recovery window of this type."
    }
    return copy(
        status = AgentTaskStatus.FAILED,
        attemptCount = 0,
        taskGeneration = taskGeneration + 1,
        automaticWindowReopenCount = automaticWindowReopenCount + 1,
        globalAutomaticWindowReopenCount = if (incrementGlobal) globalAutomaticWindowReopenCount + 1 else globalAutomaticWindowReopenCount,
        lastError = preciseFailure.trim().take(MAX_ERROR_LENGTH).ifBlank { errorMessageIfBlank },
        finishedAt = now,
    )
}

internal fun milestoneBoundaryInstruction(capability: AgentCapability): String = when (capability) {
    AgentCapability.REASON ->
        "Produce only the assigned decision framework, definitions, unknowns, or evidence needs. Stay focused on the assigned reasoning problem. You may search or use tools when facts, definitions, calculations, or current information are required."
    AgentCapability.SYNTHESIZE ->
        "Synthesize the preserved evidence into the complete result for this milestone. Integrate existing evidence. You may gather new evidence when a critical gap remains."
    AgentCapability.CORRECT ->
        "Correct the listed verification findings. Use search, fetch, calculate, inspect, or use another tool whenever preserved evidence is insufficient to resolve a finding."
    AgentCapability.VERIFY ->
        "Independently evaluate the work. You may search and use tools to test claims, freshness, provenance, entity fit, contradictions, and acceptance criteria."
    AgentCapability.WEB_RESEARCH,
    AgentCapability.DEEP_RESEARCH,
        -> "Complete only this research pass and its acceptance criteria; do not perform later synthesis or claim the overall mission is finished."
    AgentCapability.TOOL_USE,
    AgentCapability.TOOL_CREATE,
        -> "Complete only this tool milestone and its acceptance criteria; do not substitute unrelated workspace state for current-mission evidence."
}

internal fun hasExhaustedRequiredToolAttemptWindow(
    task: AgentTask,
    toolGatePassed: Boolean,
): Boolean = !toolGatePassed &&
    task.capability in setOf(AgentCapability.TOOL_USE, AgentCapability.TOOL_CREATE) &&
    task.attemptCount >= MAX_REQUIRED_TOOL_MILESTONE_ATTEMPTS

/** A provider result may mutate durable state only while its exact execution lease is active. */
internal fun canCommitMilestoneResult(
    goal: AgentGoal,
    taskId: String,
    taskExecutionAttemptId: String,
    ticket: AgentOwnershipTicket,
): Boolean {
    val lease = goal.executionLease ?: return false
    val activeLeaseMatch = lease.workerId == ticket.workerId &&
        lease.ownerProcessSessionId == ticket.ownerProcessSessionId &&
        lease.attemptId == ticket.attemptId &&
        lease.generation == ticket.leaseGeneration &&
        lease.taskId == (ticket.taskId ?: "none")
    
    val statusMatch = if (ticket is TaskExecutionTicket) {
        goal.status == AgentGoalStatus.RUNNING &&
        goal.tasks.any { task -> task.id == taskId && task.status == AgentTaskStatus.RUNNING }
    } else {
        goal.status == AgentGoalStatus.PLANNING || goal.status == AgentGoalStatus.VERIFYING
    }

    return statusMatch &&
        goal.attempts.any { attempt ->
            attempt.id == taskExecutionAttemptId && attempt.status == AgentAttemptStatus.RUNNING
        } && activeLeaseMatch
}

private fun AgentAttempt.isProviderInfrastructureFailure(): Boolean {
    val normalized = error.orEmpty().lowercase(Locale.US)
    return PROVIDER_FAILURE_MARKERS.any(normalized::contains)
}

private val FULL_EXECUTION_STRATEGY = AgentExecutionStrategy(
    profile = AgentExecutionProfile.FULL,
    allowsInteractiveTools = true,
    reuseCheckpointSources = false,
    explanation = "Full bounded execution profile.",
)

private val FOCUSED_TOOL_EXECUTION_STRATEGY = AgentExecutionStrategy(
    profile = AgentExecutionProfile.FOCUSED_TOOL,
    allowsInteractiveTools = true,
    reuseCheckpointSources = false,
    explanation = "The previous response skipped its required local tool, so this retry uses a focused tool set and requires the best matching deterministic function on the first round.",
)

private fun String?.isMissingRequiredToolFailure(): Boolean {
    val normalized = orEmpty().lowercase(Locale.US)
    return MISSING_REQUIRED_TOOL_MARKERS.any(normalized::contains)
}

private val PROVIDER_FAILURE_MARKERS = listOf(
    "provider request timed out",
    "provider returned an incompatible",
    "provider returned error",
    "provider capacity",
    "provider failed without a usable error response",
    "openrouter request failed",
    "openrouter rate-limited",
    "timed out",
    "timeout",
    "json response shape",
    "json shape",
    "network name resolution",
    "no response",
    "server failure",
)

private val MISSING_REQUIRED_TOOL_MARKERS = listOf(
    "tool-use milestone did not complete a successful local tool call",
    "tool foundry milestone did not both activate and exercise a tested recipe tool",
    "synthesis-gap analysis did not complete a successful sandbox_workbench execution",
)
