package com.david.openassistant.agent

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Connects an explicit goal cancellation to the provider and tool calls owned
 * by the currently running Worker. WorkManager's CoroutineWorker stop hook is
 * final, so cancellation must be signalled before cancelling the work chain.
 */
internal object AgentCallCancellationRegistry {
    private val callbacks =
        ConcurrentHashMap<String, ConcurrentHashMap<String, () -> Unit>>()

    fun register(goalId: String, callback: () -> Unit): AutoCloseable {
        val registrationId = UUID.randomUUID().toString()
        val goalCallbacks = callbacks.computeIfAbsent(goalId) { ConcurrentHashMap() }
        goalCallbacks[registrationId] = callback

        return AutoCloseable {
            goalCallbacks.remove(registrationId)
            if (goalCallbacks.isEmpty()) {
                callbacks.remove(goalId, goalCallbacks)
            }
        }
    }

    fun cancel(goalId: String) {
        callbacks.remove(goalId)
            ?.values
            ?.toList()
            .orEmpty()
            .forEach { callback -> runCatching(callback) }
    }
}
