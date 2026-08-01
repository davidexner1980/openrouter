package com.david.openassistant.agent

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit

sealed interface SchedulingResult {
    data class NewlyEnqueued(val workId: UUID, val state: WorkInfo.State) : SchedulingResult
    data class ReusedActive(val workId: UUID, val state: WorkInfo.State) : SchedulingResult
    data class CoalescedDuplicate(val reason: String) : SchedulingResult
    data class ExistingSucceeded(val workId: UUID) : SchedulingResult
    data class ExistingFailed(val workId: UUID) : SchedulingResult
    data class ExistingCancelled(val workId: UUID) : SchedulingResult
    data class QueryFailed(val error: Throwable) : SchedulingResult
    data class WorkInfoMissing(val requestedWorkId: UUID) : SchedulingResult
    data class EnqueueFailed(val error: Throwable) : SchedulingResult
}

class AgentScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val diagnostics = com.david.openassistant.data.diagnostics.RuntimeDiagnostics(context.applicationContext)

    fun enqueue(goalId: String, replace: Boolean = false, generation: Int = 0) {
        val request = createRequest(goalId)
        workManager.enqueueUniqueWork(
            uniqueWorkName(goalId, generation),
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Enqueues unique work with KEEP and inspects WorkInfo readback to classify results without fake UUIDs.
     * Implements pre-enqueue coalescing by inspecting current WorkManager state and active lease.
     */
    fun enqueueAndWait(
        goalId: String,
        replace: Boolean = false,
        generation: Int = 0,
        activeLease: AgentExecutionLease? = null
    ): SchedulingResult {
        diagnostics.info(
            event = "mission_schedule_requested",
            component = "scheduler",
            fields = mapOf("goal_id" to goalId, "generation" to generation, "replace" to replace)
        )
        // 1. Pre-enqueue lease check
        val now = System.currentTimeMillis()
        if (activeLease != null && !AgentLeasePolicy.isStale(activeLease, now)) {
            return SchedulingResult.CoalescedDuplicate("Goal has a valid active lease for worker ${activeLease.workerId}")
        }

        // 2. Pre-enqueue WorkManager state check
        val existingWork = runCatching {
            workManager.getWorkInfosForUniqueWork(uniqueWorkName(goalId, generation)).get(3, TimeUnit.SECONDS)
        }.getOrNull()?.firstOrNull { info ->
            info.state == WorkInfo.State.ENQUEUED ||
                info.state == WorkInfo.State.RUNNING ||
                info.state == WorkInfo.State.BLOCKED
        }

        if (existingWork != null && !replace) {
            return SchedulingResult.ReusedActive(existingWork.id, existingWork.state)
        }

        val request = createRequest(goalId)

        val enqueueResult = runCatching {
            val operation = workManager.enqueueUniqueWork(
                uniqueWorkName(goalId, generation),
                if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                request,
            )
            operation.result.get(OPERATION_WAIT_SECONDS, TimeUnit.SECONDS)
        }

        if (enqueueResult.isFailure) {
            val err = enqueueResult.exceptionOrNull() ?: IllegalStateException("WorkManager enqueue operation failed")
            return SchedulingResult.EnqueueFailed(err)
        }

        val workInfosResult = runCatching {
            workManager.getWorkInfosForUniqueWork(uniqueWorkName(goalId, generation)).get(3, TimeUnit.SECONDS)
        }

        if (workInfosResult.isFailure) {
            val err = workInfosResult.exceptionOrNull() ?: IllegalStateException("Failed to query WorkInfo from WorkManager")
            return SchedulingResult.QueryFailed(err)
        }

        val infos = workInfosResult.getOrNull().orEmpty()
        val exactMatch = infos.firstOrNull { it.id == request.id }
        val activeWork = infos.firstOrNull { info ->
            info.state == WorkInfo.State.ENQUEUED ||
                info.state == WorkInfo.State.RUNNING ||
                info.state == WorkInfo.State.BLOCKED
        }

        val finalResult = when {
            exactMatch != null && (exactMatch.state == WorkInfo.State.ENQUEUED || exactMatch.state == WorkInfo.State.RUNNING || exactMatch.state == WorkInfo.State.BLOCKED) -> {
                SchedulingResult.NewlyEnqueued(exactMatch.id, exactMatch.state)
            }
            activeWork != null -> {
                SchedulingResult.ReusedActive(activeWork.id, activeWork.state)
            }
            exactMatch != null -> {
                when (exactMatch.state) {
                    WorkInfo.State.SUCCEEDED -> SchedulingResult.ExistingSucceeded(exactMatch.id)
                    WorkInfo.State.FAILED -> SchedulingResult.ExistingFailed(exactMatch.id)
                    WorkInfo.State.CANCELLED -> SchedulingResult.ExistingCancelled(exactMatch.id)
                    else -> SchedulingResult.WorkInfoMissing(request.id)
                }
            }
            infos.isNotEmpty() -> {
                val lastWork = infos.first()
                when (lastWork.state) {
                    WorkInfo.State.SUCCEEDED -> SchedulingResult.ExistingSucceeded(lastWork.id)
                    WorkInfo.State.FAILED -> SchedulingResult.ExistingFailed(lastWork.id)
                    WorkInfo.State.CANCELLED -> SchedulingResult.ExistingCancelled(lastWork.id)
                    else -> SchedulingResult.WorkInfoMissing(request.id)
                }
            }
            else -> SchedulingResult.WorkInfoMissing(request.id)
        }

        diagnostics.info(
            event = "mission_schedule_result",
            component = "scheduler",
            fields = mapOf(
                "goal_id" to goalId,
                "result" to finalResult.javaClass.simpleName,
                "work_id" to request.id.toString()
            )
        )
        return finalResult
    }

    fun enqueueContinuation(goalId: String, generation: Int = 0) {
        workManager.enqueueUniqueWork(
            uniqueWorkName(goalId, generation),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            createRequest(goalId),
        )
    }

    fun cancel(goalId: String, generation: Int = 0) {
        AgentCallCancellationRegistry.cancel(goalId)
        workManager.cancelUniqueWork(uniqueWorkName(goalId, generation))
    }

    fun cancelAndWait(goalId: String, generation: Int = 0) {
        AgentCallCancellationRegistry.cancel(goalId)
        try {
            workManager.cancelUniqueWork(uniqueWorkName(goalId, generation))
                .result
                .get(CANCELLATION_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (error: Throwable) {
            android.util.Log.w("AgentScheduler", "WorkManager cancellation wait failed for $goalId", error)
            if (error is InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    fun cancelAllForGoal(goalId: String) {
        AgentCallCancellationRegistry.cancel(goalId)
        workManager.cancelAllWorkByTag(goalTag(goalId))
    }

    fun isWorkRunning(goalId: String, generation: Int = 0): Boolean {
        return try {
            val infos = workManager.getWorkInfosForUniqueWork(uniqueWorkName(goalId, generation)).get()
            infos.any { it.state == androidx.work.WorkInfo.State.RUNNING }
        } catch (e: Exception) {
            false
        }
    }

    fun schedulePeriodicRecovery() {
        val request = PeriodicWorkRequestBuilder<MissionRecoveryWorker>(30, TimeUnit.MINUTES)
            .addTag("mission_recovery_watchdog")
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        
        workManager.enqueueUniquePeriodicWork(
            "mission_recovery_watchdog",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun createRequest(goalId: String): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<AgentGoalWorker>()
            .setInputData(Data.Builder().putString(AgentGoalWorker.KEY_GOAL_ID, goalId).build())
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 20, TimeUnit.SECONDS)
            .addTag(goalTag(goalId))
            .build()

    companion object {
        private const val CANCELLATION_WAIT_SECONDS = 20L
        private const val OPERATION_WAIT_SECONDS = 10L
        fun uniqueWorkName(goalId: String, generation: Int = 0): String {
            return if (generation > 0) {
                "openassistant_agent_goal_${goalId}_gen$generation"
            } else {
                "openassistant_agent_goal_$goalId"
            }
        }
        fun goalTag(goalId: String) = "openassistant_agent_tag_$goalId"
    }
}
