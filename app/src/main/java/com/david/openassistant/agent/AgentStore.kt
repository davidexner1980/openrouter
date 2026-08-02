package com.david.openassistant.agent

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.core.util.AtomicFile
import com.david.openassistant.data.openrouter.requireOpenRouterObject
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Durable per-goal store.
 *
 * Version 1 stored every goal, attempt, evidence item, and event in one
 * SharedPreferences string. Version 2 migrates that snapshot once, then writes
 * each goal through AtomicFile so one large or damaged goal cannot overwrite
 * every other mission. SharedPreferences now carries only selection and a tiny
 * revision signal observed by the UI.
 */
sealed class CreateAttemptResult {
    object Created : CreateAttemptResult()
    object GoalMissing : CreateAttemptResult()
    object DuplicateExchange : CreateAttemptResult()
    data class InvalidGeneration(val expected: Int, val actual: Int) : CreateAttemptResult()
    object InvalidLeaseOrGoalState : CreateAttemptResult()
    data class StorageFailure(val cause: Throwable) : CreateAttemptResult()
    object UnauthorizedRetry : CreateAttemptResult()
}

sealed class TransitionOutcomeResult {
    data class Updated(val attempt: ProviderRequestAttempt) : TransitionOutcomeResult()
    object GoalMissing : TransitionOutcomeResult()
    object ExchangeMissing : TransitionOutcomeResult()
    data class AlreadyTerminal(val outcome: ExchangeOutcome) : TransitionOutcomeResult()
    data class InvalidGeneration(val expected: Int, val actual: Int) : TransitionOutcomeResult()
    object InvalidLeaseOrGoalState : TransitionOutcomeResult()
    data class StorageFailure(val cause: Throwable) : TransitionOutcomeResult()
}

sealed class RefreshLeaseResult {
    object Refreshed : RefreshLeaseResult()
    object GoalMissing : RefreshLeaseResult()
    object LeaseLost : RefreshLeaseResult()
    data class StorageFailure(val cause: Throwable) : RefreshLeaseResult()
}

sealed class LeaseAcquisitionResult {
    data class Acquired(val ticket: AgentOwnershipTicket, val goal: AgentGoal) : LeaseAcquisitionResult()
    object LiveOwnerPresent : LeaseAcquisitionResult()
    data class OrphanReclaimed(val ticket: AgentOwnershipTicket, val goal: AgentGoal) : LeaseAcquisitionResult()
    object RetryRequired : LeaseAcquisitionResult()
    object MissionTerminal : LeaseAcquisitionResult()
    data class Rejected(val reason: String) : LeaseAcquisitionResult()
    data class StorageFailure(val cause: Throwable) : LeaseAcquisitionResult()
}

