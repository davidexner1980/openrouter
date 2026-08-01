package com.david.openassistant.agent

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * CoroutineWorker.onStopped is final in current WorkManager. The UI therefore
 * signals active provider and tool calls through this process-local registry
 * before waiting for WorkManager cancellation.
 */
internal object AgentCancellationRegistry {
    private data class Entry(val token: String, val cancel: () -> Unit)

    private val entries = ConcurrentHashMap<String, Entry>()

    fun register(goalId: String, cancel: () -> Unit): String {
        val token = UUID.randomUUID().toString()
        entries[goalId] = Entry(token, cancel)
        return token
    }

    fun cancel(goalId: String): Boolean {
        val entry = entries[goalId] ?: return false
        runCatching(entry.cancel)
        return true
    }

    fun unregister(goalId: String, token: String) {
        entries.computeIfPresent(goalId) { _, current ->
            current.takeUnless { it.token == token }
        }
    }
}
