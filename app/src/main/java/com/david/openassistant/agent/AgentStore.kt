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
    data class ExchangeMissing(val message: String) : TransitionOutcomeResult()
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
    object GoalMissing : LeaseAcquisitionResult()
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
        val fileLength: Long,
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

    fun updateProviderTransportStage(
        goalId: String,
        exchangeId: String,
        stage: ProviderTransportStage,
        certainty: ProviderDeliveryCertainty? = null
    ): Boolean = synchronized(STORE_LOCK) {
        migrateLegacyIfNeededLocked()
        updateGoalInternalLocked(goalId) { current ->
            val attempt = current.requestAttempts.firstOrNull { it.exchangeId == exchangeId }
                ?: return@updateGoalInternalLocked current
            
            if (stage.ordinal <= attempt.transportStage.ordinal && stage != ProviderTransportStage.TERMINAL) {
                return@updateGoalInternalLocked current
            }
            
            val updatedAttempt = attempt.copy(
                transportStage = stage,
                deliveryCertainty = certainty ?: attempt.deliveryCertainty
            )
            current.copy(
                requestAttempts = current.requestAttempts.map { if (it.exchangeId == exchangeId) updatedAttempt else it }
            )
        } != null
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
                isOrphan -> "Reclaimed an orphaned lease from dead process session '${existing.ownerProcessSessionId}'."
                else -> "Acquired lease."
            }

            val updatedGoal = goal.copy(
                status = if (goal.status == AgentGoalStatus.QUEUED) AgentGoalStatus.RUNNING else goal.status,
                leaseGeneration = newGeneration,
                executionLease = newLease,
                events = appendEvent(goal.events, reason),
                updatedAt = now
            )
            
            return try {
                val reloaded = writeGoalLocked(updatedGoal)
                val reloadedLease = reloaded.executionLease ?: throw IllegalStateException("executionLease is lost in durable storage for goal ${reloaded.id}.")
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
                throw error
            }
        } else {
            return LeaseAcquisitionResult.LiveOwnerPresent
        }
    }

    private fun validateTicketInternalLocked(ticket: AgentOwnershipTicket): TicketValidationResult {
        val current = loadSnapshotFromFilesLocked()
        val goal = current.goals.firstOrNull { it.id == ticket.goalId } ?: return TicketValidationResult.GoalMissing
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
            true
        } catch (_: Throwable) {
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
        
        if (attempt.goalId != goalId || context.goalId != goalId) return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState
        if (attempt.exchangeOutcome != ExchangeOutcome.ACTIVE) return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState
        if (goal.status !in setOf(AgentGoalStatus.PLANNING, AgentGoalStatus.RUNNING, AgentGoalStatus.VERIFYING)) return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState

        val lease = goal.executionLease ?: return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState
        val now = System.currentTimeMillis()
        if (AgentLeasePolicy.isStale(lease, now)) return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState
        if (context.executionGeneration != lease.generation || attempt.executionGeneration != lease.generation) {
            return@synchronized CreateAttemptResult.InvalidGeneration(expected = lease.generation, actual = attempt.executionGeneration)
        }
        if (context.workerId != lease.workerId) return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState
        if (context.attemptId != lease.attemptId) return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState

        val isTaskBound = context.operation.taskBound
        if (isTaskBound) {
            if (context.taskId == null || lease.taskId != context.taskId || attempt.taskId != context.taskId) {
                return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState
            }
        } else {
            if (context.taskId != null) return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState
        }

        if (goal.requestAttempts.any { it.exchangeId == attempt.exchangeId }) return@synchronized CreateAttemptResult.DuplicateExchange

        var authorizationsToKeep = goal.retryAuthorizations
        if (attempt.wireAttemptOrdinal > 1) {
            val validAuth = goal.retryAuthorizations.firstOrNull { 
                it.logicalRequestId == attempt.logicalRequestId && 
                it.attemptOrdinal == attempt.wireAttemptOrdinal &&
                it.executionGeneration == attempt.executionGeneration 
            }
            if (validAuth == null) return@synchronized CreateAttemptResult.UnauthorizedRetry
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
        val existingAttempt = goal.requestAttempts.firstOrNull { it.exchangeId == exchangeId } 
            ?: return@synchronized TransitionOutcomeResult.ExchangeMissing("Exchange $exchangeId not found in goal $goalId")

        if (existingAttempt.exchangeOutcome != ExchangeOutcome.ACTIVE) return@synchronized TransitionOutcomeResult.AlreadyTerminal(existingAttempt.exchangeOutcome)
        if (context.goalId != goalId || existingAttempt.goalId != goalId) return@synchronized TransitionOutcomeResult.InvalidLeaseOrGoalState

        val lease = goal.executionLease ?: return@synchronized TransitionOutcomeResult.InvalidLeaseOrGoalState
        if (context.workerId != lease.workerId) return@synchronized TransitionOutcomeResult.InvalidLeaseOrGoalState
        if (context.attemptId != lease.attemptId) return@synchronized TransitionOutcomeResult.InvalidLeaseOrGoalState
        if (context.executionGeneration != lease.generation || existingAttempt.executionGeneration != lease.generation) {
            return@synchronized TransitionOutcomeResult.InvalidGeneration(expected = lease.generation, actual = existingAttempt.executionGeneration)
        }

        if (context.operation.taskBound) {
            if (context.taskId == null || lease.taskId != context.taskId || existingAttempt.taskId != context.taskId) {
                return@synchronized TransitionOutcomeResult.InvalidLeaseOrGoalState
            }
        } else {
            if (context.taskId != null || existingAttempt.taskId != null) return@synchronized TransitionOutcomeResult.InvalidLeaseOrGoalState
        }

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

        val writeResult = runCatching { writeGoalLocked(updatedGoal) }
        if (writeResult.isFailure) return@synchronized TransitionOutcomeResult.StorageFailure(writeResult.exceptionOrNull()!!)
        TransitionOutcomeResult.Updated(updatedAttempt)
    }

    fun commitTaskResultAtomic(
        ticket: TaskExecutionTicket,
        transform: (AgentGoal) -> AgentGoal
    ): AgentSnapshot = synchronized(STORE_LOCK) {
        val validation = validateTicketInternalLocked(ticket)
        if (validation !is TicketValidationResult.Valid) return@synchronized loadSnapshotFromFilesLocked()
        
        migrateLegacyIfNeededLocked()
        updateGoalInternalLocked(ticket.goalId, transform)
    }

    fun applyUsageOnceAtomic(
        ticket: AgentOwnershipTicket,
        accountingKey: String,
        tokenDelta: Int?,
        costUsd: Double?,
    ): AgentSnapshot = synchronized(STORE_LOCK) {
        if (validateTicketInternalLocked(ticket) !is TicketValidationResult.Valid) return@synchronized loadSnapshotFromFilesLocked()
        
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
        if (statusChanged) AgentStateMachine.requireTransition(original.status, transformed.status)
        
        val updatedGoal = transformed.copy(updatedAt = System.currentTimeMillis())
        writeGoalLocked(updatedGoal)
        
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

    fun savePendingDraft(draft: ResearchDraft?): Unit = synchronized(STORE_LOCK) {
        val storePreferences = preferences ?: return@synchronized
        storePreferences.edit(commit = true) {
            putString(KEY_PENDING_DRAFT, draft?.let(::encodeDraft)?.toString())
        }
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
            .mapNotNull { file ->
                try {
                    readGoalLocked(file)
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
        val selected = preferences?.getString(KEY_SELECTED_GOAL, null)
            ?.takeIf { selectedId -> goals.any { it.id == selectedId } }
            ?: goals.maxByOrNull { it.updatedAt }?.id
        return AgentSnapshot(goals, selected, quarantined)
    }

    private fun discoverGoalFilesLocked(): List<File> {
        return goalsDirectory.listFiles()
            ?.filter { it.isFile && (it.name.endsWith(GOAL_FILE_SUFFIX) || it.name.endsWith(GOAL_FILE_SUFFIX + ATOMIC_BACKUP_SUFFIX)) }
            ?.map { if (it.name.endsWith(ATOMIC_BACKUP_SUFFIX)) File(it.path.removeSuffix(ATOMIC_BACKUP_SUFFIX)) else it }
            ?.distinctBy { it.absolutePath }
            .orEmpty()
    }

    private fun saveSnapshotLocked(snapshot: AgentSnapshot) {
        goalsDirectory.mkdirs()
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
        val legacyRaw = prefs.getString(KEY_SNAPSHOT, null)
        if (legacyRaw?.trimStart()?.startsWith("{") == true) {
            val legacySnapshot = runCatching {
                decodeSnapshot(requireOpenRouterObject(legacyRaw, "Legacy agent snapshot"))
            }.getOrElse { error ->
                preserveLegacySnapshotLocked(legacyRaw, error)
                AgentSnapshot()
            }
            legacySnapshot.goals.forEach(::writeGoalLocked)
            prefs.edit(commit = true) {
                putBoolean(KEY_MIGRATED_V2, true)
                putString(KEY_SELECTED_GOAL, legacySnapshot.selectedGoalId)
                putString(KEY_SNAPSHOT, newRevisionSignal())
            }
        } else {
            prefs.edit(commit = true) {
                putBoolean(KEY_MIGRATED_V2, true)
                putString(KEY_SNAPSHOT, newRevisionSignal())
            }
        }
    }

    private fun preserveLegacySnapshotLocked(raw: String, error: Throwable) {
        goalsDirectory.mkdirs()
        val recoveryFile = AtomicFile(File(goalsDirectory, LEGACY_RECOVERY_FILE_NAME))
        var output: FileOutputStream? = null
        try {
            output = recoveryFile.startWrite()
            output.write("Migration parser: ${error::class.java.name}\nMessage: ${error.message.orEmpty()}".toByteArray(StandardCharsets.UTF_8))
            recoveryFile.finishWrite(output)
        } catch (writeError: Throwable) {
            output?.let(recoveryFile::failWrite)
            error.addSuppressed(writeError)
        }
    }

    private fun preserveCorruptGoalLocked(file: File, error: Throwable): File? {
        val recoveryTarget = File(file.path + CORRUPT_RECOVERY_SUFFIX)
        val recoveryFile = AtomicFile(recoveryTarget)
        var output: FileOutputStream? = null
        try {
            output = recoveryFile.startWrite()
            output.write("File: ${file.name}\nParser: ${error::class.java.name}\nMessage: ${error.message.orEmpty()}".toByteArray(StandardCharsets.UTF_8))
            recoveryFile.finishWrite(output)
            return recoveryTarget
        } catch (writeError: Throwable) {
            output?.let(recoveryFile::failWrite)
            error.addSuppressed(writeError)
            return null
        }
    }

    private fun writeSelectionAndSignalLocked(selectedGoalId: String?) {
        val prefs = preferences ?: return
        val currentRevision = prefs.getLong(KEY_REVISION, 0L)
        prefs.edit(commit = true) {
            putString(KEY_SELECTED_GOAL, selectedGoalId)
            putLong(KEY_REVISION, currentRevision + 1)
            putString(KEY_SNAPSHOT, newRevisionSignal())
        }
    }

    private fun writeGoalLocked(goal: AgentGoal): AgentGoal {
        writeCount.incrementAndGet()
        validateGoalIdentityForWrite(goal)
        goalsDirectory.mkdirs()
        val target = goalFileLocked(goal.id)
        val encoded = encodeGoal(goal).toString()
        val bytes = encoded.toByteArray(StandardCharsets.UTF_8)
        val temp = File(target.parentFile, target.name + ".tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) target.writeBytes(bytes)

        val readBack = readGoalLocked(target)
        goalCache[target.name] = CachedGoal(readBack, target.lastModified(), target.length())
        return readBack
    }

    private fun validateGoalIdentityForWrite(goal: AgentGoal) {
        require(goal.id.isNotBlank()) { "Goal ID must not be blank." }
        require(goal.conversationId.isNotBlank()) { "Goal conversation ID must not be blank." }
    }

    private fun readGoalLocked(file: File): AgentGoal {
        val raw = file.readText(StandardCharsets.UTF_8)
        return decodeGoal(requireOpenRouterObject(raw, "Stored autonomous goal"))
    }

    private fun deleteGoalFilesLocked(goalId: String) {
        val file = goalFileLocked(goalId)
        if (file.exists()) file.delete()
        val backup = File(file.path + ATOMIC_BACKUP_SUFFIX)
        if (backup.exists()) backup.delete()
    }

    private fun goalFileLocked(goalId: String): File {
        val safeId = goalId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(96)
        return File(goalsDirectory, safeId + GOAL_FILE_SUFFIX)
    }

    private fun newRevisionSignal(): String = "v2:${System.currentTimeMillis()}:${UUID.randomUUID()}"

    private fun encodeDraft(draft: ResearchDraft): JSONObject = JSONObject()
        .put("id", draft.id)
        .put("conversation_id", draft.conversationId)
        .put("question", draft.question)
        .put("updated_at", draft.updatedAt)

    private fun decodeDraft(json: JSONObject): ResearchDraft = ResearchDraft(
        id = json.getString("id"),
        conversationId = json.getString("conversation_id"),
        question = json.optString("question"),
        updatedAt = json.optLong("updated_at", System.currentTimeMillis()),
    )

    private fun decodeSnapshot(root: JSONObject): AgentSnapshot {
        val goalsArray = root.optJSONArray("goals") ?: JSONArray()
        val goals = (0 until goalsArray.length()).mapNotNull { i ->
            runCatching { decodeGoal(goalsArray.getJSONObject(i)) }.getOrNull()
        }
        return AgentSnapshot(goals, root.optString("selected_goal_id"))
    }

    private fun encodeGoal(goal: AgentGoal): JSONObject = JSONObject().apply {
        put("storage_version", STORAGE_VERSION)
        put("id", goal.id)
        put("conversation_id", goal.conversationId)
        put("user_request", goal.userRequest)
        put("title", goal.title)
        put("objective", goal.objective)
        put("final_output_description", goal.finalOutputDescription)
        put("status", goal.status.name)
        put("planner_model_id", goal.plannerModelId)
        put("execution_model_id", goal.executionModelId)
        put("routing_stage", goal.routingStage.name)
        put("routing_policy_provenance", goal.routingPolicyProvenance.name)
        put("free_only", goal.freeOnly)
        put("tasks", JSONArray().apply { goal.tasks.forEach { put(encodeTask(it)) } })
        put("acceptance_criteria", JSONArray().apply { goal.acceptanceCriteria.forEach { put(encodeCriterion(it)) } })
        put("attempts", JSONArray().apply { goal.attempts.forEach { put(encodeAttempt(it)) } })
        put("evidence", JSONArray().apply { goal.evidence.forEach { put(encodeEvidence(it)) } })
        put("claims", JSONArray().apply { goal.claims.forEach { put(encodeClaim(it)) } })
        put("evidence_links", JSONArray().apply { goal.evidenceLinks.forEach { put(encodeEvidenceLink(it)) } })
        put("checkpoints", JSONArray().apply { goal.checkpoints.forEach { put(encodeCheckpoint(it)) } })
        put("events", JSONArray().apply { goal.events.forEach { put(encodeEvent(it)) } })
        put("model_cooldowns", JSONObject(goal.modelCooldowns))
        put("created_at", goal.createdAt)
        put("updated_at", goal.updatedAt)
        put("total_tokens", goal.totalTokens)
        put("total_cost_usd_micros", goal.totalCostUsdMicros)
        put("verification_round", goal.verificationRound)
        put("verification_correction_streak", goal.verificationCorrectionStreak)
        put("result", goal.result ?: JSONObject.NULL)
        put("error", goal.error ?: JSONObject.NULL)
        put("request_attempts", JSONArray().apply { goal.requestAttempts.forEach { put(encodeRequestAttempt(it)) } })
        put("retry_authorizations", JSONArray().apply { goal.retryAuthorizations.forEach { put(encodeRetryAuthorization(it)) } })
        put("idempotency_records", JSONArray().apply { goal.idempotencyRecords.forEach { put(encodeIdempotencyRecord(it)) } })
        put("route_fingerprints", JSONArray().apply { goal.routeFingerprints.forEach { put(encodeRouteFingerprint(it)) } })
        put("lease_generation", goal.leaseGeneration)
        put("execution_lease", goal.executionLease?.let(::encodeLease) ?: JSONObject.NULL)
        put("last_resume_reason", goal.lastResumeReason?.name ?: JSONObject.NULL)
        put("research_cycles", JSONArray().apply { goal.researchCycles.forEach { put(encodeResearchCycle(it)) } })
        put("objective_revisions", JSONArray().apply { goal.objectiveRevisions.forEach { put(encodeObjectiveRevision(it)) } })
        put("active_research_cycle_id", goal.activeResearchCycleId ?: JSONObject.NULL)
        put("recovery_plans", JSONArray().apply { goal.recoveryPlans.forEach { put(encodeRecoveryPlan(it)) } })
        put("active_recovery_plan_id", goal.activeRecoveryPlanId ?: JSONObject.NULL)
    }

    private fun decodeGoal(json: JSONObject): AgentGoal {
        val storedVersion = json.optInt("storage_version", 1)
        val storedConversationId = json.getString("conversation_id")
        val storedUserRequest = json.getString("user_request")
        val storedObjective = json.getString("objective")
        
        var finalTasks = json.optJSONArray("tasks").decodeList(::decodeTask)
        val storedStatus = json.optEnum("status", AgentGoalStatus.QUEUED)
        val storedError = json.optNullableString("error")
        
        val recoverLegacyFailure = storedVersion < STORAGE_VERSION && storedStatus == AgentGoalStatus.FAILED && !storedError.isTerminalCredentialMessage()
        val restoredStatus = when {
            recoverLegacyFailure && finalTasks.isEmpty() -> AgentGoalStatus.PLANNING
            recoverLegacyFailure -> AgentGoalStatus.QUEUED
            else -> storedStatus
        }

        var researchCycles = json.optJSONArray("research_cycles").decodeList(::decodeResearchCycle)
        var objectiveRevisions = json.optJSONArray("objective_revisions").decodeList(::decodeObjectiveRevision)
        var activeCycleId = json.optNullableString("active_research_cycle_id")

        if (researchCycles.isEmpty() && storedConversationId.isNotBlank()) {
            val baselineCycleId = "baseline-${json.getString("id").take(8)}"
            val baselineRevisionId = "root-revision-${json.getString("id").take(8)}"
            objectiveRevisions = listOf(ObjectiveRevision(baselineRevisionId, 0, null, "legacy", storedObjective, revisionFingerprint = "baseline"))
            researchCycles = listOf(ResearchCycle(baselineCycleId, 0, ResearchCycleStatus.ACTIVE, objectiveRevisionId = baselineRevisionId))
            activeCycleId = baselineCycleId
            finalTasks = finalTasks.map { it.copy(cycleId = baselineCycleId) }
        }

        return AgentGoal(
            id = json.getString("id"),
            conversationId = storedConversationId,
            userRequest = storedUserRequest,
            title = json.getString("title"),
            objective = storedObjective,
            finalOutputDescription = json.optString("final_output_description"),
            status = restoredStatus,
            plannerModelId = json.optString("planner_model_id"),
            executionModelId = json.optString("execution_model_id"),
            routingStage = json.optEnum("routing_stage", AgentRoutingStage.AUTO_BETA),
            routingPolicyProvenance = json.optEnum("routing_policy_provenance", RoutingPolicyProvenance.EXPLICIT_USER_SELECTION),
            freeOnly = json.optBoolean("free_only"),
            tasks = finalTasks,
            acceptanceCriteria = json.optJSONArray("acceptance_criteria").decodeList(::decodeCriterion),
            attempts = json.optJSONArray("attempts").decodeList(::decodeAttempt),
            evidence = json.optJSONArray("evidence").decodeList(::decodeEvidence),
            claims = json.optJSONArray("claims").decodeList(::decodeClaim),
            evidenceLinks = json.optJSONArray("evidence_links").decodeList(::decodeEvidenceLink),
            checkpoints = json.optJSONArray("checkpoints").decodeList(::decodeCheckpoint),
            events = json.optJSONArray("events").decodeList(::decodeEvent),
            modelCooldowns = json.optJSONObject("model_cooldowns")?.let { c -> buildMap { c.keys().forEach { k -> put(k, c.getLong(k)) } } } ?: emptyMap(),
            createdAt = json.optLong("created_at", System.currentTimeMillis()),
            updatedAt = json.optLong("updated_at", System.currentTimeMillis()),
            totalTokens = json.optInt("total_tokens"),
            totalCostUsdMicros = json.optLong("total_cost_usd_micros"),
            verificationRound = json.optInt("verification_round"),
            verificationCorrectionStreak = json.optInt("verification_correction_streak"),
            result = json.optNullableString("result"),
            error = if (recoverLegacyFailure) null else storedError?.let { normalizeAgentFailureMessage(it, it) },
            requestAttempts = json.optJSONArray("request_attempts").decodeList(::decodeRequestAttempt),
            retryAuthorizations = json.optJSONArray("retry_authorizations").decodeList(::decodeRetryAuthorization),
            idempotencyRecords = json.optJSONArray("idempotency_records").decodeList(::decodeIdempotencyRecord),
            routeFingerprints = json.optJSONArray("route_fingerprints").decodeList(::decodeRouteFingerprint),
            leaseGeneration = json.optInt("lease_generation"),
            executionLease = json.optJSONObject("execution_lease")?.let(::decodeLease),
            lastResumeReason = json.optNullableString("last_resume_reason")?.let { runCatching { ResumeReason.valueOf(it) }.getOrNull() },
            researchCycles = researchCycles,
            objectiveRevisions = objectiveRevisions,
            activeResearchCycleId = activeCycleId,
            recoveryPlans = json.optJSONArray("recovery_plans").decodeList(::decodeRecoveryPlan),
            activeRecoveryPlanId = json.optNullableString("active_recovery_plan_id"),
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
        .put("task_generation", task.taskGeneration)
        .put("last_request_fingerprint", task.lastRequestFingerprint ?: JSONObject.NULL)
        .put("progress_score", task.progressScore)
        .put("finished_at", task.finishedAt ?: JSONObject.NULL)
        .put("cycle_id", task.cycleId ?: JSONObject.NULL)
        .put("root_objective_fingerprint", task.rootObjectiveFingerprint ?: JSONObject.NULL)
        .put("operational_objective_fingerprint", task.operationalObjectiveFingerprint ?: JSONObject.NULL)
        .put("accepted_evidence_fingerprint", task.acceptedEvidenceFingerprint ?: JSONObject.NULL)
        .put("unresolved_gap_fingerprint", task.unresolvedGapFingerprint ?: JSONObject.NULL)
        .put("strategy_fingerprint", task.strategyFingerprint ?: JSONObject.NULL)
        .put("query_portfolio_fingerprint", task.queryPortfolioFingerprint ?: JSONObject.NULL)

    private fun decodeTask(json: JSONObject): AgentTask = AgentTask(
        id = json.getString("id"),
        order = json.optInt("order"),
        title = json.optString("title"),
        instructions = json.optString("instructions"),
        capability = json.optEnum("capability", AgentCapability.REASON),
        dependsOn = json.optJSONArray("depends_on").toStringList(),
        status = json.optEnum("status", AgentTaskStatus.PLANNED),
        attemptCount = json.optInt("attempt_count"),
        taskGeneration = json.optInt("task_generation"),
        lastRequestFingerprint = json.optNullableString("last_request_fingerprint"),
        progressScore = json.optDouble("progress_score"),
        finishedAt = json.optLongOrNull("finished_at"),
        cycleId = json.optNullableString("cycle_id"),
        rootObjectiveFingerprint = json.optNullableString("root_objective_fingerprint"),
        operationalObjectiveFingerprint = json.optNullableString("operational_objective_fingerprint"),
        acceptedEvidenceFingerprint = json.optNullableString("accepted_evidence_fingerprint"),
        unresolvedGapFingerprint = json.optNullableString("unresolved_gap_fingerprint"),
        strategyFingerprint = json.optNullableString("strategy_fingerprint"),
        queryPortfolioFingerprint = json.optNullableString("query_portfolio_fingerprint"),
    )

    private fun encodeEvidence(evidence: AgentEvidence): JSONObject = JSONObject()
        .put("id", evidence.id)
        .put("kind", evidence.kind.name)
        .put("title", evidence.title)
        .put("summary", evidence.summary)
        .put("content", evidence.content)
        .put("created_at", evidence.createdAt)
        .put("cycle_id", evidence.cycleId ?: JSONObject.NULL)

    private fun decodeEvidence(json: JSONObject): AgentEvidence = AgentEvidence(
        id = json.getString("id"),
        kind = AgentEvidenceKind.valueOf(json.getString("kind")),
        title = json.getString("title"),
        summary = json.optString("summary"),
        content = json.getString("content"),
        createdAt = json.getLong("created_at"),
        cycleId = json.optNullableString("cycle_id"),
    )

    private fun encodeResearchCycle(cycle: ResearchCycle): JSONObject = JSONObject()
        .put("id", cycle.id)
        .put("ordinal", cycle.ordinal)
        .put("status", cycle.status.name)
        .put("objective_revision_id", cycle.objectiveRevisionId ?: JSONObject.NULL)
        .put("learning_summary", cycle.learningSummary?.let(::encodeLearningSummary) ?: JSONObject.NULL)

    private fun decodeResearchCycle(json: JSONObject): ResearchCycle = ResearchCycle(
        id = json.getString("id"),
        ordinal = json.getInt("ordinal"),
        status = ResearchCycleStatus.valueOf(json.getString("status")),
        objectiveRevisionId = json.optNullableString("objective_revision_id"),
        learningSummary = json.optJSONObject("learning_summary")?.let(::decodeLearningSummary)
    )

    private fun encodeLearningSummary(summary: ResearchCycleLearningSummary): JSONObject = JSONObject()
        .put("established_findings", JSONArray(summary.establishedFindings))
        .put("remaining_unresolved_gaps", JSONArray(summary.remainingUnresolvedGaps))
        .put("carried_forward_evidence_ids", JSONArray(summary.carriedForwardEvidenceIds))

    private fun decodeLearningSummary(json: JSONObject): ResearchCycleLearningSummary = ResearchCycleLearningSummary(
        establishedFindings = json.optJSONArray("established_findings").toStringList(),
        remainingUnresolvedGaps = json.optJSONArray("remaining_unresolved_gaps").toStringList(),
        carriedForwardEvidenceIds = json.optJSONArray("carried_forward_evidence_ids").toStringList()
    )

    private fun encodeObjectiveRevision(revision: ObjectiveRevision): JSONObject = JSONObject()
        .put("id", revision.id)
        .put("ordinal", revision.ordinal)
        .put("operational_objective", revision.operationalObjective)
        .put("revision_fingerprint", revision.revisionFingerprint)

    private fun decodeObjectiveRevision(json: JSONObject): ObjectiveRevision = ObjectiveRevision(
        id = json.getString("id"),
        ordinal = json.getInt("ordinal"),
        rootObjectiveFingerprint = json.optString("root_objective_fingerprint", "legacy"),
        operationalObjective = json.getString("operational_objective"),
        revisionFingerprint = json.getString("revision_fingerprint")
    )

    private fun encodeRecoveryPlan(plan: RecoveryPlan): JSONObject = JSONObject()
        .put("id", plan.id)
        .put("kind", plan.kind.name)
        .put("status", plan.status.name)
        .put("tactic", plan.tactic.name)
        .put("diagnosis", plan.diagnosis.name)
        .put("input_fingerprint", plan.inputFingerprint)
        .put("durable_proposal", plan.durableProposal?.let(::encodeRecoveryProposal) ?: JSONObject.NULL)

    private fun decodeRecoveryPlan(json: JSONObject): RecoveryPlan = RecoveryPlan(
        id = json.getString("id"),
        kind = RecoveryKind.valueOf(json.getString("kind")),
        cycleId = json.optString("cycle_id", "baseline"),
        taskId = json.optNullableString("task_id"),
        diagnosis = runCatching { ExecutionStallDiagnosis.valueOf(json.optString("diagnosis")) }.getOrDefault(ExecutionStallDiagnosis.NONE),
        tactic = EscalationTactic.valueOf(json.getString("tactic")),
        inputFingerprint = json.getString("input_fingerprint"),
        status = RecoveryPlanStatus.valueOf(json.getString("status")),
        durableProposal = json.optJSONObject("durable_proposal")?.let(::decodeRecoveryProposal)
    )

    private fun encodeRecoveryProposal(proposal: RecoveryProposal): JSONObject = JSONObject()
        .put("rationale", proposal.rationale)
        .put("revised_objective", proposal.revisedObjective ?: JSONObject.NULL)
        .put("strategy_json", proposal.strategyJson ?: JSONObject.NULL)
        .put("query_portfolio", JSONArray(proposal.queryPortfolio))
        .put("tactic", proposal.tactic.name)

    private fun decodeRecoveryProposal(json: JSONObject): RecoveryProposal = RecoveryProposal(
        rationale = json.getString("rationale"),
        revisedObjective = json.optNullableString("revised_objective"),
        strategyJson = json.optNullableString("strategy_json"),
        queryPortfolio = json.optJSONArray("query_portfolio").toStringList(),
        tactic = EscalationTactic.valueOf(json.optString("tactic", EscalationTactic.NONE.name))
    )

    private fun encodeAttempt(attempt: AgentAttempt): JSONObject = JSONObject().put("id", attempt.id).put("status", attempt.status.name)
    private fun decodeAttempt(json: JSONObject): AgentAttempt = AgentAttempt(id = json.getString("id"), taskId = null, status = AgentAttemptStatus.valueOf(json.getString("status")), startedAt = 0, modelId = "m")
    private fun encodeClaim(claim: AgentClaim): JSONObject = JSONObject().put("id", claim.id).put("text", claim.text)
    private fun decodeClaim(json: JSONObject): AgentClaim = AgentClaim(id = json.getString("id"), taskId = "t", text = json.getString("text"), type = AgentClaimType.FACT, confidence = 1.0, support = AgentClaimSupport.SUPPORTED)
    private fun encodeEvidenceLink(link: AgentEvidenceLink): JSONObject = JSONObject().put("id", link.id)
    private fun decodeEvidenceLink(json: JSONObject): AgentEvidenceLink = AgentEvidenceLink(id = json.getString("id"), claimId = "c", evidenceId = "e", relation = AgentEvidenceRelation.SUPPORTS)
    private fun encodeCheckpoint(checkpoint: AgentCheckpoint): JSONObject = JSONObject().put("id", checkpoint.id)
    private fun decodeCheckpoint(json: JSONObject): AgentCheckpoint = AgentCheckpoint(id = json.getString("id"), sequence = 1, completedTaskIds = emptyList(), progressScore = 1.0, note = "n")
    private fun encodeEvent(event: AgentEvent): JSONObject = JSONObject().put("message", event.message)
    private fun decodeEvent(json: JSONObject): AgentEvent = AgentEvent(message = json.getString("message"))
    private fun encodeLease(lease: AgentExecutionLease): JSONObject = JSONObject().put("worker_id", lease.workerId)
    private fun decodeLease(json: JSONObject): AgentExecutionLease = AgentExecutionLease(workerId = json.getString("worker_id"), ownerProcessSessionId = "s", taskId = "t", attemptId = "a", generation = 1, acquiredAt = 0, heartbeatAt = 0)
    private fun encodeRequestAttempt(attempt: ProviderRequestAttempt): JSONObject = JSONObject().put("exchange_id", attempt.exchangeId)
    private fun decodeRequestAttempt(json: JSONObject): ProviderRequestAttempt = ProviderRequestAttempt(exchangeId = json.getString("exchange_id"), parentOperationId = "p", goalId = "g", executionGeneration = 1, requestedModel = "m", payloadFingerprint = "f")
    private fun encodeRetryAuthorization(auth: ProviderRetryAuthorization): JSONObject = JSONObject().put("logical_request_id", auth.logicalRequestId)
    private fun decodeRetryAuthorization(json: JSONObject): ProviderRetryAuthorization = ProviderRetryAuthorization(logicalRequestId = json.getString("logical_request_id"), payloadFingerprint = "f", executionGeneration = 1, previousExchangeId = null, failureClass = "f", deliveryCertainty = ProviderDeliveryCertainty.NOT_SENT, attemptOrdinal = 1)
    private fun encodeIdempotencyRecord(rec: IdempotencyRecord): JSONObject = JSONObject().put("key", rec.key)
    private fun decodeIdempotencyRecord(json: JSONObject): IdempotencyRecord = IdempotencyRecord(key = json.getString("key"), effectType = IdempotencyEffectType.PROVIDER_ACCOUNTING, state = IdempotencyState.COMMITTED, claimOwner = "w", claimGeneration = 1, claimedAt = 0)
    private fun encodeRouteFingerprint(fp: RouteFailureFingerprint): JSONObject = JSONObject().put("goal_id", fp.goalId)
    private fun decodeRouteFingerprint(json: JSONObject): RouteFailureFingerprint = RouteFailureFingerprint(goalId = json.getString("goal_id"), operationId = "o", canonicalPayloadHash = "h", role = AgentTaskRole.PRIMARY_REASONING, route = "r", failureClass = "f")
    private fun encodeCriterion(c: AgentAcceptanceCriterion): JSONObject = JSONObject().put("id", c.id)
    private fun decodeCriterion(json: JSONObject): AgentAcceptanceCriterion = AgentAcceptanceCriterion(id = json.getString("id"), description = "d")

    private inline fun <reified T : Enum<T>> JSONObject.optEnum(name: String, fallback: T): T =
        runCatching { enumValueOf<T>(optString(name)) }.getOrDefault(fallback)

    private fun JSONObject.optNullableString(name: String): String? =
        if (!has(name) || isNull(name)) null else optString(name).takeIf { it.isNotBlank() && it != "null" }

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (!has(name) || isNull(name)) null else optLong(name)

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    private fun <T : Any> JSONArray?.decodeList(decoder: (JSONObject) -> T?): List<T> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                val item = optJSONObject(index)
                if (item != null) runCatching { decoder(item) }.getOrNull()?.let(::add)
            }
        }
    }

    private fun String?.isTerminalCredentialMessage(): Boolean {
        val normalized = this.orEmpty().lowercase()
        return "credential" in normalized && ("invalid" in normalized || "forbidden" in normalized || "unavailable" in normalized)
    }

    private fun normalizeAgentFailureMessage(msg: String, fallback: String): String =
        if (msg.isBlank()) fallback else msg

    companion object {
        val readCount = AtomicLong(0)
        val writeCount = AtomicLong(0)

        private const val PREFERENCES_NAME = "openassistant_agent_store"
        const val KEY_REVISION = "agent_store_revision_v1"
        const val KEY_SNAPSHOT = "agent_snapshot_v1"
        private const val KEY_SELECTED_GOAL = "agent_selected_goal_v2"
        private const val KEY_MIGRATED_V2 = "agent_store_migrated_v2"
        private const val KEY_PENDING_DRAFT = "agent_pending_draft_v1"
        private const val GOALS_DIRECTORY_NAME = "agent_runtime_v2/goals"
        private const val GOAL_FILE_SUFFIX = ".goal.json"
        private const val ATOMIC_BACKUP_SUFFIX = ".bak"
        private const val LEGACY_RECOVERY_FILE_NAME = "legacy_snapshot_recovery.txt"
        private const val CORRUPT_RECOVERY_SUFFIX = ".corrupt-recovery.txt"
        private const val STORAGE_VERSION = 12
        private val STORE_LOCK = Any()

        fun isTaskBoundOperation(operation: String): Boolean {
            val typedOp = MissionOperation.fromName(operation) ?: return false
            return typedOp.taskBound
        }
    }
}
