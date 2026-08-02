package com.david.openassistant.agent

import com.david.openassistant.data.openrouter.OpenRouterException
import java.util.concurrent.CancellationException

/** True when a completed provider subcall left billable usage to preserve. */
private fun AgentApiSummary.hasUsage(): Boolean =
    promptTokens != null || completionTokens != null || totalTokens != null ||
        costUsd != null || webSearchRequests != null

/**
 * Carries usage from successful subcalls across a later failure in the same
 * operation. Multi-call milestones can perform planning, search strategy, or
 * tool rounds before a continuation request fails; dropping those earlier
 * responses makes the durable ledger and attempt history under-report usage.
 */
internal fun Throwable.withAgentUsage(summary: AgentApiSummary): Throwable {
    // Cancellation is control flow. Wrapping it in an IOException would make
    // WorkManager treat a stopped coroutine as a provider failure and retry it.
    if (this is CancellationException) return this
    if (!summary.hasUsage()) return this
    val existing = this as? OpenRouterException
    return OpenRouterException(
        statusCode = existing?.statusCode,
        userMessage = existing?.userMessage ?: message.orEmpty().ifBlank {
            "The provider operation failed after one or more completed subcalls."
        },
        cause = this,
        role = existing?.role ?: summary.role,
        selectionReason = existing?.selectionReason ?: summary.selectionReason,
        previousRoute = existing?.previousRoute ?: summary.previousRoute,
        cooldownState = existing?.cooldownState ?: summary.cooldownState,
        provider = existing?.provider ?: summary.provider,
        finishReason = existing?.finishReason ?: summary.finishReason,
        nativeFinishReason = existing?.nativeFinishReason ?: summary.nativeFinishReason,
        promptTokens = nullableUsageSum(existing?.promptTokens, summary.promptTokens),
        completionTokens = nullableUsageSum(existing?.completionTokens, summary.completionTokens),
        totalTokens = nullableUsageSum(existing?.totalTokens, summary.totalTokens),
        costUsd = nullableUsageSum(existing?.costUsd, summary.costUsd),
        webSearchRequests = nullableUsageSum(existing?.webSearchRequests, summary.webSearchRequests),
    )
}

/**
 * Preserves usage from successful planner responses even when their structure
 * is rejected. This keeps the durable goal's token and cost ledger honest.
 */
internal fun OpenRouterException.withPlanningUsage(summary: AgentApiSummary): OpenRouterException {
    return withAgentUsage(summary) as OpenRouterException
}

internal fun planningFailure(
    message: String,
    summary: AgentApiSummary,
): OpenRouterException = OpenRouterException(
    statusCode = null,
    userMessage = message,
    role = summary.role,
    selectionReason = summary.selectionReason,
    previousRoute = summary.previousRoute,
    cooldownState = summary.cooldownState,
    provider = summary.provider,
    finishReason = summary.finishReason,
    nativeFinishReason = summary.nativeFinishReason,
    promptTokens = summary.promptTokens,
    completionTokens = summary.completionTokens,
    totalTokens = summary.totalTokens,
    costUsd = summary.costUsd,
    webSearchRequests = summary.webSearchRequests,
)

internal fun AgentGoal.accountPlanningFailureUsage(error: Throwable): AgentGoal {
    return accountAgentFailureUsage(error)
}

/** Adds usage carried by a failed multi-call operation to the durable ledger. */
internal fun AgentGoal.accountAgentFailureUsage(error: Throwable): AgentGoal {
    val planningError = error as? OpenRouterException ?: return this
    return this.withAdditionalUsage(planningError.totalTokens, planningError.costUsd)
}

private fun nullableUsageSum(first: Int?, second: Int?): Int? =
    if (first == null && second == null) null else (first ?: 0) + (second ?: 0)

private fun nullableUsageSum(first: Double?, second: Double?): Double? =
    if (first == null && second == null) null else (first ?: 0.0) + (second ?: 0.0)
