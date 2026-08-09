package com.david.openassistant.agent

internal const val MAX_LOCAL_TOOL_ROUNDS = 4
internal const val MAX_LOCAL_TOOL_CALLS_PER_ROUND = 4
internal const val MAX_LOCAL_TOOL_CALLS_TOTAL = 10
internal const val MAX_PROVIDER_TOOL_CALL_RECORDS_PER_ROUND = MAX_LOCAL_TOOL_CALLS_PER_ROUND
internal const val MAX_PROVIDER_TOOL_CALL_ID_CHARS = 160
internal const val MAX_LOCAL_TOOL_RESULT_PROMPT_CHARS = 5_000
internal const val MAX_LOCAL_TOOL_TRANSCRIPT_CHARS = 48_000
internal const val MIN_RESERVED_TOOL_RESULT_CHARS = 96

enum class ToolBudgetPolicy {
    CHAT,
    MISSION
}

data class ToolBudget(
    val maxRounds: Int,
    val maxExecutions: Int,
    val maxDurationMs: Long,
    val maxOutputChars: Int = 64_000
) {
    companion object {
        val CHAT = ToolBudget(
            maxRounds = 10,
            maxExecutions = 20,
            maxDurationMs = 300_000L
        )
        
        val MISSION = ToolBudget(
            maxRounds = 32,
            maxExecutions = 128,
            maxDurationMs = 1_800_000L
        )
        
        fun forPolicy(policy: ToolBudgetPolicy) = when(policy) {
            ToolBudgetPolicy.CHAT -> CHAT
            ToolBudgetPolicy.MISSION -> MISSION
        }
    }
}

/** Returns how many provider-requested calls may execute in the current round. */
internal fun allowedLocalToolCalls(
    requestedCalls: Int,
    totalAcceptedCalls: Int,
): Int {
    require(requestedCalls >= 0) { "requestedCalls must not be negative." }
    require(totalAcceptedCalls >= 0) { "totalAcceptedCalls must not be negative." }
    return minOf(
        requestedCalls,
        MAX_LOCAL_TOOL_CALLS_PER_ROUND,
        (MAX_LOCAL_TOOL_CALLS_TOTAL - totalAcceptedCalls).coerceAtLeast(0),
    )
}

internal fun localToolBudgetExhausted(
    completedRounds: Int,
    totalAcceptedCalls: Int,
    transcriptCharacters: Int,
): Boolean =
    completedRounds >= MAX_LOCAL_TOOL_ROUNDS ||
        totalAcceptedCalls >= MAX_LOCAL_TOOL_CALLS_TOTAL ||
        transcriptCharacters >= MAX_LOCAL_TOOL_TRANSCRIPT_CHARS - MIN_RESERVED_TOOL_RESULT_CHARS

/**
 * Keeps one tool result and the cumulative provider transcript bounded. The
 * marker is included inside the limit so a caller never exceeds its budget.
 */
internal fun boundedLocalToolResult(
    rawResult: String,
    remainingTranscriptCharacters: Int,
): String {
    val maximum = minOf(
        MAX_LOCAL_TOOL_RESULT_PROMPT_CHARS,
        remainingTranscriptCharacters.coerceAtLeast(0),
    )
    if (rawResult.length <= maximum) return rawResult
    if (maximum == 0) return ""
    val marker = "\n[Runtime note: tool output truncated to fit the bounded transcript.]"
    return if (maximum <= marker.length) {
        rawResult.take(maximum)
    } else {
        rawResult.take(maximum - marker.length) + marker
    }
}

/** Repairs missing, unsafe, overlong, or duplicate provider call ids. */
internal fun normalizedProviderToolCallId(
    rawId: String,
    fallbackId: String,
    usedIds: MutableSet<String>,
): String {
    val normalizedFallback = fallbackId
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
        .take(MAX_PROVIDER_TOOL_CALL_ID_CHARS)
        .ifBlank { "tool_call" }
    val base = rawId
        .trim()
        .replace(Regex("[^A-Za-z0-9_-]"), "_")
        .trim('_')
        .take(MAX_PROVIDER_TOOL_CALL_ID_CHARS)
        .ifBlank { normalizedFallback }
    var candidate = base
    var duplicate = 2
    while (!usedIds.add(candidate)) {
        val suffix = "_$duplicate"
        candidate = base.take(MAX_PROVIDER_TOOL_CALL_ID_CHARS - suffix.length) + suffix
        duplicate += 1
    }
    return candidate
}
