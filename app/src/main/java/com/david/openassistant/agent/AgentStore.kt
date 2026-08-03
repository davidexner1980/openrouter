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
        require(goal.userRequest.isNotBlank()) { "The original user request must not be blank." }
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

        val currentSessionId = getCurrentSessionId()
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
        val currentSessionId = getCurrentSessionId()
        
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

        val currentSessionId = getCurrentSessionId()

        return when {
            lease.workerId != ticket.workerId -> 
                TicketValidationResult.Mismatch("WORKER_MISMATCH", "workerId", ticket.workerId, lease.workerId)
            lease.ownerProcessSessionId != ticket.ownerProcessSessionId ->
                TicketValidationResult.Mismatch("PROCESS_SESSION_MISMATCH", "ownerProcessSessionId", ticket.ownerProcessSessionId, lease.ownerProcessSessionId)
            lease.ownerProcessSessionId != currentSessionId ->
                TicketValidationResult.Mismatch("SESSION_ID_MISMATCH", "ownerProcessSessionId", currentSessionId, lease.ownerProcessSessionId)
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
        
        if (attempt.goalId != goalId || context.goalId != goalId) {
            return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState
        }

        if (attempt.exchangeOutcome != ExchangeOutcome.ACTIVE) {
            return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState
        }

        if (goal.status !in setOf(AgentGoalStatus.PLANNING, AgentGoalStatus.RUNNING, AgentGoalStatus.VERIFYING)) {
            return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState
        }

        val lease = goal.executionLease ?: return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState

        val now = System.currentTimeMillis()
        if (AgentLeasePolicy.isStale(lease, now)) {
            return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState
        }

        if (context.executionGeneration != lease.generation || attempt.executionGeneration != lease.generation) {
            return@synchronized CreateAttemptResult.InvalidGeneration(expected = lease.generation, actual = attempt.executionGeneration)
        }

        if (context.workerId != lease.workerId) {
            return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState
        }

        if (context.attemptId != lease.attemptId) {
            return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState
        }

        val currentSessionId = getCurrentSessionId()
        if (lease.ownerProcessSessionId != currentSessionId) {
            return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState
        }

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

        if (goal.requestAttempts.any { it.exchangeId == attempt.exchangeId }) {
            return@synchronized CreateAttemptResult.DuplicateExchange
        }

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
            ?: return@synchronized TransitionOutcomeResult.ExchangeMissing("Exchange $exchangeId not found.")

        if (existingAttempt.exchangeOutcome != ExchangeOutcome.ACTIVE) {
            return@synchronized TransitionOutcomeResult.AlreadyTerminal(existingAttempt.exchangeOutcome)
        }

        if (context.goalId != goalId || existingAttempt.goalId != goalId) {
            return@synchronized TransitionOutcomeResult.InvalidLeaseOrGoalState
        }

        val lease = goal.executionLease ?: return@synchronized TransitionOutcomeResult.InvalidLeaseOrGoalState

        if (context.workerId != lease.workerId) {
            return@synchronized TransitionOutcomeResult.InvalidLeaseOrGoalState
        }

        if (context.attemptId != lease.attemptId) {
            return@synchronized TransitionOutcomeResult.InvalidLeaseOrGoalState
        }

        val currentSessionId = getCurrentSessionId()
        if (lease.ownerProcessSessionId != currentSessionId) {
            return@synchronized TransitionOutcomeResult.InvalidLeaseOrGoalState
        }

        if (context.executionGeneration != lease.generation || existingAttempt.executionGeneration != lease.generation) {
            return@synchronized TransitionOutcomeResult.InvalidGeneration(expected = lease.generation, actual = existingAttempt.executionGeneration)
        }

        if (context.operation.taskBound) {
            if (context.taskId == null || lease.taskId != context.taskId || existingAttempt.taskId != context.taskId) {
                return@synchronized TransitionOutcomeResult.InvalidLeaseOrGoalState
            }
        } else {
            if (context.taskId != null || existingAttempt.taskId != null) {
                return@synchronized TransitionOutcomeResult.InvalidLeaseOrGoalState
            }
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
        if (writeResult.isFailure) {
            return@synchronized TransitionOutcomeResult.StorageFailure(writeResult.exceptionOrNull()!!)
        }
        TransitionOutcomeResult.Updated(updatedAttempt)
    }

    fun commitTaskResultAtomic(
        ticket: TaskExecutionTicket,
        transform: (AgentGoal) -> AgentGoal
    ): AgentSnapshot = synchronized(STORE_LOCK) {
        val validation = validateTicketInternalLocked(ticket)
        if (validation !is TicketValidationResult.Valid) {
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
        
        if (original.status != transformed.status) {
            AgentStateMachine.requireTransition(original.status, transformed.status)
        }
        
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

    fun savePendingDraft(draft: ResearchDraft?) = synchronized(STORE_LOCK) {
        val storePreferences = preferences ?: return@synchronized
        val committed = storePreferences.edit()
            .putString(KEY_PENDING_DRAFT, draft?.let(::encodeDraft)?.toString())
            .commit()
        check(committed) { "Pending research draft could not be committed." }
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
            val stream = recoveryFile.startWrite()
            output = stream
            val diagnostic = buildString {
                appendLine("OpenAssistant legacy snapshot recovery")
                appendLine("Error: ${error::class.java.name}")
                appendLine("Message: ${error.message.orEmpty()}")
            }
            stream.write(diagnostic.toByteArray(StandardCharsets.UTF_8))
            recoveryFile.finishWrite(stream)
        } catch (writeError: Throwable) {
            output?.let(recoveryFile::failWrite)
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
                appendLine("Error: ${error::class.java.name}")
            }
            stream.write(diagnostic.toByteArray(StandardCharsets.UTF_8))
            recoveryFile.finishWrite(stream)
            return recoveryTarget
        } catch (writeError: Throwable) {
            output?.let(recoveryFile::failWrite)
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
        testWriterInjection?.write(goal)
        validateGoalIdentityForWrite(goal)
        writeCount.incrementAndGet()
        goalsDirectory.mkdirs()
        val target = goalFileLocked(goal.id)
        val atomicFile = AtomicFile(target)
        val encoded = encodeGoal(goal)
        val encodedStr = encoded.toString()
        
        val bytes = encodedStr.toByteArray(StandardCharsets.UTF_8)
        var output: FileOutputStream? = null
        try {
            output = atomicFile.startWrite()
            output.write(bytes)
            atomicFile.finishWrite(output)
        } catch (error: Throwable) {
            output?.let { atomicFile.failWrite(it) }
            throw error
        }

        val readBack = readGoalLocked(target)
        goalCache[target.name] = CachedGoal(readBack, target.lastModified(), target.length())
        return readBack
    }

    private fun validateGoalIdentityForWrite(goal: AgentGoal) {
        require(goal.id.isNotBlank()) { "Goal ID must not be blank." }
        require(goal.conversationId.isNotBlank()) { "Goal conversation ID must not be blank." }
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
        if (file.exists()) file.delete()
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

    private fun decodeSnapshot(root: JSONObject): AgentSnapshot {
        val goalsArray = root.optJSONArray("goals") ?: JSONArray()
        val goals = buildList {
            for (index in 0 until goalsArray.length()) {
                val goalJson = goalsArray.optJSONObject(index) ?: continue
                runCatching { decodeGoal(goalJson) }.getOrNull()?.let(::add)
            }
        }.sortedByDescending { it.updatedAt }
        val selectedId = root.optString("selected_goal_id")
            .takeIf { it.isNotBlank() && it != "null" && goals.any { goal -> goal.id == it } }
        return AgentSnapshot(goals, selectedId)
    }

    private fun encodeGoal(goal: AgentGoal): JSONObject {
        val json = JSONObject()
        json.put("storage_version", STORAGE_VERSION)
        json.put("id", goal.id)
        json.put("execution_lease", goal.executionLease?.let(::encodeLease) ?: JSONObject.NULL)
        json.put("conversation_id", goal.conversationId)
        json.put("submission_id", goal.submissionId ?: JSONObject.NULL)
        json.put("user_request", goal.userRequest)
        json.put("title", goal.title)
        json.put("objective", goal.objective)
        json.put("final_output_description", goal.finalOutputDescription)
        json.put("confirmed_constraints", JSONArray(goal.confirmedConstraints))
        json.put("inferred_preferences", JSONArray(goal.inferredPreferences))
        json.put("unresolved_questions", JSONArray(goal.unresolvedQuestions))
        json.put("evidence_requirements", JSONArray(goal.evidenceRequirements))
        json.put("preferred_source_types", JSONArray(goal.preferredSourceTypes))
        json.put("freshness_requirement", goal.freshnessRequirement ?: JSONObject.NULL)
        json.put("exclusions", JSONArray(goal.exclusions))
        json.put("source_message_ids", JSONArray(goal.sourceMessageIds))
        json.put("grounded_constraints", JSONArray().apply { goal.groundedConstraints.forEach { put(it.toJson()) } })
        json.put("status", goal.status.name)
        json.put("planner_model_id", goal.plannerModelId)
        json.put("execution_model_id", goal.executionModelId)
        json.put("routing_stage", goal.routingStage.name)
        json.put("requested_model_profile_name", goal.requestedModelProfileName ?: JSONObject.NULL)
        json.put("routing_policy_provenance", goal.routingPolicyProvenance.name)
        json.put("free_only", goal.freeOnly)
        json.put("tasks", JSONArray().apply { goal.tasks.forEach { put(encodeTask(it)) } })
        json.put("acceptance_criteria", JSONArray().apply { goal.acceptanceCriteria.forEach { put(encodeCriterion(it)) } })
        json.put("acceptance_checks", JSONArray().apply { goal.acceptanceChecks.forEach { put(encodeCheck(it)) } })
        json.put("attempts", JSONArray().apply { goal.attempts.forEach { put(encodeAttempt(it)) } })
        json.put("evidence", JSONArray().apply { goal.evidence.forEach { put(encodeEvidence(it)) } })
        json.put("source_reads", JSONArray().apply { goal.sourceReads.forEach { put(encodeSourceRead(it)) } })
        json.put("evidence_candidates", JSONArray().apply { goal.evidenceCandidates.forEach { put(encodeEvidenceCandidate(it)) } })
        json.put("normalized_facts", JSONArray().apply { goal.normalizedFacts.forEach { put(encodeNormalizedFact(it)) } })
        json.put("accepted_claims", JSONArray().apply { goal.acceptedClaims.forEach { put(encodeAcceptedClaim(it)) } })
        json.put("claims", JSONArray().apply { goal.claims.forEach { put(encodeClaim(it)) } })
        json.put("evidence_links", JSONArray().apply { goal.evidenceLinks.forEach { put(encodeEvidenceLink(it)) } })
        json.put("checkpoints", JSONArray().apply { goal.checkpoints.forEach { put(encodeCheckpoint(it)) } })
        json.put("concept_candidates", JSONArray().apply { goal.conceptCandidates.forEach { put(encodeConcept(it)) } })
        json.put("refinements", JSONArray().apply { goal.refinements.forEach { put(it) } })
        json.put("events", JSONArray().apply { goal.events.forEach { put(encodeEvent(it)) } })
        json.put("model_cooldowns", JSONObject(goal.modelCooldowns))
        json.put("created_at", goal.createdAt)
        json.put("updated_at", goal.updatedAt)
        json.put("total_tokens", goal.totalTokens)
        json.put("verification_round", goal.verificationRound)
        json.put("verification_correction_streak", goal.verificationCorrectionStreak)
        json.put("total_cost_usd_micros", goal.totalCostUsdMicros)
        json.put("result", goal.result ?: JSONObject.NULL)
        json.put("error", goal.error ?: JSONObject.NULL)
        json.put("blocked_reason", goal.blockedReason ?: JSONObject.NULL)
        json.put("terminal_result_delivered", goal.terminalResultDelivered)
        json.put("next_retry_at", goal.nextRetryAt ?: JSONObject.NULL)
        json.put("network_wait_started_at", goal.networkWaitStartedAt ?: JSONObject.NULL)
        json.put("network_retry_count", goal.networkRetryCount)
        json.put("network_wait_reason", goal.networkWaitReason ?: JSONObject.NULL)
        json.put("resume_status_after_network", goal.resumeStatusAfterNetwork?.name ?: JSONObject.NULL)
        json.put("request_attempts", JSONArray().apply { goal.requestAttempts.forEach { put(encodeRequestAttempt(it)) } })
        json.put("retry_authorizations", JSONArray().apply { goal.retryAuthorizations.forEach { put(encodeRetryAuthorization(it)) } })
        json.put("idempotency_records", JSONArray().apply { goal.idempotencyRecords.forEach { put(encodeIdempotencyRecord(it)) } })
        json.put("monitor_outbox", JSONArray().apply { goal.monitorOutbox.forEach { put(encodeMonitorOutbox(it)) } })
        json.put("route_fingerprints", JSONArray().apply { goal.routeFingerprints.forEach { put(encodeRouteFingerprint(it)) } })
        json.put("body_builder_claims", JSONArray().apply { goal.bodyBuilderClaims.forEach { put(encodeBodyBuilderClaim(it)) } })
        json.put("quarantined_records", JSONArray().apply { goal.quarantinedRecords.forEach { put(encodeQuarantinedRecord(it)) } })
        json.put("is_corrupt", goal.isCorrupt)
        json.put("objective_contract", goal.objectiveContract?.let(::encodeObjectiveContract) ?: JSONObject.NULL)
        json.put("resolved_research_request", goal.resolvedResearchRequest?.toJson() ?: JSONObject.NULL)
        json.put("requires_user_clarification", goal.requiresUserClarification)
        json.put("clarification_details", goal.clarificationDetails ?: JSONObject.NULL)
        json.put("blocked_sources", JSONArray().apply { goal.blockedSources.forEach { put(encodeBlockedSource(it)) } })
        json.put("allocation_profile_name", goal.allocationProfileName ?: JSONObject.NULL)
        json.put("allocation_summary", goal.allocationSummary ?: JSONObject.NULL)
        json.put("last_allocation_reason", goal.lastAllocationReason ?: JSONObject.NULL)
        json.put("plan_revision", goal.planRevision)
        json.put("last_meaningful_progress_at", goal.lastMeaningfulProgressAt ?: JSONObject.NULL)
        json.put("no_progress_count", goal.noProgressCount)
        json.put("blocker_recovery_condition", goal.blockerRecoveryCondition ?: JSONObject.NULL)
        json.put("final_validation_result", goal.finalValidationResult ?: JSONObject.NULL)
        json.put("attempted_strategies", JSONArray(goal.attemptedStrategies))
        json.put("operation_fingerprints", JSONArray(goal.operationFingerprints))
        json.put("classified_failures", JSONArray(goal.classifiedFailures))
        json.put("lease_generation", goal.leaseGeneration)
        json.put("last_resume_reason", goal.lastResumeReason?.name ?: JSONObject.NULL)
        json.put("recovery_plans", JSONArray().apply { goal.recoveryPlans.forEach { put(encodeRecoveryPlan(it)) } })
        json.put("active_recovery_plan_id", goal.activeRecoveryPlanId ?: JSONObject.NULL)
        json.put("research_cycles", JSONArray().apply { goal.researchCycles.forEach { put(encodeResearchCycle(it)) } })
        json.put("objective_revisions", JSONArray().apply { goal.objectiveRevisions.forEach { put(encodeObjectiveRevision(it)) } })
        json.put("active_research_cycle_id", goal.activeResearchCycleId ?: JSONObject.NULL)
        json.put("investigation_map", goal.investigationMap?.let(::encodeInvestigationMap) ?: JSONObject.NULL)
        return json
    }

    private fun encodeInvestigationMap(map: InvestigationMap): JSONObject = JSONObject()
        .put("entities", JSONArray().apply { map.entities.forEach { put(encodeEntity(it)) } })
        .put("gaps", JSONArray().apply { map.gaps.forEach { put(encodeGap(it)) } })
        .put("hypotheses", JSONArray().apply { map.hypotheses.forEach { put(encodeHypothesis(it)) } })
        .put("source_targets", JSONArray().apply { map.sourceTargets.forEach { put(encodeSourceTarget(it)) } })
        .put("query_outcomes", JSONArray().apply { map.queryOutcomes.forEach { put(encodeQueryOutcome(it)) } })
        .put("last_checkpoint_at", map.lastCheckpointAt)

    private fun encodeEntity(entity: EntityRecord): JSONObject = JSONObject()
        .put("id", entity.id)
        .put("canonical_name", entity.canonicalName)
        .put("aliases", JSONArray(entity.aliases))
        .put("type", entity.type ?: JSONObject.NULL)
        .put("parent_organizations", JSONArray(entity.parentOrganizations))
        .put("product_relationships", JSONArray(entity.productRelationships))
        .put("version_relationships", JSONArray(entity.versionRelationships))
        .put("geographic_scope", entity.geographicScope ?: JSONObject.NULL)
        .put("temporal_validity", entity.temporalValidity ?: JSONObject.NULL)
        .put("disambiguation_status", entity.disambiguationStatus.name)
        .put("supporting_evidence_ids", JSONArray(entity.supportingEvidenceIds))
        .put("rejected_interpretations", JSONArray(entity.rejectedInterpretations))
        .put("confidence", entity.confidence)
        .put("last_updated_at", entity.lastUpdatedAt)

    private fun encodeGap(gap: InformationGap): JSONObject = JSONObject()
        .put("id", gap.id)
        .put("description", gap.description)
        .put("impact_on_final_answer", gap.impactOnFinalAnswer)
        .put("related_acceptance_criterion_id", gap.relatedAcceptanceCriterionId ?: JSONObject.NULL)
        .put("related_entity_ids", JSONArray(gap.relatedEntityIds))
        .put("required_evidence_type", gap.requiredEvidenceType ?: JSONObject.NULL)
        .put("preferred_source_families", JSONArray(gap.preferredSourceFamilies))
        .put("answer_changing_threshold", gap.answerChangingThreshold ?: JSONObject.NULL)
        .put("status", gap.status.name)
        .put("attempts_made", gap.attemptsMade)
        .put("blocking_reason", gap.blockingReason ?: JSONObject.NULL)
        .put("resolution_evidence_ids", JSONArray(gap.resolutionEvidenceIds))

    private fun encodeHypothesis(hypothesis: Hypothesis): JSONObject = JSONObject()
        .put("id", hypothesis.id)
        .put("statement", hypothesis.statement)
        .put("related_gap_id", hypothesis.relatedGapId ?: JSONObject.NULL)
        .put("supporting_evidence_ids", JSONArray(hypothesis.supportingEvidenceIds))
        .put("contradicting_evidence_ids", JSONArray(hypothesis.contradictingEvidenceIds))
        .put("falsifiers", JSONArray(hypothesis.falsifiers))
        .put("confidence", hypothesis.confidence)
        .put("status", hypothesis.status.name)
        .put("last_tested_at", hypothesis.lastTestedAt ?: JSONObject.NULL)

    private fun encodeSourceTarget(target: SourceTarget): JSONObject = JSONObject()
        .put("id", target.id)
        .put("source_family", target.sourceFamily)
        .put("target_identity", target.targetIdentity)
        .put("rationale", target.rationale ?: JSONObject.NULL)
        .put("expected_evidence", target.expectedEvidence ?: JSONObject.NULL)
        .put("authority_expectation", target.authorityExpectation)
        .put("independence_group_id", target.independenceGroupId ?: JSONObject.NULL)
        .put("access_status", target.accessStatus ?: JSONObject.NULL)
        .put("previous_attempts", target.previousAttempts)
        .put("follow_up_links", JSONArray(target.followUpLinks))
        .put("priority", target.priority)

    private fun encodeQueryOutcome(outcome: QueryOutcome): JSONObject = JSONObject()
        .put("id", outcome.id)
        .put("canonical_query", outcome.canonicalQuery)
        .put("purpose", outcome.purpose ?: JSONObject.NULL)
        .put("related_gap_id", outcome.relatedGapId ?: JSONObject.NULL)
        .put("related_hypothesis_id", outcome.relatedHypothesisId ?: JSONObject.NULL)
        .put("source_family", outcome.sourceFamily ?: JSONObject.NULL)
        .put("result_domains", JSONArray(outcome.resultDomains))
        .put("useful_source_ids", JSONArray(outcome.usefulSourceIds))
        .put("new_entities_discovered", JSONArray(outcome.newEntitiesDiscovered))
        .put("new_citations_discovered", JSONArray(outcome.newCitationsDiscovered))
        .put("new_gaps_created", JSONArray(outcome.newGapsCreated))
        .put("evidence_accepted_ids", JSONArray(outcome.evidenceAcceptedIds))
        .put("utility_rationale", outcome.utilityRationale ?: JSONObject.NULL)
        .put("execution_timestamp", outcome.executionTimestamp)
        .put("cycle_id", outcome.cycleId ?: JSONObject.NULL)
        .put("tactic_id", outcome.tacticId ?: JSONObject.NULL)

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
        val storedRequestedProfile = json.optNullableString("requested_model_profile_name")
        val storedFreeOnly = if (json.has("free_only")) json.optBoolean("free_only") else null
        val storedRoutingStage = json.optEnum("routing_stage", AgentRoutingStage.AUTO_BETA)
        val (finalFreeOnly, finalProfile, finalProvenance) = when {
            storedRequestedProfile != null && storedFreeOnly != null -> 
                Triple(storedFreeOnly, storedRequestedProfile, json.optEnum("routing_policy_provenance", RoutingPolicyProvenance.EXPLICIT_USER_SELECTION))
            storedFreeOnly != null ->
                Triple(storedFreeOnly, null, json.optEnum("routing_policy_provenance", RoutingPolicyProvenance.EXPLICIT_USER_SELECTION))
            storedRoutingStage == AgentRoutingStage.FREE ->
                Triple(true, null, RoutingPolicyProvenance.LEGACY_AMBIGUOUS_SAFETY_LOCK)
            storedRoutingStage == AgentRoutingStage.AUTO_BETA ->
                Triple(false, "AUTO", RoutingPolicyProvenance.LEGACY_EXPLICIT)
            else -> Triple(false, null, RoutingPolicyProvenance.EXPLICIT_USER_SELECTION)
        }

        val storedStatus = json.optEnum("status", AgentGoalStatus.QUEUED)
        val storedError = json.optNullableString("error")
        val normalizedStoredError = storedError?.let { normalizeAgentFailureMessage(it, it) }
        val storedTasks = json.optJSONArray("tasks").decodeList(::decodeTask)
        val storedConversationId = json.optString("conversation_id")
        val storedUserRequest = json.optString("user_request")
        val (restoredStatus, isCorrupt, corruptionError) = when {
            storedConversationId.isBlank() || storedUserRequest.isBlank() -> Triple(AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION, true, "The original user request or conversation ID is missing.")
            else -> Triple(storedStatus, json.optBoolean("is_corrupt", false), normalizedStoredError)
        }
        val restoredEvents = json.optJSONArray("events").decodeList(::decodeEvent)
        
        val goalBeforeCycles = AgentGoal(
            id = json.getString("id"),
            conversationId = storedConversationId,
            submissionId = json.optNullableString("submission_id"),
            userRequest = storedUserRequest,
            title = json.optString("title"),
            objective = json.optString("objective"),
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
            routingStage = if (finalFreeOnly) AgentRoutingStage.FREE else storedRoutingStage,
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
            events = restoredEvents,
            modelCooldowns = json.optJSONObject("model_cooldowns")?.let { cooldownsJson ->
                buildMap { cooldownsJson.keys().forEach { key -> put(key, cooldownsJson.getLong(key)) } }
            } ?: emptyMap(),
            executionLease = json.optJSONObject("execution_lease")?.let(::decodeLease),
            createdAt = json.optLong("created_at", System.currentTimeMillis()),
            updatedAt = json.optLong("updated_at", System.currentTimeMillis()),
            totalTokens = json.optInt("total_tokens", 0),
            totalCostUsdMicros = convertedCostMicros,
            verificationRound = json.optInt("verification_round", 0),
            verificationCorrectionStreak = json.optInt("verification_correction_streak", 0),
            result = json.optNullableString("result"),
            error = corruptionError,
            blockedReason = json.optNullableString("blocked_reason"),
            terminalResultDelivered = json.optBoolean("terminal_result_delivered", false),
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
            isCorrupt = isCorrupt,
            objectiveContract = json.optJSONObject("objective_contract")?.let(::decodeObjectiveContract),
            resolvedResearchRequest = ResolvedResearchRequest.fromJson(json.optJSONObject("resolved_research_request")),
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
            recoveryPlans = json.optJSONArray("recovery_plans").decodeList(::decodeRecoveryPlan),
            activeRecoveryPlanId = json.optNullableString("active_recovery_plan_id"),
            researchCycles = json.optJSONArray("research_cycles").decodeList(::decodeResearchCycle),
            objectiveRevisions = json.optJSONArray("objective_revisions").decodeList(::decodeObjectiveRevision),
            activeResearchCycleId = json.optNullableString("active_research_cycle_id"),
            investigationMap = json.optJSONObject("investigation_map")?.let(::decodeInvestigationMap),
        )

        val isStuckV41 = storedVersion <= 12 && (restoredStatus == AgentGoalStatus.PAUSED || restoredStatus == AgentGoalStatus.BLOCKED_WITH_PARTIAL_EVIDENCE) &&
            storedTasks.any { it.status == AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE && it.failureClass == "STRUCTURED_SYNTHESIS_DEFICIT" } &&
            restoredEvents.any { it.message.contains("identical context fingerprint detected") } &&
            goalBeforeCycles.idempotencyRecords.none { it.key == "v41_stuck_migration" }

        val migratedGoal = if (isStuckV41) {
            val repairedTasks = goalBeforeCycles.tasks.map { task ->
                if (task.status == AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE && task.failureClass == "STRUCTURED_SYNTHESIS_DEFICIT") {
                    // Correct counters: if it was suppressed pre-dispatch, the attempt that led to suppression
                    // might have incremented counts. We decrement if they are > 0 to allow the task to run.
                    task.copy(
                        status = AgentTaskStatus.QUEUED,
                        failureClass = null,
                        attemptCount = (task.attemptCount - 1).coerceAtLeast(0),
                        lifetimeAttemptCount = (task.lifetimeAttemptCount - 1).coerceAtLeast(0)
                    )
                } else task
            }
            
            // Close dangling RUNNING attempts
            val repairedAttempts = goalBeforeCycles.attempts.map {
                if (it.status == AgentAttemptStatus.RUNNING) {
                    it.copy(status = AgentAttemptStatus.FAILED, error = "Attempt closed by migration (V41 stuck state).", finishedAt = System.currentTimeMillis())
                } else it
            }

            val migrationRecord = IdempotencyRecord(
                key = "v41_stuck_migration",
                effectType = IdempotencyEffectType.SYSTEM_REPAIR,
                state = IdempotencyState.COMMITTED,
                claimOwner = "migration_v42_4",
                committedAt = System.currentTimeMillis()
            )
            goalBeforeCycles.copy(
                status = AgentGoalStatus.QUEUED,
                tasks = repairedTasks,
                attempts = repairedAttempts,
                events = goalBeforeCycles.events + AgentEvent(message = "V41 stuck mission repaired. Counters corrected and dangling attempts closed."),
                idempotencyRecords = goalBeforeCycles.idempotencyRecords + migrationRecord
            )
        } else goalBeforeCycles

        return validateAndRepairInvariants(migratedGoal)
    }

    private fun validateAndRepairInvariants(goal: AgentGoal): AgentGoal {
        if (goal.isCorrupt || goal.userRequest.isBlank()) return goal
        
        val activeCycleId = goal.activeResearchCycleId
        if (activeCycleId == null && goal.tasks.isNotEmpty()) {
            return createBaselineCycle(goal)
        }
        
        if (activeCycleId == null) return goal

        // REQUIRED CHANGE 2: Store Invariant Validation
        // 1. activeResearchCycleId references an existing cycle.
        val activeCycle = goal.researchCycles.firstOrNull { it.id == activeCycleId }
            ?: run {
                diagnostics?.warning(
                    event = "active_cycle_invariant_failed",
                    component = "storage",
                    fields = mapOf("goal_id" to goal.id, "reason" to "Active cycle $activeCycleId not found.")
                )
                return goal.copy(status = AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION, isCorrupt = true, error = "Active cycle $activeCycleId not found.")
            }

        // 2. Exactly one cycle is ACTIVE.
        val activeCycles = goal.researchCycles.filter { it.status == ResearchCycleStatus.ACTIVE }
        if (activeCycles.size != 1) {
            diagnostics?.warning(
                event = "active_cycle_invariant_failed",
                component = "storage",
                fields = mapOf("goal_id" to goal.id, "reason" to "Exactly one active cycle required. Found ${activeCycles.size}.")
            )
            return goal.copy(status = AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION, isCorrupt = true, error = "Exactly one active cycle required. Found ${activeCycles.size}.")
        }

        // 3. The referenced cycle is the active cycle.
        if (activeCycle.id != activeCycleId || activeCycle.status != ResearchCycleStatus.ACTIVE) {
            diagnostics?.warning(
                event = "active_cycle_invariant_failed",
                component = "storage",
                fields = mapOf("goal_id" to goal.id, "reason" to "Referenced cycle $activeCycleId is not the ACTIVE cycle.")
            )
            return goal.copy(status = AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION, isCorrupt = true, error = "Referenced cycle $activeCycleId is not the ACTIVE cycle.")
        }

        // REQUIRED CHANGE 3: EXACT REPAIR FOR THE REPRODUCED STATE
        // Implement a deterministic, idempotent migration for missions created by the defective build.
        val hasNullCycleTasks = goal.tasks.any { it.cycleId == null }
        val isInitialPlanState = goal.status in setOf(AgentGoalStatus.QUEUED, AgentGoalStatus.RUNNING) && 
            goal.tasks.isNotEmpty() && 
            goal.requestAttempts.none { it.taskId != null } && // No task provider request was dispatched.
            goal.attempts.none { it.taskId != null && it.status != AgentAttemptStatus.RUNNING } // No task execution attempt has started.
        
        val repairKey = "v42-plan-cycle-binding:${goal.id}"
        val alreadyRepaired = goal.idempotencyRecords.any { it.key == repairKey }

        if (hasNullCycleTasks && isInitialPlanState && !alreadyRepaired) {
            val baselineCycleId = ResearchRecoveryEngine.generateCycleIdentity(goal.id, 1)
            val baselineCycle = goal.researchCycles.firstOrNull { it.id == baselineCycleId }
                ?: goal.researchCycles.firstOrNull { it.ordinal == 1 }
            
            if (baselineCycle != null && goal.researchCycles.size == 1 && goal.objectiveRevisions.size == 1) {
                val repairedTasks = goal.tasks.map { it.copy(cycleId = baselineCycle.id) }
                val repairedEvidence = goal.evidence.map { if (it.cycleId == null) it.copy(cycleId = baselineCycle.id) else it }
                val migrationRecord = IdempotencyRecord(
                    key = repairKey,
                    effectType = IdempotencyEffectType.SYSTEM_REPAIR,
                    state = IdempotencyState.COMMITTED,
                    claimOwner = "migration_v42_4",
                    committedAt = System.currentTimeMillis()
                )
                diagnostics?.info(
                    event = "initial_task_cycle_binding_repaired",
                    component = "storage",
                    fields = mapOf(
                        "goal_id" to goal.id,
                        "cycle_id" to baselineCycle.id,
                        "task_count" to repairedTasks.size
                    )
                )
                return goal.copy(
                    tasks = repairedTasks,
                    evidence = repairedEvidence,
                    idempotencyRecords = goal.idempotencyRecords + migrationRecord,
                    events = appendEvent(goal.events, "V42.4: Repaired initial task-cycle binding for mission.")
                )
            }
        }

        // 4. Every executable task has a non-null cycle ID.
        // 5. Every current executable task belongs to the active cycle.
        // 6. Every task’s cycle ID references an existing cycle.
        val allCycleIds = goal.researchCycles.map { it.id }.toSet()
        goal.tasks.forEach { task ->
            if (task.status.isExecutable()) {
                if (task.cycleId == null) {
                    return goal.copy(status = AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION, isCorrupt = true, error = "Executable task ${task.id} missing cycleId.")
                }
                if (task.cycleId != activeCycleId) {
                    return goal.copy(status = AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION, isCorrupt = true, error = "Executable task ${task.id} belongs to cycle ${task.cycleId}, but active cycle is $activeCycleId.")
                }
            }
            if (task.cycleId != null && !allCycleIds.contains(task.cycleId)) {
                return goal.copy(status = AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION, isCorrupt = true, error = "Task ${task.id} references non-existent cycle ${task.cycleId}.")
            }
        }

        return goal
    }

    private fun createBaselineCycle(goal: AgentGoal): AgentGoal {
        if (goal.activeResearchCycleId != null) return goal
        val revisionId = ResearchRecoveryEngine.generateRevisionIdentity(goal.id, 1)
        val cycleId = ResearchRecoveryEngine.generateCycleIdentity(goal.id, 1)
        
        val rootFp = FingerprintUtils.calculateRootObjectiveFingerprint(goal)
        val strategyFp = FingerprintUtils.calculateStrategyFingerprint(goal.objective, goal.unresolvedQuestions, null)
        
        val baselineRevision = ObjectiveRevision(
            id = revisionId,
            ordinal = 1,
            parentRevisionId = null,
            immutableRootObjectiveFingerprint = rootFp,
            operationalObjective = goal.objective,
            unresolvedGaps = goal.unresolvedQuestions,
            retainedConstraints = goal.confirmedConstraints,
            evidenceRequirements = goal.evidenceRequirements,
            revisionReason = "Initial baseline revision.",
            revisionFingerprint = rootFp
        )
        val baselineCycle = ResearchCycle(
            id = cycleId,
            ordinal = 1,
            parentCycleId = null,
            status = ResearchCycleStatus.ACTIVE,
            objectiveRevisionId = revisionId,
            triggerDiagnosis = ExecutionStallDiagnosis.NONE,
            selectedAdvancementTactic = EscalationTactic.NONE,
            strategyFingerprint = strategyFp,
            queryPortfolioFingerprint = FingerprintUtils.hash("v1:portfolio:empty"),
            acceptedEvidenceFingerprint = FingerprintUtils.hash("v1:accepted_evidence:empty"),
            unresolvedGapFingerprint = FingerprintUtils.hash("v1:unresolved_gap:initial"),
            learningSummary = null,
            activatedAt = goal.createdAt
        )
        return goal.copy(
            researchCycles = goal.researchCycles + baselineCycle,
            objectiveRevisions = goal.objectiveRevisions + baselineRevision,
            activeResearchCycleId = cycleId,
            tasks = goal.tasks.map { if (it.cycleId == null) it.copy(cycleId = cycleId) else it },
            evidence = goal.evidence.map { if (it.cycleId == null) it.copy(cycleId = cycleId) else it }
        )
    }

    private fun encodeResearchCycle(cycle: ResearchCycle): JSONObject = JSONObject()
        .put("id", cycle.id)
        .put("ordinal", cycle.ordinal)
        .put("parent_cycle_id", cycle.parentCycleId ?: JSONObject.NULL)
        .put("status", cycle.status.name)
        .put("objective_revision_id", cycle.objectiveRevisionId)
        .put("trigger_diagnosis", cycle.triggerDiagnosis.name)
        .put("selected_advancement_tactic", cycle.selectedAdvancementTactic.name)
        .put("strategy_fingerprint", cycle.strategyFingerprint)
        .put("query_portfolio_fingerprint", cycle.queryPortfolioFingerprint)
        .put("accepted_evidence_fingerprint", cycle.acceptedEvidenceFingerprint)
        .put("unresolved_gap_fingerprint", cycle.unresolvedGapFingerprint)
        .put("learning_summary", cycle.learningSummary?.let(::encodeLearningSummary) ?: JSONObject.NULL)
        .put("created_at", cycle.createdAt)
        .put("activated_at", cycle.activatedAt ?: JSONObject.NULL)
        .put("superseded_at", cycle.supersededAt ?: JSONObject.NULL)
        .put("completed_at", cycle.completedAt ?: JSONObject.NULL)
        .put("exhausted_at", cycle.exhaustedAt ?: JSONObject.NULL)

    private fun encodeLearningSummary(summary: ResearchCycleLearningSummary): JSONObject = JSONObject()
        .put("established_findings", JSONArray(summary.establishedFindings))
        .put("accepted_evidence_ids", JSONArray(summary.acceptedEvidenceIds))
        .put("accepted_claim_ids", JSONArray(summary.acceptedClaimIds))
        .put("remaining_unresolved_gaps", JSONArray(summary.remainingUnresolvedGaps))
        .put("contradictions", JSONArray(summary.contradictions))
        .put("rejected_or_unreliable_material", JSONArray(summary.rejectedOrUnreliableMaterial))
        .put("exhausted_query_approaches", JSONArray(summary.exhaustedQueryApproaches))
        .put("exhausted_source_families", JSONArray(summary.exhaustedSourceFamilies))
        .put("attempted_tactics", JSONArray(summary.attemptedTactics.map { it.name }))
        .put("failed_strategy_fingerprints", JSONArray(summary.failedStrategyFingerprints))
        .put("carry_forward_evidence_ids", JSONArray(summary.carryForwardEvidenceIds))
        .put("advancement_reason", summary.advancementReason)

    private fun encodeObjectiveRevision(revision: ObjectiveRevision): JSONObject = JSONObject()
        .put("id", revision.id)
        .put("ordinal", revision.ordinal)
        .put("parent_revision_id", revision.parentRevisionId ?: JSONObject.NULL)
        .put("immutable_root_objective_fingerprint", revision.immutableRootObjectiveFingerprint)
        .put("operational_objective", revision.operationalObjective)
        .put("unresolved_gaps", JSONArray(revision.unresolvedGaps))
        .put("retained_constraints", JSONArray(revision.retainedConstraints))
        .put("evidence_requirements", JSONArray(revision.evidenceRequirements))
        .put("revision_reason", revision.revisionReason)
        .put("revision_fingerprint", revision.revisionFingerprint)
        .put("created_at", revision.createdAt)

    private fun decodeResearchCycle(json: JSONObject): ResearchCycle = ResearchCycle(
        id = json.getString("id"),
        ordinal = json.optInt("ordinal"),
        parentCycleId = json.optNullableString("parent_cycle_id"),
        status = json.optEnum("status", ResearchCycleStatus.PLANNING),
        objectiveRevisionId = json.getString("objective_revision_id"),
        triggerDiagnosis = json.optEnum("trigger_diagnosis", ExecutionStallDiagnosis.NONE),
        selectedAdvancementTactic = json.optEnum("selected_advancement_tactic", EscalationTactic.NONE),
        strategyFingerprint = json.optString("strategy_fingerprint"),
        queryPortfolioFingerprint = json.optString("query_portfolio_fingerprint"),
        acceptedEvidenceFingerprint = json.optString("accepted_evidence_fingerprint"),
        unresolvedGapFingerprint = json.optString("unresolved_gap_fingerprint"),
        learningSummary = json.optJSONObject("learning_summary")?.let(::decodeLearningSummary),
        createdAt = json.optLong("created_at", System.currentTimeMillis()),
        activatedAt = json.optLongOrNull("activated_at"),
        supersededAt = json.optLongOrNull("superseded_at"),
        completedAt = json.optLongOrNull("completed_at"),
        exhaustedAt = json.optLongOrNull("exhausted_at")
    )

    private fun decodeLearningSummary(json: JSONObject): ResearchCycleLearningSummary = ResearchCycleLearningSummary(
        establishedFindings = json.optJSONArray("established_findings").toStringList(),
        acceptedEvidenceIds = json.optJSONArray("accepted_evidence_ids").toStringList(),
        acceptedClaimIds = json.optJSONArray("accepted_claim_ids").toStringList(),
        remainingUnresolvedGaps = json.optJSONArray("remaining_unresolved_gaps").toStringList(),
        contradictions = json.optJSONArray("contradictions").toStringList(),
        rejectedOrUnreliableMaterial = json.optJSONArray("rejected_or_unreliable_material").toStringList(),
        exhaustedQueryApproaches = json.optJSONArray("exhausted_query_approaches").toStringList(),
        exhaustedSourceFamilies = json.optJSONArray("exhausted_source_families").toStringList(),
        attemptedTactics = json.optJSONArray("attempted_tactics").decodeList { obj -> runCatching { EscalationTactic.valueOf(obj.toString()) }.getOrNull() },
        failedStrategyFingerprints = json.optJSONArray("failed_strategy_fingerprints").toStringList(),
        carryForwardEvidenceIds = json.optJSONArray("carry_forward_evidence_ids").toStringList(),
        advancementReason = json.optString("advancement_reason")
    )

    private fun decodeObjectiveRevision(json: JSONObject): ObjectiveRevision = ObjectiveRevision(
        id = json.getString("id"),
        ordinal = json.optInt("ordinal"),
        parentRevisionId = json.optNullableString("parent_revision_id"),
        immutableRootObjectiveFingerprint = json.optString("immutable_root_objective_fingerprint"),
        operationalObjective = json.optString("operational_objective"),
        unresolvedGaps = json.optJSONArray("unresolved_gaps").toStringList(),
        retainedConstraints = json.optJSONArray("retained_constraints").toStringList(),
        evidenceRequirements = json.optJSONArray("evidence_requirements").toStringList(),
        revisionReason = json.optString("revision_reason"),
        revisionFingerprint = json.optString("revision_fingerprint"),
        createdAt = json.optLong("created_at", System.currentTimeMillis())
    )

    private fun decodeInvestigationMap(json: JSONObject): InvestigationMap = InvestigationMap(
        entities = json.optJSONArray("entities").decodeList(::decodeEntity),
        gaps = json.optJSONArray("gaps").decodeList(::decodeGap),
        hypotheses = json.optJSONArray("hypotheses").decodeList(::decodeHypothesis),
        sourceTargets = json.optJSONArray("source_targets").decodeList(::decodeSourceTarget),
        queryOutcomes = json.optJSONArray("query_outcomes").decodeList(::decodeQueryOutcome),
        lastCheckpointAt = json.optLong("last_checkpoint_at", System.currentTimeMillis())
    )

    private fun decodeEntity(json: JSONObject): EntityRecord = EntityRecord(
        id = json.getString("id"),
        canonicalName = json.getString("canonical_name"),
        aliases = json.optJSONArray("aliases").toStringList(),
        type = json.optNullableString("type"),
        parentOrganizations = json.optJSONArray("parent_organizations").toStringList(),
        productRelationships = json.optJSONArray("product_relationships").toStringList(),
        versionRelationships = json.optJSONArray("version_relationships").toStringList(),
        geographicScope = json.optNullableString("geographic_scope"),
        temporalValidity = json.optNullableString("temporal_validity"),
        disambiguationStatus = json.optEnum("disambiguation_status", DisambiguationStatus.AMBIGUOUS),
        supportingEvidenceIds = json.optJSONArray("supporting_evidence_ids").toStringList(),
        rejectedInterpretations = json.optJSONArray("rejected_interpretations").toStringList(),
        confidence = json.optDouble("confidence", 0.5),
        lastUpdatedAt = json.optLong("last_updated_at", System.currentTimeMillis())
    )

    private fun decodeGap(json: JSONObject): InformationGap = InformationGap(
        id = json.getString("id"),
        description = json.getString("description"),
        impactOnFinalAnswer = json.getString("impact_on_final_answer"),
        relatedAcceptanceCriterionId = json.optNullableString("related_acceptance_criterion_id"),
        relatedEntityIds = json.optJSONArray("related_entity_ids").toStringList(),
        requiredEvidenceType = json.optNullableString("required_evidence_type"),
        preferredSourceFamilies = json.optJSONArray("preferred_source_families").toStringList(),
        answerChangingThreshold = json.optNullableString("answer_changing_threshold"),
        status = json.optEnum("status", GapStatus.OPEN),
        attemptsMade = json.optInt("attempts_made", 0),
        blockingReason = json.optNullableString("blocking_reason"),
        resolutionEvidenceIds = json.optJSONArray("resolution_evidence_ids").toStringList()
    )

    private fun decodeHypothesis(json: JSONObject): Hypothesis = Hypothesis(
        id = json.getString("id"),
        statement = json.getString("statement"),
        relatedGapId = json.optNullableString("related_gap_id"),
        supportingEvidenceIds = json.optJSONArray("supporting_evidence_ids").toStringList(),
        contradictingEvidenceIds = json.optJSONArray("contradicting_evidence_ids").toStringList(),
        falsifiers = json.optJSONArray("falsifiers").toStringList(),
        confidence = json.optDouble("confidence", 0.5),
        status = json.optEnum("status", HypothesisStatus.PROPOSED),
        lastTestedAt = json.optLongOrNull("last_tested_at")
    )

    private fun decodeSourceTarget(json: JSONObject): SourceTarget = SourceTarget(
        id = json.getString("id"),
        sourceFamily = json.getString("source_family"),
        targetIdentity = json.getString("target_identity"),
        rationale = json.optNullableString("rationale"),
        expectedEvidence = json.optNullableString("expected_evidence"),
        authorityExpectation = json.optDouble("authority_expectation", 0.5),
        independenceGroupId = json.optNullableString("independence_group_id"),
        accessStatus = json.optNullableString("access_status"),
        previousAttempts = json.optInt("previous_attempts", 0),
        followUpLinks = json.optJSONArray("follow_up_links").toStringList(),
        priority = json.optDouble("priority", 0.5)
    )

    private fun decodeQueryOutcome(json: JSONObject): QueryOutcome = QueryOutcome(
        id = json.getString("id"),
        canonicalQuery = json.getString("canonical_query"),
        purpose = json.optNullableString("purpose"),
        relatedGapId = json.optNullableString("related_gap_id"),
        relatedHypothesisId = json.optNullableString("related_hypothesis_id"),
        sourceFamily = json.optNullableString("source_family"),
        resultDomains = json.optJSONArray("result_domains").toStringList(),
        usefulSourceIds = json.optJSONArray("useful_source_ids").toStringList(),
        newEntitiesDiscovered = json.optJSONArray("new_entities_discovered").toStringList(),
        newCitationsDiscovered = json.optJSONArray("new_citations_discovered").toStringList(),
        newGapsCreated = json.optJSONArray("new_gaps_created").toStringList(),
        evidenceAcceptedIds = json.optJSONArray("evidence_accepted_ids").toStringList(),
        utilityRationale = json.optNullableString("utility_rationale"),
        executionTimestamp = json.optLong("execution_timestamp", System.currentTimeMillis()),
        cycleId = json.optNullableString("cycle_id"),
        tacticId = json.optNullableString("tactic_id")
    )

    private fun encodeRecoveryPlan(plan: ResearchRecoveryPlan): JSONObject = JSONObject()
        .put("id", plan.id)
        .put("goal_id", plan.goalId)
        .put("task_id", plan.taskId)
        .put("input_execution_fingerprint", plan.inputExecutionFingerprint)
        .put("diagnosis", plan.diagnosis.name)
        .put("selected_tactic", plan.selectedTactic.name)
        .put("status", plan.status.name)
        .put("logical_provider_request_id", plan.logicalProviderRequestId ?: JSONObject.NULL)
        .put("proposal", plan.proposal?.let(::encodeRecoveryProposal) ?: JSONObject.NULL)
        .put("proposal_fingerprint", plan.proposalFingerprint ?: JSONObject.NULL)
        .put("validation_result", plan.validationResult ?: JSONObject.NULL)
        .put("failure_classification", plan.failureClassification ?: JSONObject.NULL)
        .put("failure_message", plan.failureMessage ?: JSONObject.NULL)
        .put("accounting_summary", plan.accountingSummary?.let(::encodeApiSummary) ?: JSONObject.NULL)
        .put("retry_authorized_fingerprint", plan.retryAuthorizedFingerprint ?: JSONObject.NULL)
        .put("created_at", plan.createdAt)
        .put("generated_at", plan.generatedAt ?: JSONObject.NULL)
        .put("committed_at", plan.committedAt ?: JSONObject.NULL)

    private fun decodeRecoveryPlan(json: JSONObject): ResearchRecoveryPlan = ResearchRecoveryPlan(
        id = json.getString("id"),
        goalId = json.getString("goal_id"),
        taskId = json.getString("task_id"),
        inputExecutionFingerprint = json.getString("input_execution_fingerprint"),
        diagnosis = json.optEnum("diagnosis", ExecutionStallDiagnosis.NONE),
        selectedTactic = json.optEnum("selected_tactic", EscalationTactic.NONE),
        status = json.optEnum("status", RecoveryPlanStatus.PREPARED),
        logicalProviderRequestId = json.optNullableString("logical_provider_request_id"),
        proposal = json.optJSONObject("proposal")?.let(::decodeRecoveryProposal),
        proposalFingerprint = json.optNullableString("proposal_fingerprint"),
        validationResult = json.optNullableString("validation_result"),
        failureClassification = json.optNullableString("failure_classification"),
        failureMessage = json.optNullableString("failure_message"),
        accountingSummary = json.optJSONObject("accounting_summary")?.let(::decodeApiSummary),
        retryAuthorizedFingerprint = json.optNullableString("retry_authorized_fingerprint"),
        createdAt = json.optLong("created_at", System.currentTimeMillis()),
        generatedAt = json.optLongOrNull("generated_at"),
        committedAt = json.optLongOrNull("committed_at")
    )

    private fun encodeApiSummary(summary: AgentApiSummary): JSONObject = JSONObject()
        .put("response_id", summary.responseId ?: JSONObject.NULL)
        .put("resolved_model", summary.resolvedModel ?: JSONObject.NULL)
        .put("role", summary.role?.name ?: JSONObject.NULL)
        .put("selection_reason", summary.selectionReason ?: JSONObject.NULL)
        .put("previous_route", summary.previousRoute ?: JSONObject.NULL)
        .put("cooldown_state", summary.cooldownState ?: JSONObject.NULL)
        .put("provider", summary.provider ?: JSONObject.NULL)
        .put("finish_reason", summary.finishReason ?: JSONObject.NULL)
        .put("native_finish_reason", summary.nativeFinishReason ?: JSONObject.NULL)
        .put("http_status_code", summary.httpStatusCode ?: JSONObject.NULL)
        .put("prompt_tokens", summary.promptTokens ?: JSONObject.NULL)
        .put("completion_tokens", summary.completionTokens ?: JSONObject.NULL)
        .put("total_tokens", summary.totalTokens ?: JSONObject.NULL)
        .put("cost_usd", summary.costUsd ?: JSONObject.NULL)
        .put("web_search_requests", summary.webSearchRequests ?: JSONObject.NULL)
        .put("web_fetch_requests", summary.webFetchRequests ?: JSONObject.NULL)
        .put("discovered_leads", summary.discoveredLeads ?: JSONObject.NULL)
        .put("rabbit_hole_iterations", summary.rabbitHoleIterations ?: JSONObject.NULL)
        .put("duration_ms", summary.durationMs ?: JSONObject.NULL)

    private fun decodeApiSummary(json: JSONObject): AgentApiSummary = AgentApiSummary(
        responseId = json.optNullableString("response_id"),
        resolvedModel = json.optNullableString("resolved_model"),
        role = json.optNullableString("role")?.let { runCatching { AgentTaskRole.valueOf(it) }.getOrNull() },
        selectionReason = json.optNullableString("selection_reason"),
        previousRoute = json.optNullableString("previous_route"),
        cooldownState = json.optNullableString("cooldown_state"),
        provider = json.optNullableString("provider"),
        finishReason = json.optNullableString("finish_reason"),
        nativeFinishReason = json.optNullableString("native_finish_reason"),
        httpStatusCode = json.optIntOrNull("http_status_code"),
        promptTokens = json.optIntOrNull("prompt_tokens"),
        completionTokens = json.optIntOrNull("completion_tokens"),
        totalTokens = json.optIntOrNull("total_tokens"),
        costUsd = json.optDoubleOrNull("cost_usd"),
        webSearchRequests = json.optIntOrNull("web_search_requests"),
        webFetchRequests = json.optIntOrNull("web_fetch_requests"),
        discoveredLeads = json.optIntOrNull("discovered_leads"),
        rabbitHoleIterations = json.optIntOrNull("rabbit_hole_iterations"),
        durationMs = json.optLongOrNull("duration_ms")
    )

    private fun encodeRecoveryProposal(proposal: RecoveryProposal): JSONObject = JSONObject()
        .put("revised_investigation_interpretation", proposal.revisedInvestigationInterpretation)
        .put("specific_unresolved_gap", proposal.specificUnresolvedGap)
        .put("selected_source_family_shift", proposal.selectedSourceFamilyShift ?: JSONObject.NULL)
        .put("evidence_targets", JSONArray(proposal.evidenceTargets))
        .put("falsifiers", JSONArray(proposal.falsifiers))
        .put("new_query_portfolio", JSONArray(proposal.newQueryPortfolio))
        .put("follow_up_rule", proposal.followUpRule ?: JSONObject.NULL)
        .put("rationale", proposal.rationale)
        .put("expected_novelty_dimensions", JSONArray(proposal.expectedNoveltyDimensions))

    private fun encodeObjectiveContract(contract: ObjectiveContract): JSONObject = JSONObject()
        .put("version", contract.version)
        .put("primary_subject", contract.primarySubject)
        .put("strong_anchors", JSONArray(contract.strongAnchors))
        .put("temporal_context", contract.temporalContext ?: JSONObject.NULL)
        .put("expected_deliverable_kind", contract.expectedDeliverableKind ?: JSONObject.NULL)
        .put("domain_classification", contract.domainClassification)
        .put("contract_hash", contract.contractHash ?: JSONObject.NULL)

    private fun decodeObjectiveContract(json: JSONObject): ObjectiveContract = ObjectiveContract(
        version = json.optInt("version", 1),
        primarySubject = json.optString("primary_subject", "unknown"),
        strongAnchors = json.optJSONArray("strong_anchors").toStringList(),
        temporalContext = json.optNullableString("temporal_context"),
        expectedDeliverableKind = json.optNullableString("expected_deliverable_kind"),
        domainClassification = json.optString("domain_classification", "GENERAL"),
        contractHash = json.optNullableString("contract_hash"),
    )

    private fun encodeTask(task: AgentTask): JSONObject = JSONObject()
        .put("id", task.id)
        .put("cycle_id", task.cycleId ?: JSONObject.NULL)
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
            cycleId = json.optNullableString("cycle_id"),
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
            progressScore = json.optDouble("progress_score", persistedProgress).coerceIn(0.0, 1.0),
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

    private fun decodeRequestAttempt(json: JSONObject): ProviderRequestAttempt? {
        val exchangeId = json.optNullableString("exchange_id") ?: return null
        return ProviderRequestAttempt(
            exchangeId = exchangeId,
            logicalRequestId = json.optNullableString("logical_request_id") ?: exchangeId,
            wireAttemptOrdinal = json.optInt("wire_attempt_ordinal", 1),
            previousExchangeId = json.optNullableString("previous_exchange_id"),
            providerResponseId = json.optNullableString("provider_response_id"),
            transportStage = json.optEnum("transport_stage", ProviderTransportStage.NOT_DISPATCHED),
            deliveryCertainty = json.optEnum("delivery_certainty", ProviderDeliveryCertainty.NOT_SENT),
            parentOperationId = json.optNullableString("parent_operation_id") ?: "unknown",
            goalId = json.optNullableString("goal_id") ?: "unknown",
            taskId = json.optNullableString("task_id"),
            executionGeneration = json.optInt("execution_generation", 0),
            requestedModel = json.optNullableString("requested_model") ?: "unknown",
            resolvedModel = json.optNullableString("resolved_model"),
            role = json.optNullableString("role")?.let { runCatching { AgentTaskRole.valueOf(it) }.getOrNull() },
            payloadFingerprint = json.optNullableString("payload_fingerprint") ?: "",
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
            startedAt = json.optLong("started_at", 0L),
            finishedAt = json.optLongOrNull("finished_at"),
            reconciliationClaimOwner = json.optNullableString("reconciliation_claim_owner"),
            reconciliationClaimedAt = json.optLongOrNull("reconciliation_claimed_at"),
            safeDiagnosticSummary = json.optNullableString("safe_diagnostic_summary"),
        )
    }

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
        .put("role", attempt.role?.name ?: JSONObject.NULL)
        .put("selection_reason", attempt.selectionReason ?: JSONObject.NULL)
        .put("previous_route", attempt.previousRoute ?: JSONObject.NULL)
        .put("cooldown_state", attempt.cooldownState ?: JSONObject.NULL)
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
        role = json.optNullableString("role")?.let { runCatching { AgentTaskRole.valueOf(it) }.getOrNull() },
        selectionReason = json.optNullableString("selection_reason"),
        previousRoute = json.optNullableString("previous_route"),
        cooldownState = json.optNullableString("cooldown_state"),
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
        .put("cycle_id", evidence.cycleId ?: JSONObject.NULL)
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
        cycleId = json.optNullableString("cycle_id"),
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

    private fun decodeSourceRead(json: JSONObject): SourceRead = SourceRead(
        id = json.getString("id"),
        url = json.getString("url"),
        canonicalUrl = json.getString("canonical_url"),
        httpCode = json.getInt("http_code"),
        contentType = json.getString("content_type"),
        content = json.getString("content"),
        sourceRole = json.getString("source_role"),
        authorityScore = json.getInt("authority_score"),
        readAt = json.getLong("read_at"),
        provenance = json.optEnum("provenance", SourceReadProvenance.UNVERIFIED_CITATION),
    )

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
        progressScore = json.optDouble("progress_score", 0.0),
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

    private fun encodeLease(lease: AgentExecutionLease): JSONObject = JSONObject()
        .put("worker_id", lease.workerId)
        .put("owner_process_session_id", lease.ownerProcessSessionId)
        .put("task_id", lease.taskId)
        .put("attempt_id", lease.attemptId)
        .put("generation", lease.generation)
        .put("acquired_at", lease.acquiredAt)
        .put("heartbeat_at", lease.heartbeatAt)

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

    private fun encodeEvent(event: AgentEvent): JSONObject = JSONObject()
        .put("id", event.id)
        .put("created_at", event.createdAt)
        .put("message", event.message)

    private fun decodeEvent(json: JSONObject): AgentEvent = AgentEvent(
        id = json.getString("id"),
        createdAt = json.optLong("created_at"),
        message = json.optString("message"),
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

    private fun decodeLease(json: JSONObject): AgentExecutionLease = AgentExecutionLease(
        workerId = json.getString("worker_id"),
        ownerProcessSessionId = json.optString("owner_process_session_id", "unknown"),
        taskId = json.getString("task_id"),
        attemptId = json.getString("attempt_id"),
        generation = json.getInt("generation"),
        acquiredAt = json.getLong("acquired_at"),
        heartbeatAt = json.getLong("heartbeat_at"),
    )

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

    private fun decodeRecoveryProposal(json: JSONObject): RecoveryProposal = RecoveryProposal(
        revisedInvestigationInterpretation = json.optString("revised_investigation_interpretation"),
        specificUnresolvedGap = json.optString("specific_unresolved_gap"),
        selectedSourceFamilyShift = json.optNullableString("selected_source_family_shift"),
        evidenceTargets = json.optJSONArray("evidence_targets").toStringList(),
        falsifiers = json.optJSONArray("falsifiers").toStringList(),
        newQueryPortfolio = json.optJSONArray("new_query_portfolio").toStringList(),
        followUpRule = json.optNullableString("follow_up_rule"),
        rationale = json.optString("rationale"),
        expectedNoveltyDimensions = json.optJSONArray("expected_novelty_dimensions").toStringList()
    )

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

    private fun <T : Any> JSONArray?.decodeList(decoder: (JSONObject) -> T?): List<T> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until length()) {
                optJSONObject(index)?.let { item -> runCatching { decoder(item) }.getOrNull()?.let(::add) }
            }
        }
    }

    companion object {
        val readCount = AtomicLong(0)
        val writeCount = AtomicLong(0)

        @get:org.jetbrains.annotations.VisibleForTesting
        @set:org.jetbrains.annotations.VisibleForTesting
        internal var processSessionIdOverride: String? = null

        internal fun getCurrentSessionId(): String = processSessionIdOverride ?: com.david.openassistant.data.diagnostics.DiagnosticEvent.PROCESS_SESSION_ID

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
        private const val STORAGE_VERSION = 13
        private val STORE_LOCK = Any()

        fun isTaskBoundOperation(operation: String): Boolean {
            val typedOp = MissionOperation.fromName(operation) ?: return false
            return typedOp.taskBound
        }
    }
}
