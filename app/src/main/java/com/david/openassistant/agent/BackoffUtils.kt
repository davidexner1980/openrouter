package com.david.openassistant.agent

import java.util.Random
import kotlin.math.min
import kotlin.math.pow

object BackoffUtils {
    private val random = Random()

    /**
     * Computes exponential backoff with jitter.
     * @param attempt 1-based attempt count
     * @param baseDelayMs initial delay
     * @param maxDelayMs maximum allowed delay
     */
    fun computeBackoff(attempt: Int, baseDelayMs: Long = 2000L, maxDelayMs: Long = 60000L): Long {
        if (attempt <= 0) return 0
        val exp = 2.0.pow(attempt - 1).toLong()
        val delay = min(maxDelayMs, baseDelayMs * exp)
        val jitter = if (delay > 0) random.nextInt((delay * 0.2).toInt().coerceAtLeast(1)) else 0
        return delay + jitter
    }
}
