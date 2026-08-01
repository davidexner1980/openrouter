package com.david.openassistant.agent

import org.json.JSONArray
import org.json.JSONObject

/**
 * Converts real completed tool work into an explicitly incomplete durable
 * checkpoint when a provider fails to return the requested structured prose.
 * It never manufactures claims or upgrades the milestone's quality score.
 */
internal fun buildIncompleteToolCheckpointJson(
    reason: String,
    executions: List<AgentToolExecution>,
    distinctSourceCount: Int,
): String? {
    val successfulExecutions = executions.count {
        it.succeeded && !it.toolName.startsWith("cached_")
    }
    if (successfulExecutions == 0) return null
    return JSONObject()
        .put(
            "work_product",
            buildString {
                append(reason)
                append(" Preserved ")
                append(successfulExecutions)
                append(" successful runtime tool execution(s) and ")
                append(distinctSourceCount.coerceAtLeast(0))
                append(" distinct source URL(s). This is durable research evidence, not a completed answer.")
            },
        )
        .put("completion_score", 0.0)
        .put("acceptance_checks", JSONArray())
        .put("claims", JSONArray())
        .put(
            "unresolved_questions",
            JSONArray().put("A compatible completion pass must analyze and synthesize the preserved evidence before this milestone can pass."),
        )
        .toString()
}
