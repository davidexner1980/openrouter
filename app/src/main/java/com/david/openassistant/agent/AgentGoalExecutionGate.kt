package com.david.openassistant.agent

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes all Worker instances that target the same durable goal.
 *
 * WorkManager normally serializes a unique chain, but replacement, retry, and
 * cancellation races can briefly leave an old Worker alive while its successor
 * starts. Provider cancellation is cooperative, so that overlap is enough for
 * two Workers to mutate the same task or account the same result. This
 * process-wide gate is the final in-process fence around goal execution.
 */
internal object AgentGoalExecutionGate {
    private data class Entry(
        val mutex: Mutex = Mutex(),
        var references: Int = 1,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    suspend fun <T> withGoalLock(goalId: String, block: suspend () -> T): T {
        require(goalId.isNotBlank()) { "A goal execution lock requires a goal ID." }
        val entry = entries.compute(goalId) { _, current ->
            current?.also { it.references += 1 } ?: Entry()
        } ?: error("Could not register the goal execution lock.")

        try {
            return entry.mutex.withLock { block() }
        } finally {
            entries.computeIfPresent(goalId) { _, current ->
                if (current !== entry) {
                    current
                } else {
                    current.references -= 1
                    current.takeIf { it.references > 0 }
                }
            }
        }
    }

    /**
     * Waits for any active worker lease to be released for the given goal.
     */
    suspend fun waitGoalSettlement(goalId: String, timeoutMs: Long = 5000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val entry = entries[goalId]
            if (entry == null || !entry.mutex.isLocked) break
            kotlinx.coroutines.delay(100)
        }
    }
}
