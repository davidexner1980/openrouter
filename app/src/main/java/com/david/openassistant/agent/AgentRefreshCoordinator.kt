package com.david.openassistant.agent

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

// Force re-eval
data class RefreshMetrics(
    val requested: Int,
    val executed: Int,
    val coalesced: Int,
    val skipped: Int,
    val activeWorkers: Int,
    val lastRequestedRevision: Long,
    val lastProcessedRevision: Long,
    val uiEmissions: Int,
    val failures: Int,
    val retryAttempts: Int,
    val retryExhaustions: Int,
    val stableReadRetries: Int,
    val stableReadFailures: Int,
    val workerRunning: Boolean,
    val pendingLatestRevision: Long,
)

class AgentRefreshCoordinator(
    private val refreshSource: AgentRefreshSource,
    private val toolCountSource: ToolCountSource,
    private val diagnostics: RefreshDiagnostics,
    private val stateApplier: RefreshStateApplier,
    private val deliverPendingResults: suspend (AgentSnapshot) -> Boolean,
    private val onWorkerLoopExit: suspend () -> Unit = {}
) {
    private val requestedCount = AtomicInteger(0)
    private val executedCount = AtomicInteger(0)
    private val coalescedCount = AtomicInteger(0)
    private val skippedCount = AtomicInteger(0)
    private val uiEmissionsCount = AtomicInteger(0)
    private val activeWorkersCount = AtomicInteger(0)
    private val failureCount = AtomicInteger(0)
    private val retryAttemptCount = AtomicInteger(0)
    private val retryExhaustionCount = AtomicInteger(0)
    private val stableReadRetryCount = AtomicInteger(0)
    private val stableReadFailureCount = AtomicInteger(0)
    
    // Authoritative state guarded by mutex
    private var activeOwner: WorkerOwner? = null
    private var pendingLatestRevision = -1L
    private var lastProcessedRevision = -1L
    
    private val lastRequestedRevision = AtomicLong(-1)
    
    private val lastEmittedSnapshot = java.util.concurrent.atomic.AtomicReference<AgentSnapshot?>(null)
    private val lastRecipeCount = AtomicInteger(-1)
    private val lastWorkspaceCount = AtomicInteger(-1)

    private val mutex = Mutex()
    private val metricsSnapshot = java.util.concurrent.atomic.AtomicReference<RefreshMetrics>(createInitialMetrics())

    private data class WorkerOwner(val identity: String, val job: Job)

    private companion object {
        const val MAX_RETRIES = 3
        const val INITIAL_BACKOFF_MS = 200L
    }

    private fun createInitialMetrics() = RefreshMetrics(
        requested = 0, executed = 0, coalesced = 0, skipped = 0,
        activeWorkers = 0, lastRequestedRevision = -1, lastProcessedRevision = -1,
        uiEmissions = 0, failures = 0, retryAttempts = 0, retryExhaustions = 0,
        stableReadRetries = 0, stableReadFailures = 0, workerRunning = false,
        pendingLatestRevision = -1
    )

    private fun updateMetricsSnapshot() {
        metricsSnapshot.set(RefreshMetrics(
            requested = requestedCount.get(),
            executed = executedCount.get(),
            coalesced = coalescedCount.get(),
            skipped = skippedCount.get(),
            activeWorkers = activeWorkersCount.get(),
            lastRequestedRevision = lastRequestedRevision.get(),
            lastProcessedRevision = lastProcessedRevision,
            uiEmissions = uiEmissionsCount.get(),
            failures = failureCount.get(),
            retryAttempts = retryAttemptCount.get(),
            retryExhaustions = retryExhaustionCount.get(),
            stableReadRetries = stableReadRetryCount.get(),
            stableReadFailures = stableReadFailureCount.get(),
            workerRunning = activeOwner != null,
            pendingLatestRevision = pendingLatestRevision
        ))
    }

    fun metrics(): RefreshMetrics = metricsSnapshot.get()

    fun refresh(scope: CoroutineScope, requestedRevision: Long = -1) {
        val currentRequested = if (requestedRevision == -1L) {
            refreshSource.getLatestRevision()
        } else {
            requestedRevision
        }
        
        lastRequestedRevision.getAndUpdate { current -> max(current, currentRequested) }

        scope.launch {
            mutex.withLock {
                requestedCount.incrementAndGet()
                pendingLatestRevision = max(pendingLatestRevision, currentRequested)
                
                if (activeOwner != null) {
                    coalescedCount.incrementAndGet()
                    updateMetricsSnapshot()
                    return@withLock
                }
                
                val identity = java.util.UUID.randomUUID().toString()
                val job = launch {
                    try {
                        runWorker(identity)
                    } finally {
                        withContext(NonCancellable) {
                            mutex.withLock {
                                if (activeOwner?.identity == identity) {
                                    activeOwner = null
                                    updateMetricsSnapshot()
                                }
                            }
                        }
                    }
                }
                activeOwner = WorkerOwner(identity, job)
                updateMetricsSnapshot()
            }
        }
    }

    private suspend fun runWorker(identity: String) {
        activeWorkersCount.incrementAndGet()
        mutex.withLock { updateMetricsSnapshot() }
        try {
            while (true) {
                yield()
                
                val targetRevision = mutex.withLock { pendingLatestRevision }
                val processed = mutex.withLock { lastProcessedRevision }
                
                if (targetRevision <= processed && processed != -1L) {
                    skippedCount.incrementAndGet()
                } else {
                    executedCount.incrementAndGet()
                    diagnostics.info("refresh_executing", mapOf("revision" to targetRevision, "worker" to identity))

                    val result = performRefreshWithRetry(targetRevision)

                    when (result) {
                        is RefreshApplyResult.Success -> {
                            // lastProcessedRevision is updated inside commitRefresh under mutex
                        }
                        is RefreshApplyResult.Failure -> {
                            failureCount.incrementAndGet()
                            when (result.failure) {
                                is RefreshFailure.PermanentConfigurationFailure,
                                is RefreshFailure.PersistenceFailure -> {
                                    mutex.withLock {
                                        if (activeOwner?.identity == identity) {
                                            activeOwner = null
                                        }
                                        updateMetricsSnapshot()
                                    }
                                    return
                                }
                                is RefreshFailure.Cancelled -> {
                                    return
                                }
                                else -> {
                                    retryExhaustionCount.incrementAndGet()
                                    diagnostics.error("refresh_exhausted", Throwable("Exhausted retries for target revision $targetRevision"))
                                    mutex.withLock {
                                        if (activeOwner?.identity == identity) {
                                            activeOwner = null
                                        }
                                        updateMetricsSnapshot()
                                    }
                                    return
                                }
                            }
                        }
                    }
                }

                val shouldExit = mutex.withLock {
                    if (pendingLatestRevision <= lastProcessedRevision) {
                        if (activeOwner?.identity == identity) {
                            activeOwner = null
                        }
                        updateMetricsSnapshot()
                        true
                    } else false
                }
                if (shouldExit) {
                    onWorkerLoopExit()
                    return
                }
            }
        } finally {
            activeWorkersCount.decrementAndGet()
            mutex.withLock { updateMetricsSnapshot() }
        }
    }

    private suspend fun performRefreshWithRetry(targetRevision: Long): RefreshApplyResult {
        var attempts = 0
        while (attempts <= MAX_RETRIES) {
            if (attempts > 0) {
                retryAttemptCount.incrementAndGet()
                delay(INITIAL_BACKOFF_MS * attempts)
            }
            
            val result = runCatching {
                val stable = loadStableSnapshotWithRetry(targetRevision)
                
                // Phase 1: Delivery
                val resultsDelivered = deliverPendingResults(stable.snapshot)
                
                // Phase 2: Final Load (if needed)
                val finalStable = if (resultsDelivered) {
                    loadStableSnapshotWithRetry(stable.revision)
                } else stable
                
                val toolCounts = toolCountSource.loadToolCounts()
                
                commitRefresh(finalStable, toolCounts)
            }

            result.onSuccess { return it }
            
            val error = result.exceptionOrNull()
            if (error is CancellationException) {
                // Propagate cancellation correctly
                throw error
            }
            
            val failure = when (error) {
                is java.io.IOException -> RefreshFailure.TransientIO(error)
                is RefreshFailureException -> result.exceptionOrNull()?.let { (it as RefreshFailureException).failure } ?: RefreshFailure.Unknown(error)
                else -> RefreshFailure.Unknown(error!!)
            }

            // For Permanent failures, return failure immediately
            if (error is IllegalStateException || error is IllegalArgumentException) {
                return RefreshApplyResult.Failure(failure)
            }
            
            attempts++
        }
        return RefreshApplyResult.Failure(RefreshFailure.Unknown(Throwable("Exhausted retries")))
    }

    private class RefreshFailureException(val failure: RefreshFailure) : Exception()

    private suspend fun loadStableSnapshotWithRetry(target: Long): AgentSnapshotWithRevision {
        var attempts = 0
        while (attempts < 3) {
            val stable = refreshSource.loadStableSnapshot()
            
            if (stable.revision >= target) {
                return stable
            }
            
            stableReadRetryCount.incrementAndGet()
            attempts++
            delay(50)
        }
        stableReadFailureCount.incrementAndGet()
        // Protocol 2.3: Return TRANSIENT_STABLE_READ_CHURN if exhausted
        throw RefreshFailureException(RefreshFailure.TransientStableReadChurn)
    }

    private suspend fun commitRefresh(
        stable: AgentSnapshotWithRevision,
        toolCounts: ToolCounts
    ): RefreshApplyResult {
        val snapshot = stable.snapshot
        val revision = stable.revision
        val recipeCount = toolCounts.activeRecipeCount
        val workspaceCount = toolCounts.workspaceFileCount

        val currentEmitted = lastEmittedSnapshot.get()
        val snapshotChanged = currentEmitted == null || snapshot != currentEmitted
        val recipesChanged = lastRecipeCount.get() == -1 || recipeCount != lastRecipeCount.get()
        val workspaceChanged = lastWorkspaceCount.get() == -1 || workspaceCount != lastWorkspaceCount.get()

        return if (snapshotChanged || recipesChanged || workspaceChanged) {
            val applyResult = stateApplier.apply(snapshot, recipeCount, workspaceCount)
            if (applyResult is RefreshApplyResult.Success) {
                mutex.withLock {
                    lastProcessedRevision = max(lastProcessedRevision, revision)
                    lastEmittedSnapshot.set(snapshot)
                    lastRecipeCount.set(recipeCount)
                    lastWorkspaceCount.set(workspaceCount)
                    uiEmissionsCount.incrementAndGet()
                    updateMetricsSnapshot()
                }
            }
            applyResult
        } else {
            mutex.withLock {
                lastProcessedRevision = max(lastProcessedRevision, revision)
                updateMetricsSnapshot()
            }
            RefreshApplyResult.Success
        }
    }
}
