package com.david.openassistant.agent

/**
 * Builds bounded durable evidence while reserving space for runtime-owned
 * proof of actual tool execution. Provider text cannot crowd the audit out of
 * a checkpoint, and recovery can safely read the final audit section.
 */
internal fun durableEvidenceContent(
    result: AgentStepResult,
    maximumCharacters: Int,
): String {
    require(maximumCharacters > 0)
    val runtimeAppendix = buildString {
        if (result.toolExecutions.isNotEmpty()) {
            appendLine()
            appendLine()
            appendLine("Autonomous local tool activity:")
            result.toolExecutions.forEach { execution ->
                val status = if (execution.succeeded) "PASS" else "ERROR"
                append("- [$status] ${execution.toolName}: ")
                appendLine(execution.summary.take(MAX_TOOL_AUDIT_SUMMARY_CHARS))
            }
        }
        if (result.structuredOutputRepaired) {
            appendLine()
            appendLine()
            appendLine("Runtime note: the provider's useful work was preserved and its response envelope was repaired before deterministic quality checks.")
        }
        if (result.unresolvedQuestions.isNotEmpty()) {
            appendLine()
            appendLine()
            appendLine("Unresolved questions:")
            result.unresolvedQuestions.forEach { appendLine("- $it") }
        }
    }.take(minOf(MAX_RUNTIME_APPENDIX_CHARS, maximumCharacters))
    return buildString {
        val resultBudget = (maximumCharacters - runtimeAppendix.length).coerceAtLeast(0)
        append(result.content.take(resultBudget))
        append(runtimeAppendix)
    }.take(maximumCharacters)
}

private const val MAX_RUNTIME_APPENDIX_CHARS = 8_000
private const val MAX_TOOL_AUDIT_SUMMARY_CHARS = 600
