package com.david.openassistant.agent

import com.david.openassistant.domain.tools.OpenRouterToolCall
import org.json.JSONObject
import java.util.Locale

/**
 * Resolves only known safe provider-native web aliases. Unknown tool names are
 * left untouched so the normal registry rejects them rather than guessing.
 */
internal fun canonicalizeProviderToolCall(call: OpenRouterToolCall): OpenRouterToolCall {
    val canonicalName = when (call.name.lowercase(Locale.US)) {
        "search", "web_search", "search_web", "browser.search" -> "public_web_search"
        "fetch", "web_fetch", "fetch_url", "open_url", "browser.open" -> "public_web_fetch"
        // Some OpenRouter routes expose provider-native web work as a generic
        // `run` function. Resolve it only when its arguments unambiguously
        // match one of our guarded public-web tools.
        "run" -> canonicalRunAlias(call.argumentsJson) ?: call.name
        else -> call.name
    }
    return if (canonicalName == call.name) call else call.copy(name = canonicalName)
}

private fun canonicalRunAlias(argumentsJson: String): String? {
    val arguments = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return null
    val query = arguments.optString("query").trim()
    val url = arguments.optString("url").trim()
    return when {
        query.isNotBlank() && url.isBlank() -> "public_web_search"
        url.isNotBlank() && query.isBlank() -> "public_web_fetch"
        else -> null
    }
}
