package com.david.openassistant.data.openrouter

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Parses an OpenRouter response without assuming that JSONTokener returned an
 * object. A few compatible providers wrap an object in a one-item array; that
 * shape is safe to unwrap. Every other array is rejected with a stable,
 * actionable provider error instead of leaking Android's JSONObject cast
 * exception into the UI.
 */
internal fun requireOpenRouterObject(raw: String, context: String): JSONObject {
    val value = try {
        val tokener = JSONTokener(raw)
        val parsed = tokener.nextValue()
        require(tokener.nextClean() == '\u0000') { "unexpected trailing content" }
        parsed
    } catch (error: Throwable) {
        throw OpenRouterException(
            null,
            "$context returned malformed JSON: ${error.message.orEmpty().ifBlank { "unknown parse error" }}",
        )
    }
    return when (value) {
        is JSONObject -> value
        is JSONArray -> value.optJSONObject(0)
            ?.takeIf { value.length() == 1 }
            ?: throw OpenRouterException(
                null,
                "$context returned a top-level JSON array with ${value.length()} item(s); an object envelope was required.",
            )
        else -> throw OpenRouterException(
            null,
            "$context returned ${value?.javaClass?.simpleName ?: "null"}; an object envelope was required.",
        )
    }
}

/**
 * `/api/v1/key` is documented to return `data` as an object. If a proxy or
 * provider temporarily returns an empty array, keep the credential connected
 * with conservative free-tier routing rather than crashing startup.
 */
internal fun parseOpenRouterKeyInfo(body: String): OpenRouterKeyInfo {
    val root = requireOpenRouterObject(body, "OpenRouter key metadata")
    val data = when (val value = root.opt("data")) {
        is JSONObject -> value
        is JSONArray -> if (value.length() == 1) value.optJSONObject(0) else null
        else -> null
    } ?: return OpenRouterKeyInfo(
        label = "Connected key (metadata unavailable)",
        isFreeTier = true,
        usage = null,
        limit = null,
        limitRemaining = null,
        expiresAt = null,
    )
    return OpenRouterKeyInfo(
        label = data.optString("label", "Connected key"),
        isFreeTier = data.optBoolean("is_free_tier", false),
        usage = data.optDoubleOrNull("usage"),
        limit = data.optDoubleOrNull("limit"),
        limitRemaining = data.optDoubleOrNull("limit_remaining"),
        expiresAt = data.optString("expires_at").takeIf { it.isNotBlank() && it != "null" },
    )
}

private fun JSONObject.optDoubleOrNull(name: String): Double? =
    if (has(name) && !isNull(name)) optDouble(name) else null
