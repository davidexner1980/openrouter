package com.david.openassistant.agent

/**
 * Rehydrates runtime-owned research-tool proof from durable evidence.
 *
 * Provider text is deliberately ignored: only the final appendix written by
 * [durableEvidenceContent] is parsed. This lets a checkpoint retry reuse real
 * local or provider searches, exact fetches, and conservatively qualified
 * provider extracts without pretending that it performed them a second time.
 */
internal fun recoverResearchToolAudit(
    evidence: Iterable<AgentEvidence>,
    taskId: String? = null,
): List<AgentToolExecution> = evidence
    .asSequence()
    .filter { taskId == null || it.taskId == taskId }
    .flatMap { item ->
        val runtimeAudit = item.content.substringAfterLast(
            delimiter = RESEARCH_TOOL_AUDIT_HEADER,
            missingDelimiterValue = "",
        )
        RESEARCH_TOOL_AUDIT_LINE.findAll(runtimeAudit).map { match ->
            val execution = AgentToolExecution(
                toolName = match.groupValues[2],
                summary = match.groupValues[3].trim().ifBlank {
                    "Recovered from the runtime-owned durable tool audit."
                },
                succeeded = match.groupValues[1] == "PASS",
            )
            // Use a wrapper to preserve the task ID for de-duplication
            execution to item.taskId
        }
    }
    .filter { it.first.toolName in RESEARCH_AUDIT_TOOL_NAMES }
    .distinctBy { (execution, evidenceTaskId) ->
        Triple(execution.toolName, execution.summary, evidenceTaskId)
    }
    .map { it.first }
    .toList()

private const val RESEARCH_TOOL_AUDIT_HEADER = "Autonomous local tool activity:"

private val RESEARCH_TOOL_AUDIT_LINE = Regex(
    pattern = "(?m)^- \\[(PASS|ERROR)]\\s+([A-Za-z0-9_.-]+):[ \\t]*(.*)$",
)
