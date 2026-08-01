package com.david.openassistant.agent

import java.util.Locale

/**
 * Converts low-level provider and org.json failures into stable mission-level
 * messages. Diagnostics still receive the original Throwable; persisted state
 * and the Work screen receive an actionable explanation instead of Android
 * implementation details.
 */
internal fun Throwable.toAgentFailureMessage(fallback: String): String =
    normalizeAgentFailureMessage(message, fallback)

internal fun normalizeAgentFailureMessage(
    raw: String?,
    fallback: String = "The autonomous operation did not complete.",
): String {
    val message = raw.orEmpty().trim().ifBlank { fallback }
    val scrubbedMessage = LEGACY_JSON_ARRAY_CAST_PATTERN.replace(
        message,
        "The provider returned an incompatible JSON response shape",
    )
    val normalized = scrubbedMessage.lowercase(Locale.US)
    return when {
        scrubbedMessage != message -> if (scrubbedMessage.length > JSON_SHAPE_REPLACEMENT.length) {
            scrubbedMessage.take(MAX_PERSISTED_ERROR_CHARS)
        } else {
            "$JSON_SHAPE_REPLACEMENT. Work was preserved and automatic route recovery will continue."
        }

        JSON_SHAPE_ERROR_MARKERS.any(normalized::contains) ->
            "The provider returned an incompatible JSON response shape. Work was preserved and automatic route recovery will continue."

        REQUEST_STALL_ERROR_MARKERS.any(normalized::contains) ->
            "The provider request timed out before a complete response arrived. Work was preserved and will retry automatically."

        NETWORK_RESOLUTION_ERROR_MARKERS.any(normalized::contains) ->
            "Network name resolution is temporarily unavailable. Work was preserved and will retry automatically when connectivity recovers."

        GENERIC_PROVIDER_ERROR_MARKERS.any { normalized.trimEnd('.') == it } ->
            "The selected provider failed without a usable error response. Work was preserved; the next retry will use a reduced compatibility request when repeated failures require it."

        else -> scrubbedMessage.take(MAX_PERSISTED_ERROR_CHARS)
    }
}

internal fun String?.isLegacyMissionBudgetStop(): Boolean {
    val normalized = this.orEmpty().lowercase(Locale.US)
    return LEGACY_BUDGET_ERROR_MARKERS.any(normalized::contains)
}

private const val MAX_PERSISTED_ERROR_CHARS = 2_000
private const val JSON_SHAPE_REPLACEMENT = "The provider returned an incompatible JSON response shape"

private val LEGACY_JSON_ARRAY_CAST_PATTERN = Regex(
    "Value \\[.*?of type org\\.json\\.JSONArray cannot be converted to JSONObject",
    RegexOption.IGNORE_CASE,
)

private val JSON_SHAPE_ERROR_MARKERS = listOf(
    "cannot be converted to jsonobject",
    "top-level json array",
    "object envelope was required",
    "did not contain a valid json object",
    "value [] of type org.json.jsonarray",
)

private val NETWORK_RESOLUTION_ERROR_MARKERS = listOf(
    "unable to resolve host",
    "no address associated with hostname",
    "name or service not known",
    "temporary failure in name resolution",
)

private val REQUEST_STALL_ERROR_MARKERS = listOf(
    "timeout",
    "timed out",
    "deadline exceeded",
    "deadline reached",
)

private val GENERIC_PROVIDER_ERROR_MARKERS = setOf(
    "provider returned error",
    "the provider returned an error",
    "openrouter returned an error",
    "upstream provider error",
)

private val LEGACY_BUDGET_ERROR_MARKERS = listOf(
    "goal reached its token budget",
    "reached its token budget",
    "token budget of",
    "reached its cost budget",
    "mission cost budget",
    "maximum mission tokens",
)
