package com.david.openassistant.data.local

internal const val DEFAULT_CONVERSATION_TITLE = "New conversation"

internal fun createConversationTitle(message: String): String {
    val normalized = message.replace(Regex("\\s+"), " ").trim()
    if (normalized.isBlank()) return DEFAULT_CONVERSATION_TITLE
    return if (normalized.length <= 48) {
        normalized
    } else {
        normalized.take(45).trimEnd() + "..."
    }
}
