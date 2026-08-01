package com.david.openassistant.data.openrouter

sealed interface SseEvent {
    data object Ignore : SseEvent
    data object Done : SseEvent
    data class Data(val payload: String) : SseEvent
}

object SseEventParser {
    fun parse(line: String): SseEvent {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(':')) return SseEvent.Ignore
        if (!trimmed.startsWith("data:")) return SseEvent.Ignore

        val payload = trimmed.removePrefix("data:").trim()
        if (payload == "[DONE]") return SseEvent.Done
        if (payload.isEmpty()) return SseEvent.Ignore
        return SseEvent.Data(payload)
    }
}

