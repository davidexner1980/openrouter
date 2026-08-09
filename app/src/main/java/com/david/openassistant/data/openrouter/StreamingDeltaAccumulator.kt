package com.david.openassistant.data.openrouter

/**
 * Thread-safe buffer used to coalesce many small SSE text deltas into fewer
 * UI state updates. OpenRouter callbacks may arrive on an OkHttp thread while
 * Compose observes state on the main thread.
 */
class StreamingDeltaAccumulator {
    private val lock = Any()
    private val buffer = StringBuilder()

    fun append(text: String) {
        if (text.isEmpty()) return
        synchronized(lock) {
            buffer.append(text)
        }
    }

    fun drain(): String = synchronized(lock) {
        if (buffer.isEmpty()) return@synchronized ""
        buffer.toString().also { buffer.setLength(0) }
    }

    fun content(): String = synchronized(lock) { buffer.toString() }

    fun isEmpty(): Boolean = synchronized(lock) { buffer.isEmpty() }

    fun clear() {
        synchronized(lock) {
            buffer.setLength(0)
        }
    }
}
