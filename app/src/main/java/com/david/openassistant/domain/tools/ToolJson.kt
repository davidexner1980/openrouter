package com.david.openassistant.domain.tools

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Normalizes provider-generated function arguments at the single tool boundary.
 * Some models emit [] for a no-argument call or wrap one argument object in an
 * array. Both are recoverable without pretending that a multi-item array is an
 * object.
 */
internal fun parseToolArguments(raw: String): JSONObject {
    if (raw.isBlank()) return JSONObject()
    val value = try {
        val tokener = JSONTokener(raw)
        val parsed = tokener.nextValue()
        require(tokener.nextClean() == '\u0000') { "unexpected trailing content" }
        parsed
    } catch (_: Throwable) {
        throw ToolValidationException("The model supplied malformed JSON arguments for a local tool.")
    }
    return when (value) {
        is JSONObject -> value
        is JSONArray -> when {
            value.length() == 0 -> JSONObject()
            value.length() == 1 && value.opt(0) is JSONObject -> value.optJSONObject(0)
            else -> null
        } ?: throw ToolValidationException(
            "Local tool arguments must be one JSON object; a multi-item or non-object array cannot be executed.",
        )
        else -> throw ToolValidationException("Local tool arguments must be a JSON object.")
    }
}
