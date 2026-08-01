package com.david.openassistant.agent

import com.david.openassistant.data.openrouter.OpenRouterException
import com.david.openassistant.data.openrouter.requireOpenRouterObject
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Parses provider responses without assuming the top-level JSON shape.
 * OpenRouter-compatible providers occasionally return arrays or array-based
 * content blocks even when an object response was requested.
 */
internal object JsonEnvelopeParser {
    fun requireObject(raw: String, context: String): JSONObject {
        return runCatching { requireOpenRouterObject(raw, context) }
            .getOrElse { error ->
                // If the strict parse fails, attempt to recover a brace-delimited
                // object from the text. This handles leading/trailing whitespace,
                // markdown fences, and multiple SSE fragments safely.
                //
                // We perform the scan logic directly here to avoid mutual recursion.
                recoverEmbeddedObject(raw) ?: throw error
            }
    }

    /**
     * Recovers an object from provider text that may contain markdown fences,
     * prose, or more than one brace-delimited fragment. The final valid object
     * wins because reasoning models commonly emit an example before their
     * actual answer. Braces inside JSON strings are ignored while scanning.
     */
    fun requireEmbeddedObject(raw: String, context: String): JSONObject {
        return runCatching { requireOpenRouterObject(raw, context) }.getOrNull()
            ?: recoverEmbeddedObject(raw)
            ?: throw OpenRouterException(null, "$context did not contain a valid JSON object.")
    }

    private fun recoverEmbeddedObject(raw: String): JSONObject? {
        val candidates = mutableListOf<String>()
        var start = -1
        var depth = 0
        var inString = false
        var escaped = false

        raw.forEachIndexed { index, character ->
            if (start < 0) {
                if (character == '{') {
                    start = index
                    depth = 1
                    inString = false
                    escaped = false
                }
                return@forEachIndexed
            }

            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                return@forEachIndexed
            }

            when (character) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        candidates += raw.substring(start, index + 1)
                        start = -1
                    }
                }
            }
        }

        for (candidate in candidates.asReversed()) {
            val parsed = runCatching { JSONTokener(candidate).nextValue() }.getOrNull()
            if (parsed is JSONObject) return parsed
        }
        return null
    }

    fun messageText(message: JSONObject): String? {
        val content = message.opt("content")
        return when (content) {
            is String -> content.takeIf { it.isNotBlank() && it != "null" }
            is JSONArray -> buildString {
                for (index in 0 until content.length()) {
                    when (val block = content.opt(index)) {
                        is String -> append(block)
                        is JSONObject -> {
                            val text = block.optString("text")
                                .ifBlank { block.optString("content") }
                                .ifBlank { block.optJSONObject("text")?.optString("value").orEmpty() }
                            append(text)
                        }
                    }
                }
            }.trim().takeIf { it.isNotBlank() }
            is JSONObject -> content.optString("text")
                .ifBlank { content.optString("value") }
                .takeIf { it.isNotBlank() && it != "null" }
            else -> null
        }
    }
}
