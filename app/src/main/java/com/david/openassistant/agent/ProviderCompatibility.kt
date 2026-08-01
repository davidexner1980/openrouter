package com.david.openassistant.agent

import org.json.JSONObject
import java.util.Locale

/** Provider errors that mean the request should be retried without strict JSON Schema. */
internal fun isStructuredOutputCompatibilityError(statusCode: Int?, message: String): Boolean {
    if (statusCode !in setOf(400, 404, 422)) return false
    val normalized = message.lowercase(Locale.US)
    return STRUCTURED_OUTPUT_ERROR_MARKERS.any(normalized::contains)
}

/**
 * A per-call ceiling prevents a routed model from spending tens of thousands
 * of tokens repeating prose or tool intentions. This is not a mission budget:
 * the durable worker can continue from its checkpoint in another call.
 */
internal fun applyAgentCompletionLimit(
    payload: JSONObject,
    maximumTokens: Int = MAX_AGENT_COMPLETION_TOKENS,
): JSONObject {
    require(maximumTokens > 0)
    payload.put("max_tokens", maximumTokens)
    return payload
}

internal const val MAX_AGENT_COMPLETION_TOKENS = 8_192

private val STRUCTURED_OUTPUT_ERROR_MARKERS = listOf(
    "response_format",
    "response format",
    "json_schema",
    "json schema",
    "structured output",
    "structured outputs",
    "strict schema",
    "specified schema",
    "schema produces a constraint",
    "too many states",
)
