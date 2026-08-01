package com.david.openassistant.agent

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Debug-only implementation of MissionStartBoundaryHook.
 */
class DebugMissionStartHook(private val context: Context) : MissionStartBoundaryHook {
    private val isPaused = AtomicBoolean(false)
    private var activeBoundary: String? = null

    @Volatile
    var enabledInMemory = false

    fun isEnabled(): Boolean {
        if (enabledInMemory) return true
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_HOOK_ENABLED, false)
    }

    override suspend fun onBoundaryReached(boundary: String, draft: ResearchDraft) {
        if (!isEnabled()) return

        // Reset to false (one-shot)
        enabledInMemory = false
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_HOOK_ENABLED, false)
        }

        activeBoundary = boundary
        isPaused.set(true)
        android.util.Log.w(
            "DebugMissionStartHook",
            "PAUSED at boundary '$boundary' for draft ${draft.id} (submissionId=${draft.id}, linkedGoalId=${draft.linkedGoalId}). Force-stop now to test recovery."
        )

        val timeoutAt = System.currentTimeMillis() + TIMEOUT_MS
        while (isPaused.get() && System.currentTimeMillis() < timeoutAt) {
            delay(100)
        }
        if (isPaused.get()) {
            android.util.Log.w("DebugMissionStartHook", "TIMED OUT waiting at boundary '$boundary'. Resuming execution.")
            isPaused.set(false)
        } else {
            android.util.Log.i("DebugMissionStartHook", "Resumed from boundary '$boundary'.")
        }
    }

    fun resume() {
        isPaused.set(false)
        activeBoundary = null
    }

    fun isPausedAt(boundary: String): Boolean {
        return isPaused.get() && activeBoundary == boundary
    }

    companion object {
        private const val PREFS_NAME = "openassistant_debug_config"
        private const val KEY_HOOK_ENABLED = "debug_mission_start_hook_enabled"
        private const val TIMEOUT_MS = 30_000L

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                putBoolean(KEY_HOOK_ENABLED, enabled)
            }
        }
    }
}