class AgentStore private constructor(
    context: Context?,
    baseDir: File?,
    prefs: SharedPreferences?,
) : AgentRefreshSource {
    constructor(context: Context) : this(context = context, baseDir = null, prefs = null)
    constructor(baseDir: File) : this(context = null, baseDir = baseDir, prefs = null)

    private val preferences: SharedPreferences? = prefs ?: context?.applicationContext?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val goalsDirectory: File = when {
        baseDir != null -> File(baseDir, GOALS_DIRECTORY_NAME)
        context != null -> File(context.applicationContext.filesDir, GOALS_DIRECTORY_NAME)
        else -> throw IllegalArgumentException("AgentStore requires either a Context or a base directory File.")
    }

    private val goalCache = ConcurrentHashMap<String, CachedGoal>()
    private val diagnostics: com.david.openassistant.data.diagnostics.RuntimeDiagnostics? = context?.let { com.david.openassistant.data.diagnostics.RuntimeDiagnostics(it) }
    
    private data class CachedGoal(
        val goal: AgentGoal,
        val fileTimestamp: Long,
        val fileLength: Long
    )

    fun loadSnapshot(): AgentSnapshot = synchronized(STORE_LOCK) {
        readCount.incrementAndGet()
        loadSnapshotLocked()
    }

    override suspend fun loadStableSnapshot(): AgentSnapshotWithRevision = synchronized(STORE_LOCK) {
        readCount.incrementAndGet()
        val snapshot = loadSnapshotLocked()
        val revision = getLatestRevision()
        AgentSnapshotWithRevision(snapshot, revision)
    }

    override fun getLatestRevision(): Long {
        return preferences?.getLong(KEY_REVISION, 0L) ?: 0L
    }

    fun saveSnapshot(snapshot: AgentSnapshot) = synchronized(STORE_LOCK) {
        migrateLegacyIfNeededLocked()
        saveSnapshotLocked(snapshot)
    }

    fun upsertGoal(goal: AgentGoal, select: Boolean = false): AgentSnapshot = synchronized(STORE_LOCK) {
        migrateLegacyIfNeededLocked()
        val current = loadSnapshotFromFilesLocked()
        writeGoalLocked(goal)
        val selectedId = when {
            select -> goal.id
            current.selectedGoalId != null && (current.goals.any { it.id == current.selectedGoalId } || current.selectedGoalId == goal.id) ->
                current.selectedGoalId
            else -> goal.id
        }
        writeSelectionAndSignalLocked(selectedId)
        loadSnapshotFromFilesLocked()
    }

    fun updateGoal(goalId: String, transform: (AgentGoal) -> AgentGoal): AgentSnapshot = synchronized(STORE_LOCK) {
        migrateLegacyIfNeededLocked()
        updateGoalInternalLocked(goalId, transform)
    }

    fun updateGoalAtomic(
        goalId: String,
        ticket: AgentOwnershipTicket?,
        transform: (AgentGoal) -> AgentGoal
    ): AgentSnapshot = synchronized(STORE_LOCK) {
        if (ticket != null) {
            val validation = validateTicketInternalLocked(ticket)
            if (validation !is TicketValidationResult.Valid) {
                // If validation fails, return current snapshot without changes.
                // Callers must handle the fact that their update was rejected.
                return@synchronized loadSnapshotFromFilesLocked()
            }
        }
        migrateLegacyIfNeededLocked()
        updateGoalInternalLocked(goalId, transform)
    }

    fun refreshExecutionLease(
        goalId: String,
        workerId: String,
        attemptId: String,
        generation: Int,
        taskId: String?,
    ): RefreshLeaseResult = synchronized(STORE_LOCK) {
        migrateLegacyIfNeededLocked()
        val current = loadSnapshotFromFilesLocked()
        val goal = current.goals.firstOrNull { it.id == goalId } ?: return@synchronized RefreshLeaseResult.GoalMissing
        val lease = goal.executionLease ?: return@synchronized RefreshLeaseResult.LeaseLost

        val currentSessionId = com.david.openassistant.data.diagnostics.DiagnosticEvent.PROCESS_SESSION_ID
        if (lease.workerId != workerId || lease.attemptId != attemptId || lease.generation != generation || 
            lease.taskId != (taskId ?: "none") || lease.ownerProcessSessionId != currentSessionId
        ) {
            return@synchronized RefreshLeaseResult.LeaseLost
        }

        val now = System.currentTimeMillis()
        val updatedGoal = goal.copy(
            executionLease = lease.copy(heartbeatAt = now),
            updatedAt = now
        )
        runCatching { writeGoalLocked(updatedGoal) }.onFailure { return@synchronized RefreshLeaseResult.StorageFailure(it) }
        RefreshLeaseResult.Refreshed
    }

    fun acquirePlanningLeaseAtomic(goalId: String, workerId: String): LeaseAcquisitionResult = synchronized(STORE_LOCK) {
        acquireLeaseInternalLocked(goalId, workerId, null)
    }

    fun acquireTaskLeaseAtomic(goalId: String, workerId: String, taskId: String): LeaseAcquisitionResult = synchronized(STORE_LOCK) {
        if (taskId.isBlank() || taskId == "none") {
            return@synchronized LeaseAcquisitionResult.Rejected("Execution requires a valid task ID.")
        }
        acquireLeaseInternalLocked(goalId, workerId, taskId)
    }

    private fun acquireLeaseInternalLocked(
        goalId: String,
        workerId: String,
        taskId: String?,
    ): LeaseAcquisitionResult {
        migrateLegacyIfNeededLocked()
        val currentSnapshot = loadSnapshotFromFilesLocked()
        val goal = currentSnapshot.goals.firstOrNull { it.id == goalId } ?: return LeaseAcquisitionResult.RetryRequired
        
        if (goal.status.isFinalTerminalStatus() || goal.status == AgentGoalStatus.REJECTED || goal.status == AgentGoalStatus.BLOCKED) {
            return LeaseAcquisitionResult.MissionTerminal
        }

        val now = System.currentTimeMillis()
        val existing = goal.executionLease
        val currentSessionId = com.david.openassistant.data.diagnostics.DiagnosticEvent.PROCESS_SESSION_ID
        
        val isStale = AgentLeasePolicy.isStale(existing, now)
        // Explicit legacy detection: missing session ID or 'unknown'
        val isLegacy = existing != null && (existing.ownerProcessSessionId.isBlank() || existing.ownerProcessSessionId == "unknown")
        val isOrphan = existing != null && !isLegacy && existing.ownerProcessSessionId != currentSessionId
        val isMyOwn = existing != null && !isLegacy && existing.workerId == workerId && existing.ownerProcessSessionId == currentSessionId

        if (existing == null || isStale || isOrphan || isMyOwn || isLegacy) {
            val attemptId = UUID.randomUUID().toString()
            val newGeneration = maxOf(goal.leaseGeneration, existing?.generation ?: 0) + 1
            
            val newLease = AgentExecutionLease(
                workerId = workerId,
                ownerProcessSessionId = currentSessionId,
                taskId = taskId ?: "none",
                attemptId = attemptId,
                generation = newGeneration,
                acquiredAt = now,
                heartbeatAt = now
            )
            
            val reason = when {
                isLegacy -> "Recovered legacy lease without process identity."
                isMyOwn -> "Re-acquired existing local lease."
                existing == null -> "Acquired initial execution lease."
                isStale -> "Recovered a stale execution lease (last heartbeat: ${existing.heartbeatAt})."
                isOrphan -> "Reclaimed an orphaned lease from dead process session '${existing?.ownerProcessSessionId}'."
                else -> "Acquired lease."
            }

            val updatedGoal = goal.copy(
                // Forced rebuild of copy method call
                status = if (goal.status == AgentGoalStatus.QUEUED) AgentGoalStatus.RUNNING else goal.status,
                leaseGeneration = newGeneration,
                executionLease = newLease,
                events = appendEvent(goal.events, reason),
                updatedAt = now
            )
            
            return try {
                writeGoalLocked(updatedGoal)
                
                val reloaded = readGoalLocked(goalFileLocked(goalId))
                val reloadedLease = reloaded.executionLease ?: throw IllegalStateException("executionLease is null in reloaded goal: ${goalFileLocked(goalId).readText()}")
                val ticket = if (taskId != null) {
                    TaskExecutionTicket(
                        goalId = goalId,
                        taskIdentity = reloadedLease.taskId,
                        workerId = reloadedLease.workerId,
                        ownerProcessSessionId = reloadedLease.ownerProcessSessionId,
                        generation = reloadedLease.generation,
                        attemptId = reloadedLease.attemptId,
                        acquiredAt = reloadedLease.acquiredAt
                    )
                } else {
                    PlanningTicket(
                        goalId = goalId,
                        workerId = reloadedLease.workerId,
                        ownerProcessSessionId = reloadedLease.ownerProcessSessionId,
                        generation = reloadedLease.generation,
                        attemptId = reloadedLease.attemptId,
                        acquiredAt = reloadedLease.acquiredAt
                    )
                }

                if (isOrphan || (isStale && existing != null) || isLegacy) {
                    diagnostics?.info(
                        event = if (isOrphan) "lease_orphan_reclaimed" else if (isLegacy) "lease_legacy_reclaimed" else "lease_stale_reclaimed",
                        component = "lease",
                        fields = mapOf(
                            "goal_id" to goalId,
                            "worker_id" to workerId,
                            "old_session" to (existing?.ownerProcessSessionId ?: "none"),
                            "new_session" to currentSessionId,
                            "task_id" to (taskId ?: "none")
                        )
                    )
                    LeaseAcquisitionResult.OrphanReclaimed(ticket, updatedGoal)
                } else {
                    diagnostics?.info(
                        event = "lease_acquired",
                        component = "lease",
                        fields = mapOf("goal_id" to goalId, "worker_id" to workerId, "lease_gen" to newGeneration, "task_id" to (taskId ?: "none"))
                    )
                    LeaseAcquisitionResult.Acquired(ticket, updatedGoal)
                }
            } catch (error: Throwable) {
                error.printStackTrace()
                throw error
            }
        } else {
            diagnostics?.info(
                event = "lease_acquisition_contended",
                component = "lease",
                fields = mapOf(
                    "goal_id" to goalId,
                    "worker_id" to workerId,
                    "owner_sid" to existing.ownerProcessSessionId,
                    "owner_worker" to existing.workerId,
                    "target_task" to (taskId ?: "none"),
                    "owner_task" to existing.taskId
                )
            )
            return LeaseAcquisitionResult.LiveOwnerPresent
        }
    }

    private fun validateTicketInternalLocked(ticket: AgentOwnershipTicket): TicketValidationResult {
        val current = loadSnapshotFromFilesLocked()
        val goal = current.goals.firstOrNull { it.id == ticket.goalId } ?: return TicketValidationResult.LeaseMissing
        val lease = goal.executionLease ?: return TicketValidationResult.LeaseMissing

        return when {
            lease.workerId != ticket.workerId -> 
                TicketValidationResult.Mismatch("WORKER_MISMATCH", "workerId", ticket.workerId, lease.workerId)
            lease.ownerProcessSessionId != ticket.ownerProcessSessionId ->
                TicketValidationResult.Mismatch("PROCESS_SESSION_MISMATCH", "ownerProcessSessionId", ticket.ownerProcessSessionId, lease.ownerProcessSessionId)
            lease.generation != ticket.generation ->
                TicketValidationResult.Mismatch("GENERATION_MISMATCH", "generation", ticket.generation.toString(), lease.generation.toString())
            lease.attemptId != ticket.attemptId ->
                TicketValidationResult.Mismatch("ATTEMPT_MISMATCH", "attemptId", ticket.attemptId, lease.attemptId)
            lease.taskId != (ticket.taskId ?: "none") ->
                TicketValidationResult.Mismatch("TASK_MISMATCH", "taskId", ticket.taskId ?: "none", lease.taskId)
            AgentLeasePolicy.isStale(lease) ->
                TicketValidationResult.LeaseExpired
            else -> TicketValidationResult.Valid
        }
    }

    fun validateTicket(ticket: AgentOwnershipTicket): TicketValidationResult = synchronized(STORE_LOCK) {
        validateTicketInternalLocked(ticket)
    }

    fun releaseLeaseAtomic(ticket: AgentOwnershipTicket): Boolean = synchronized(STORE_LOCK) {
        if (validateTicketInternalLocked(ticket) !is TicketValidationResult.Valid) return@synchronized false

        val current = loadSnapshotFromFilesLocked()
        val goal = current.goals.firstOrNull { it.id == ticket.goalId } ?: return@synchronized false
        
        val updatedGoal = goal.copy(
            executionLease = null,
            events = appendEvent(goal.events, "Released execution lease for worker ${ticket.workerId}."),
            updatedAt = System.currentTimeMillis()
        )
        return try {
            writeGoalLocked(updatedGoal)
            diagnostics?.info(
                event = "lease_released",
                component = "lease",
                fields = mapOf("goal_id" to ticket.goalId, "worker_id" to ticket.workerId, "task_id" to (ticket.taskId ?: "none"))
            )
            true
        } catch (e: Throwable) {
            false
        }
    }

    internal interface GoalStateWriter {
        fun write(goal: AgentGoal)
    }

    private var testWriterInjection: GoalStateWriter? = null

    internal fun setTestWriterInjection(writer: GoalStateWriter?) {
        synchronized(STORE_LOCK) {
            testWriterInjection = writer
        }
    }

    fun createActiveRequestAttempt(
        goalId: String,
        attempt: ProviderRequestAttempt,
        context: ProviderRequestContext.Mission,
    ): CreateAttemptResult = synchronized(STORE_LOCK) {
        migrateLegacyIfNeededLocked()
        val current = loadSnapshotFromFilesLocked()
        val goal = current.goals.firstOrNull { it.id == goalId } ?: return@synchronized CreateAttemptResult.GoalMissing
        
        // 1. Validate goal ID matching
        if (attempt.goalId != goalId || context.goalId != goalId) {
            return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState
        }

        // 2. Validate initial outcome is ACTIVE
        if (attempt.exchangeOutcome != ExchangeOutcome.ACTIVE) {
            return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState
        }

        // 3. Goal status must be in explicit runnable allowlist
        if (goal.status !in setOf(AgentGoalStatus.PLANNING, AgentGoalStatus.RUNNING, AgentGoalStatus.VERIFYING)) {
            return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState
        }

        // 4. Validate active lease presence
        val lease = goal.executionLease ?: return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState

        // 5. Validate lease heartbeat freshness
        val now = System.currentTimeMillis()
        if (AgentLeasePolicy.isStale(lease, now)) {
            return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState
        }

        // 6. Validate generation matching
        if (context.executionGeneration != lease.generation || attempt.executionGeneration != lease.generation) {
            return@synchronized CreateAttemptResult.InvalidGeneration(expected = lease.generation, actual = attempt.executionGeneration)
        }

        // 7. Validate worker ownership
        if (context.workerId != lease.workerId) {
            return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState
        }

        // 8. Validate lease attempt ID
        if (context.attemptId != lease.attemptId) {
            return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState
        }

        // 9. Validate task ownership based on operation classification
        val isTaskBound = context.operation.taskBound
        if (isTaskBound) {
            if (context.taskId == null || lease.taskId != context.taskId || attempt.taskId != context.taskId) {
                return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState
            }
        } else {
            if (context.taskId != null) {
                return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState
            }
        }

        // 10. Reject duplicate exchange ID
        if (goal.requestAttempts.any { it.exchangeId == attempt.exchangeId }) {
            return@synchronized CreateAttemptResult.DuplicateExchange
        }

        // 11. Validate retry authorization if attempt > 1
        var authorizationsToKeep = goal.retryAuthorizations
        if (attempt.wireAttemptOrdinal > 1) {
            val validAuth = goal.retryAuthorizations.firstOrNull { 
                it.logicalRequestId == attempt.logicalRequestId && 
                it.attemptOrdinal == attempt.wireAttemptOrdinal &&
                it.executionGeneration == attempt.executionGeneration 
            }
            if (validAuth == null) {
                return@synchronized CreateAttemptResult.UnauthorizedRetry
            }
            // Consume it
            authorizationsToKeep = goal.retryAuthorizations.filter { it != validAuth }
        }

        val updatedGoal = goal.copy(
            requestAttempts = goal.requestAttempts + attempt, 
            retryAuthorizations = authorizationsToKeep,
            updatedAt = now
        )
        runCatching { writeGoalLocked(updatedGoal) }.onFailure { return@synchronized CreateAttemptResult.StorageFailure(it) }
        CreateAttemptResult.Created
    }

    fun authorizeRetry(goalId: String, authorization: ProviderRetryAuthorization): Boolean = synchronized(STORE_LOCK) {
        migrateLegacyIfNeededLocked()
        updateGoalInternalLocked(goalId) { current ->
            if (current.retryAuthorizations.any { it.logicalRequestId == authorization.logicalRequestId && it.attemptOrdinal == authorization.attemptOrdinal }) {
                current
            } else {
                current.copy(retryAuthorizations = current.retryAuthorizations + authorization)
            }
        } != null
    }

    fun transitionExchangeOutcome(
        goalId: String,
        exchangeId: String,
        newOutcome: ExchangeOutcome,
        context: ProviderRequestContext.Mission,
        statusCode: Int? = null,
        failureClass: String? = null,
        safeDiagnosticSummary: String? = null,
        providerResponseId: String? = null,
    ): TransitionOutcomeResult = synchronized(STORE_LOCK) {
        migrateLegacyIfNeededLocked()
        val current = loadSnapshotFromFilesLocked()
        val goal = current.goals.firstOrNull { it.id == goalId } ?: return@synchronized TransitionOutcomeResult.GoalMissing
        val existingAttempt = goal.requestAttempts.firstOrNull { it.exchangeId == exchangeId } ?: return@synchronized TransitionOutcomeResult.ExchangeMissing

        // 1. Check if already terminal first
        if (existingAttempt.exchangeOutcome != ExchangeOutcome.ACTIVE) {
            return@synchronized TransitionOutcomeResult.AlreadyTerminal(existingAttempt.exchangeOutcome)
        }

        // 2. Validate exact goal ID matching
        if (context.goalId != goalId || existingAttempt.goalId != goalId) {
            return@synchronized TransitionOutcomeResult.InvalidLeaseOrGoalState
        }

        // 3. Validate active lease presence on goal
        val lease = goal.executionLease ?: return@synchronized TransitionOutcomeResult.InvalidLeaseOrGoalState

        // 4. Validate worker ownership
        if (context.workerId != lease.workerId) {
            return@synchronized TransitionOutcomeResult.InvalidLeaseOrGoalState
        }

        // 5. Validate lease attempt ID
        if (context.attemptId != lease.attemptId) {
            return@synchronized TransitionOutcomeResult.InvalidLeaseOrGoalState
        }

        // 6. Validate generation lock
        if (context.executionGeneration != lease.generation || existingAttempt.executionGeneration != lease.generation) {
            return@synchronized TransitionOutcomeResult.InvalidGeneration(expected = lease.generation, actual = existingAttempt.executionGeneration)
        }

        // 7. Validate task ownership based on operation classification
        if (context.operation.taskBound) {
            if (context.taskId == null || lease.taskId != context.taskId || existingAttempt.taskId != context.taskId) {
                return@synchronized TransitionOutcomeResult.InvalidLeaseOrGoalState
            }
        } else {
            if (context.taskId != null || existingAttempt.taskId != null) {
                return@synchronized TransitionOutcomeResult.InvalidLeaseOrGoalState
            }
        }

        // Note: Do NOT reject terminalization solely because goal state changed (e.g. paused/cancelled)
        // or because lease heartbeat aged while HTTP was in flight.

        val now = System.currentTimeMillis()
        val updatedAttempt = existingAttempt.copy(
            exchangeOutcome = newOutcome,
            httpStatusCode = statusCode ?: existingAttempt.httpStatusCode,
            failureClass = failureClass ?: existingAttempt.failureClass,
            safeDiagnosticSummary = safeDiagnosticSummary ?: existingAttempt.safeDiagnosticSummary,
            providerResponseId = providerResponseId ?: existingAttempt.providerResponseId,
            finishedAt = now,
        )

        val updatedGoal = goal.copy(
            requestAttempts = goal.requestAttempts.map { if (it.exchangeId == exchangeId) updatedAttempt else it },
            updatedAt = now,
        )

        runCatching { writeGoalLocked(updatedGoal) }.onFailure { return@synchronized TransitionOutcomeResult.StorageFailure(it) }
        TransitionOutcomeResult.Updated(updatedAttempt)
    }

    fun commitTaskResultAtomic(
        ticket: TaskExecutionTicket,
        transform: (AgentGoal) -> AgentGoal
    ): AgentSnapshot = synchronized(STORE_LOCK) {
        val validation = validateTicketInternalLocked(ticket)
        if (validation !is TicketValidationResult.Valid) {
            diagnostics?.warning(
                event = "atomic_commit_rejected_ownership_lost",
                fields = mapOf(
                    "goal_id" to ticket.goalId,
                    "task_id" to ticket.taskId,
                    "reason" to (validation as? TicketValidationResult.Mismatch)?.reason
                )
            )
            return@synchronized loadSnapshotFromFilesLocked()
        }
        
        migrateLegacyIfNeededLocked()
        updateGoalInternalLocked(ticket.goalId, transform)
    }

    fun applyUsageOnceAtomic(
        ticket: AgentOwnershipTicket,
        accountingKey: String,
        tokenDelta: Int?,
        costUsd: Double?,
        usageSource: UsageSource,
    ): AgentSnapshot = synchronized(STORE_LOCK) {
        if (validateTicketInternalLocked(ticket) !is TicketValidationResult.Valid) {
            return@synchronized loadSnapshotFromFilesLocked()
        }
        
        migrateLegacyIfNeededLocked()
        updateGoalInternalLocked(ticket.goalId) { current ->
            if (current.idempotencyRecords.any { it.key == accountingKey && it.state == IdempotencyState.COMMITTED }) {
                current
            } else {
                val updated = current.withAdditionalUsage(tokenDelta, costUsd)
                val record = IdempotencyRecord(
                    key = accountingKey,
                    effectType = IdempotencyEffectType.PROVIDER_ACCOUNTING,
                    state = IdempotencyState.COMMITTED,
                    claimOwner = ticket.workerId,
                    committedAt = System.currentTimeMillis(),
                    completedBy = ticket.workerId
                )
                updated.copy(idempotencyRecords = updated.idempotencyRecords + record)
            }
        }
    }

    private fun updateGoalInternalLocked(goalId: String, transform: (AgentGoal) -> AgentGoal): AgentSnapshot {
        val current = loadSnapshotFromFilesLocked()
        val original = current.goals.firstOrNull { it.id == goalId } ?: return current
        val transformed = transform(original)
        
        val statusChanged = original.status != transformed.status
        if (statusChanged) {
            AgentStateMachine.requireTransition(original.status, transformed.status)
        }
        
        val checkpointAdded = transformed.checkpoints.size > original.checkpoints.size
        
        val updatedGoal = transformed.copy(updatedAt = System.currentTimeMillis())
        writeGoalLocked(updatedGoal)
        
        if (statusChanged) {
            diagnostics?.info(
                event = "mission_state_transition",
                component = "mission",
                fields = mapOf(
                    "goal_id" to goalId,
                    "state_before" to original.status.name,
                    "state_after" to transformed.status.name,
                    "reason_code" to "store_update"
                )
            )
        }
        
        if (checkpointAdded) {
            val last = transformed.checkpoints.lastOrNull()
            diagnostics?.info(
                event = "mission_checkpoint_committed",
                component = "mission",
                fields = mapOf(
                    "goal_id" to goalId,
                    "checkpoint_id" to (last?.id ?: "none"),
                    "progress_score" to transformed.denseProgressScore
                )
            )
        }

        writeSelectionAndSignalLocked(current.selectedGoalId ?: goalId)
        return loadSnapshotFromFilesLocked()
    }

    fun selectGoal(goalId: String?): AgentSnapshot = synchronized(STORE_LOCK) {
        migrateLegacyIfNeededLocked()
        val current = loadSnapshotFromFilesLocked()
        val validId = goalId?.takeIf { id -> current.goals.any { it.id == id } }
        writeSelectionAndSignalLocked(validId)
        current.copy(selectedGoalId = validId)
    }

    fun deleteGoal(goalId: String): AgentSnapshot = synchronized(STORE_LOCK) {
        migrateLegacyIfNeededLocked()
        val current = loadSnapshotFromFilesLocked()
        deleteGoalFilesLocked(goalId)
        val remaining = current.goals.filterNot { it.id == goalId }
        val selected = when {
            current.selectedGoalId != goalId && remaining.any { it.id == current.selectedGoalId } -> current.selectedGoalId
            else -> remaining.maxByOrNull { it.updatedAt }?.id
        }
        writeSelectionAndSignalLocked(selected)
        loadSnapshotFromFilesLocked()
    }

    fun savePendingDraft(draft: ResearchDraft?) = synchronized(STORE_LOCK) {
        val storePreferences = preferences ?: return@synchronized
        val committed = storePreferences.edit()
            .putString(KEY_PENDING_DRAFT, draft?.let(::encodeDraft)?.toString())
            .commit()
        check(committed) { "Pending research draft could not be committed to durable storage." }
    }

    fun loadPendingDraft(): ResearchDraft? = synchronized(STORE_LOCK) {
        val raw = preferences?.getString(KEY_PENDING_DRAFT, null) ?: return null
        runCatching { decodeDraft(JSONObject(raw)) }.getOrNull()
    }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences?.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences?.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun loadSnapshotLocked(): AgentSnapshot {
        migrateLegacyIfNeededLocked()
        return loadSnapshotFromFilesLocked()
    }

    private fun loadSnapshotFromFilesLocked(): AgentSnapshot {
        goalsDirectory.mkdirs()
        val quarantined = mutableListOf<MissionQuarantineEntry>()
        val goals = discoverGoalFilesLocked()
            .asSequence()
            .mapNotNull { file ->
                val cached = goalCache[file.name]
                if (cached != null && cached.fileTimestamp == file.lastModified() && cached.fileLength == file.length()) {
                    return@mapNotNull cached.goal
                }

                try {
                    val goal = readGoalLocked(file)
                    goalCache[file.name] = CachedGoal(goal, file.lastModified(), file.length())
                    goal
                } catch (error: Throwable) {
                    val recoveryArtifact = preserveCorruptGoalLocked(file, error)
                    quarantined += MissionQuarantineEntry(
                        fileName = file.name,
                        reason = "${error::class.java.simpleName}: ${error.message.orEmpty()}",
                        recoveryArtifactPath = recoveryArtifact?.absolutePath,
                        baseFileSize = file.length(),
                        backupPresent = File(file.path + ATOMIC_BACKUP_SUFFIX).exists(),
                    )
                    null
                }
            }
            .sortedByDescending { it.updatedAt }
            .toList()
        val selected = preferences?.getString(KEY_SELECTED_GOAL, null)
            ?.takeIf { selectedId -> goals.any { it.id == selectedId } }
            ?: goals.maxByOrNull { it.updatedAt }?.id
        return AgentSnapshot(goals, selected, quarantined)
    }

    private fun discoverGoalFilesLocked(): List<File> {
        goalsDirectory.mkdirs()
        return goalsDirectory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile }
            .mapNotNull { file ->
                when {
                    file.name.endsWith(GOAL_FILE_SUFFIX) -> file
                    file.name.endsWith(GOAL_FILE_SUFFIX + ATOMIC_BACKUP_SUFFIX) ->
                        File(file.path.removeSuffix(ATOMIC_BACKUP_SUFFIX))
                    else -> null
                }
            }
            .distinctBy { it.absolutePath }
            .toList()
    }

    private fun saveSnapshotLocked(snapshot: AgentSnapshot) {
        goalsDirectory.mkdirs()
        // Snapshot saves are merge-only. Deletion is intentionally restricted to
        // deleteGoal(), because a stale or partially restored empty snapshot must
        // never erase durable mission files.
        snapshot.goals.forEach(::writeGoalLocked)
        val persisted = loadSnapshotFromFilesLocked()
        val selected = snapshot.selectedGoalId
            ?.takeIf { id -> persisted.goals.any { it.id == id } }
            ?: persisted.selectedGoalId
        writeSelectionAndSignalLocked(selected)
    }

    private fun migrateLegacyIfNeededLocked() {
        val prefs = preferences ?: return
        if (prefs.getBoolean(KEY_MIGRATED_V2, false)) return
        goalsDirectory.mkdirs()
        val legacyRaw = prefs.getString(KEY_SNAPSHOT, null)
        if (legacyRaw?.trimStart()?.startsWith("{") == true) {
            val legacySnapshot = runCatching {
                decodeSnapshot(requireOpenRouterObject(legacyRaw, "Legacy agent snapshot"))
            }
                .getOrElse { error ->
                    preserveLegacySnapshotLocked(legacyRaw, error)
                    AgentSnapshot()
                }
            legacySnapshot.goals.forEach(::writeGoalLocked)
            check(
                runCatching {
                    prefs.edit(commit = true) {
                        putBoolean(KEY_MIGRATED_V2, true)
                        putString(KEY_SELECTED_GOAL, legacySnapshot.selectedGoalId)
                        putString(KEY_SNAPSHOT, newRevisionSignal())
                    }
                    true
                }.getOrDefault(false),
            ) { "Agent state migration could not be finalized." }
        } else {
            check(
                runCatching {
                    prefs.edit(commit = true) {
                        putBoolean(KEY_MIGRATED_V2, true)
                        putString(KEY_SNAPSHOT, newRevisionSignal())
                    }
                    true
                }.getOrDefault(false),
            ) { "Agent storage could not be initialized." }
        }
    }

    /**
     * A corrupt version-1 preference must not disappear silently during the
     * one-way migration. Preserve its original bytes in app-private storage so
     * diagnostics or a future repair tool can recover whatever remains valid.
     */
    private fun preserveLegacySnapshotLocked(raw: String, error: Throwable) {
        goalsDirectory.mkdirs()
        val recoveryFile = AtomicFile(File(goalsDirectory, LEGACY_RECOVERY_FILE_NAME))
        var output: FileOutputStream? = null
        try {
            val stream = recoveryFile.startWrite()
            output = stream
            val diagnostic = buildString {
                appendLine("OpenAssistant legacy agent snapshot recovery")
                appendLine("Migration parser: ${error::class.java.name}")
                appendLine("Message: ${error.message.orEmpty()}")
                appendLine("--- original preference value ---")
                append(raw)
            }
            stream.write(diagnostic.toByteArray(StandardCharsets.UTF_8))
            recoveryFile.finishWrite(stream)
        } catch (writeError: Throwable) {
            output?.let(recoveryFile::failWrite)
            throw IllegalStateException(
                "The unreadable legacy snapshot could not be preserved; migration was stopped.",
                writeError,
            ).also { migrationError -> migrationError.addSuppressed(error) }
        }
    }

    private fun preserveCorruptGoalLocked(file: File, error: Throwable): File? {
        val recoveryTarget = File(file.path + CORRUPT_RECOVERY_SUFFIX)
        val recoveryFile = AtomicFile(recoveryTarget)
        var output: FileOutputStream? = null
        try {
            val stream = recoveryFile.startWrite()
            output = stream
            val diagnostic = buildString {
                appendLine("OpenAssistant corrupt goal recovery")
                appendLine("File: ${file.name}")
                appendLine("Parser: ${error::class.java.name}")
                appendLine("Message: ${error.message.orEmpty()}")
                appendLine("--- base file ---")
                append(runCatching { file.readText(StandardCharsets.UTF_8) }.getOrDefault("<unreadable>"))
                val backup = File(file.path + ATOMIC_BACKUP_SUFFIX)
                if (backup.exists()) {
                    appendLine()
                    appendLine("--- atomic backup ---")
                    append(runCatching { backup.readText(StandardCharsets.UTF_8) }.getOrDefault("<unreadable>"))
                }
            }
            stream.write(diagnostic.toByteArray(StandardCharsets.UTF_8))
            recoveryFile.finishWrite(stream)
            return recoveryTarget
        } catch (writeError: Throwable) {
            output?.let(recoveryFile::failWrite)
            // Loading the remaining healthy goals is safer than crashing the
            // entire Work screen when a forensic copy cannot be written.
            error.addSuppressed(writeError)
            return null
        }
    }

    private fun writeSelectionAndSignalLocked(selectedGoalId: String?) {
        val prefs = preferences ?: return
        val currentRevision = prefs.getLong(KEY_REVISION, 0L)
        check(
            runCatching {
                prefs.edit(commit = true) {
                    putString(KEY_SELECTED_GOAL, selectedGoalId)
                    putLong(KEY_REVISION, currentRevision + 1)
                    putString(KEY_SNAPSHOT, newRevisionSignal())
                }
                true
            }.getOrDefault(false),
        ) { "Agent state revision could not be persisted." }
    }

    private fun writeGoalLocked(goal: AgentGoal) {
        writeCount.incrementAndGet()
        testWriterInjection?.write(goal)
        validateGoalIdentityForWrite(goal)
        goalsDirectory.mkdirs()
        val target = goalFileLocked(goal.id)
        val atomicFile = AtomicFile(target)
        val stream = atomicFile.startWrite()
        try {
            stream.write(encodeGoal(goal).toString().toByteArray(StandardCharsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (error: Throwable) {
            println("NPE DETAILS: ")
            error.printStackTrace()
            atomicFile.failWrite(stream)
            throw error
        }

        val readBack = readGoalLocked(target)
        check(readBack.id == goal.id) { "Goal readback identity mismatch for ${goal.id}." }
        check(readBack.conversationId == goal.conversationId) { "Goal readback conversation mismatch for ${goal.id}." }
        check(readBack.userRequest == goal.userRequest) { "Goal readback request mismatch for ${goal.id}." }
    }

    private fun validateGoalIdentityForWrite(goal: AgentGoal) {
        require(goal.id.isNotBlank()) { "Goal ID must not be blank." }
        require(goal.conversationId.isNotBlank()) { "Goal conversation ID must not be blank." }
        if (goal.status != AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION) {
            require(goal.userRequest.isNotBlank()) { "Goal original user request must not be blank." }
        }
    }

    private fun readGoalLocked(file: File): AgentGoal {
        val atomicFile = AtomicFile(file)
        val raw = atomicFile.openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        return decodeGoal(requireOpenRouterObject(raw, "Stored autonomous goal"))
    }

    private fun deleteGoalFilesLocked(goalId: String) {
        val file = goalFileLocked(goalId)
        deleteFileIfPresent(file, "goal")
        deleteFileIfPresent(File(file.path + ATOMIC_BACKUP_SUFFIX), "goal backup")
    }

    private fun deleteFileIfPresent(file: File, description: String) {
        if (file.exists()) {
            check(file.delete()) { "Could not delete $description file ${file.name}." }
        }
    }

    private fun goalFileLocked(goalId: String): File {
        val safeId = goalId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(96)
        return File(goalsDirectory, safeId + GOAL_FILE_SUFFIX)
    }

    private fun newRevisionSignal(): String = "v2:${System.currentTimeMillis()}:${UUID.randomUUID()}"

    private fun encodeDraft(draft: ResearchDraft): JSONObject = JSONObject()
        .put("id", draft.id)
        .put("conversation_id", draft.conversationId)
        .put("original_user_request", draft.originalUserRequest)
        .put("title", draft.title)
        .put("question", draft.question)
        .put("objective", draft.objective)
        .put("confirmed_constraints", JSONArray(draft.confirmedConstraints))
        .put("inferred_preferences", JSONArray(draft.inferredPreferences))
        .put("unresolved_questions", JSONArray(draft.unresolvedQuestions))
        .put("evidence_requirements", JSONArray(draft.evidenceRequirements))
        .put("preferred_source_types", JSONArray(draft.preferredSourceTypes))
        .put("freshness_requirement", draft.freshnessRequirement ?: JSONObject.NULL)
        .put("exclusions", JSONArray(draft.exclusions))
        .put("desired_deliverable", draft.desiredDeliverable)
        .put("source_message_ids", JSONArray(draft.sourceMessageIds))
        .put("resolved_research_request", draft.resolvedResearchRequest?.toJson() ?: JSONObject.NULL)
        .put("version", draft.version)
        .put("status", draft.status.name)
        .put("durable_scheduling_state", draft.durableSchedulingState.name)
        .put("linked_goal_id", draft.linkedGoalId ?: JSONObject.NULL)
        .put("updated_at", draft.updatedAt)

    private fun decodeDraft(json: JSONObject): ResearchDraft = ResearchDraft(
        id = json.getString("id"),
        conversationId = json.getString("conversation_id"),
        originalUserRequest = json.optString("original_user_request"),
        title = json.optString("title"),
        question = json.optString("question"),
        objective = json.optString("objective"),
        confirmedConstraints = json.optJSONArray("confirmed_constraints").toStringList(),
        inferredPreferences = json.optJSONArray("inferred_preferences").toStringList(),
        unresolvedQuestions = json.optJSONArray("unresolved_questions").toStringList(),
        evidenceRequirements = json.optJSONArray("evidence_requirements").toStringList(),
        preferredSourceTypes = json.optJSONArray("preferred_source_types").toStringList(),
        freshnessRequirement = json.optNullableString("freshness_requirement"),
        exclusions = json.optJSONArray("exclusions").toStringList(),
        desiredDeliverable = json.optString("desired_deliverable"),
        sourceMessageIds = json.optJSONArray("source_message_ids").toStringList(),
        resolvedResearchRequest = ResolvedResearchRequest.fromJson(json.optJSONObject("resolved_research_request")),
        version = json.optInt("version", 1),
        status = json.optEnum("status", ResearchDraftStatus.DRAFT),
        durableSchedulingState = json.optEnum("durable_scheduling_state", DurableSchedulingState.NOT_SCHEDULED),
        linkedGoalId = json.optNullableString("linked_goal_id"),
        updatedAt = json.optLong("updated_at", System.currentTimeMillis()),
    )

    private fun encodeSnapshot(snapshot: AgentSnapshot): JSONObject = JSONObject()
        .put("version", STORAGE_VERSION)
        .put("selected_goal_id", snapshot.selectedGoalId ?: JSONObject.NULL)
        .put("goals", JSONArray().apply { snapshot.goals.forEach { put(encodeGoal(it)) } })

    private fun decodeSnapshot(root: JSONObject): AgentSnapshot {
        val goalsArray = root.optJSONArray("goals") ?: JSONArray()
        val goals = buildList {
            for (index in 0 until goalsArray.length()) {
                val goalJson = goalsArray.optJSONObject(index) ?: continue
                val goal = runCatching { decodeGoal(goalJson) }
                    .getOrElse { error ->
                        throw IllegalArgumentException(
                            "Legacy agent snapshot contains an unreadable goal at index $index.",
                            error,
                        )
                    }
                add(goal)
            }
        }.sortedByDescending { it.updatedAt }
        val selectedId = root.optString("selected_goal_id")
            .takeIf { it.isNotBlank() && it != "null" && goals.any { goal -> goal.id == it } }
        return AgentSnapshot(goals, selectedId)
    }

    private fun encodeGoal(goal: AgentGoal): JSONObject = JSONObject()
        .put("storage_version", STORAGE_VERSION)
        .put("id", goal.id)
        .put("conversation_id", goal.conversationId)
        .put("submission_id", goal.submissionId ?: JSONObject.NULL)
        .put("user_request", goal.userRequest)
        .put("title", goal.title)
        .put("objective", goal.objective)
        .put("final_output_description", goal.finalOutputDescription)
        .put("confirmed_constraints", JSONArray(goal.confirmedConstraints))
        .put("inferred_preferences", JSONArray(goal.inferredPreferences))
        .put("unresolved_questions", JSONArray(goal.unresolvedQuestions))
        .put("evidence_requirements", JSONArray(goal.evidenceRequirements))
        .put("preferred_source_types", JSONArray(goal.preferredSourceTypes))
        .put("freshness_requirement", goal.freshnessRequirement ?: JSONObject.NULL)
        .put("exclusions", JSONArray(goal.exclusions))
        .put("source_message_ids", JSONArray(goal.sourceMessageIds))
        .put("grounded_constraints", JSONArray().apply { goal.groundedConstraints.forEach { put(it.toJson()) } })
        .put("status", goal.status.name)
        .put("planner_model_id", goal.plannerModelId)
        .put("execution_model_id", goal.executionModelId)
        .put("routing_stage", goal.routingStage.name)
        .put("requested_model_profile_name", goal.requestedModelProfileName ?: JSONObject.NULL)
        .put("routing_policy_provenance", goal.routingPolicyProvenance.name)
        .put("free_only", goal.freeOnly)
        .put("tasks", JSONArray().apply { goal.tasks.forEach { put(encodeTask(it)) } })
        .put("acceptance_criteria", JSONArray().apply { goal.acceptanceCriteria.forEach { put(encodeCriterion(it)) } })
        .put("acceptance_checks", JSONArray().apply { goal.acceptanceChecks.forEach { put(encodeCheck(it)) } })
        .put("attempts", JSONArray().apply { goal.attempts.forEach { put(encodeAttempt(it)) } })
        .put("evidence", JSONArray().apply { goal.evidence.forEach { put(encodeEvidence(it)) } })
        .put("source_reads", JSONArray().apply { goal.sourceReads.forEach { put(encodeSourceRead(it)) } })
        .put("evidence_candidates", JSONArray().apply { goal.evidenceCandidates.forEach { put(encodeEvidenceCandidate(it)) } })
        .put("normalized_facts", JSONArray().apply { goal.normalizedFacts.forEach { put(encodeNormalizedFact(it)) } })
        .put("accepted_claims", JSONArray().apply { goal.acceptedClaims.forEach { put(encodeAcceptedClaim(it)) } })
        .put("claims", JSONArray().apply { goal.claims.forEach { put(encodeClaim(it)) } })
        .put("evidence_links", JSONArray().apply { goal.evidenceLinks.forEach { put(encodeEvidenceLink(it)) } })
        .put("checkpoints", JSONArray().apply { goal.checkpoints.forEach { put(encodeCheckpoint(it)) } })
        .put("concept_candidates", JSONArray().apply { goal.conceptCandidates.forEach { put(encodeConcept(it)) } })
        .put("refinements", JSONArray().apply { goal.refinements.forEach { put(it) } })
        .put("events", JSONArray().apply { goal.events.forEach { put(encodeEvent(it)) } })
        .put("execution_lease", goal.executionLease?.let(::encodeLease) ?: JSONObject.NULL)
        .put("created_at", goal.createdAt)
        .put("updated_at", goal.updatedAt)
        .put("total_tokens", goal.totalTokens)
        .put("verification_round", goal.verificationRound)
        .put("verification_correction_streak", goal.verificationCorrectionStreak)
        .put("total_cost_usd_micros", goal.totalCostUsdMicros)
        .put("result", goal.result ?: JSONObject.NULL)
        .put("error", goal.error ?: JSONObject.NULL)
        .put("blocked_reason", goal.blockedReason ?: JSONObject.NULL)
        .put("terminal_result_delivered", goal.terminalResultDelivered)
        .put("next_retry_at", goal.nextRetryAt ?: JSONObject.NULL)
        .put("network_wait_started_at", goal.networkWaitStartedAt ?: JSONObject.NULL)
        .put("network_retry_count", goal.networkRetryCount)
        .put("network_wait_reason", goal.networkWaitReason ?: JSONObject.NULL)
        .put("resume_status_after_network", goal.resumeStatusAfterNetwork?.name ?: JSONObject.NULL)
        .put("request_attempts", JSONArray().apply { goal.requestAttempts.forEach { put(encodeRequestAttempt(it)) } })
        .put("retry_authorizations", JSONArray().apply { goal.retryAuthorizations.forEach { put(encodeRetryAuthorization(it)) } })
        .put("idempotency_records", JSONArray().apply { goal.idempotencyRecords.forEach { put(encodeIdempotencyRecord(it)) } })
        .put("monitor_outbox", JSONArray().apply { goal.monitorOutbox.forEach { put(encodeMonitorOutbox(it)) } })
        .put("route_fingerprints", JSONArray().apply { goal.routeFingerprints.forEach { put(encodeRouteFingerprint(it)) } })
        .put("body_builder_claims", JSONArray().apply { goal.bodyBuilderClaims.forEach { put(encodeBodyBuilderClaim(it)) } })
        .put("quarantined_records", JSONArray().apply { goal.quarantinedRecords.forEach { put(encodeQuarantinedRecord(it)) } })
        .put("is_corrupt", goal.isCorrupt)
        .put("resolved_research_request", goal.resolvedResearchRequest?.toJson() ?: JSONObject.NULL)
        .put("requires_user_clarification", goal.requiresUserClarification)
        .put("clarification_details", goal.clarificationDetails ?: JSONObject.NULL)
        .put("blocked_sources", JSONArray().apply { goal.blockedSources.forEach { put(encodeBlockedSource(it)) } })
        .put("allocation_profile_name", goal.allocationProfileName ?: JSONObject.NULL)
        .put("allocation_summary", goal.allocationSummary ?: JSONObject.NULL)
        .put("last_allocation_reason", goal.lastAllocationReason ?: JSONObject.NULL)
        .put("plan_revision", goal.planRevision)
        .put("last_meaningful_progress_at", goal.lastMeaningfulProgressAt ?: JSONObject.NULL)
        .put("no_progress_count", goal.noProgressCount)
        .put("blocker_recovery_condition", goal.blockerRecoveryCondition ?: JSONObject.NULL)
        .put("final_validation_result", goal.finalValidationResult ?: JSONObject.NULL)
        .put("attempted_strategies", JSONArray(goal.attemptedStrategies))
        .put("operation_fingerprints", JSONArray(goal.operationFingerprints))
        .put("classified_failures", JSONArray(goal.classifiedFailures))
        .put("lease_generation", goal.leaseGeneration)
        .put("last_resume_reason", goal.lastResumeReason?.name ?: JSONObject.NULL)

    private fun decodeGoal(json: JSONObject): AgentGoal {
        val legacyCostUsd = json.optDouble("total_cost_usd", 0.0)
        val convertedCostMicros = if (json.has("total_cost_usd_micros")) {
            json.optLong("total_cost_usd_micros", 0L)
        } else if (legacyCostUsd > 0.0) {
            runCatching {
                java.math.BigDecimal.valueOf(legacyCostUsd)
                    .movePointRight(6)
                    .setScale(0, java.math.RoundingMode.HALF_UP)
                    .longValueExact()
            }.getOrDefault((legacyCostUsd * 1_000_000.0).toLong())
        } else {
            0L
        }
        val storedVersion = json.optInt("storage_version", 1)
        
        // Routing Migration Precedence
        val storedRequestedProfile = json.optNullableString("requested_model_profile_name")
        val storedFreeOnly = if (json.has("free_only")) json.optBoolean("free_only") else null
        val storedRoutingStage = json.optEnum("routing_stage", AgentRoutingStage.AUTO_BETA)
        val plannerModel = json.optString("planner_model_id")
        val executionModel = json.optString("execution_model_id")

        val (finalFreeOnly, finalProfile, finalProvenance) = when {
            storedRequestedProfile != null && storedFreeOnly != null -> 
                Triple(storedFreeOnly, storedRequestedProfile, json.optEnum("routing_policy_provenance", RoutingPolicyProvenance.EXPLICIT_USER_SELECTION))
            
            storedFreeOnly != null ->
                Triple(storedFreeOnly, storedRequestedProfile, json.optEnum("routing_policy_provenance", RoutingPolicyProvenance.EXPLICIT_USER_SELECTION))

            storedRoutingStage == AgentRoutingStage.AUTO_BETA ->
                Triple(false, "AUTO", RoutingPolicyProvenance.LEGACY_EXPLICIT)

            storedRoutingStage == AgentRoutingStage.FREE || plannerModel == "openrouter/free" || executionModel == "openrouter/free" ->
                Triple(true, "FREE", RoutingPolicyProvenance.LEGACY_AMBIGUOUS_SAFETY_LOCK)

            else -> Triple(false, null, RoutingPolicyProvenance.EXPLICIT_USER_SELECTION)
        }

        val storedStatus = json.optEnum("status", AgentGoalStatus.QUEUED)
        val storedError = json.optNullableString("error")
        val normalizedStoredError = storedError?.let { normalizeAgentFailureMessage(it, it) }
        val storedTasks = json.optJSONArray("tasks").decodeList(::decodeTask)
        val storedConversationId = json.optString("conversation_id")
        val storedUserRequest = json.optString("user_request")
        val storedTitle = json.optString("title")
        val storedObjective = json.optString("objective")
        val integrityFailure = when {
            storedConversationId.isBlank() -> "Missing conversation identity."
            storedUserRequest.isBlank() -> "Missing original user request."
            else -> null
        }
        val legacyFailure = storedVersion < STORAGE_VERSION && storedStatus == AgentGoalStatus.FAILED
        val legacyCredentialWait = legacyFailure && storedError.isTerminalCredentialMessage()
        val obsoleteBudgetStop = storedStatus == AgentGoalStatus.FAILED &&
            storedError.isLegacyMissionBudgetStop()
        val recoverLegacyFailure = (legacyFailure && !legacyCredentialWait) || obsoleteBudgetStop
        val restoredStatus = when {
            integrityFailure != null -> AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION
            legacyCredentialWait -> AgentGoalStatus.WAITING_FOR_CREDENTIAL
            recoverLegacyFailure && storedTasks.isEmpty() -> AgentGoalStatus.PLANNING
            recoverLegacyFailure -> AgentGoalStatus.QUEUED
            else -> storedStatus
        }
        val restoredEvents = json.optJSONArray("events").decodeList(::decodeEvent).let { events ->
            val migrationMessage = when {
                legacyCredentialWait -> "Legacy credential failure converted to a durable wait state; work will resume after reconnection."
                obsoleteBudgetStop -> "Removed an obsolete mission-budget stop. Existing checkpoints were preserved and unfinished work was re-queued automatically."
                recoverLegacyFailure -> "The completion-seeking runtime reopened this legacy non-terminal failure automatically."
                else -> null
            }
            if (migrationMessage == null || events.any { it.message == migrationMessage }) {
                events
            } else {
                events + AgentEvent(message = migrationMessage)
            }
        }
        val finalRoutingStage = if (finalFreeOnly) AgentRoutingStage.FREE else storedRoutingStage
        
        val finalEvents = restoredEvents.let { events ->
            if (finalProvenance == RoutingPolicyProvenance.LEGACY_AMBIGUOUS_SAFETY_LOCK) {
                val msg = "Legacy FREE route detected without explicit policy. Applied safety lock: FREE ONLY."
                if (events.none { it.message == msg }) {
                    events + AgentEvent(message = msg)
                } else events
            } else events
        }

        return AgentGoal(
            id = json.getString("id"),
            conversationId = storedConversationId,
            submissionId = json.optNullableString("submission_id"),
            userRequest = storedUserRequest,
            title = storedTitle,
            objective = storedObjective,
            finalOutputDescription = json.optString("final_output_description"),
            confirmedConstraints = json.optJSONArray("confirmed_constraints").toStringList(),
            inferredPreferences = json.optJSONArray("inferred_preferences").toStringList(),
            unresolvedQuestions = json.optJSONArray("unresolved_questions").toStringList(),
            evidenceRequirements = json.optJSONArray("evidence_requirements").toStringList(),
            preferredSourceTypes = json.optJSONArray("preferred_source_types").toStringList(),
            freshnessRequirement = json.optNullableString("freshness_requirement"),
            exclusions = json.optJSONArray("exclusions").toStringList(),
            sourceMessageIds = json.optJSONArray("source_message_ids").toStringList(),
            groundedConstraints = json.optJSONArray("grounded_constraints").decodeList(GroundedConstraint::fromJson),
            status = restoredStatus,
            plannerModelId = json.optString("planner_model_id"),
            executionModelId = json.optString("execution_model_id"),
            routingStage = finalRoutingStage,
            requestedModelProfileName = finalProfile,
            routingPolicyProvenance = finalProvenance,
            freeOnly = finalFreeOnly,
            tasks = storedTasks,
            acceptanceCriteria = json.optJSONArray("acceptance_criteria").decodeList(::decodeCriterion),
            acceptanceChecks = json.optJSONArray("acceptance_checks").decodeList(::decodeCheck),
            attempts = json.optJSONArray("attempts").decodeList(::decodeAttempt),
            evidence = json.optJSONArray("evidence").decodeList(::decodeEvidence),
            sourceReads = json.optJSONArray("source_reads").decodeList(::decodeSourceRead),
            evidenceCandidates = json.optJSONArray("evidence_candidates").decodeList(::decodeEvidenceCandidate),
            normalizedFacts = json.optJSONArray("normalized_facts").decodeList(::decodeNormalizedFact),
            acceptedClaims = json.optJSONArray("accepted_claims").decodeList(::decodeAcceptedClaim),
            claims = json.optJSONArray("claims").decodeList(::decodeClaim),
            evidenceLinks = json.optJSONArray("evidence_links").decodeList(::decodeEvidenceLink),
            checkpoints = json.optJSONArray("checkpoints").decodeList(::decodeCheckpoint),
            conceptCandidates = json.optJSONArray("concept_candidates").decodeList(::decodeConcept),
            refinements = json.optJSONArray("refinements").toStringList(),
            events = finalEvents,
            executionLease = json.optJSONObject("execution_lease")?.let(::decodeLease),
            createdAt = json.optLong("created_at", System.currentTimeMillis()),
            updatedAt = json.optLong("updated_at", System.currentTimeMillis()),
            totalTokens = json.optInt("total_tokens", 0),
            totalCostUsdMicros = convertedCostMicros,
            verificationRound = json.optInt("verification_round", 0),
            // Added after storage version 5. Existing missions receive a fresh,
            // bounded convergence window instead of inheriting an old loop.
            verificationCorrectionStreak = json.optInt("verification_correction_streak", 0)
                .coerceAtLeast(0),
            result = json.optNullableString("result"),
            error = when {
                integrityFailure != null -> integrityFailure
                recoverLegacyFailure -> null
                else -> normalizedStoredError
            },
            blockedReason = json.optNullableString("blocked_reason"),
            terminalResultDelivered = if (recoverLegacyFailure) {
                false
            } else {
                json.optBoolean("terminal_result_delivered", false)
            },
            nextRetryAt = json.optLongOrNull("next_retry_at"),
            networkWaitStartedAt = json.optLongOrNull("network_wait_started_at"),
            networkRetryCount = json.optInt("network_retry_count", 0),
            networkWaitReason = json.optNullableString("network_wait_reason"),
            resumeStatusAfterNetwork = json.optNullableString("resume_status_after_network")
                ?.let { runCatching { AgentGoalStatus.valueOf(it) }.getOrNull() },
            requestAttempts = json.optJSONArray("request_attempts").decodeList(::decodeRequestAttempt),
            retryAuthorizations = json.optJSONArray("retry_authorizations").decodeList(::decodeRetryAuthorization),
            idempotencyRecords = json.optJSONArray("idempotency_records").decodeList(::decodeIdempotencyRecord),
            monitorOutbox = json.optJSONArray("monitor_outbox").decodeList(::decodeMonitorOutbox),
            routeFingerprints = json.optJSONArray("route_fingerprints").decodeList(::decodeRouteFingerprint),
            bodyBuilderClaims = json.optJSONArray("body_builder_claims").decodeList(::decodeBodyBuilderClaim),
            quarantinedRecords = json.optJSONArray("quarantined_records").decodeList(::decodeQuarantinedRecord),
            isCorrupt = json.optBoolean("is_corrupt", false) || integrityFailure != null,
            resolvedResearchRequest = ResolvedResearchRequest.fromJson(json.optJSONObject("resolved_research_request"))
                ?: ResolvedResearchRequest.createFallbackSingleRequest(storedUserRequest),
            requiresUserClarification = json.optBoolean("requires_user_clarification", false),
            clarificationDetails = json.optNullableString("clarification_details"),
            blockedSources = json.optJSONArray("blocked_sources").decodeList(::decodeBlockedSource),
            allocationProfileName = json.optNullableString("allocation_profile_name"),
            allocationSummary = json.optNullableString("allocation_summary"),
            lastAllocationReason = json.optNullableString("last_allocation_reason"),
            planRevision = json.optInt("plan_revision", 0),
            lastMeaningfulProgressAt = json.optLongOrNull("last_meaningful_progress_at"),
            noProgressCount = json.optInt("no_progress_count", 0),
            blockerRecoveryCondition = json.optNullableString("blocker_recovery_condition"),
            finalValidationResult = json.optNullableString("final_validation_result"),
            attemptedStrategies = json.optJSONArray("attempted_strategies").toStringList(),
            operationFingerprints = json.optJSONArray("operation_fingerprints").toStringList(),
            classifiedFailures = json.optJSONArray("classified_failures").toStringList(),
            leaseGeneration = json.optInt("lease_generation", 0),
            lastResumeReason = json.optNullableString("last_resume_reason")?.let { runCatching { ResumeReason.valueOf(it) }.getOrNull() },
        )
    }

    private fun encodeTask(task: AgentTask): JSONObject = JSONObject()
        .put("id", task.id)
        .put("order", task.order)
        .put("title", task.title)
        .put("instructions", task.instructions)
        .put("capability", task.capability.name)
        .put("depends_on", JSONArray(task.dependsOn))
        .put("status", task.status.name)
        .put("attempt_count", task.attemptCount)
        .put("lifetime_attempt_count", task.lifetimeAttemptCount)
        .put("task_generation", task.taskGeneration)
        .put("consecutive_no_progress_count", task.consecutiveNoProgressCount)
        .put("last_material_progress_at", task.lastMaterialProgressAt ?: JSONObject.NULL)
        .put("last_material_progress_fingerprint", task.lastMaterialProgressFingerprint ?: JSONObject.NULL)
        .put("branch_exhaustion_reason", task.branchExhaustionReason ?: JSONObject.NULL)
        .put("branch_exhausted_at", task.branchExhaustedAt ?: JSONObject.NULL)
        .put("last_error", task.lastError ?: JSONObject.NULL)
        .put("weight", task.weight)
        .put("automatic_window_reopen_count", task.automaticWindowReopenCount)
        .put("global_automatic_window_reopen_count", task.globalAutomaticWindowReopenCount)
        .put("last_request_fingerprint", task.lastRequestFingerprint ?: JSONObject.NULL)
        .put("last_escalated_fingerprint", task.lastEscalatedFingerprint ?: JSONObject.NULL)
        .put("progress_fingerprint", task.progressFingerprint ?: JSONObject.NULL)
        .put("query_fingerprints", JSONArray(task.queryFingerprints))
        .put("recent_query_fingerprints", JSONArray(task.recentQueryFingerprints))
        .put("recent_source_fingerprints", JSONArray(task.recentSourceFingerprints))
        .put("recent_claim_fingerprints", JSONArray(task.recentClaimFingerprints))
        .put("acceptance_criteria", JSONArray().apply { task.acceptanceCriteria.forEach { put(encodeCriterion(it)) } })
        .put("acceptance_checks", JSONArray().apply { task.acceptanceChecks.forEach { put(encodeCheck(it)) } })
        .put("progress_score", task.progressScore)
        .put("cooldown_until", task.cooldownUntil ?: JSONObject.NULL)
        .put("started_at", task.startedAt ?: JSONObject.NULL)
        .put("finished_at", task.finishedAt ?: JSONObject.NULL)
        .put("output_evidence_id", task.outputEvidenceId ?: JSONObject.NULL)
        .put("failure_class", task.failureClass ?: JSONObject.NULL)
        .put("wait_reason", task.waitReason ?: JSONObject.NULL)
        .put("wait_condition", task.waitCondition ?: JSONObject.NULL)
        .put("last_recovery_strategy", task.lastRecoveryStrategy ?: JSONObject.NULL)
        .put("result_set_fingerprint", task.resultSetFingerprint ?: JSONObject.NULL)
        .put("recovery_strategy_fingerprint", task.recoveryStrategyFingerprint ?: JSONObject.NULL)
        .put("last_tactic", task.lastTactic ?: JSONObject.NULL)
        .put("next_tactic", task.nextTactic ?: JSONObject.NULL)
        .put("outcome_classification", task.outcomeClassification ?: JSONObject.NULL)
        .put("error_classification", task.errorClassification ?: JSONObject.NULL)
        .put("retry_eligibility", task.retryEligibility)
        .put("retry_authorized_fingerprint", task.retryAuthorizedFingerprint ?: JSONObject.NULL)
        .put("rejected_queries", JSONArray().apply { task.rejectedQueries.forEach { put(encodeRejectedQuery(it)) } })
        .put("active_research_strategy_json", task.activeResearchStrategyJson ?: JSONObject.NULL)
        .put("repair_lineage", task.repairLineage?.let { encodeStructureRepairLineage(it) } ?: JSONObject.NULL)

    private fun decodeTask(json: JSONObject): AgentTask {
        val status = json.optEnum("status", AgentTaskStatus.PLANNED)
        val persistedProgress = if (json.has("progress_score")) {
            json.optDouble("progress_score", 0.0)
        } else if (status == AgentTaskStatus.COMPLETED) {
            1.0
        } else {
            0.0
        }
        return AgentTask(
            id = json.getString("id"),
            order = json.optInt("order"),
            title = json.optString("title"),
            instructions = json.optString("instructions"),
            capability = json.optEnum("capability", AgentCapability.REASON),
            dependsOn = json.optJSONArray("depends_on").toStringList(),
            status = status,
            attemptCount = json.optInt("attempt_count", 0),
            lifetimeAttemptCount = json.optInt("lifetime_attempt_count", json.optInt("attempt_count", 0)),
            taskGeneration = json.optInt("task_generation", 0),
            consecutiveNoProgressCount = json.optInt("consecutive_no_progress_count", 0),
            lastMaterialProgressAt = json.optLongOrNull("last_material_progress_at"),
            lastMaterialProgressFingerprint = json.optNullableString("last_material_progress_fingerprint"),
            branchExhaustionReason = json.optNullableString("branch_exhaustion_reason"),
            branchExhaustedAt = json.optLongOrNull("branch_exhausted_at"),
            lastError = json.optNullableString("last_error")?.let { normalizeAgentFailureMessage(it, it) },
            weight = json.optDouble("weight", 1.0).coerceIn(0.1, 10.0),
            automaticWindowReopenCount = json.optInt("automatic_window_reopen_count", 0),
            globalAutomaticWindowReopenCount = json.optInt("global_automatic_window_reopen_count", 0),
            lastRequestFingerprint = json.optNullableString("last_request_fingerprint"),
            lastEscalatedFingerprint = json.optNullableString("last_escalated_fingerprint"),
            progressFingerprint = json.optNullableString("progress_fingerprint"),
            queryFingerprints = json.optJSONArray("query_fingerprints").toStringList(),
            recentQueryFingerprints = json.optJSONArray("recent_query_fingerprints").toStringList(),
            recentSourceFingerprints = json.optJSONArray("recent_source_fingerprints").toStringList(),
            recentClaimFingerprints = json.optJSONArray("recent_claim_fingerprints").toStringList(),
            acceptanceCriteria = json.optJSONArray("acceptance_criteria").decodeList(::decodeCriterion),
            acceptanceChecks = json.optJSONArray("acceptance_checks").decodeList(::decodeCheck),
            progressScore = persistedProgress.coerceIn(0.0, 1.0),
            cooldownUntil = json.optLongOrNull("cooldown_until"),
            startedAt = json.optLongOrNull("started_at"),
            finishedAt = json.optLongOrNull("finished_at"),
            outputEvidenceId = json.optNullableString("output_evidence_id"),
            failureClass = json.optNullableString("failure_class"),
            waitReason = json.optNullableString("wait_reason"),
            waitCondition = json.optNullableString("wait_condition"),
            lastRecoveryStrategy = json.optNullableString("last_recovery_strategy"),
            resultSetFingerprint = json.optNullableString("result_set_fingerprint"),
            recoveryStrategyFingerprint = json.optNullableString("recovery_strategy_fingerprint"),
            lastTactic = json.optNullableString("last_tactic"),
            nextTactic = json.optNullableString("next_tactic"),
            outcomeClassification = json.optNullableString("outcome_classification"),
            errorClassification = json.optNullableString("error_classification"),
            retryEligibility = json.optBoolean("retry_eligibility", true),
            retryAuthorizedFingerprint = json.optNullableString("retry_authorized_fingerprint"),
            rejectedQueries = json.optJSONArray("rejected_queries").decodeList(::decodeRejectedQuery),
            activeResearchStrategyJson = json.optNullableString("active_research_strategy_json"),
            repairLineage = json.optJSONObject("repair_lineage")?.let { decodeStructureRepairLineage(it) },
        )
    }

    private fun encodeStructureRepairLineage(lineage: StructureRepairLineage): JSONObject = JSONObject()
        .put("original_response_hash", lineage.originalResponseHash)
        .put("original_request_fingerprint", lineage.originalRequestFingerprint)
        .put("repair_request_fingerprint", lineage.repairRequestFingerprint)
        .put("repair_attempt_count", lineage.repairAttemptCount)
        .put("repair_reason", lineage.repairReason.name)
        .put("repair_outcome", lineage.repairOutcome.name)
        .put("pre_repair_content_chars", lineage.preRepairContentChars)
        .put("post_repair_content_chars", lineage.postRepairContentChars)
        .put("pre_repair_raw_claims", lineage.preRepairRawClaims)
        .put("post_repair_raw_claims", lineage.postRepairRawClaims)
        .put("pre_repair_retained_claims", lineage.preRepairRetainedClaims)
        .put("post_repair_retained_claims", lineage.postRepairRetainedClaims)
        .put("pre_repair_supported_claims", lineage.preRepairSupportedClaims)
        .put("post_repair_supported_claims", lineage.postRepairSupportedClaims)

    private fun decodeStructureRepairLineage(json: JSONObject): StructureRepairLineage = StructureRepairLineage(
        originalResponseHash = json.getString("original_response_hash"),
        originalRequestFingerprint = json.getString("original_request_fingerprint"),
        repairRequestFingerprint = json.getString("repair_request_fingerprint"),
        repairAttemptCount = json.getInt("repair_attempt_count"),
        repairReason = StructureRepairReason.valueOf(json.getString("repair_reason")),
        repairOutcome = StructureRepairOutcome.valueOf(json.getString("repair_outcome")),
        preRepairContentChars = json.getInt("pre_repair_content_chars"),
        postRepairContentChars = json.getInt("post_repair_content_chars"),
        preRepairRawClaims = json.getInt("pre_repair_raw_claims"),
        postRepairRawClaims = json.getInt("post_repair_raw_claims"),
        preRepairRetainedClaims = json.getInt("pre_repair_retained_claims"),
        postRepairRetainedClaims = json.getInt("post_repair_retained_claims"),
        preRepairSupportedClaims = json.getInt("pre_repair_supported_claims"),
        postRepairSupportedClaims = json.getInt("post_repair_supported_claims"),
    )

    private fun encodeCriterion(criterion: AgentAcceptanceCriterion): JSONObject = JSONObject()
        .put("id", criterion.id)
        .put("description", criterion.description)
        .put("weight", criterion.weight)

    private fun decodeCriterion(json: JSONObject): AgentAcceptanceCriterion = AgentAcceptanceCriterion(
        id = json.optString("id"),
        description = json.optString("description"),
        weight = json.optDouble("weight", 1.0).coerceIn(0.1, 10.0),
    )

    private fun encodeCheck(check: AgentAcceptanceCheck): JSONObject = JSONObject()
        .put("criterion_id", check.criterionId)
        .put("status", check.status.name)
        .put("score", check.score)
        .put("explanation", check.explanation)

    private fun decodeCheck(json: JSONObject): AgentAcceptanceCheck = AgentAcceptanceCheck(
        criterionId = json.optString("criterion_id"),
        status = json.optEnum("status", AgentAcceptanceCheckStatus.NOT_EVALUATED),
        score = json.optDouble("score", 0.0).coerceIn(0.0, 1.0),
        explanation = json.optString("explanation"),
    )

    private fun encodeRequestAttempt(attempt: ProviderRequestAttempt): JSONObject = JSONObject()
        .put("exchange_id", attempt.exchangeId)
        .put("logical_request_id", attempt.logicalRequestId)
        .put("wire_attempt_ordinal", attempt.wireAttemptOrdinal)
        .put("previous_exchange_id", attempt.previousExchangeId ?: JSONObject.NULL)
        .put("provider_response_id", attempt.providerResponseId ?: JSONObject.NULL)
        .put("transport_stage", attempt.transportStage.name)
        .put("delivery_certainty", attempt.deliveryCertainty.name)
        .put("parent_operation_id", attempt.parentOperationId)
        .put("goal_id", attempt.goalId)
        .put("task_id", attempt.taskId ?: JSONObject.NULL)
        .put("execution_generation", attempt.executionGeneration)
        .put("requested_model", attempt.requestedModel)
        .put("resolved_model", attempt.resolvedModel ?: JSONObject.NULL)
        .put("role", attempt.role?.name ?: JSONObject.NULL)
        .put("payload_fingerprint", attempt.payloadFingerprint)
        .put("exchange_outcome", attempt.exchangeOutcome.name)
        .put("provider_accounting_outcome", attempt.providerAccountingOutcome.name)
        .put("domain_commit_outcome", attempt.domainCommitOutcome.name)
        .put("usage_source", attempt.usageSource?.name ?: JSONObject.NULL)
        .put("prompt_tokens", attempt.promptTokens ?: JSONObject.NULL)
        .put("completion_tokens", attempt.completionTokens ?: JSONObject.NULL)
        .put("total_tokens", attempt.totalTokens ?: JSONObject.NULL)
        .put("cost_usd", attempt.costUsd ?: JSONObject.NULL)
        .put("pricing_model_id", attempt.pricingModelId ?: JSONObject.NULL)
        .put("http_status_code", attempt.httpStatusCode ?: JSONObject.NULL)
        .put("failure_class", attempt.failureClass ?: JSONObject.NULL)
        .put("started_at", attempt.startedAt)
        .put("finished_at", attempt.finishedAt ?: JSONObject.NULL)
        .put("reconciliation_claim_owner", attempt.reconciliationClaimOwner ?: JSONObject.NULL)
        .put("reconciliation_claimed_at", attempt.reconciliationClaimedAt ?: JSONObject.NULL)
        .put("safe_diagnostic_summary", attempt.safeDiagnosticSummary ?: JSONObject.NULL)

    private fun decodeRequestAttempt(json: JSONObject): ProviderRequestAttempt = ProviderRequestAttempt(
        exchangeId = json.getString("exchange_id"),
        logicalRequestId = json.optString("logical_request_id", json.getString("exchange_id")), // fallback for old records
        wireAttemptOrdinal = json.optInt("wire_attempt_ordinal", 1),
        previousExchangeId = json.optNullableString("previous_exchange_id"),
        providerResponseId = json.optNullableString("provider_response_id"),
        transportStage = json.optEnum("transport_stage", ProviderTransportStage.NOT_DISPATCHED),
        deliveryCertainty = json.optEnum("delivery_certainty", ProviderDeliveryCertainty.NOT_SENT),
        parentOperationId = json.optString("parent_operation_id"),
        goalId = json.optString("goal_id"),
        taskId = json.optNullableString("task_id"),
        executionGeneration = json.optInt("execution_generation", 0),
        requestedModel = json.optString("requested_model"),
        resolvedModel = json.optNullableString("resolved_model"),
        role = json.optNullableString("role")?.let { runCatching { AgentTaskRole.valueOf(it) }.getOrNull() },
        payloadFingerprint = json.optString("payload_fingerprint"),
        exchangeOutcome = json.optEnum("exchange_outcome", ExchangeOutcome.INTERRUPTED_OUTCOME_UNKNOWN),
        providerAccountingOutcome = json.optEnum("provider_accounting_outcome", ProviderAccountingOutcome.NOT_AVAILABLE),
        domainCommitOutcome = json.optEnum("domain_commit_outcome", MissionDomainCommitOutcome.NOT_APPLICABLE),
        usageSource = json.optNullableString("usage_source")?.let { runCatching { UsageSource.valueOf(it) }.getOrNull() },
        promptTokens = json.optIntOrNull("prompt_tokens"),
        completionTokens = json.optIntOrNull("completion_tokens"),
        totalTokens = json.optIntOrNull("total_tokens"),
        costUsd = json.optDoubleOrNull("cost_usd") ?: json.optLongOrNull("cost_usd_micros")?.let { it / 1_000_000.0 },
        pricingModelId = json.optNullableString("pricing_model_id"),
        httpStatusCode = json.optIntOrNull("http_status_code"),
        failureClass = json.optNullableString("failure_class"),
        startedAt = json.optLong("started_at"),
        finishedAt = json.optLongOrNull("finished_at"),
        reconciliationClaimOwner = json.optNullableString("reconciliation_claim_owner"),
        reconciliationClaimedAt = json.optLongOrNull("reconciliation_claimed_at"),
        safeDiagnosticSummary = json.optNullableString("safe_diagnostic_summary"),
    )

    private fun encodeRetryAuthorization(auth: ProviderRetryAuthorization): JSONObject = JSONObject()
        .put("logical_request_id", auth.logicalRequestId)
        .put("payload_fingerprint", auth.payloadFingerprint)
        .put("execution_generation", auth.executionGeneration)
        .put("previous_exchange_id", auth.previousExchangeId ?: JSONObject.NULL)
        .put("failure_class", auth.failureClass)
        .put("delivery_certainty", auth.deliveryCertainty.name)
        .put("attempt_ordinal", auth.attemptOrdinal)
        .put("authorization_timestamp", auth.authorizationTimestamp)

    private fun decodeRetryAuthorization(json: JSONObject): ProviderRetryAuthorization = ProviderRetryAuthorization(
        logicalRequestId = json.getString("logical_request_id"),
        payloadFingerprint = json.getString("payload_fingerprint"),
        executionGeneration = json.getInt("execution_generation"),
        previousExchangeId = json.optNullableString("previous_exchange_id"),
        failureClass = json.getString("failure_class"),
        deliveryCertainty = ProviderDeliveryCertainty.valueOf(json.getString("delivery_certainty")),
        attemptOrdinal = json.getInt("attempt_ordinal"),
        authorizationTimestamp = json.optLong("authorization_timestamp", System.currentTimeMillis()),
    )

    private fun encodeIdempotencyRecord(rec: IdempotencyRecord): JSONObject = JSONObject()
        .put("key", rec.key)
        .put("effect_type", rec.effectType.name)
        .put("state", rec.state.name)
        .put("claim_owner", rec.claimOwner)
        .put("claim_generation", rec.claimGeneration)
        .put("effect_fingerprint", rec.effectFingerprint ?: JSONObject.NULL)
        .put("lease_expires_at", rec.leaseExpiresAt ?: JSONObject.NULL)
        .put("claimed_at", rec.claimedAt)
        .put("committed_at", rec.committedAt ?: JSONObject.NULL)
        .put("last_attempt_at", rec.lastAttemptAt)
        .put("completed_by", rec.completedBy ?: JSONObject.NULL)
        .put("last_failure", rec.lastFailure ?: JSONObject.NULL)
        .put("retry_count", rec.retryCount)
        .put("target_object_ids", JSONArray(rec.targetObjectIds))

    private fun decodeIdempotencyRecord(json: JSONObject): IdempotencyRecord = IdempotencyRecord(
        key = json.getString("key"),
        effectType = runCatching { IdempotencyEffectType.valueOf(json.optString("effect_type")) }.getOrDefault(IdempotencyEffectType.PROVIDER_ACCOUNTING),
        state = runCatching { IdempotencyState.valueOf(json.optString("state")) }.getOrDefault(IdempotencyState.CLAIMED),
        claimOwner = json.optString("claim_owner"),
        claimGeneration = json.optInt("claim_generation", 0),
        effectFingerprint = json.optNullableString("effect_fingerprint"),
        leaseExpiresAt = json.optLongOrNull("lease_expires_at"),
        claimedAt = json.optLong("claimed_at"),
        committedAt = json.optLongOrNull("committed_at"),
        lastAttemptAt = json.optLong("last_attempt_at"),
        completedBy = json.optNullableString("completed_by"),
        lastFailure = json.optNullableString("last_failure"),
        retryCount = json.optInt("retry_count", 0),
        targetObjectIds = json.optJSONArray("target_object_ids").toStringList(),
    )

    private fun encodeMonitorOutbox(rec: MonitorOutboxRecord): JSONObject = JSONObject()
        .put("event_id", rec.eventId)
        .put("exchange_id", rec.exchangeId)
        .put("event_type", rec.eventType)
        .put("safe_payload_json", rec.safePayloadJson)
        .put("state", rec.state.name)
        .put("created_at", rec.createdAt)
        .put("delivered_at", rec.deliveredAt ?: JSONObject.NULL)
        .put("attempt_count", rec.attemptCount)

    private fun decodeMonitorOutbox(json: JSONObject): MonitorOutboxRecord = MonitorOutboxRecord(
        eventId = json.getString("event_id"),
        exchangeId = json.optString("exchange_id"),
        eventType = json.optString("event_type"),
        safePayloadJson = json.optString("safe_payload_json"),
        state = runCatching { MonitorOutboxState.valueOf(json.optString("state")) }.getOrDefault(MonitorOutboxState.PENDING),
        createdAt = json.optLong("created_at"),
        deliveredAt = json.optLongOrNull("delivered_at"),
        attemptCount = json.optInt("attempt_count", 0),
    )

    private fun encodeQuarantinedRecord(rec: QuarantinedRecord): JSONObject = JSONObject()
        .put("id", rec.id)
        .put("record_type", rec.recordType)
        .put("original_index_or_id", rec.originalIndexOrId)
        .put("sha256_hash", rec.sha256Hash)
        .put("safe_parse_error", rec.safeParseError)
        .put("detected_timestamp", rec.detectedTimestamp)
        .put("source_schema_version", rec.sourceSchemaVersion)

    private fun decodeQuarantinedRecord(json: JSONObject): QuarantinedRecord = QuarantinedRecord(
        id = json.optString("id"),
        recordType = json.optString("record_type"),
        originalIndexOrId = json.optString("original_index_or_id"),
        sha256Hash = json.optString("sha256_hash"),
        safeParseError = json.optString("safe_parse_error"),
        detectedTimestamp = json.optLong("detected_timestamp"),
        sourceSchemaVersion = json.optInt("source_schema_version", 7),
    )

    private fun encodeRouteFingerprint(fp: RouteFailureFingerprint): JSONObject = JSONObject()
        .put("goal_id", fp.goalId)
        .put("task_id", fp.taskId ?: JSONObject.NULL)
        .put("operation_id", fp.operationId)
        .put("canonical_payload_hash", fp.canonicalPayloadHash)
        .put("schema_version", fp.schemaVersion)
        .put("role", fp.role.name)
        .put("route", fp.route)
        .put("resolved_model", fp.resolvedModel ?: JSONObject.NULL)
        .put("failure_class", fp.failureClass)
        .put("repair_applied", fp.repairApplied ?: JSONObject.NULL)
        .put("retry_after_ms", fp.retryAfterMs ?: JSONObject.NULL)
        .put("next_eligible_time", fp.nextEligibleTime ?: JSONObject.NULL)
        .put("timestamp", fp.timestamp)

    private fun decodeRouteFingerprint(json: JSONObject): RouteFailureFingerprint = RouteFailureFingerprint(
        goalId = json.getString("goal_id"),
        taskId = json.optNullableString("task_id"),
        operationId = json.optString("operation_id"),
        canonicalPayloadHash = json.optString("canonical_payload_hash"),
        schemaVersion = json.optInt("schema_version", 1),
        role = json.optEnum("role", AgentTaskRole.PRIMARY_REASONING),
        route = json.optString("route"),
        resolvedModel = json.optNullableString("resolved_model"),
        failureClass = json.optString("failure_class"),
        repairApplied = json.optNullableString("repair_applied"),
        retryAfterMs = json.optLongOrNull("retry_after_ms"),
        nextEligibleTime = json.optLongOrNull("next_eligible_time"),
        timestamp = json.optLong("timestamp"),
    )

    private fun encodeBodyBuilderClaim(claim: BodyBuilderProposalClaim): JSONObject = JSONObject()
        .put("claim_id", claim.claimId)
        .put("task_id", claim.taskId)
        .put("payload_fingerprint", claim.payloadFingerprint)
        .put("claim_owner", claim.claimOwner)
        .put("execution_generation", claim.executionGeneration)
        .put("claimed_at", claim.claimedAt)
        .put("lease_expires_at", claim.leaseExpiresAt)
        .put("dispatch_status", claim.dispatchStatus.name)
        .put("provider_exchange_id", claim.providerExchangeId ?: JSONObject.NULL)
        .put("terminal_proposal_outcome", claim.terminalProposalOutcome ?: JSONObject.NULL)
        .put("accepted_field_paths", JSONArray(claim.acceptedFieldPaths))

    private fun decodeBodyBuilderClaim(json: JSONObject): BodyBuilderProposalClaim = BodyBuilderProposalClaim(
        claimId = json.getString("claim_id"),
        taskId = json.optString("task_id"),
        payloadFingerprint = json.optString("payload_fingerprint"),
        claimOwner = json.optString("claim_owner"),
        executionGeneration = json.optInt("execution_generation", 0),
        claimedAt = json.optLong("claimed_at"),
        leaseExpiresAt = json.optLong("lease_expires_at"),
        dispatchStatus = json.optEnum("dispatch_status", ProposalDispatchStatus.CLAIMED),
        providerExchangeId = json.optNullableString("provider_exchange_id"),
        terminalProposalOutcome = json.optNullableString("terminal_proposal_outcome"),
        acceptedFieldPaths = json.optJSONArray("accepted_field_paths").toStringList(),
    )

    private fun encodeAttempt(attempt: AgentAttempt): JSONObject = JSONObject()
        .put("id", attempt.id)
        .put("task_id", attempt.taskId ?: JSONObject.NULL)
        .put("status", attempt.status.name)
        .put("started_at", attempt.startedAt)
        .put("finished_at", attempt.finishedAt ?: JSONObject.NULL)
        .put("model_id", attempt.modelId)
        .put("council_role", attempt.councilRole?.name ?: JSONObject.NULL)
        .put("resolved_model", attempt.resolvedModel ?: JSONObject.NULL)
        .put("response_id", attempt.responseId ?: JSONObject.NULL)
        .put("provider", attempt.provider ?: JSONObject.NULL)
        .put("finish_reason", attempt.finishReason ?: JSONObject.NULL)
        .put("native_finish_reason", attempt.nativeFinishReason ?: JSONObject.NULL)
        .put("prompt_tokens", attempt.promptTokens ?: JSONObject.NULL)
        .put("completion_tokens", attempt.completionTokens ?: JSONObject.NULL)
        .put("total_tokens", attempt.totalTokens ?: JSONObject.NULL)
        .put("cost_usd", attempt.costUsd ?: JSONObject.NULL)
        .put("web_search_requests", attempt.webSearchRequests ?: JSONObject.NULL)
        .put("web_fetch_requests", attempt.webFetchRequests ?: JSONObject.NULL)
        .put("discovered_leads", attempt.discoveredLeads ?: JSONObject.NULL)
        .put("rabbit_hole_iterations", attempt.rabbitHoleIterations ?: JSONObject.NULL)
        .put("error", attempt.error ?: JSONObject.NULL)

    private fun decodeAttempt(json: JSONObject): AgentAttempt = AgentAttempt(
        id = json.getString("id"),
        taskId = json.optNullableString("task_id"),
        status = json.optEnum("status", AgentAttemptStatus.FAILED),
        startedAt = json.optLong("started_at"),
        finishedAt = json.optLongOrNull("finished_at"),
        modelId = json.optString("model_id"),
        councilRole = json.optNullableString("council_role")?.let { runCatching { CouncilRole.valueOf(it) }.getOrNull() },
        resolvedModel = json.optNullableString("resolved_model"),
        responseId = json.optNullableString("response_id"),
        provider = json.optNullableString("provider"),
        finishReason = json.optNullableString("finish_reason"),
        nativeFinishReason = json.optNullableString("native_finish_reason"),
        promptTokens = json.optIntOrNull("prompt_tokens"),
        completionTokens = json.optIntOrNull("completion_tokens"),
        totalTokens = json.optIntOrNull("total_tokens"),
        costUsd = json.optDoubleOrNull("cost_usd"),
        webSearchRequests = json.optIntOrNull("web_search_requests"),
        webFetchRequests = json.optIntOrNull("web_fetch_requests"),
        discoveredLeads = json.optIntOrNull("discovered_leads"),
        rabbitHoleIterations = json.optIntOrNull("rabbit_hole_iterations"),
        error = json.optNullableString("error")?.let { normalizeAgentFailureMessage(it, it) },
    )

    private fun encodeEvidence(evidence: AgentEvidence): JSONObject = JSONObject()
        .put("id", evidence.id)
        .put("task_id", evidence.taskId ?: JSONObject.NULL)
        .put("kind", evidence.kind.name)
        .put("title", evidence.title)
        .put("summary", evidence.summary)
        .put("content", evidence.content)
        .put(
            "sources",
            JSONArray().apply {
                evidence.sources.forEach { source ->
                    put(
                        JSONObject()
                            .put("title", source.title)
                            .put("url", source.url)
                            .put("excerpt", source.excerpt ?: JSONObject.NULL),
                    )
                }
            },
        )
        .put("created_at", evidence.createdAt)

    private fun decodeEvidence(json: JSONObject): AgentEvidence = AgentEvidence(
        id = json.getString("id"),
        taskId = json.optNullableString("task_id"),
        kind = json.optEnum("kind", AgentEvidenceKind.SYSTEM_EVENT),
        title = json.optString("title"),
        summary = json.optString("summary"),
        content = json.optString("content"),
        sources = json.optJSONArray("sources").decodeList { source ->
            AgentSourceCitation(
                title = source.optString("title"),
                url = source.optString("url"),
                excerpt = source.optNullableString("excerpt"),
            ).sanitizedForPersistence()
        }.filter { source -> source.url.startsWith("https://") },
        createdAt = json.optLong("created_at"),
    )

    private fun encodeClaim(claim: AgentClaim): JSONObject = JSONObject()
        .put("id", claim.id)
        .put("task_id", claim.taskId)
        .put("text", claim.text)
        .put("type", claim.type.name)
        .put("confidence", claim.confidence)
        .put("support", claim.support.name)
        .put("supporting_evidence_ids", JSONArray(claim.supportingEvidenceIds))
        .put("source_urls", JSONArray(claim.sourceUrls))
        .put("review_explanation", claim.reviewExplanation ?: JSONObject.NULL)

    private fun decodeClaim(json: JSONObject): AgentClaim = AgentClaim(
        id = json.getString("id"),
        taskId = json.optString("task_id"),
        text = json.optString("text"),
        type = json.optEnum("type", AgentClaimType.INFERENCE),
        confidence = json.optDouble("confidence", 0.5).coerceIn(0.0, 1.0),
        support = json.optEnum("support", AgentClaimSupport.UNSUPPORTED),
        supportingEvidenceIds = json.optJSONArray("supporting_evidence_ids").toStringList(),
        sourceUrls = json.optJSONArray("source_urls").toStringList().filter { it.startsWith("https://") },
        reviewExplanation = json.optNullableString("review_explanation"),
    )

    private fun encodeEvidenceLink(link: AgentEvidenceLink): JSONObject = JSONObject()
        .put("id", link.id)
        .put("claim_id", link.claimId)
        .put("evidence_id", link.evidenceId)
        .put("relation", link.relation.name)
        .put("explanation", link.explanation ?: JSONObject.NULL)

    private fun decodeEvidenceLink(json: JSONObject): AgentEvidenceLink = AgentEvidenceLink(
        id = json.getString("id"),
        claimId = json.optString("claim_id"),
        evidenceId = json.optString("evidence_id"),
        relation = json.optEnum("relation", AgentEvidenceRelation.SUPPORTS),
        explanation = json.optNullableString("explanation"),
    )

    private fun encodeSourceRead(read: SourceRead): JSONObject = JSONObject()
        .put("id", read.id)
        .put("url", read.url)
        .put("canonical_url", read.canonicalUrl)
        .put("http_code", read.httpCode)
        .put("content_type", read.contentType)
        .put("content", read.content)
        .put("source_role", read.sourceRole)
        .put("authority_score", read.authorityScore)
        .put("read_at", read.readAt)
        .put("provenance", read.provenance.name)

    private fun decodeSourceRead(json: JSONObject): SourceRead {
        val httpCode = json.getInt("http_code")
        val parsedProvenanceStr = json.optString("provenance", "")
        val provenance = if (parsedProvenanceStr.isNotBlank()) {
            SourceReadProvenance.valueOf(parsedProvenanceStr)
        } else {
            // Legacy Migration logic for provenance
            val content = json.optString("content", "")
            val hasContent = content.isNotBlank()
            if (hasContent && httpCode == 200) {
                // We shouldn't blindly infer VERIFIED_FETCH from httpCode == 200
                // We'll map to LEGACY_ASSUMED if it has http 200, but not fully verified.
                SourceReadProvenance.LEGACY_ASSUMED
            } else {
                SourceReadProvenance.UNVERIFIED_CITATION
            }
        }
        return SourceRead(
            id = json.getString("id"),
            url = json.getString("url"),
            canonicalUrl = json.getString("canonical_url"),
            httpCode = httpCode,
            contentType = json.getString("content_type"),
            content = json.getString("content"),
            sourceRole = json.getString("source_role"),
            authorityScore = json.getInt("authority_score"),
            readAt = json.getLong("read_at"),
            provenance = provenance,
        )
    }

    private fun encodeEvidenceCandidate(candidate: EvidenceCandidate): JSONObject = JSONObject()
        .put("id", candidate.id)
        .put("source_read_id", candidate.sourceReadId)
        .put("canonical_url", candidate.canonicalUrl)
        .put("raw_text", candidate.rawText)
        .put("structured_path", candidate.structuredPath ?: JSONObject.NULL)
        .put("relevance_score", candidate.relevanceScore)

    private fun decodeEvidenceCandidate(json: JSONObject): EvidenceCandidate = EvidenceCandidate(
        id = json.getString("id"),
        sourceReadId = json.getString("source_read_id"),
        canonicalUrl = json.getString("canonical_url"),
        rawText = json.getString("raw_text"),
        structuredPath = json.optNullableString("structured_path"),
        relevanceScore = json.getInt("relevance_score"),
    )

    private fun encodeNormalizedFact(fact: NormalizedFact): JSONObject = JSONObject()
        .put("id", fact.id)
        .put("evidence_candidate_id", fact.evidenceCandidateId)
        .put("fact_value", fact.factValue)
        .put("units", fact.units ?: JSONObject.NULL)
        .put("entity_name", fact.entityName)
        .put("content_hash", fact.contentHash)

    private fun decodeNormalizedFact(json: JSONObject): NormalizedFact = NormalizedFact(
        id = json.getString("id"),
        evidenceCandidateId = json.getString("evidence_candidate_id"),
        factValue = json.getString("fact_value"),
        units = json.optNullableString("units"),
        entityName = json.getString("entity_name"),
        contentHash = json.getString("content_hash"),
    )

    private fun encodeAcceptedClaim(claim: AcceptedClaim): JSONObject = JSONObject()
        .put("id", claim.id)
        .put("task_id", claim.taskId)
        .put("claim_text", claim.claimText)
        .put("source_read_id", claim.sourceReadId)
        .put("evidence_candidate_id", claim.evidenceCandidateId)
        .put("canonical_url", claim.canonicalUrl)
        .put("source_role", claim.sourceRole)
        .put("normalized_value", claim.normalizedValue)
        .put("units", claim.units ?: JSONObject.NULL)
        .put("content_hash", claim.contentHash)

    private fun decodeAcceptedClaim(json: JSONObject): AcceptedClaim = AcceptedClaim(
        id = json.getString("id"),
        taskId = json.getString("task_id"),
        claimText = json.getString("claim_text"),
        sourceReadId = json.getString("source_read_id"),
        evidenceCandidateId = json.getString("evidence_candidate_id"),
        canonicalUrl = json.getString("canonical_url"),
        sourceRole = json.getString("source_role"),
        normalizedValue = json.getString("normalized_value"),
        units = json.optNullableString("units"),
        contentHash = json.getString("content_hash"),
    )

    private fun encodeCheckpoint(checkpoint: AgentCheckpoint): JSONObject = JSONObject()
        .put("id", checkpoint.id)
        .put("sequence", checkpoint.sequence)
        .put("created_at", checkpoint.createdAt)
        .put("completed_task_ids", JSONArray(checkpoint.completedTaskIds))
        .put("progress_score", checkpoint.progressScore)
        .put("note", checkpoint.note)

    private fun decodeCheckpoint(json: JSONObject): AgentCheckpoint = AgentCheckpoint(
        id = json.getString("id"),
        sequence = json.optInt("sequence"),
        createdAt = json.optLong("created_at"),
        completedTaskIds = json.optJSONArray("completed_task_ids").toStringList(),
        progressScore = json.optDouble("progress_score", 0.0).coerceIn(0.0, 1.0),
        note = json.optString("note"),
    )

    private fun encodeConcept(concept: AgentConceptCandidate): JSONObject = JSONObject()
        .put("id", concept.id)
        .put("name", concept.name)
        .put("definition", concept.definition)
        .put("trigger_pattern", concept.triggerPattern)
        .put("expected_benefit", concept.expectedBenefit)
        .put("risks", JSONArray(concept.risks))
        .put("validation_tests", JSONArray(concept.validationTests))
        .put("status", concept.status.name)
        .put("created_at", concept.createdAt)

    private fun decodeConcept(json: JSONObject): AgentConceptCandidate = AgentConceptCandidate(
        id = json.getString("id"),
        name = json.optString("name"),
        definition = json.optString("definition"),
        triggerPattern = json.optString("trigger_pattern"),
        expectedBenefit = json.optString("expected_benefit"),
        risks = json.optJSONArray("risks").toStringList(),
        validationTests = json.optJSONArray("validation_tests").toStringList(),
        status = json.optEnum("status", AgentConceptStatus.PROPOSED),
        createdAt = json.optLong("created_at"),
    )

    private fun encodeEvent(event: AgentEvent): JSONObject = JSONObject()
        .put("id", event.id)
        .put("created_at", event.createdAt)
        .put("message", event.message)

    private fun decodeEvent(json: JSONObject): AgentEvent = AgentEvent(
        id = json.getString("id"),
        createdAt = json.optLong("created_at"),
        message = json.optString("message").let { normalizeAgentFailureMessage(it, it) },
    )

    private fun encodeLease(lease: AgentExecutionLease): JSONObject = JSONObject()
        .put("worker_id", lease.workerId)
        .put("owner_process_session_id", lease.ownerProcessSessionId)
        .put("task_id", lease.taskId)
        .put("attempt_id", lease.attemptId)
        .put("generation", lease.generation)
        .put("acquired_at", lease.acquiredAt)
        .put("heartbeat_at", lease.heartbeatAt)

    private fun decodeLease(json: JSONObject): AgentExecutionLease = AgentExecutionLease(
        workerId = json.getString("worker_id"),
        ownerProcessSessionId = json.optString("owner_process_session_id", "unknown"),
        taskId = json.getString("task_id"),
        attemptId = json.getString("attempt_id"),
        generation = json.getInt("generation"),
        acquiredAt = json.getLong("acquired_at"),
        heartbeatAt = json.getLong("heartbeat_at"),
    )

    private fun encodeBlockedSource(rec: BlockedSourceRecord): JSONObject = JSONObject()
        .put("canonical_document_id", rec.canonicalDocumentId ?: JSONObject.NULL)
        .put("canonical_url", rec.canonicalUrl)
        .put("route_kind", rec.routeKind)
        .put("failure_class", rec.failureClass)
        .put("first_failed_at", rec.firstFailedAt)
        .put("last_failed_at", rec.lastFailedAt)
        .put("last_failure_detail_code", rec.lastFailureDetailCode ?: JSONObject.NULL)
        .put("attempt_count", rec.attemptCount)
        .put("alternate_routes_attempted", JSONArray(rec.alternateRoutesAttempted))
        .put("terminal_state", rec.terminalState)
        .put("source_task_id", rec.sourceTaskId ?: JSONObject.NULL)

    private fun decodeBlockedSource(json: JSONObject): BlockedSourceRecord = BlockedSourceRecord(
        canonicalDocumentId = json.optNullableString("canonical_document_id"),
        canonicalUrl = json.getString("canonical_url"),
        routeKind = json.optString("route_kind", "UNKNOWN"),
        failureClass = json.optString("failure_class", "UNKNOWN"),
        firstFailedAt = json.optLong("first_failed_at"),
        lastFailedAt = json.optLong("last_failed_at"),
        lastFailureDetailCode = json.optNullableString("last_failure_detail_code"),
        attemptCount = json.optInt("attempt_count", 1),
        alternateRoutesAttempted = json.optJSONArray("alternate_routes_attempted").toStringList(),
        terminalState = json.optBoolean("terminal_state", false),
        sourceTaskId = json.optNullableString("source_task_id"),
    )

    private fun encodeRejectedQuery(rec: RejectedResearchQuery): JSONObject = JSONObject()
        .put("original_query", rec.originalQuery)
        .put("normalized_query", rec.normalizedQuery)
        .put("canonical_fingerprint", rec.canonicalFingerprint)
        .put("task_id", rec.taskId)
        .put("reason_code", rec.reasonCode)
        .put("reason_detail", rec.reasonDetail)
        .put("matched_weak_anchors", JSONArray(rec.matchedWeakAnchors))
        .put("created_at", rec.createdAt)
        .put("generation", rec.generation)

    private fun decodeRejectedQuery(json: JSONObject): RejectedResearchQuery = RejectedResearchQuery(
        originalQuery = json.getString("original_query"),
        normalizedQuery = json.getString("normalized_query"),
        canonicalFingerprint = json.getString("canonical_fingerprint"),
        taskId = json.getString("task_id"),
        reasonCode = json.getString("reason_code"),
        reasonDetail = json.getString("reason_detail"),
        matchedWeakAnchors = json.optJSONArray("matched_weak_anchors").toStringList(),
        createdAt = json.optLong("created_at"),
        generation = json.optInt("generation"),
    )

    private inline fun <reified T : Enum<T>> JSONObject.optEnum(name: String, fallback: T): T =
        runCatching { enumValueOf<T>(optString(name)) }.getOrDefault(fallback)

    private fun JSONObject.optNullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (!has(name) || isNull(name)) null else optInt(name)

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (!has(name) || isNull(name)) null else optLong(name)

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (!has(name) || isNull(name)) null else optDouble(name)

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun <T> JSONArray?.decodeList(decoder: (JSONObject) -> T): List<T> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.let { item -> runCatching { decoder(item) }.getOrNull()?.let(::add) }
            }
        }
    }

    private fun String?.isTerminalCredentialMessage(): Boolean {
        val normalized = this.orEmpty().lowercase()
        return "credential" in normalized && ("invalid" in normalized || "forbidden" in normalized || "unavailable" in normalized)
    }

    companion object {
        val readCount = AtomicLong(0)
        val writeCount = AtomicLong(0)

        private const val PREFERENCES_NAME = "openassistant_agent_store"
        const val KEY_SNAPSHOT = "agent_snapshot_v1"
        const val KEY_REVISION = "agent_store_revision_v1"
        private const val KEY_SELECTED_GOAL = "agent_selected_goal_v2"
        private const val KEY_MIGRATED_V2 = "agent_store_migrated_v2"
        private const val KEY_PENDING_DRAFT = "agent_pending_draft_v1"
        private const val GOALS_DIRECTORY_NAME = "agent_runtime_v2/goals"
        private const val GOAL_FILE_SUFFIX = ".goal.json"
        private const val ATOMIC_BACKUP_SUFFIX = ".bak"
        private const val LEGACY_RECOVERY_FILE_NAME = "legacy_snapshot_recovery.txt"
        private const val CORRUPT_RECOVERY_SUFFIX = ".corrupt-recovery.txt"
        private const val STORAGE_VERSION = 10
        private val STORE_LOCK = Any()

        fun isTaskBoundOperation(operation: String): Boolean {
            val typedOp = MissionOperation.fromName(operation) ?: return false
            return typedOp.taskBound
        }
    }
}
