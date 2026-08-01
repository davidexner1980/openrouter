package com.david.openassistant.agent

import java.util.Locale

/**
 * Internal safety classification and response filtering.
 * Prevents leaked internal metadata (e.g., "User Safety: safe") from reaching the UI.
 */
enum class SafetyResult {
    ALLOWED,
    BLOCKED,
}

object SafetyClassifier {
    private val INTERNAL_METADATA_MARKERS = listOf(
        "User Safety:",
        "Safety classification:",
        "policy result",
        "moderation result",
        "classifier JSON",
        "routing diagnostics",
        "internal reasoning:",
        "thought trace",
        "System Policy:",
        "Safety Check:",
    )

    /**
     * Classifies a user request before dispatching to a provider.
     */
    fun classifyRequest(request: String): SafetyResult {
        // For Slice 1, we assume all requests are allowed unless they violate 
        // basic local patterns. A real implementation would use a moderation API.
        return SafetyResult.ALLOWED
    }

    /**
     * Filters assistant response text to strip leaked internal markers.
     */
    fun filterResponse(content: String): String {
        var filtered = content
        INTERNAL_METADATA_MARKERS.forEach { marker ->
            val regex = Regex("(?im)^.*$marker.*$", RegexOption.MULTILINE)
            filtered = filtered.replace(regex, "").trim()
        }
        return filtered
    }

    /**
     * Returns true if the content contains internal safety metadata.
     */
    fun isInternalMetadata(content: String): Boolean {
        val normalized = content.lowercase(Locale.US)
        return INTERNAL_METADATA_MARKERS.any { marker ->
            normalized.contains(marker.lowercase(Locale.US))
        }
    }
}
