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

sealed class UpdateTransportStageResult {
    object Updated : UpdateTransportStageResult()
    object GoalMissing : UpdateTransportStageResult()
    object ExchangeMissing : UpdateTransportStageResult()
    object AlreadyAtOrBeyondStage : UpdateTransportStageResult()
    object InvalidTransition : UpdateTransportStageResult()
    data class StorageFailure(val cause: Throwable) : UpdateTransportStageResult()
}

sealed class AuthorizeRetryResult {
    object Authorized : AuthorizeRetryResult()
    object AlreadyAuthorized : AuthorizeRetryResult()
    object GoalMissing : AuthorizeRetryResult()
    object InvalidAuthorization : AuthorizeRetryResult()
    data class StorageFailure(val cause: Throwable) : AuthorizeRetryResult()
}

sealed class ReconciliationResult {
    data class NewDispatchClaimed(val attempt: ProviderRequestAttempt) : ReconciliationResult()
    data class ExistingNotDispatched(val attempt: ProviderRequestAttempt) : ReconciliationResult()
    data class ExistingActive(val attempt: ProviderRequestAttempt) : ReconciliationResult()
    data class ExistingInFlight(val attempt: ProviderRequestAttempt) : ReconciliationResult()
    data class ExistingAmbiguous(val attempt: ProviderRequestAttempt) : ReconciliationResult()
    data class ExistingSuccessfulResultAvailable(val attempt: ProviderRequestAttempt, val proposal: RecoveryProposal?, val summary: AgentApiSummary?, val responseContent: String? = null) : ReconciliationResult()
    data class ExistingSuccessfulResultMissing(val attempt: ProviderRequestAttempt) : ReconciliationResult()
    data class ExistingTerminalFailure(val attempt: ProviderRequestAttempt) : ReconciliationResult()
    data class RetryDispatchClaimed(val attempt: ProviderRequestAttempt) : ReconciliationResult()
    data class RetryAuthorizationRequired(val previousAttempt: ProviderRequestAttempt) : ReconciliationResult()
    data class StaleOwnership(val currentGeneration: Int, val requestedGeneration: Int) : ReconciliationResult()
    object OwnershipMismatch : ReconciliationResult()
    data class LogicalIdentityConflict(val existingFingerprint: String, val requestedFingerprint: String) : ReconciliationResult()
    data class RecoveryPlanMismatch(val expectedPlanId: String, val actualPlanId: String?) : ReconciliationResult()
    data class GoalTerminal(val status: AgentGoalStatus) : ReconciliationResult()
    data class GoalMissing(val status: AgentGoalStatus) : ReconciliationResult()
    data class StorageFailure(val cause: Throwable) : ReconciliationResult()
}

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

sealed interface TerminalRecoveryRepairResult {
    data class AuthorizedRetry(val logicalRequestId: String, val failedAttemptOrdinal: Int, val nextAttemptOrdinal: Int, val authorizationFingerprint: String) : TerminalRecoveryRepairResult
    data class ReconciliationRequired(val logicalRequestId: String, val exchangeId: String, val deliveryCertainty: ProviderDeliveryCertainty) : TerminalRecoveryRepairResult
    data class AlternateStrategyRequired(val failedLogicalRequestId: String, val failedExchangeId: String?, val reasonCode: String) : TerminalRecoveryRepairResult
    object AlreadyRepaired : TerminalRecoveryRepairResult
    object NotApplicable : TerminalRecoveryRepairResult
    object LiveOwnerPresent : TerminalRecoveryRepairResult
    object OwnershipRejected : TerminalRecoveryRepairResult
    object UserPaused : TerminalRecoveryRepairResult
    object Cancelling : TerminalRecoveryRepairResult
    object Terminal : TerminalRecoveryRepairResult
    data class StorageFailure(val cause: Throwable) : TerminalRecoveryRepairResult
}

sealed interface RecordSourceReadResult {
    data class Persisted(val sourceRead: SourceRead) : RecordSourceReadResult
    data class ReusedExisting(val sourceRead: SourceRead) : RecordSourceReadResult
    object StaleOwnership : RecordSourceReadResult
    object TaskMismatch : RecordSourceReadResult
    object FetchClaimMissing : RecordSourceReadResult
    object FetchClaimAmbiguous : RecordSourceReadResult
    object GoalTerminal : RecordSourceReadResult
    data class StorageFailure(val cause: Throwable) : RecordSourceReadResult
}

sealed interface SourceFetchClaimResult {
    data class Claimed(val attempt: SourceFetchAttempt) : SourceFetchClaimResult
    data class ReusedExisting(val attempt: SourceFetchAttempt) : SourceFetchClaimResult
    object StaleOwnership : SourceFetchClaimResult
    object TaskMismatch : SourceFetchClaimResult
    object GoalTerminal : SourceFetchClaimResult
    data class StorageFailure(val cause: Throwable) : SourceFetchClaimResult
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

sealed class TypedRepairResult {
    object Repaired : TypedRepairResult()
    object AlreadyRepaired : TypedRepairResult()
    object NotApplicable : TypedRepairResult()
    object LiveOwnerPresent : TypedRepairResult()
    object ProviderReconciliationRequired : TypedRepairResult()
    object UserPaused : TypedRepairResult()
    object Cancelling : TypedRepairResult()
    object Terminal : TypedRepairResult()
    data class StorageFailure(val cause: Throwable) : TypedRepairResult()
}

open class AgentStore private constructor(
    context: Context?,
    baseDir: File?,
    prefs: SharedPreferences?,
) : AgentRefreshSource {
    constructor(context: Context) : this(context = context, baseDir = null, prefs = null)
    constructor(baseDir: File) : this(context = null, baseDir = baseDir, prefs = null)
    internal constructor() : this(context = null, baseDir = null, prefs = null)

    private val preferences: SharedPreferences? = prefs ?: context?.applicationContext?.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val diagnostics: com.david.openassistant.data.diagnostics.RuntimeDiagnostics? = context?.let { com.david.openassistant.data.diagnostics.RuntimeDiagnostics(it) }
    private val goalsDirectory: File = when {
        baseDir != null -> File(baseDir, GOALS_DIRECTORY_NAME)
        context != null -> File(context.applicationContext.filesDir, GOALS_DIRECTORY_NAME)
        else -> throw IllegalArgumentException("AgentStore requires either a Context or a base directory File.")
    }

    private val goalCache = ConcurrentHashMap<String, CachedGoal>()
    private data class CachedGoal(val goal: AgentGoal, val fileTimestamp: Long, val fileLength: Long)

    open fun loadSnapshot(): AgentSnapshot = synchronized(STORE_LOCK) { readCount.incrementAndGet(); loadSnapshotLocked() }
    override suspend fun loadStableSnapshot(): AgentSnapshotWithRevision = synchronized(STORE_LOCK) { readCount.incrementAndGet(); AgentSnapshotWithRevision(loadSnapshotLocked(), getLatestRevision()) }
    override fun getLatestRevision(): Long = preferences?.getLong(KEY_REVISION, 0L) ?: 0L

    fun saveSnapshot(snapshot: AgentSnapshot) = synchronized(STORE_LOCK) { migrateLegacyIfNeededLocked(); saveSnapshotLocked(snapshot) }

    fun upsertGoal(goal: AgentGoal, select: Boolean = false): AgentSnapshot = synchronized(STORE_LOCK) {
        migrateLegacyIfNeededLocked()
        val current = loadSnapshotFromFilesLocked()
        writeGoalLocked(goal, signal = false)
        val selectedId = when {
            select -> goal.id
            current.selectedGoalId != null && (current.goals.any { it.id == current.selectedGoalId } || current.selectedGoalId == goal.id) -> current.selectedGoalId
            else -> goal.id
        }
        writeSelectionAndSignalLocked(selectedId)
        loadSnapshotFromFilesLocked()
    }

    fun updateGoal(goalId: String, transform: (AgentGoal) -> AgentGoal): AgentSnapshot = synchronized(STORE_LOCK) { migrateLegacyIfNeededLocked(); updateGoalInternalLocked(goalId, transform) }
    fun updateGoalAtomic(goalId: String, ticket: AgentOwnershipTicket?, transform: (AgentGoal) -> AgentGoal): AgentSnapshot = synchronized(STORE_LOCK) { if (ticket != null && validateTicketInternalLocked(ticket) !is TicketValidationResult.Valid) return@synchronized loadSnapshotFromFilesLocked(); migrateLegacyIfNeededLocked(); updateGoalInternalLocked(goalId, transform) }

    fun refreshExecutionLease(goalId: String, workerId: String, attemptId: String, leaseGeneration: Int, taskId: String?): RefreshLeaseResult = synchronized(STORE_LOCK) {
        migrateLegacyIfNeededLocked(); val goal = loadSnapshotFromFilesLocked().goals.firstOrNull { it.id == goalId } ?: return@synchronized RefreshLeaseResult.GoalMissing
        val lease = goal.executionLease ?: return@synchronized RefreshLeaseResult.LeaseLost
        if (lease.workerId != workerId || lease.attemptId != attemptId || lease.generation != leaseGeneration || lease.taskId != (taskId ?: "none") || lease.ownerProcessSessionId != com.david.openassistant.data.diagnostics.DiagnosticEvent.PROCESS_SESSION_ID) return@synchronized RefreshLeaseResult.LeaseLost
        runCatching { writeGoalLocked(goal.copy(executionLease = lease.copy(heartbeatAt = System.currentTimeMillis()), updatedAt = System.currentTimeMillis())) }.onFailure { return@synchronized RefreshLeaseResult.StorageFailure(it) }; RefreshLeaseResult.Refreshed
    }

    fun acquirePlanningLeaseAtomic(goalId: String, workerId: String): LeaseAcquisitionResult = synchronized(STORE_LOCK) { acquireLeaseInternalLocked(goalId, workerId, null) }
    fun acquireTaskLeaseAtomic(goalId: String, workerId: String, taskId: String): LeaseAcquisitionResult = synchronized(STORE_LOCK) { if (taskId.isBlank() || taskId == "none") return@synchronized LeaseAcquisitionResult.Rejected("Invalid task ID."); acquireLeaseInternalLocked(goalId, workerId, taskId) }

    fun repairRecoveryStarvationAtomic(goalId: String): Boolean = synchronized(STORE_LOCK) {
        val goal = loadSnapshotFromFilesLocked().goals.firstOrNull { it.id == goalId } ?: return false
        val plan = goal.activeRecoveryPlanId?.let { id -> goal.recoveryPlans.firstOrNull { it.id == id } } ?: return false
        if (plan.status.isTerminal() || goal.executionLease?.let { !AgentLeasePolicy.isStale(it) && it.ownerProcessSessionId == com.david.openassistant.data.diagnostics.DiagnosticEvent.PROCESS_SESSION_ID } == true) return false
        val key = "recovery_starvation_repair:${goal.id}:${plan.taskId}:${plan.id}:${plan.inputExecutionFingerprint}"
        if (goal.idempotencyRecords.any { it.key == key }) return false
        writeGoalLocked(goal.copy(executionLease = null, idempotencyRecords = goal.idempotencyRecords + IdempotencyRecord(key = key, effectType = IdempotencyEffectType.SYSTEM_REPAIR, state = IdempotencyState.COMMITTED, claimOwner = "system", committedAt = System.currentTimeMillis()), events = appendEvent(goal.events, "Applied structural repair for recovery plan starvation."), updatedAt = System.currentTimeMillis())); true
    }

    fun repairTerminalRecoveryLivelockAtomic(goalId: String, ticket: PlanningTicket? = null): TerminalRecoveryRepairResult = synchronized(STORE_LOCK) {
        val goal = loadSnapshotFromFilesLocked().goals.firstOrNull { it.id == goalId } ?: return TerminalRecoveryRepairResult.NotApplicable
        if (goal.status.isFinalTerminalStatus()) return TerminalRecoveryRepairResult.Terminal
        if (ticket != null) { if (ticket.leaseGeneration != goal.leaseGeneration || goal.executionLease?.workerId != ticket.workerId) return TerminalRecoveryRepairResult.OwnershipRejected }
        else if (goal.executionLease?.let { !AgentLeasePolicy.isStale(it) && it.ownerProcessSessionId == com.david.openassistant.data.diagnostics.DiagnosticEvent.PROCESS_SESSION_ID } == true) return TerminalRecoveryRepairResult.LiveOwnerPresent
        val plan = goal.activeRecoveryPlanId?.let { id -> goal.recoveryPlans.firstOrNull { it.id == id } } ?: return TerminalRecoveryRepairResult.NotApplicable
        if (plan.status != RecoveryPlanStatus.FAILED_RETRYABLE) return TerminalRecoveryRepairResult.NotApplicable
        val rid = plan.logicalProviderRequestId ?: "recovery-${plan.id}"
        val failed = goal.requestAttempts.filter { it.logicalRequestId == rid }.maxByOrNull { it.wireAttemptOrdinal } ?: return TerminalRecoveryRepairResult.NotApplicable
        val key = "terminal-recovery-reconciliation-v1:${goal.id}:${plan.id}:$rid:${failed.exchangeId}"; if (goal.idempotencyRecords.any { it.key == key }) return TerminalRecoveryRepairResult.AlreadyRepaired
        val auth = ProviderRetryAuthorization(logicalRequestId = rid, payloadFingerprint = failed.payloadFingerprint, executionGeneration = goal.leaseGeneration, previousExchangeId = failed.exchangeId, failureClass = failed.failureClass ?: "UNKNOWN", deliveryCertainty = failed.deliveryCertainty, attemptOrdinal = failed.wireAttemptOrdinal + 1)
        writeGoalLocked(goal.copy(recoveryPlans = goal.recoveryPlans.map { if (it.id == plan.id) it.copy(status = RecoveryPlanStatus.GENERATING) else it }, retryAuthorizations = goal.retryAuthorizations + auth, idempotencyRecords = goal.idempotencyRecords + IdempotencyRecord(key = key, effectType = IdempotencyEffectType.SYSTEM_REPAIR, state = IdempotencyState.COMMITTED, claimOwner = "system", committedAt = System.currentTimeMillis()), events = appendEvent(goal.events, "Applied terminal recovery livelock repair."), updatedAt = System.currentTimeMillis())); TerminalRecoveryRepairResult.AuthorizedRetry(rid, failed.wireAttemptOrdinal, auth.attemptOrdinal, "$rid-${auth.attemptOrdinal}")
    }

    fun claimSourceFetchAtomic(ticket: TaskExecutionTicket, taskId: String, canonicalUrl: String, fetchFingerprint: String): SourceFetchClaimResult = synchronized(STORE_LOCK) {
        val goal = loadSnapshotFromFilesLocked().goals.firstOrNull { it.id == ticket.goalId } ?: return SourceFetchClaimResult.GoalTerminal
        if (goal.leaseGeneration != ticket.leaseGeneration || goal.executionLease?.workerId != ticket.workerId) return SourceFetchClaimResult.StaleOwnership
        val lfid = "fetch-${goal.id}-$taskId-$canonicalUrl"; val existing = goal.fetchAttempts.firstOrNull { it.logicalFetchId == lfid && it.fetchFingerprint == fetchFingerprint }
        if (existing != null) return SourceFetchClaimResult.ReusedExisting(existing)
        val attempt = SourceFetchAttempt(id = UUID.randomUUID().toString(), logicalFetchId = lfid, goalId = goal.id, taskId = taskId, canonicalUrl = canonicalUrl, fetchFingerprint = fetchFingerprint, attemptOrdinal = (goal.fetchAttempts.filter { it.logicalFetchId == lfid }.maxOfOrNull { it.attemptOrdinal } ?: 0) + 1, executionGeneration = goal.leaseGeneration, status = SourceFetchStatus.CLAIMED, transportStage = SourceFetchTransportStage.NOT_DISPATCHED, deliveryCertainty = ProviderDeliveryCertainty.NOT_SENT, retryAuthorizationFingerprint = null, sourceReadId = null, failureClassification = null, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
        writeGoalLocked(goal.copy(fetchAttempts = goal.fetchAttempts + attempt, updatedAt = System.currentTimeMillis())); SourceFetchClaimResult.Claimed(attempt)
    }

    fun commitSourceReadAtomic(ticket: TaskExecutionTicket, fetchClaimId: String, sourceRead: SourceRead, toolAccounting: AgentToolExecution): RecordSourceReadResult = synchronized(STORE_LOCK) {
        val goal = loadSnapshotFromFilesLocked().goals.firstOrNull { it.id == ticket.goalId } ?: return RecordSourceReadResult.GoalTerminal
        if (goal.leaseGeneration != ticket.leaseGeneration || goal.executionLease?.workerId != ticket.workerId) return RecordSourceReadResult.StaleOwnership
        val attempt = goal.fetchAttempts.firstOrNull { it.id == fetchClaimId } ?: return RecordSourceReadResult.FetchClaimMissing
        if (attempt.status == SourceFetchStatus.SOURCE_READ_COMMITTED) return goal.sourceReads.firstOrNull { it.id == attempt.sourceReadId }?.let { RecordSourceReadResult.ReusedExisting(it) } ?: RecordSourceReadResult.FetchClaimAmbiguous
        val er = goal.sourceReads.firstOrNull { it.id == sourceRead.id }; val now = System.currentTimeMillis()
        writeGoalLocked(goal.copy(fetchAttempts = goal.fetchAttempts.map { if (it.id == attempt.id) it.copy(status = SourceFetchStatus.SOURCE_READ_COMMITTED, sourceReadId = er?.id ?: sourceRead.id, updatedAt = now) else it }, sourceReads = mergeSourceReads(goal.sourceReads, listOf(er ?: sourceRead)), toolExecutions = goal.toolExecutions + toolAccounting, updatedAt = now)); if (er != null) RecordSourceReadResult.ReusedExisting(er) else RecordSourceReadResult.Persisted(sourceRead)
    }

    fun updateProviderTransportStage(goalId: String, exchangeId: String, stage: ProviderTransportStage, certainty: ProviderDeliveryCertainty? = null): UpdateTransportStageResult = synchronized(STORE_LOCK) {
        migrateLegacyIfNeededLocked(); val goal = loadSnapshotFromFilesLocked().goals.firstOrNull { it.id == goalId } ?: return@synchronized UpdateTransportStageResult.GoalMissing
        val att = goal.requestAttempts.firstOrNull { it.exchangeId == exchangeId } ?: return@synchronized UpdateTransportStageResult.ExchangeMissing
        if (stage.ordinal <= att.transportStage.ordinal && stage != ProviderTransportStage.TERMINAL) return@synchronized UpdateTransportStageResult.AlreadyAtOrBeyondStage
        writeGoalLocked(goal.copy(requestAttempts = goal.requestAttempts.map { if (it.exchangeId == exchangeId) att.copy(transportStage = stage, deliveryCertainty = certainty ?: it.deliveryCertainty) else it }, updatedAt = System.currentTimeMillis())); UpdateTransportStageResult.Updated
    }

    fun authorizeRetry(goalId: String, authorization: ProviderRetryAuthorization): AuthorizeRetryResult = synchronized(STORE_LOCK) {
        migrateLegacyIfNeededLocked(); val goal = loadSnapshotFromFilesLocked().goals.firstOrNull { it.id == goalId } ?: return@synchronized AuthorizeRetryResult.GoalMissing
        if (goal.retryAuthorizations.any { it.logicalRequestId == authorization.logicalRequestId && it.attemptOrdinal == authorization.attemptOrdinal && it.executionGeneration == authorization.executionGeneration }) return@synchronized AuthorizeRetryResult.AlreadyAuthorized
        writeGoalLocked(goal.copy(retryAuthorizations = goal.retryAuthorizations + authorization, updatedAt = System.currentTimeMillis())); AuthorizeRetryResult.Authorized
    }

    fun repairUniversalToolAvailabilityStateAtomic(goalId: String): TypedRepairResult = synchronized(STORE_LOCK) {
        val goal = loadSnapshotFromFilesLocked().goals.firstOrNull { it.id == goalId } ?: return@synchronized TypedRepairResult.NotApplicable
        if (goal.status.isFinalTerminalStatus()) return@synchronized TypedRepairResult.Terminal
        if (goal.executionLease?.let { !AgentLeasePolicy.isStale(it) && it.ownerProcessSessionId == com.david.openassistant.data.diagnostics.DiagnosticEvent.PROCESS_SESSION_ID } == true) return@synchronized TypedRepairResult.LiveOwnerPresent
        val key = "universal-tool-availability-v3:${goal.id}"; if (goal.idempotencyRecords.any { it.key == key }) return@synchronized TypedRepairResult.AlreadyRepaired
        val restrictedMarkers = setOf("evidence-bounded", "model-only", "without new searches", "without tool loops")
        val isRestricted = goal.isToolRestricted || goal.failureClassification == MissionFailureClassification.TOOL_RESTRICTED_PHASE_STALL || (goal.error?.lowercase()?.let { err -> restrictedMarkers.any { err.contains(it) } } ?: false)
        if (!isRestricted && goal.noProgressCount < 2) return@synchronized TypedRepairResult.NotApplicable
        val repairedTasks = goal.tasks.map { if (it.isToolRestricted || it.status == AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE) it.copy(status = AgentTaskStatus.QUEUED, isToolRestricted = false, attemptCount = 0) else it }
        writeGoalLocked(goal.copy(status = AgentGoalStatus.QUEUED, isToolRestricted = false, failureClassification = MissionFailureClassification.NONE, tasks = repairedTasks, noProgressCount = 0, idempotencyRecords = goal.idempotencyRecords + IdempotencyRecord(key = key, effectType = IdempotencyEffectType.SYSTEM_REPAIR, state = IdempotencyState.COMMITTED, claimOwner = "system", committedAt = System.currentTimeMillis()), updatedAt = System.currentTimeMillis(), error = null)); TypedRepairResult.Repaired
    }

    private fun acquireLeaseInternalLocked(goalId: String, workerId: String, taskId: String?): LeaseAcquisitionResult {
        migrateLegacyIfNeededLocked(); val goal = loadSnapshotFromFilesLocked().goals.firstOrNull { it.id == goalId } ?: return LeaseAcquisitionResult.RetryRequired
        if (goal.status.isFinalTerminalStatus() || goal.status == AgentGoalStatus.REJECTED || goal.status == AgentGoalStatus.BLOCKED) return LeaseAcquisitionResult.MissionTerminal
        val sid = com.david.openassistant.data.diagnostics.DiagnosticEvent.PROCESS_SESSION_ID; val existing = goal.executionLease
        if (existing == null || AgentLeasePolicy.isStale(existing) || existing.ownerProcessSessionId != sid || (existing.workerId == workerId && existing.ownerProcessSessionId == sid)) {
            val newGen = maxOf(goal.leaseGeneration, existing?.generation ?: 0) + 1
            val reloaded = writeGoalLocked(goal.copy(status = if (goal.status == AgentGoalStatus.QUEUED) AgentGoalStatus.RUNNING else goal.status, leaseGeneration = newGen, executionLease = AgentExecutionLease(workerId = workerId, ownerProcessSessionId = sid, taskId = taskId ?: "none", attemptId = UUID.randomUUID().toString(), generation = newGen, acquiredAt = System.currentTimeMillis(), heartbeatAt = System.currentTimeMillis()), updatedAt = System.currentTimeMillis()))
            val rl = reloaded.executionLease!!; val ticket = if (taskId != null) TaskExecutionTicket(goalId, rl.taskId, workerId, rl.ownerProcessSessionId, rl.generation, reloaded.executionGeneration, rl.attemptId, rl.acquiredAt) else PlanningTicket(goalId, workerId, rl.ownerProcessSessionId, rl.generation, reloaded.executionGeneration, rl.attemptId, rl.acquiredAt)
            return LeaseAcquisitionResult.Acquired(ticket, reloaded)
        } else return LeaseAcquisitionResult.LiveOwnerPresent
    }

    private fun validateTicketInternalLocked(t: AgentOwnershipTicket): TicketValidationResult {
        val goal = loadSnapshotFromFilesLocked().goals.firstOrNull { it.id == t.goalId } ?: return TicketValidationResult.LeaseMissing
        val lease = goal.executionLease ?: return TicketValidationResult.LeaseMissing
        return when {
            lease.workerId != t.workerId -> TicketValidationResult.Mismatch("WORKER_MISMATCH", "workerId", lease.workerId, t.workerId)
            lease.ownerProcessSessionId != t.ownerProcessSessionId -> TicketValidationResult.Mismatch("PROCESS_SESSION_MISMATCH", "ownerProcessSessionId", lease.ownerProcessSessionId, t.ownerProcessSessionId)
            lease.generation != t.leaseGeneration -> TicketValidationResult.Mismatch("LEASE_GENERATION_MISMATCH", "leaseGeneration", lease.generation.toString(), t.leaseGeneration.toString())
            goal.executionGeneration != t.executionGeneration -> TicketValidationResult.Mismatch("EXECUTION_GENERATION_MISMATCH", "executionGeneration", goal.executionGeneration.toString(), t.executionGeneration.toString())
            AgentLeasePolicy.isStale(lease) -> TicketValidationResult.LeaseExpired
            else -> TicketValidationResult.Valid
        }
    }

    open fun validateTicket(t: AgentOwnershipTicket): TicketValidationResult = synchronized(STORE_LOCK) { validateTicketInternalLocked(t) }
    fun releaseLeaseAtomic(t: AgentOwnershipTicket): Boolean = synchronized(STORE_LOCK) { if (validateTicketInternalLocked(t) !is TicketValidationResult.Valid) return@synchronized false; val goal = loadSnapshotFromFilesLocked().goals.firstOrNull { it.id == t.goalId } ?: return@synchronized false; writeGoalLocked(goal.copy(executionLease = null, updatedAt = System.currentTimeMillis())); true }
    fun createActiveRequestAttempt(goalId: String, attempt: ProviderRequestAttempt, context: ProviderRequestContext.Mission): CreateAttemptResult = synchronized(STORE_LOCK) { migrateLegacyIfNeededLocked(); val goal = loadSnapshotFromFilesLocked().goals.firstOrNull { it.id == goalId } ?: return@synchronized CreateAttemptResult.GoalMissing; if (goal.executionLease == null || AgentLeasePolicy.isStale(goal.executionLease) || context.executionGeneration != goal.executionGeneration) return@synchronized CreateAttemptResult.InvalidLeaseOrGoalState; writeGoalLocked(goal.copy(requestAttempts = goal.requestAttempts + attempt, updatedAt = System.currentTimeMillis())); CreateAttemptResult.Created }

    open fun claimOrReconcileProviderRequestAtomic(
        goalId: String,
        logicalRequestId: String,
        operation: MissionOperation,
        payloadFingerprint: String,
        ticket: AgentOwnershipTicket,
        parentOperationId: String? = null,
        role: AgentTaskRole? = null,
        recoveryPlanId: String? = null,
        wirePayloadFingerprint: String? = null,
        wireVariantKind: ProviderWireVariantKind? = null,
        wireVariantOrdinal: Int = 0,
        fingerprintSchemaVersion: Int = 1
    ): ReconciliationResult = synchronized(STORE_LOCK) {
        migrateLegacyIfNeededLocked()
        val currentSnapshot = loadSnapshotFromFilesLocked()
        val goal = currentSnapshot.goals.firstOrNull { it.id == goalId } ?: return@synchronized ReconciliationResult.GoalMissing(AgentGoalStatus.QUEUED) // Should not happen with valid ticket

        val validation = validateTicketInternalLocked(ticket)
        if (validation !is TicketValidationResult.Valid) {
            return@synchronized when (validation) {
                is TicketValidationResult.Mismatch -> when (validation.field) {
                    "leaseGeneration" -> ReconciliationResult.StaleOwnership(goal.leaseGeneration, ticket.leaseGeneration)
                    "executionGeneration" -> ReconciliationResult.StaleOwnership(goal.executionGeneration, ticket.executionGeneration)
                    else -> ReconciliationResult.OwnershipMismatch
                }
                is TicketValidationResult.LeaseExpired -> ReconciliationResult.StaleOwnership(goal.leaseGeneration, ticket.leaseGeneration)
                else -> ReconciliationResult.OwnershipMismatch
            }
        }

        if (goal.status.isFinalTerminalStatus()) return@synchronized ReconciliationResult.GoalTerminal(goal.status)

        // Enforce recovery operation binding contract
        if (operation == MissionOperation.RECOVERY_PROPOSAL || operation == MissionOperation.CYCLE_ADVANCE) {
            if (ticket !is PlanningTicket) return@synchronized ReconciliationResult.OwnershipMismatch
            if (recoveryPlanId == null || recoveryPlanId != goal.activeRecoveryPlanId) {
                return@synchronized ReconciliationResult.RecoveryPlanMismatch(goal.activeRecoveryPlanId ?: "none", recoveryPlanId)
            }
            if (goal.status != AgentGoalStatus.RECOVERING) return@synchronized ReconciliationResult.GoalTerminal(goal.status)
        }

        // Search for existing logical request across all generations, matching exact wire candidate identity
        val effectiveWireVariantKind = wireVariantKind ?: ProviderWireVariantKind.PRIMARY
        val existingAttempts = goal.requestAttempts.filter { 
            val existingKind = it.wireVariantKind ?: ProviderWireVariantKind.PRIMARY
            it.logicalRequestId == logicalRequestId && 
            it.recoveryPlanId == recoveryPlanId &&
            existingKind == effectiveWireVariantKind &&
            it.wireVariantOrdinal == wireVariantOrdinal
        }
        
        val latestAttempt = existingAttempts.maxByOrNull { it.startedAt }
        
        if (latestAttempt != null) {
            // Schema-based fingerprint validation
            if (latestAttempt.fingerprintSchemaVersion == 1) {
                // Schema 1: Match legacy logical fingerprint only
                if (latestAttempt.payloadFingerprint != payloadFingerprint) {
                    return@synchronized ReconciliationResult.LogicalIdentityConflict(latestAttempt.payloadFingerprint, payloadFingerprint)
                }
            } else if (latestAttempt.fingerprintSchemaVersion == 2) {
                // Schema 2: Match logical AND wire fingerprints
                if (latestAttempt.payloadFingerprint != payloadFingerprint || 
                    latestAttempt.wirePayloadFingerprint != wirePayloadFingerprint) {
                    val actualFp = "L:${latestAttempt.payloadFingerprint} W:${latestAttempt.wirePayloadFingerprint}"
                    val requestedFp = "L:$payloadFingerprint W:$wirePayloadFingerprint"
                    
                    diagnostics?.warning(
                        event = "provider_request_reconciliation_conflict",
                        component = "reconciliation",
                        fields = mapOf(
                            "goal_id" to goalId,
                            "logical_id" to logicalRequestId,
                            "actual_fp" to actualFp,
                            "requested_fp" to requestedFp
                        )
                    )
                    return@synchronized ReconciliationResult.LogicalIdentityConflict(actualFp, requestedFp)
                }
            } else {
                // Unknown future schema: Fail safely
                return@synchronized ReconciliationResult.StorageFailure(
                    IllegalStateException("Unknown fingerprint schema version: ${latestAttempt.fingerprintSchemaVersion}")
                )
            }

            return when (latestAttempt.exchangeOutcome) {
                ExchangeOutcome.ACTIVE -> {
                    if (latestAttempt.reconciliationClaimOwner == ticket.workerId && 
                        latestAttempt.executionGeneration == ticket.leaseGeneration) {
                        // Resume locally owned
                        when {
                            latestAttempt.transportStage == ProviderTransportStage.NOT_DISPATCHED -> ReconciliationResult.NewDispatchClaimed(latestAttempt) // Treat as new dispatch if not sent
                            else -> ReconciliationResult.ExistingInFlight(latestAttempt)
                        }
                    } else if (latestAttempt.transportStage == ProviderTransportStage.NOT_DISPATCHED && ticket.leaseGeneration > latestAttempt.executionGeneration) {
                        val exchangeId = "openrouter-${UUID.randomUUID()}"
                        val newAttempt = latestAttempt.copy(
                            exchangeId = exchangeId,
                            wireAttemptOrdinal = latestAttempt.wireAttemptOrdinal + 1,
                            previousExchangeId = latestAttempt.exchangeId,
                            executionGeneration = ticket.leaseGeneration,
                            payloadFingerprint = payloadFingerprint,
                            wirePayloadFingerprint = wirePayloadFingerprint,
                            fingerprintSchemaVersion = fingerprintSchemaVersion,
                            reconciliationClaimOwner = ticket.workerId,
                            reconciliationClaimedAt = System.currentTimeMillis(),
                            startedAt = System.currentTimeMillis()
                        )
                        val updatedGoal = goal.copy(
                            requestAttempts = goal.requestAttempts + newAttempt,
                            updatedAt = System.currentTimeMillis()
                        )
                        writeGoalLocked(updatedGoal)
                        ReconciliationResult.NewDispatchClaimed(newAttempt)
                    } else {
                        // Check if owner is still live? For now, if ACTIVE, it's either in-flight or ambiguous
                        ReconciliationResult.ExistingAmbiguous(latestAttempt)
                    }
                }
                ExchangeOutcome.RESPONSE_SUCCESS -> {
                    // DESIGN A: Check if proposal exists in the recovery plan
                    val plan = goal.recoveryPlans.firstOrNull { it.id == recoveryPlanId }
                    if (plan != null && plan.proposal != null) {
                        ReconciliationResult.ExistingSuccessfulResultAvailable(latestAttempt, plan.proposal, plan.accountingSummary, latestAttempt.reconciledResponseContent)
                    } else if (latestAttempt.reconciledResponseContent != null) {
                        ReconciliationResult.ExistingSuccessfulResultAvailable(latestAttempt, null, null, latestAttempt.reconciledResponseContent)
                    } else {
                        ReconciliationResult.ExistingSuccessfulResultMissing(latestAttempt)
                    }
                }
                ExchangeOutcome.RESPONSE_ERROR, 
                ExchangeOutcome.TRANSPORT_FAILURE,
                ExchangeOutcome.CANCELLED,
                ExchangeOutcome.CANCELLATION_TIMEOUT,
                ExchangeOutcome.REFUSAL,
                ExchangeOutcome.RATE_LIMITED,
                ExchangeOutcome.AUTHENTICATION_FAILED,
                ExchangeOutcome.PROVIDER_UNAVAILABLE,
                ExchangeOutcome.RECOVERABLE_NETWORK_FAILURE,
                ExchangeOutcome.PERMANENT_FAILURE,
                ExchangeOutcome.MALFORMED_STRUCTURED_RESPONSE -> {
                    // Check for authorization: MUST match the exact wire candidate identity
                    val auth = goal.retryAuthorizations.firstOrNull { 
                        it.logicalRequestId == logicalRequestId && 
                        it.attemptOrdinal == latestAttempt.wireAttemptOrdinal + 1 &&
                        it.executionGeneration == ticket.leaseGeneration &&
                        (it.fingerprintSchemaVersion == 1 || (
                            it.wirePayloadFingerprint == wirePayloadFingerprint &&
                            it.wireVariantKind == wireVariantKind &&
                            it.wireVariantOrdinal == wireVariantOrdinal
                        ))
                    }
                    if (auth != null) {
                        // Claim retry
                        val exchangeId = "openrouter-${UUID.randomUUID()}"
                        val newAttempt = latestAttempt.copy(
                            exchangeId = exchangeId,
                            wireAttemptOrdinal = latestAttempt.wireAttemptOrdinal + 1,
                            previousExchangeId = latestAttempt.exchangeId,
                            transportStage = ProviderTransportStage.NOT_DISPATCHED,
                            deliveryCertainty = ProviderDeliveryCertainty.NOT_SENT,
                            executionGeneration = ticket.leaseGeneration,
                            payloadFingerprint = payloadFingerprint,
                            wirePayloadFingerprint = wirePayloadFingerprint,
                            fingerprintSchemaVersion = fingerprintSchemaVersion,
                            wireVariantKind = wireVariantKind,
                            wireVariantOrdinal = wireVariantOrdinal,
                            exchangeOutcome = ExchangeOutcome.ACTIVE,
                            startedAt = System.currentTimeMillis(),
                            reconciliationClaimOwner = ticket.workerId,
                            reconciliationClaimedAt = System.currentTimeMillis(),
                            finishedAt = null,
                            httpStatusCode = null,
                            failureClass = null,
                            providerResponseId = null,
                            safeDiagnosticSummary = null
                        )
                        val updatedGoal = goal.copy(
                            requestAttempts = goal.requestAttempts + newAttempt,
                            retryAuthorizations = goal.retryAuthorizations.filter { it != auth },
                            updatedAt = System.currentTimeMillis()
                        )
                        writeGoalLocked(updatedGoal)
                        ReconciliationResult.RetryDispatchClaimed(newAttempt)
                    } else {
                        ReconciliationResult.ExistingTerminalFailure(latestAttempt)
                    }
                }
                ExchangeOutcome.INTERRUPTED_OUTCOME_UNKNOWN,
                ExchangeOutcome.UNCERTAIN_REMOTE_OUTCOME,
                ExchangeOutcome.UNUSABLE_EMPTY_RESPONSE,
                ExchangeOutcome.UNUSABLE_WHITESPACE_RESPONSE,
                ExchangeOutcome.USABLE_STRUCTURED_RESULT -> {
                    // Reconcile ambiguous
                    ReconciliationResult.ExistingAmbiguous(latestAttempt)
                }
            }
        }
        
        // Create new attempt
        val exchangeId = "openrouter-${UUID.randomUUID()}"
        val newAttempt = ProviderRequestAttempt(
            exchangeId = exchangeId,
            logicalRequestId = logicalRequestId,
            wireAttemptOrdinal = 1,
            parentOperationId = parentOperationId ?: logicalRequestId,
            goalId = goalId,
            taskId = if (operation.taskBound) ticket.taskId else null,
            executionGeneration = ticket.leaseGeneration,
            requestedModel = "unknown", // To be filled by client
            role = role,
            payloadFingerprint = payloadFingerprint,
            wirePayloadFingerprint = wirePayloadFingerprint,
            fingerprintSchemaVersion = fingerprintSchemaVersion,
            wireVariantKind = wireVariantKind,
            wireVariantOrdinal = wireVariantOrdinal,
            exchangeOutcome = ExchangeOutcome.ACTIVE,
            transportStage = ProviderTransportStage.NOT_DISPATCHED,
            deliveryCertainty = ProviderDeliveryCertainty.NOT_SENT,
            recoveryPlanId = recoveryPlanId,
            reconciliationClaimOwner = ticket.workerId,
            reconciliationClaimedAt = System.currentTimeMillis(),
            startedAt = System.currentTimeMillis()
        )
        
        val updatedGoal = goal.copy(
            requestAttempts = goal.requestAttempts + newAttempt,
            updatedAt = System.currentTimeMillis()
        )
        writeGoalLocked(updatedGoal)
        ReconciliationResult.NewDispatchClaimed(newAttempt)
    }

    fun transitionRecoveryPlanAtomic(ticket: PlanningTicket, planId: String, expectedStatus: RecoveryPlanStatus, nextStatus: RecoveryPlanStatus, expectedInputFingerprint: String, mutation: (AgentGoal, ResearchRecoveryPlan) -> AgentGoal): RecoveryPlanTransitionResult = synchronized(STORE_LOCK) {
        val goal = loadSnapshotFromFilesLocked().goals.firstOrNull { it.id == ticket.goalId } ?: return RecoveryPlanTransitionResult.GoalMissing
        if (goal.status.isFinalTerminalStatus()) return RecoveryPlanTransitionResult.GoalTerminal
        if (validateTicketInternalLocked(ticket) !is TicketValidationResult.Valid) return RecoveryPlanTransitionResult.OwnershipRejected
        val plan = goal.recoveryPlans.firstOrNull { it.id == planId } ?: return RecoveryPlanTransitionResult.PlanMissing
        if (plan.status == nextStatus) return RecoveryPlanTransitionResult.AlreadyAtTarget(goal, plan)
        if (plan.status != expectedStatus || plan.inputExecutionFingerprint != expectedInputFingerprint) return RecoveryPlanTransitionResult.StatusMismatch(expectedStatus, plan.status)
        val updatedGoal = mutation(goal, plan); val finalPlan = updatedGoal.recoveryPlans.firstOrNull { it.id == planId }?.copy(status = nextStatus) ?: return RecoveryPlanTransitionResult.PlanMissing
        writeGoalLocked(updatedGoal.copy(recoveryPlans = updatedGoal.recoveryPlans.map { if (it.id == planId) finalPlan else it }, updatedAt = System.currentTimeMillis())); RecoveryPlanTransitionResult.Committed(updatedGoal, finalPlan)
    }

    fun commitRecoveryProposalAtomic(ticket: PlanningTicket, planId: String, expectedInputFingerprint: String, logicalProviderRequestId: String, proposal: RecoveryProposal, proposalFingerprint: String, accountingSummary: AgentApiSummary?, retryAuthorizedFingerprint: String?, targetStatus: RecoveryPlanStatus, mutation: ((AgentGoal, ResearchRecoveryPlan) -> AgentGoal)? = null): RecoveryPlanTransitionResult = synchronized(STORE_LOCK) {
        val goal = loadSnapshotFromFilesLocked().goals.firstOrNull { it.id == ticket.goalId } ?: return RecoveryPlanTransitionResult.GoalMissing
        if (validateTicketInternalLocked(ticket) !is TicketValidationResult.Valid) return RecoveryPlanTransitionResult.OwnershipRejected
        val plan = goal.recoveryPlans.firstOrNull { it.id == planId } ?: return RecoveryPlanTransitionResult.PlanMissing
        if (plan.status == targetStatus) return RecoveryPlanTransitionResult.AlreadyAtTarget(goal, plan)
        if (plan.status != RecoveryPlanStatus.GENERATING || plan.inputExecutionFingerprint != expectedInputFingerprint) return RecoveryPlanTransitionResult.StatusMismatch(RecoveryPlanStatus.GENERATING, plan.status)
        val updatedPlan = plan.copy(status = targetStatus, proposal = proposal, proposalFingerprint = proposalFingerprint, accountingSummary = accountingSummary, retryAuthorizedFingerprint = retryAuthorizedFingerprint, generatedAt = System.currentTimeMillis())
        var updatedGoal = goal.copy(recoveryPlans = goal.recoveryPlans.map { if (it.id == planId) updatedPlan else it }, updatedAt = System.currentTimeMillis())
        if (mutation != null) updatedGoal = mutation(updatedGoal, updatedPlan)
        writeGoalLocked(updatedGoal); RecoveryPlanTransitionResult.Committed(updatedGoal, updatedPlan)
    }

    fun createRecoveryPlanAtomic(ticket: AgentOwnershipTicket, plan: ResearchRecoveryPlan): Boolean = synchronized(STORE_LOCK) {
        val goal = loadSnapshotFromFilesLocked().goals.firstOrNull { it.id == ticket.goalId } ?: return false
        if (validateTicketInternalLocked(ticket) !is TicketValidationResult.Valid || goal.recoveryPlans.any { it.id == plan.id }) return false
        writeGoalLocked(goal.copy(status = AgentGoalStatus.RECOVERING, activeRecoveryPlanId = plan.id, recoveryPlans = goal.recoveryPlans + plan, updatedAt = System.currentTimeMillis())); true
    }

    fun claimContinuationAtomic(goalId: String, fingerprint: String, claimantLeaseGeneration: Int, workName: String): ContinuationSchedulingClaim? = synchronized(STORE_LOCK) {
        val goal = loadSnapshotFromFilesLocked().goals.firstOrNull { it.id == goalId } ?: return@synchronized null
        val existing = goal.activeContinuationSchedulingClaim; if (existing != null && ((existing.continuationFingerprint == fingerprint && existing.state == ContinuationSchedulingState.CONFIRMED_ACTIVE) || (existing.state == ContinuationSchedulingState.PENDING && existing.claimantGeneration == claimantLeaseGeneration))) return@synchronized existing
        val newClaim = ContinuationSchedulingClaim(goalId = goalId, continuationFingerprint = fingerprint, claimantGeneration = claimantLeaseGeneration, workName = workName, state = ContinuationSchedulingState.PENDING)
        writeGoalLocked(goal.copy(activeContinuationSchedulingClaim = newClaim, updatedAt = System.currentTimeMillis())); newClaim
    }

    fun confirmContinuationAtomic(goalId: String, claimId: String, state: ContinuationSchedulingState, workId: String? = null, failureClass: String? = null, failureMessage: String? = null): Boolean = synchronized(STORE_LOCK) {
        val goal = loadSnapshotFromFilesLocked().goals.firstOrNull { it.id == goalId } ?: return@synchronized false
        val existing = goal.activeContinuationSchedulingClaim ?: return@synchronized false
        if (existing.claimId != claimId) return@synchronized false
        writeGoalLocked(goal.copy(activeContinuationSchedulingClaim = existing.copy(state = state, workId = workId ?: existing.workId, failureClass = failureClass, failureMessage = failureMessage, confirmedAt = if (state == ContinuationSchedulingState.CONFIRMED_ACTIVE || state == ContinuationSchedulingState.REUSED_ACTIVE) System.currentTimeMillis() else existing.confirmedAt, lastCheckedAt = System.currentTimeMillis()), updatedAt = System.currentTimeMillis())); true
    }

    fun applyUsageOnceAtomic(ticket: AgentOwnershipTicket, accountingKey: String, tokenDelta: Int?, costUsd: Double?): AgentSnapshot = synchronized(STORE_LOCK) {
        if (validateTicketInternalLocked(ticket) !is TicketValidationResult.Valid) return@synchronized loadSnapshotFromFilesLocked()
        migrateLegacyIfNeededLocked(); updateGoalInternalLocked(ticket.goalId) { current ->
            if (current.idempotencyRecords.any { it.key == accountingKey && it.state == IdempotencyState.COMMITTED }) current
            else current.withAdditionalUsage(tokenDelta, costUsd).let { it.copy(idempotencyRecords = it.idempotencyRecords + IdempotencyRecord(key = accountingKey, effectType = IdempotencyEffectType.PROVIDER_ACCOUNTING, state = IdempotencyState.COMMITTED, claimOwner = ticket.workerId, committedAt = System.currentTimeMillis(), completedBy = ticket.workerId)) }
        }
    }

    fun transitionExchangeOutcomeWithResultAtomic(goalId: String, exchangeId: String, newOutcome: ExchangeOutcome, context: ProviderRequestContext.Mission, summary: AgentApiSummary? = null, statusCode: Int? = null, failureClass: String? = null, safeDiagnosticSummary: String? = null, providerResponseId: String? = null, responseContent: String? = null): TransitionOutcomeResult = synchronized(STORE_LOCK) {
        migrateLegacyIfNeededLocked(); val goal = loadSnapshotFromFilesLocked().goals.firstOrNull { it.id == goalId } ?: return@synchronized TransitionOutcomeResult.GoalMissing
        val ex = goal.requestAttempts.firstOrNull { it.exchangeId == exchangeId } ?: return@synchronized TransitionOutcomeResult.ExchangeMissing("Exchange not found.")
        if (ex.exchangeOutcome != ExchangeOutcome.ACTIVE || validateTicketInternalLocked(context.toTicket(context.acquiredAt)) !is TicketValidationResult.Valid) return@synchronized TransitionOutcomeResult.InvalidLeaseOrGoalState
        val now = System.currentTimeMillis(); val ua = ex.copy(exchangeOutcome = newOutcome, httpStatusCode = statusCode ?: ex.httpStatusCode, failureClass = failureClass ?: ex.failureClass, safeDiagnosticSummary = safeDiagnosticSummary ?: ex.safeDiagnosticSummary, providerResponseId = providerResponseId ?: ex.providerResponseId, reconciledResponseContent = responseContent ?: ex.reconciledResponseContent, promptTokens = summary?.promptTokens ?: ex.promptTokens, completionTokens = summary?.completionTokens ?: ex.completionTokens, totalTokens = summary?.totalTokens ?: ex.totalTokens, costUsd = summary?.costUsd ?: ex.costUsd, finishedAt = now)
        writeGoalLocked(goal.copy(requestAttempts = goal.requestAttempts.map { if (it.exchangeId == exchangeId) ua else it }, updatedAt = now)); TransitionOutcomeResult.Updated(ua)
    }

    fun commitTaskResultAtomic(ticket: TaskExecutionTicket, transform: (AgentGoal) -> AgentGoal): AgentSnapshot = synchronized(STORE_LOCK) { if (validateTicketInternalLocked(ticket) !is TicketValidationResult.Valid) return@synchronized loadSnapshotFromFilesLocked(); updateGoalInternalLocked(ticket.goalId, transform) }

    private fun updateGoalInternalLocked(goalId: String, transform: (AgentGoal) -> AgentGoal): AgentSnapshot {
        val current = loadSnapshotFromFilesLocked(); val original = current.goals.firstOrNull { it.id == goalId } ?: return current
        val transformed = transform(original); if (original.status != transformed.status) AgentStateMachine.requireTransition(original.status, transformed.status)
        writeGoalLocked(transformed.copy(revision = original.revision + 1, updatedAt = System.currentTimeMillis()), signal = false)
        writeSelectionAndSignalLocked(current.selectedGoalId ?: goalId); return loadSnapshotFromFilesLocked()
    }

    fun selectGoal(goalId: String?): AgentSnapshot = synchronized(STORE_LOCK) { migrateLegacyIfNeededLocked(); val validId = goalId?.takeIf { id -> loadSnapshotFromFilesLocked().goals.any { it.id == id } }; writeSelectionAndSignalLocked(validId); loadSnapshotFromFilesLocked() }
    fun deleteGoal(goalId: String): AgentSnapshot = synchronized(STORE_LOCK) { migrateLegacyIfNeededLocked(); deleteGoalFilesLocked(goalId); val remaining = loadSnapshotFromFilesLocked().goals.filterNot { it.id == goalId }; writeSelectionAndSignalLocked(remaining.maxByOrNull { it.updatedAt }?.id); loadSnapshotFromFilesLocked() }
    fun savePendingDraft(draft: ResearchDraft?) = synchronized(STORE_LOCK) { preferences?.edit()?.putString(KEY_PENDING_DRAFT, draft?.let(::encodeDraft)?.toString())?.commit() }
    fun loadPendingDraft(): ResearchDraft? = synchronized(STORE_LOCK) { preferences?.getString(KEY_PENDING_DRAFT, null)?.let { runCatching { decodeDraft(JSONObject(it)) }.getOrNull() } }
    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { preferences?.registerOnSharedPreferenceChangeListener(listener) }
    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) { preferences?.unregisterOnSharedPreferenceChangeListener(listener) }

    private fun loadSnapshotLocked(): AgentSnapshot { migrateLegacyIfNeededLocked(); return loadSnapshotFromFilesLocked() }
    private fun loadSnapshotFromFilesLocked(): AgentSnapshot {
        goalsDirectory.mkdirs(); val quarantined = mutableListOf<MissionQuarantineEntry>()
        val goals = discoverGoalFilesLocked().mapNotNull { file ->
            try { readGoalLocked(file) } catch (error: Throwable) {
                diagnostics?.error(event = "goal_read_failed", component = "storage", throwable = error, fields = mapOf("file" to file.name))
                val recoveryArtifact = preserveCorruptGoalLocked(file, error)
                quarantined += MissionQuarantineEntry(fileName = file.name, reason = "${error::class.java.simpleName}: ${error.message.orEmpty()}", recoveryArtifactPath = recoveryArtifact?.absolutePath, baseFileSize = file.length(), backupPresent = File(file.path + ATOMIC_BACKUP_SUFFIX).exists())
                null
            }
        }.sortedByDescending { it.updatedAt }
        return AgentSnapshot(goals, preferences?.getString(KEY_SELECTED_GOAL, null)?.takeIf { sid -> goals.any { it.id == sid } } ?: goals.firstOrNull()?.id, quarantined)
    }

    private fun discoverGoalFilesLocked(): List<File> {
        goalsDirectory.mkdirs(); return goalsDirectory.listFiles().orEmpty().asSequence().filter { it.isFile }.mapNotNull { file -> when { file.name.endsWith(GOAL_FILE_SUFFIX) -> file; file.name.endsWith(GOAL_FILE_SUFFIX + ATOMIC_BACKUP_SUFFIX) -> File(file.path.removeSuffix(ATOMIC_BACKUP_SUFFIX)); else -> null } }.distinctBy { it.absolutePath }.toList()
    }

    private fun saveSnapshotLocked(snapshot: AgentSnapshot) { goalsDirectory.mkdirs(); snapshot.goals.forEach { writeGoalLocked(it, signal = false) }; val persisted = loadSnapshotFromFilesLocked(); writeSelectionAndSignalLocked(snapshot.selectedGoalId?.takeIf { id -> persisted.goals.any { it.id == id } } ?: persisted.selectedGoalId) }

    private fun migrateLegacyIfNeededLocked() {
        val prefs = preferences ?: return; if (prefs.getBoolean(KEY_MIGRATED_V2, false)) return
        goalsDirectory.mkdirs(); val legacyRaw = prefs.getString(KEY_SNAPSHOT, null)
        if (legacyRaw?.trimStart()?.startsWith("{") == true) {
            val legacySnapshot = runCatching { decodeSnapshot(requireOpenRouterObject(legacyRaw, "Legacy agent snapshot")) }.getOrElse { error -> preserveLegacySnapshotLocked(legacyRaw, error); AgentSnapshot() }
            legacySnapshot.goals.forEach { writeGoalLocked(it, signal = false) }
            prefs.edit(commit = true) { putBoolean(KEY_MIGRATED_V2, true); putString(KEY_SELECTED_GOAL, legacySnapshot.selectedGoalId); putLong(KEY_REVISION, prefs.getLong(KEY_REVISION, 0L) + 1); putString(KEY_SNAPSHOT, newRevisionSignal()) }
        } else { prefs.edit(commit = true) { putBoolean(KEY_MIGRATED_V2, true); putString(KEY_SNAPSHOT, newRevisionSignal()) } }
    }

    private fun preserveLegacySnapshotLocked(raw: String, error: Throwable) {
        goalsDirectory.mkdirs(); val recoveryFile = AtomicFile(File(goalsDirectory, "legacy_snapshot_recovery.txt"))
        var output: FileOutputStream? = null
        try { val stream = recoveryFile.startWrite(); output = stream; stream.write("OpenAssistant legacy snapshot recovery\nError: ${error::class.java.name}\nMessage: ${error.message.orEmpty()}".toByteArray(StandardCharsets.UTF_8)); recoveryFile.finishWrite(stream) } catch (writeError: Throwable) { output?.let(recoveryFile::failWrite) }
    }

    private fun preserveCorruptGoalLocked(file: File, error: Throwable): File? {
        val recoveryTarget = File(file.path + ".corrupt-recovery.txt"); val recoveryFile = AtomicFile(recoveryTarget)
        var output: FileOutputStream? = null
        try { val stream = recoveryFile.startWrite(); output = stream; stream.write("OpenAssistant corrupt goal recovery\nFile: ${file.name}\nError: ${error::class.java.name}".toByteArray(StandardCharsets.UTF_8)); recoveryFile.finishWrite(stream); return recoveryTarget } catch (writeError: Throwable) { output?.let(recoveryFile::failWrite); return null }
    }

    private fun writeSelectionAndSignalLocked(selectedGoalId: String?) { signalMutationLocked(selectedGoalId) }
    private fun signalMutationLocked(selectedGoalId: String? = null) {
        val prefs = preferences ?: return; val currentRevision = prefs.getLong(KEY_REVISION, 0L)
        prefs.edit(commit = true) { if (selectedGoalId != null) putString(KEY_SELECTED_GOAL, selectedGoalId); putLong(KEY_REVISION, currentRevision + 1); putString(KEY_SNAPSHOT, newRevisionSignal()) }
    }

    private fun writeGoalLocked(goal: AgentGoal, signal: Boolean = true): AgentGoal {
        writeCount.incrementAndGet(); testWriterInjection?.write(goal); validateGoalIdentityForWrite(goal); goalsDirectory.mkdirs(); val target = goalFileLocked(goal.id); val atomicFile = AtomicFile(target)
        val encoded = encodeGoal(goal); val text = encoded.toString(2); var stream: FileOutputStream? = null
        try { stream = atomicFile.startWrite(); stream.write(text.toByteArray(StandardCharsets.UTF_8)); stream.flush(); try { stream.fd.sync() } catch (_: Exception) {}; atomicFile.finishWrite(stream) } catch (e: Exception) { stream?.let { atomicFile.failWrite(it) }; throw e }
        val finalRaw = atomicFile.openRead().use { it.bufferedReader(StandardCharsets.UTF_8).readText() }; val readBack = decodeGoal(requireOpenRouterObject(finalRaw, "Written autonomous goal"))
        goalCache[target.name] = CachedGoal(readBack, target.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(), target.length()); if (signal) signalMutationLocked(); return readBack
    }

    private fun validateGoalIdentityForWrite(goal: AgentGoal) { require(goal.id.isNotBlank()) { "Goal ID must not be blank." }; require(goal.conversationId.isNotBlank()) { "Goal conversation ID must not be blank." } }
    private fun readGoalLocked(file: File): AgentGoal { val cached = goalCache[file.name]; if (cached != null && cached.fileTimestamp == file.lastModified() && cached.fileLength == file.length()) return cached.goal; val atomicFile = AtomicFile(file); val raw = atomicFile.openRead().use { it.bufferedReader(StandardCharsets.UTF_8).readText() }; val goal = decodeGoal(requireOpenRouterObject(raw, "Stored autonomous goal")); goalCache[file.name] = CachedGoal(goal, file.lastModified(), file.length()); return goal }
    private fun deleteGoalFilesLocked(goalId: String) { val file = goalFileLocked(goalId); if (file.exists()) file.delete(); val bak = File(file.path + ATOMIC_BACKUP_SUFFIX); if (bak.exists()) bak.delete() }
    private fun goalFileLocked(goalId: String): File = File(goalsDirectory, goalId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(96) + GOAL_FILE_SUFFIX)

    private fun newRevisionSignal(): String = "v2:${System.currentTimeMillis()}:${UUID.randomUUID()}"

    private fun encodeDraft(draft: ResearchDraft): JSONObject = JSONObject().put("id", draft.id).put("conversation_id", draft.conversationId).put("original_user_request", draft.originalUserRequest).put("title", draft.title).put("question", draft.question).put("objective", draft.objective).put("confirmed_constraints", JSONArray(draft.confirmedConstraints)).put("inferred_preferences", JSONArray(draft.inferredPreferences)).put("unresolved_questions", JSONArray(draft.unresolvedQuestions)).put("evidence_requirements", JSONArray(draft.evidenceRequirements)).put("preferred_source_types", JSONArray(draft.preferredSourceTypes)).put("freshness_requirement", draft.freshnessRequirement ?: JSONObject.NULL).put("exclusions", JSONArray(draft.exclusions)).put("desired_deliverable", draft.desiredDeliverable).put("source_message_ids", JSONArray(draft.sourceMessageIds)).put("resolved_research_request", draft.resolvedResearchRequest?.toJson() ?: JSONObject.NULL).put("version", draft.version).put("status", draft.status.name).put("durable_scheduling_state", draft.durableSchedulingState.name).put("linked_goal_id", draft.linkedGoalId ?: JSONObject.NULL).put("updated_at", draft.updatedAt)
    private fun decodeDraft(json: JSONObject): ResearchDraft = ResearchDraft(id = json.getString("id"), conversationId = json.getString("conversation_id"), originalUserRequest = json.optString("original_user_request"), title = json.optString("title"), question = json.optString("question"), objective = json.optString("objective"), confirmedConstraints = json.optJSONArray("confirmed_constraints").toStringList(), inferredPreferences = json.optJSONArray("inferred_preferences").toStringList(), unresolvedQuestions = json.optJSONArray("unresolved_questions").toStringList(), evidenceRequirements = json.optJSONArray("evidence_requirements").toStringList(), preferredSourceTypes = json.optJSONArray("preferred_source_types").toStringList(), freshnessRequirement = json.optNullableString("freshness_requirement"), exclusions = json.optJSONArray("exclusions").toStringList(), desiredDeliverable = json.optString("desired_deliverable"), sourceMessageIds = json.optJSONArray("source_message_ids").toStringList(), resolvedResearchRequest = ResolvedResearchRequest.fromJson(json.optJSONObject("resolved_research_request")), version = json.optInt("version", 1), status = json.optEnum("status", ResearchDraftStatus.DRAFT), durableSchedulingState = json.optEnum("durable_scheduling_state", DurableSchedulingState.NOT_SCHEDULED), linkedGoalId = json.optNullableString("linked_goal_id"), updatedAt = json.optLong("updated_at", System.currentTimeMillis()))

    private fun decodeSnapshot(root: JSONObject): AgentSnapshot { val goalsArray = root.optJSONArray("goals") ?: JSONArray(); val goals = buildList { for (i in 0 until goalsArray.length()) { goalsArray.optJSONObject(i)?.let { runCatching { decodeGoal(it) }.getOrNull()?.let(::add) } } }.sortedByDescending { it.updatedAt }; return AgentSnapshot(goals, root.optString("selected_goal_id").takeIf { it.isNotBlank() && it != "null" && goals.any { goal -> goal.id == it } }) }

    private fun encodeGoal(goal: AgentGoal): JSONObject = JSONObject().apply {
        put("storage_version", STORAGE_VERSION); put("id", goal.id); put("execution_lease", goal.executionLease?.let(::encodeLease) ?: JSONObject.NULL); put("conversation_id", goal.conversationId); put("submission_id", goal.submissionId ?: JSONObject.NULL); put("user_request", goal.userRequest); put("title", goal.title); put("objective", goal.objective); put("final_output_description", goal.finalOutputDescription); put("confirmed_constraints", JSONArray(goal.confirmedConstraints)); put("inferred_preferences", JSONArray(goal.inferredPreferences)); put("unresolved_questions", JSONArray(goal.unresolvedQuestions)); put("evidence_requirements", JSONArray(goal.evidenceRequirements)); put("preferred_source_types", JSONArray(goal.preferredSourceTypes)); put("freshness_requirement", goal.freshnessRequirement ?: JSONObject.NULL); put("exclusions", JSONArray(goal.exclusions)); put("source_message_ids", JSONArray(goal.sourceMessageIds)); put("grounded_constraints", JSONArray().apply { goal.groundedConstraints.forEach { put(it.toJson()) } }); put("status", goal.status.name); put("planner_model_id", goal.plannerModelId); put("execution_model_id", goal.executionModelId); put("routing_stage", goal.routingStage.name); put("requested_model_profile_name", goal.requestedModelProfileName ?: JSONObject.NULL); put("routing_policy_provenance", goal.routingPolicyProvenance.name); put("free_only", goal.freeOnly); put("tasks", JSONArray().apply { goal.tasks.forEach { put(encodeTask(it)) } }); put("acceptance_criteria", JSONArray().apply { goal.acceptanceCriteria.forEach { put(encodeCriterion(it)) } }); put("acceptance_checks", JSONArray().apply { goal.acceptanceChecks.forEach { put(encodeCheck(it)) } }); put("attempts", JSONArray().apply { goal.attempts.forEach { put(encodeAttempt(it)) } }); put("evidence", JSONArray().apply { goal.evidence.forEach { put(encodeEvidence(it)) } }); put("source_reads", JSONArray().apply { goal.sourceReads.forEach { put(encodeSourceRead(it)) } }); put("evidence_candidates", JSONArray().apply { goal.evidenceCandidates.forEach { put(encodeEvidenceCandidate(it)) } }); put("normalized_facts", JSONArray().apply { goal.normalizedFacts.forEach { put(encodeNormalizedFact(it)) } }); put("accepted_claims", JSONArray().apply { goal.acceptedClaims.forEach { put(encodeAcceptedClaim(it)) } }); put("claims", JSONArray().apply { goal.claims.forEach { put(encodeClaim(it)) } }); put("evidence_links", JSONArray().apply { goal.evidenceLinks.forEach { put(encodeEvidenceLink(it)) } }); put("checkpoints", JSONArray().apply { goal.checkpoints.forEach { put(encodeCheckpoint(it)) } }); put("concept_candidates", JSONArray().apply { goal.conceptCandidates.forEach { put(encodeConcept(it)) } }); put("refinements", JSONArray(goal.refinements)); put("events", JSONArray().apply { goal.events.forEach { put(encodeEvent(it)) } }); put("model_cooldowns", JSONObject(goal.modelCooldowns)); put("created_at", goal.createdAt); put("updated_at", goal.updatedAt); put("total_tokens", goal.totalTokens); put("verification_round", goal.verificationRound); put("verification_correction_streak", goal.verificationCorrectionStreak); put("total_cost_usd_micros", goal.totalCostUsdMicros); put("result", goal.result ?: JSONObject.NULL); put("error", goal.error ?: JSONObject.NULL); put("blocked_reason", goal.blockedReason ?: JSONObject.NULL); put("terminal_result_delivered", goal.terminalResultDelivered); put("delivery_records", JSONArray().apply { goal.deliveryRecords.forEach { put(encodeDeliveryRecord(it)) } }); put("next_retry_at", goal.nextRetryAt ?: JSONObject.NULL); put("network_wait_started_at", goal.networkWaitStartedAt ?: JSONObject.NULL); put("network_retry_count", goal.networkRetryCount); put("network_wait_reason", goal.networkWaitReason ?: JSONObject.NULL); put("resume_status_after_network", goal.resumeStatusAfterNetwork?.name ?: JSONObject.NULL); put("request_attempts", JSONArray().apply { goal.requestAttempts.forEach { put(encodeRequestAttempt(it)) } }); put("retry_authorizations", JSONArray().apply { goal.retryAuthorizations.forEach { put(encodeRetryAuthorization(it)) } }); put("idempotency_records", JSONArray().apply { goal.idempotencyRecords.forEach { put(encodeIdempotencyRecord(it)) } }); put("monitor_outbox", JSONArray().apply { goal.monitorOutbox.forEach { put(encodeMonitorOutbox(it)) } }); put("route_fingerprints", JSONArray().apply { goal.routeFingerprints.forEach { put(encodeRouteFingerprint(it)) } }); put("body_builder_claims", JSONArray().apply { goal.bodyBuilderClaims.forEach { put(encodeBodyBuilderClaim(it)) } }); put("quarantined_records", JSONArray().apply { goal.quarantinedRecords.forEach { put(encodeQuarantinedRecord(it)) } }); put("is_corrupt", goal.isCorrupt); put("objective_contract", goal.objectiveContract?.let(::encodeObjectiveContract) ?: JSONObject.NULL); put("resolved_research_request", goal.resolvedResearchRequest?.toJson() ?: JSONObject.NULL); put("requires_user_clarification", goal.requiresUserClarification); put("clarification_details", goal.clarificationDetails ?: JSONObject.NULL); put("blocked_sources", JSONArray().apply { goal.blockedSources.forEach { put(encodeBlockedSource(it)) } }); put("allocation_profile_name", goal.allocationProfileName ?: JSONObject.NULL); put("allocation_summary", goal.allocationSummary ?: JSONObject.NULL); put("last_allocation_reason", goal.lastAllocationReason ?: JSONObject.NULL); put("plan_revision", goal.planRevision); put("last_meaningful_progress_at", goal.lastMeaningfulProgressAt ?: JSONObject.NULL); put("no_progress_count", goal.noProgressCount); put("recovery_no_progress_count", goal.recoveryNoProgressCount); put("blocker_recovery_condition", goal.blockerRecoveryCondition ?: JSONObject.NULL); put("final_validation_result", goal.finalValidationResult ?: JSONObject.NULL); put("attempted_strategies", JSONArray(goal.attemptedStrategies)); put("operation_fingerprints", JSONArray(goal.operationFingerprints)); put("classified_failures", JSONArray(goal.classifiedFailures)); put("lease_generation", goal.leaseGeneration); put("execution_generation", goal.executionGeneration); put("last_resume_reason", goal.lastResumeReason?.name ?: JSONObject.NULL); put("recovery_plans", JSONArray().apply { goal.recoveryPlans.forEach { put(encodeRecoveryPlan(it)) } }); put("active_recovery_plan_id", goal.activeRecoveryPlanId ?: JSONObject.NULL); put("research_cycles", JSONArray().apply { goal.researchCycles.forEach { put(encodeResearchCycle(it)) } }); put("objective_revisions", JSONArray().apply { goal.objectiveRevisions.forEach { put(encodeObjectiveRevision(it)) } }); put("active_research_cycle_id", goal.activeResearchCycleId ?: JSONObject.NULL); put("active_continuation_scheduling_claim", goal.activeContinuationSchedulingClaim?.let(::encodeContinuationSchedulingClaim) ?: JSONObject.NULL); put("is_tool_restricted", goal.isToolRestricted); put("failure_classification", goal.failureClassification.name); put("fetch_attempts", JSONArray().apply { goal.fetchAttempts.forEach { put(encodeSourceFetchAttempt(it)) } }); put("tool_executions", JSONArray().apply { goal.toolExecutions.forEach { put(encodeToolExecution(it)) } }); put("revision", goal.revision)
    }

    private fun decodeGoal(json: JSONObject): AgentGoal {
        val legacyCostUsd = json.optDouble("total_cost_usd", 0.0); val convertedCostMicros = if (json.has("total_cost_usd_micros")) json.optLong("total_cost_usd_micros", 0L) else (legacyCostUsd * 1_000_000.0).toLong()
        val decodedSourceReads = json.optJSONArray("source_reads").decodeList(::decodeSourceRead); val decodedClaims = json.optJSONArray("claims").decodeList(::decodeClaim)
        val verifiedClaims = decodedClaims.map { claim -> if (claim.type == AgentClaimType.FACT) { val decision = FactualClaimSupportPolicy.evaluate(claim, decodedSourceReads); val support = when (decision) { is FactualClaimSupportDecision.Supported -> AgentClaimSupport.SUPPORTED; is FactualClaimSupportDecision.PartiallyBound -> AgentClaimSupport.PARTIAL; is FactualClaimSupportDecision.Contradicted -> AgentClaimSupport.CONTRADICTED; else -> AgentClaimSupport.UNSUPPORTED }; claim.copy(support = support) } else claim }
        val storedStatus = json.optEnum("status", AgentGoalStatus.QUEUED); val storedConversationId = json.optString("conversation_id"); val storedUserRequest = json.optString("user_request")
        val restoredStatus = when { storedConversationId.isBlank() || storedUserRequest.isBlank() -> AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION; else -> storedStatus }
        return validateAndRepairInvariants(AgentGoal(
            id = json.getString("id"), conversationId = storedConversationId, submissionId = json.optNullableString("submission_id"), userRequest = storedUserRequest, title = json.optString("title"), objective = json.optString("objective"), finalOutputDescription = json.optString("final_output_description"), confirmedConstraints = json.optJSONArray("confirmed_constraints").toStringList(), inferredPreferences = json.optJSONArray("inferred_preferences").toStringList(), unresolvedQuestions = json.optJSONArray("unresolved_questions").toStringList(), evidenceRequirements = json.optJSONArray("evidence_requirements").toStringList(), preferredSourceTypes = json.optJSONArray("preferred_source_types").toStringList(), freshnessRequirement = json.optNullableString("freshness_requirement"), exclusions = json.optJSONArray("exclusions").toStringList(), sourceMessageIds = json.optJSONArray("source_message_ids").toStringList(), groundedConstraints = json.optJSONArray("grounded_constraints").decodeList(GroundedConstraint::fromJson),
            status = restoredStatus, plannerModelId = json.optString("planner_model_id"), executionModelId = json.optString("execution_model_id"), routingStage = json.optEnum("routing_stage", AgentRoutingStage.AUTO_BETA), requestedModelProfileName = json.optNullableString("requested_model_profile_name"), routingPolicyProvenance = json.optEnum("routing_policy_provenance", RoutingPolicyProvenance.EXPLICIT_USER_SELECTION), freeOnly = json.optBoolean("free_only", false), tasks = json.optJSONArray("tasks").decodeList(::decodeTask), acceptanceCriteria = json.optJSONArray("acceptance_criteria").decodeList(::decodeCriterion), acceptanceChecks = json.optJSONArray("acceptance_checks").decodeList(::decodeCheck), attempts = json.optJSONArray("attempts").decodeList(::decodeAttempt), evidence = json.optJSONArray("evidence").decodeList(::decodeEvidence), sourceReads = decodedSourceReads, evidenceCandidates = json.optJSONArray("evidence_candidates").decodeList(::decodeEvidenceCandidate), normalizedFacts = json.optJSONArray("normalized_facts").decodeList(::decodeNormalizedFact), acceptedClaims = json.optJSONArray("accepted_claims").decodeList(::decodeAcceptedClaim), claims = verifiedClaims, evidenceLinks = json.optJSONArray("evidence_links").decodeList(::decodeEvidenceLink), checkpoints = json.optJSONArray("checkpoints").decodeList(::decodeCheckpoint), conceptCandidates = json.optJSONArray("concept_candidates").decodeList(::decodeConcept), refinements = json.optJSONArray("refinements").toStringList(), events = json.optJSONArray("events").decodeList(::decodeEvent),
            modelCooldowns = json.optJSONObject("model_cooldowns")?.let { m -> buildMap { m.keys().forEach { k -> put(k, m.getLong(k)) } } } ?: emptyMap(), executionLease = json.optJSONObject("execution_lease")?.let(::decodeLease), createdAt = json.optLong("created_at", System.currentTimeMillis()), updatedAt = json.optLong("updated_at", System.currentTimeMillis()), totalTokens = json.optInt("total_tokens", 0), totalCostUsdMicros = convertedCostMicros, verificationRound = json.optInt("verification_round", 0), verificationCorrectionStreak = json.optInt("verification_correction_streak", 0), result = json.optNullableString("result"), error = json.optNullableString("error"), blockedReason = json.optNullableString("blocked_reason"), terminalResultDelivered = json.optBoolean("terminal_result_delivered", false), deliveryRecords = json.optJSONArray("delivery_records").decodeList(::decodeDeliveryRecord), nextRetryAt = json.optLongOrNull("next_retry_at"), networkWaitStartedAt = json.optLongOrNull("network_wait_started_at"), networkRetryCount = json.optInt("network_retry_count", 0), networkWaitReason = json.optNullableString("network_wait_reason"), resumeStatusAfterNetwork = json.optNullableString("resume_status_after_network")?.let { runCatching { AgentGoalStatus.valueOf(it) }.getOrNull() }, requestAttempts = json.optJSONArray("request_attempts").decodeList(::decodeRequestAttempt), retryAuthorizations = json.optJSONArray("retry_authorizations").decodeList(::decodeRetryAuthorization), idempotencyRecords = json.optJSONArray("idempotency_records").decodeList(::decodeIdempotencyRecord), monitorOutbox = json.optJSONArray("monitor_outbox").decodeList(::decodeMonitorOutbox), routeFingerprints = json.optJSONArray("route_fingerprints").decodeList(::decodeRouteFingerprint), bodyBuilderClaims = json.optJSONArray("body_builder_claims").decodeList(::decodeBodyBuilderClaim), quarantinedRecords = json.optJSONArray("quarantined_records").decodeList(::decodeQuarantinedRecord), isCorrupt = json.optBoolean("is_corrupt", false) || restoredStatus == AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION,
            resolvedResearchRequest = ResolvedResearchRequest.fromJson(json.optJSONObject("resolved_research_request")), requiresUserClarification = json.optBoolean("requires_user_clarification", false), clarificationDetails = json.optNullableString("clarification_details"), blockedSources = json.optJSONArray("blocked_sources").decodeList(::decodeBlockedSource), allocationProfileName = json.optNullableString("allocation_profile_name"), allocationSummary = json.optNullableString("allocation_summary"), lastAllocationReason = json.optNullableString("last_allocation_reason"), planRevision = json.optInt("plan_revision", 0), lastMeaningfulProgressAt = json.optLongOrNull("last_meaningful_progress_at"), noProgressCount = json.optInt("no_progress_count", 0), recoveryNoProgressCount = json.optInt("recovery_no_progress_count", 0), blockerRecoveryCondition = json.optNullableString("blocker_recovery_condition"), finalValidationResult = json.optNullableString("final_validation_result"), attemptedStrategies = json.optJSONArray("attempted_strategies").toStringList(), operationFingerprints = json.optJSONArray("operation_fingerprints").toStringList(), classifiedFailures = json.optJSONArray("classified_failures").toStringList(), leaseGeneration = json.optInt("lease_generation", 0), executionGeneration = json.optInt("execution_generation", 1), lastResumeReason = json.optNullableString("last_resume_reason")?.let { runCatching { ResumeReason.valueOf(it) }.getOrNull() }, objectiveContract = json.optJSONObject("objective_contract")?.let(::decodeObjectiveContract), recoveryPlans = json.optJSONArray("recovery_plans").decodeList(::decodeRecoveryPlan), activeRecoveryPlanId = json.optNullableString("active_recovery_plan_id"), researchCycles = json.optJSONArray("research_cycles").decodeList(::decodeResearchCycle), objectiveRevisions = json.optJSONArray("objective_revisions").decodeList(::decodeObjectiveRevision), activeResearchCycleId = json.optNullableString("active_research_cycle_id"), activeContinuationSchedulingClaim = json.optJSONObject("active_continuation_scheduling_claim")?.let(::decodeContinuationSchedulingClaim), isToolRestricted = json.optBoolean("is_tool_restricted", false), failureClassification = json.optEnum("failure_classification", MissionFailureClassification.NONE), fetchAttempts = json.optJSONArray("fetch_attempts").decodeList(::decodeSourceFetchAttempt), toolExecutions = json.optJSONArray("tool_executions").decodeList(::decodeToolExecution), revision = json.optInt("revision", 0)
        ))
    }

    private fun validateAndRepairInvariants(goal: AgentGoal): AgentGoal {
        val activeCycleId = goal.activeResearchCycleId ?: return createBaselineCycle(goal)
        if (goal.researchCycles.none { it.id == activeCycleId && it.status == ResearchCycleStatus.ACTIVE }) return goal.copy(status = AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION, isCorrupt = true, error = "Active cycle missing.")
        return goal
    }

    private fun createBaselineCycle(goal: AgentGoal): AgentGoal {
        val cid = "cycle-${goal.id}-1"; val rid = "rev-${goal.id}-1"
        val cycle = ResearchCycle(id = cid, ordinal = 1, parentCycleId = null, status = ResearchCycleStatus.ACTIVE, objectiveRevisionId = rid, triggerDiagnosis = ExecutionStallDiagnosis.NONE, selectedAdvancementTactic = EscalationTactic.NONE, strategyFingerprint = "", queryPortfolioFingerprint = "", acceptedEvidenceFingerprint = "", unresolvedGapFingerprint = "", learningSummary = null, createdAt = goal.createdAt, activatedAt = goal.createdAt)
        val revision = ObjectiveRevision(id = rid, ordinal = 1, parentRevisionId = null, immutableRootObjectiveFingerprint = "", operationalObjective = goal.objective, unresolvedGaps = emptyList(), retainedConstraints = emptyList(), evidenceRequirements = emptyList(), revisionReason = "Initial baseline.", revisionFingerprint = "", createdAt = goal.createdAt)
        return goal.copy(activeResearchCycleId = cid, researchCycles = goal.researchCycles + cycle, objectiveRevisions = goal.objectiveRevisions + revision, tasks = goal.tasks.map { if (it.cycleId == null) it.copy(cycleId = cid) else it })
    }

    private fun goalFileLocked(goalId: String): File = File(goalsDirectory, goalId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(96) + GOAL_FILE_SUFFIX)
    private fun writeSelectionAndSignalLocked(selectedGoalId: String?) { signalMutationLocked(selectedGoalId) }
    private fun signalMutationLocked(selectedGoalId: String? = null) {
        val prefs = preferences ?: return
        val currentRevision = prefs.getLong(KEY_REVISION, 0L)
        prefs.edit(commit = true) {
            if (selectedGoalId != null) putString(KEY_SELECTED_GOAL, selectedGoalId)
            putLong(KEY_REVISION, currentRevision + 1)
            putString(KEY_SNAPSHOT, "v2:${System.currentTimeMillis()}:${UUID.randomUUID()}")
        }
    }
    private fun migrateLegacyIfNeededLocked() { val prefs = preferences ?: return; if (!prefs.getBoolean(KEY_MIGRATED_V2, false)) prefs.edit(commit = true) { putBoolean(KEY_MIGRATED_V2, true) } }

    internal interface GoalStateWriter { fun write(goal: AgentGoal) }
    private var testWriterInjection: GoalStateWriter? = null
    internal fun setTestWriterInjection(writer: GoalStateWriter?) { synchronized(STORE_LOCK) { testWriterInjection = writer } }

    private fun encodeTask(t: AgentTask) = JSONObject().apply { put("id", t.id); put("cycle_id", t.cycleId ?: JSONObject.NULL); put("order", t.order); put("title", t.title); put("instructions", t.instructions); put("capability", t.capability.name); put("status", t.status.name); put("attempt_count", t.attemptCount); put("lifetime_attempt_count", t.lifetimeAttemptCount); put("task_generation", t.taskGeneration); put("consecutive_no_progress_count", t.consecutiveNoProgressCount); put("last_material_progress_at", t.lastMaterialProgressAt ?: JSONObject.NULL); put("last_material_progress_fingerprint", t.lastMaterialProgressFingerprint ?: JSONObject.NULL); put("branch_exhaustion_reason", t.branchExhaustionReason ?: JSONObject.NULL); put("branch_exhausted_at", t.branchExhaustedAt ?: JSONObject.NULL); put("last_error", t.lastError ?: JSONObject.NULL); put("weight", t.weight); put("automatic_window_reopen_count", t.automaticWindowReopenCount); put("global_automatic_window_reopen_count", t.globalAutomaticWindowReopenCount); put("last_request_fingerprint", t.lastRequestFingerprint ?: JSONObject.NULL); put("last_escalated_fingerprint", t.lastEscalatedFingerprint ?: JSONObject.NULL); put("progress_fingerprint", t.progressFingerprint ?: JSONObject.NULL); put("query_fingerprints", JSONArray(t.queryFingerprints)); put("recent_query_fingerprints", JSONArray(t.recentQueryFingerprints)); put("recent_source_fingerprints", JSONArray(t.recentSourceFingerprints)); put("recent_claim_fingerprints", JSONArray(t.recentClaimFingerprints)); put("progress_score", t.progressScore); put("cooldown_until", t.cooldownUntil ?: JSONObject.NULL); put("started_at", t.startedAt ?: JSONObject.NULL); put("finished_at", t.finishedAt ?: JSONObject.NULL); put("output_evidence_id", t.outputEvidenceId ?: JSONObject.NULL); put("failure_class", t.failureClass ?: JSONObject.NULL); put("wait_reason", t.waitReason ?: JSONObject.NULL); put("wait_condition", t.waitCondition ?: JSONObject.NULL); put("last_recovery_strategy", t.lastRecoveryStrategy ?: JSONObject.NULL); put("result_set_fingerprint", t.resultSetFingerprint ?: JSONObject.NULL); put("recovery_strategy_fingerprint", t.recoveryStrategyFingerprint ?: JSONObject.NULL); put("last_tactic", t.lastTactic ?: JSONObject.NULL); put("next_tactic", t.nextTactic ?: JSONObject.NULL); put("outcome_classification", t.outcomeClassification ?: JSONObject.NULL); put("error_classification", t.errorClassification ?: JSONObject.NULL); put("retry_eligibility", t.retryEligibility); put("is_tool_restricted", t.isToolRestricted); put("retry_authorized_fingerprint", t.retryAuthorizedFingerprint ?: JSONObject.NULL); put("active_research_strategy_json", t.activeResearchStrategyJson ?: JSONObject.NULL) }
    private fun decodeTask(j: JSONObject) = AgentTask(id = j.getString("id"), cycleId = j.optNullableString("cycle_id"), order = j.optInt("order", 0), title = j.optString("title", ""), instructions = j.optString("instructions", ""), capability = j.optEnum("capability", AgentCapability.REASON), dependsOn = j.optJSONArray("depends_on").toStringList(), status = j.optEnum("status", AgentTaskStatus.PLANNED), attemptCount = j.optInt("attempt_count", 0), lifetimeAttemptCount = j.optInt("lifetime_attempt_count", 0), taskGeneration = j.optInt("task_generation", 0), consecutiveNoProgressCount = j.optInt("consecutive_no_progress_count", 0), lastMaterialProgressAt = j.optLongOrNull("last_material_progress_at"), lastMaterialProgressFingerprint = j.optNullableString("last_material_progress_fingerprint"), branchExhaustionReason = j.optNullableString("branch_exhaustion_reason"), branchExhaustedAt = j.optLongOrNull("branch_exhausted_at"), lastError = j.optNullableString("last_error"), weight = j.optDouble("weight", 1.0), automaticWindowReopenCount = j.optInt("automatic_window_reopen_count", 0), globalAutomaticWindowReopenCount = j.optInt("global_automatic_window_reopen_count", 0), lastRequestFingerprint = j.optNullableString("last_request_fingerprint"), lastEscalatedFingerprint = j.optNullableString("last_escalated_fingerprint"), progressFingerprint = j.optNullableString("progress_fingerprint"), queryFingerprints = j.optJSONArray("query_fingerprints").toStringList(), recentQueryFingerprints = j.optJSONArray("recent_query_fingerprints").toStringList(), recentSourceFingerprints = j.optJSONArray("recent_source_fingerprints").toStringList(), recentClaimFingerprints = j.optJSONArray("recent_claim_fingerprints").toStringList(), progressScore = j.optDouble("progress_score", 0.0), cooldownUntil = j.optLongOrNull("cooldown_until"), startedAt = j.optLongOrNull("started_at"), finishedAt = j.optLongOrNull("finished_at"), outputEvidenceId = j.optNullableString("output_evidence_id"), failureClass = j.optNullableString("failure_class"), waitReason = j.optNullableString("wait_reason"), waitCondition = j.optNullableString("wait_condition"), lastRecoveryStrategy = j.optNullableString("last_recovery_strategy"), resultSetFingerprint = j.optNullableString("result_set_fingerprint"), recoveryStrategyFingerprint = j.optNullableString("recovery_strategy_fingerprint"), lastTactic = j.optNullableString("last_tactic"), nextTactic = j.optNullableString("next_tactic"), outcomeClassification = j.optNullableString("outcome_classification"), errorClassification = j.optNullableString("error_classification"), retryEligibility = j.optBoolean("retry_eligibility", true), isToolRestricted = j.optBoolean("is_tool_restricted", false), retryAuthorizedFingerprint = j.optNullableString("retry_authorized_fingerprint"), activeResearchStrategyJson = j.optNullableString("active_research_strategy_json"))

    private fun encodeLease(l: AgentExecutionLease) = JSONObject().apply { put("worker_id", l.workerId); put("owner_process_session_id", l.ownerProcessSessionId); put("task_id", l.taskId); put("attempt_id", l.attemptId); put("generation", l.generation); put("acquired_at", l.acquiredAt); put("heartbeat_at", l.heartbeatAt) }
    private fun decodeLease(j: JSONObject) = AgentExecutionLease(workerId = j.getString("worker_id"), ownerProcessSessionId = j.optString("owner_process_session_id", "unknown"), taskId = j.getString("task_id"), attemptId = j.getString("attempt_id"), generation = j.getInt("generation"), acquiredAt = j.getLong("acquired_at"), heartbeatAt = j.getLong("heartbeat_at"))

    private fun encodeCriterion(c: AgentAcceptanceCriterion) = JSONObject().apply { put("id", c.id); put("text", c.text); put("weight", c.weight) }
    private fun decodeCriterion(j: JSONObject) = AgentAcceptanceCriterion(id = j.getString("id"), text = j.getString("text"), weight = j.optDouble("weight", 1.0))

    private fun encodeCheck(c: AgentAcceptanceCheck) = JSONObject().apply { put("id", c.id); put("criterion_id", c.criterionId); put("status", c.status.name); put("score", c.score); put("explanation", c.explanation) }
    private fun decodeCheck(j: JSONObject) = AgentAcceptanceCheck(id = j.getString("id"), criterionId = j.getString("criterion_id"), status = j.optEnum("status", AgentAcceptanceCheckStatus.PENDING), score = j.optDouble("score", 0.0), explanation = j.optString("explanation", ""))

    private fun encodeEvidenceCandidate(c: EvidenceCandidate) = JSONObject().apply { put("id", c.id); put("source_read_id", c.sourceReadId); put("canonical_url", c.canonicalUrl); put("raw_text", c.rawText); put("relevance_score", c.relevanceScore) }
    private fun decodeEvidenceCandidate(j: JSONObject) = EvidenceCandidate(id = j.getString("id"), sourceReadId = j.getString("source_read_id"), canonicalUrl = j.getString("canonical_url"), rawText = j.getString("raw_text"), relevanceScore = j.optInt("relevance_score", 0))

    private fun encodeNormalizedFact(f: NormalizedFact) = JSONObject().apply { put("id", f.id); put("evidence_candidate_id", f.evidenceCandidateId); put("fact_value", f.factValue); put("units", f.units ?: JSONObject.NULL); put("entity_name", f.entityName); put("content_hash", f.contentHash) }
    private fun decodeNormalizedFact(j: JSONObject) = NormalizedFact(id = j.getString("id"), evidenceCandidateId = j.getString("evidence_candidate_id"), factValue = j.getString("fact_value"), units = j.optNullableString("units"), entityName = j.getString("entity_name"), contentHash = j.getString("content_hash"))

    private fun encodeAcceptedClaim(c: AcceptedClaim) = JSONObject().apply { put("id", c.id); put("claim_id", c.claim_id); put("accepted_at", c.acceptedAt); put("accepted_by", c.acceptedBy); put("confidence_at_acceptance", c.confidenceAtAcceptance) }
    private fun decodeAcceptedClaim(j: JSONObject) = AcceptedClaim(id = j.getString("id"), claim_id = j.getString("claim_id"), acceptedAt = j.getLong("accepted_at"), acceptedBy = j.getString("accepted_by"), confidenceAtAcceptance = j.optDouble("confidence_at_acceptance", 1.0))

    private fun encodeCheckpoint(c: AgentCheckpoint) = JSONObject().apply { put("id", c.id); put("sequence", c.sequence); put("completed_task_ids", JSONArray(c.completedTaskIds)); put("progress_score", c.progressScore); put("note", c.note); put("created_at", c.createdAt) }
    private fun decodeCheckpoint(j: JSONObject) = AgentCheckpoint(id = j.getString("id"), sequence = j.getInt("sequence"), completedTask_ids = j.optJSONArray("completed_task_ids").toStringList(), progressScore = j.getDouble("progress_score"), note = j.getString("note"), createdAt = j.getLong("created_at"))

    private fun encodeConcept(c: AgentConceptCandidate) = JSONObject().apply { put("id", c.id); put("name", c.name); put("description", c.description); put("discovery_task_id", c.discoveryTaskId) }
    private fun decodeConcept(j: JSONObject) = AgentConceptCandidate(id = j.getString("id"), name = j.getString("name"), description = j.getString("description"), discoveryTaskId = j.getString("discovery_task_id"))

    private fun encodeEvidenceLink(l: AgentEvidenceLink) = JSONObject().apply { put("id", l.id); put("claim_id", l.claimId); put("evidence_id", l.evidenceId); put("relation", l.relation.name); put("explanation", l.explanation ?: JSONObject.NULL) }
    private fun decodeEvidenceLink(j: JSONObject) = AgentEvidenceLink(id = j.getString("id"), claimId = j.optString("claim_id", ""), evidenceId = j.optString("evidence_id", ""), relation = j.optEnum("relation", AgentEvidenceRelation.SUPPORTS), explanation = j.optNullableString("explanation"))

    private fun encodeBlockedSource(s: BlockedSourceRecord) = JSONObject().apply { put("canonical_url", s.canonicalUrl); put("failure_class", s.failureClass); put("terminal_state", s.terminalState) }
    private fun decodeBlockedSource(j: JSONObject) = BlockedSourceRecord(canonicalUrl = j.getString("canonical_url"), failureClass = j.optString("failure_class", "UNKNOWN"), terminalState = j.optBoolean("terminal_state", false))

    private fun encodeRejectedQuery(q: RejectedResearchQuery) = JSONObject().apply { put("original_query", q.originalQuery); put("reason_code", q.reasonCode) }
    private fun decodeRejectedQuery(j: JSONObject) = RejectedResearchQuery(originalQuery = j.getString("original_query"), reasonCode = j.getString("reason_code"))

    private fun encodeStructureRepairLineage(l: StructureRepairLineage) = JSONObject().apply { put("repair_reason", l.repairReason.name) }
    private fun decodeStructureRepairLineage(j: JSONObject) = StructureRepairLineage(repairReason = runCatching { StructureRepairReason.valueOf(j.getString("repair_reason")) }.getOrDefault(StructureRepairReason.UNKNOWN))

    private fun encodeRecoveryProposal(p: RecoveryProposal) = JSONObject().apply { put("rationale", p.rationale) }
    private fun decodeRecoveryProposal(j: JSONObject) = RecoveryProposal(rationale = j.optString("rationale", ""))

    private fun encodeApiSummary(s: AgentApiSummary) = JSONObject().apply { put("response_id", s.responseId ?: JSONObject.NULL) }
    private fun decodeApiSummary(j: JSONObject) = AgentApiSummary(responseId = j.optNullableString("response_id"))

    private fun encodeCitationBinding(b: CitationBinding) = JSONObject().apply { put("id", b.id); put("claim_id", b.claimId); put("source_read_id", b.sourceReadId); put("document_id", b.documentId); put("content_hash", b.contentHash); put("citation_excerpt", b.citationExcerpt); put("passage_start", b.passageStart ?: JSONObject.NULL); put("passage_end", b.passageEnd ?: JSONObject.NULL); put("passage_hash", b.passageHash ?: JSONObject.NULL); put("binding_method", b.bindingMethod.name); put("confidence", b.confidence); put("created_at", b.createdAt); put("identity_schema_v", b.identitySchemaVersion); put("logical_fingerprint", b.logicalFingerprint ?: JSONObject.NULL) }
    private fun decodeCitationBinding(j: JSONObject) = CitationBinding(id = j.getString("id"), claimId = j.getString("claim_id"), sourceReadId = j.getString("source_read_id"), documentId = j.getString("document_id"), contentHash = j.getString("content_hash"), citationExcerpt = j.optString("citation_excerpt"), passageStart = j.optIntOrNull("passage_start"), passageEnd = j.optIntOrNull("passage_end"), passageHash = j.optNullableString("passage_hash"), bindingMethod = j.optEnum("binding_method", CitationBindingMethod.LEGACY_UNKNOWN), confidence = j.optDouble("confidence", 0.0), createdAt = j.optLong("created_at", System.currentTimeMillis()), identitySchemaVersion = j.optInt("identity_schema_v", 0), logicalFingerprint = j.optNullableString("logical_fingerprint"))

    private fun encodeDeliveryRecord(r: DeliveryRecord) = JSONObject().apply { put("generation", r.generation); put("delivery_kind", r.deliveryKind); put("delivered_at", r.deliveredAt); put("execution_generation", r.executionGeneration ?: JSONObject.NULL); put("is_legacy", r.isLegacy) }
    private fun decodeDeliveryRecord(j: JSONObject) = DeliveryRecord(generation = j.optInt("generation", 0), deliveryKind = j.optString("delivery_kind", "TERMINAL_RESULT"), deliveredAt = j.optLong("delivered_at", System.currentTimeMillis()), executionGeneration = if (j.has("execution_generation") && !j.isNull("execution_generation")) j.getInt("execution_generation") else null, isLegacy = j.optBoolean("is_legacy", false))

    private fun encodeObjectiveContract(c: ObjectiveContract) = JSONObject().apply { put("version", c.version); put("primary_subject", c.primarySubject); put("strong_anchors", JSONArray(c.strongAnchors)); put("temporal_context", c.temporalContext ?: JSONObject.NULL); put("expected_deliverable_kind", c.expectedDeliverableKind ?: JSONObject.NULL); put("domain_classification", c.domainClassification); put("contract_hash", c.contractHash ?: JSONObject.NULL) }
    private fun decodeObjectiveContract(j: JSONObject) = ObjectiveContract(version = j.optInt("version", 1), primarySubject = j.optString("primary_subject", ""), strongAnchors = j.optJSONArray("strong_anchors").toStringList(), temporalContext = j.optNullableString("temporal_context"), expectedDeliverableKind = j.optNullableString("expected_deliverable_kind"), domainClassification = j.optString("domain_classification", "GENERAL"), contractHash = j.optNullableString("contract_hash"))

    private fun encodeRecoveryPlan(p: ResearchRecoveryPlan) = JSONObject().apply { put("id", p.id); put("goal_id", p.goalId); put("task_id", p.taskId); put("input_execution_fingerprint", p.inputExecutionFingerprint); put("input_objective_fingerprint", p.inputObjectiveFingerprint); put("trigger_execution_fingerprint", p.triggerExecutionFingerprint); put("version", p.version); put("diagnosis", p.diagnosis.name); put("selected_tactic", p.selectedTactic.name); put("status", p.status.name); put("logical_provider_request_id", p.logicalProviderRequestId ?: JSONObject.NULL); put("proposal", p.proposal?.let(::encodeRecoveryProposal) ?: JSONObject.NULL); put("proposal_fingerprint", p.proposalFingerprint ?: JSONObject.NULL); put("validation_result", p.validationResult ?: JSONObject.NULL); put("failure_classification", p.failureClassification ?: JSONObject.NULL); put("failure_message", p.failureMessage ?: JSONObject.NULL); put("accounting_summary", p.accountingSummary?.let(::encodeApiSummary) ?: JSONObject.NULL); put("retry_authorized_fingerprint", p.retryAuthorizedFingerprint ?: JSONObject.NULL); put("created_at", p.createdAt); put("generated_at", p.generatedAt ?: JSONObject.NULL); put("committed_at", p.committedAt ?: JSONObject.NULL) }
    private fun decodeRecoveryPlan(j: JSONObject) = ResearchRecoveryPlan(id = j.getString("id"), goalId = j.getString("goal_id"), taskId = j.getString("task_id"), inputExecutionFingerprint = j.getString("input_execution_fingerprint"), inputObjectiveFingerprint = j.optString("input_objective_fingerprint", ""), triggerExecutionFingerprint = j.optString("trigger_execution_fingerprint", ""), version = j.optInt("version", 1), diagnosis = j.optEnum("diagnosis", ExecutionStallDiagnosis.NONE), selectedTactic = j.optEnum("selected_tactic", EscalationTactic.NONE), status = j.optEnum("status", RecoveryPlanStatus.PREPARED), logicalProviderRequestId = j.optNullableString("logical_provider_request_id"), proposal = j.optJSONObject("proposal")?.let(::decodeRecoveryProposal), proposalFingerprint = j.optNullableString("proposal_fingerprint"), validationResult = j.optNullableString("validation_result"), failureClassification = j.optNullableString("failure_classification"), failureMessage = j.optNullableString("failure_message"), accountingSummary = j.optJSONObject("accounting_summary")?.let(::decodeApiSummary), retryAuthorizedFingerprint = j.optNullableString("retry_authorized_fingerprint"), createdAt = j.optLong("created_at", System.currentTimeMillis()), generatedAt = j.optLongOrNull("generated_at"), committedAt = j.optLongOrNull("committed_at"))

    private fun encodeResearchCycle(c: ResearchCycle) = JSONObject().apply { put("id", c.id); put("ordinal", c.ordinal); put("parent_cycle_id", c.parentCycleId ?: JSONObject.NULL); put("status", c.status.name); put("objective_revision_id", c.objectiveRevisionId); put("trigger_diagnosis", c.triggerDiagnosis.name); put("selected_advancement_tactic", c.selectedAdvancementTactic.name); put("strategy_fingerprint", c.strategyFingerprint); put("query_portfolio_fingerprint", c.queryPortfolioFingerprint); put("accepted_evidence_fingerprint", c.acceptedEvidenceFingerprint); put("unresolved_gap_fingerprint", c.unresolvedGapFingerprint); put("learning_summary", c.learningSummary?.let(::encodeLearningSummary) ?: JSONObject.NULL); put("created_at", c.createdAt); put("activated_at", c.activatedAt ?: JSONObject.NULL); put("superseded_at", c.supersededAt ?: JSONObject.NULL); put("completed_at", c.completedAt ?: JSONObject.NULL); put("exhausted_at", c.exhaustedAt ?: JSONObject.NULL) }
    private fun decodeResearchCycle(j: JSONObject) = ResearchCycle(id = j.getString("id"), ordinal = j.optInt("ordinal"), parentCycleId = j.optNullableString("parent_cycle_id"), status = j.optEnum("status", ResearchCycleStatus.PLANNING), objectiveRevisionId = j.getString("objective_revision_id"), triggerDiagnosis = j.optEnum("trigger_diagnosis", ExecutionStallDiagnosis.NONE), selectedAdvancementTactic = j.optEnum("selected_advancement_tactic", EscalationTactic.NONE), strategyFingerprint = j.optString("strategy_fingerprint"), queryPortfolioFingerprint = j.optString("query_portfolio_fingerprint"), acceptedEvidenceFingerprint = j.optString("accepted_evidence_fingerprint"), unresolvedGapFingerprint = j.optString("unresolved_gap_fingerprint"), learningSummary = j.optJSONObject("learning_summary")?.let(::decodeLearningSummary), createdAt = j.optLong("created_at", System.currentTimeMillis()), activatedAt = j.optLongOrNull("activated_at"), supersededAt = j.optLongOrNull("superseded_at"), completedAt = j.optLongOrNull("completed_at"), exhaustedAt = j.optLongOrNull("exhausted_at"))

    private fun encodeLearningSummary(s: ResearchCycleLearningSummary) = JSONObject().apply { put("established_findings", JSONArray(s.establishedFindings)); put("accepted_evidence_ids", JSONArray(s.acceptedEvidenceIds)); put("accepted_claim_ids", JSONArray(s.acceptedClaimIds)); put("remaining_unresolved_gaps", JSONArray(s.remainingUnresolvedGaps)); put("contradictions", JSONArray(s.contradictions)); put("rejected_or_unreliable_material", JSONArray(s.rejectedOrUnreliableMaterial)); put("exhausted_query_approaches", JSONArray(s.exhaustedQueryApproaches)); put("exhausted_source_families", JSONArray(s.exhaustedSourceFamilies)); put("attempted_tactics", JSONArray(s.attemptedTactics.map { it.name })); put("failed_strategy_fingerprints", JSONArray(s.failedStrategyFingerprints)); put("carry_forward_evidence_ids", JSONArray(s.carryForwardEvidenceIds)); put("advancement_reason", s.advancementReason) }
    private fun decodeLearningSummary(j: JSONObject) = ResearchCycleLearningSummary(establishedFindings = j.optJSONArray("established_findings").toStringList(), acceptedEvidenceIds = j.optJSONArray("accepted_evidence_ids").toStringList(), acceptedClaimIds = j.optJSONArray("accepted_claim_ids").toStringList(), remainingUnresolvedGaps = j.optJSONArray("remaining_unresolved_gaps").toStringList(), contradictions = j.optJSONArray("contradictions").toStringList(), rejectedOrUnreliableMaterial = j.optJSONArray("rejected_or_unreliable_material").toStringList(), exhaustedQueryApproaches = j.optJSONArray("exhausted_query_approaches").toStringList(), exhaustedSourceFamilies = j.optJSONArray("exhausted_source_families").toStringList(), attemptedTactics = j.optJSONArray("attempted_tactics").decodeList { obj -> runCatching { EscalationTactic.valueOf(obj.toString()) }.getOrNull() }, failedStrategyFingerprints = j.optJSONArray("failed_strategy_fingerprints").toStringList(), carryForwardEvidenceIds = j.optJSONArray("carry_forward_evidence_ids").toStringList(), advancement_reason = j.optString("advancement_reason"))

    private fun encodeObjectiveRevision(r: ObjectiveRevision) = JSONObject().apply { put("id", r.id); put("ordinal", r.ordinal); put("parent_revision_id", r.parentRevisionId ?: JSONObject.NULL); put("immutable_root_objective_fingerprint", r.immutableRootObjectiveFingerprint); put("operational_objective", r.operationalObjective); put("unresolved_gaps", JSONArray(r.unresolvedGaps)); put("retained_constraints", JSONArray(r.retainedConstraints)); put("evidence_requirements", JSONArray(r.evidenceRequirements)); put("revision_reason", r.revisionReason); put("revision_fingerprint", r.revisionFingerprint); put("created_at", r.createdAt) }
    private fun decodeObjectiveRevision(j: JSONObject) = ObjectiveRevision(id = j.getString("id"), ordinal = j.optInt("ordinal"), parentRevisionId = j.optNullableString("parent_revision_id"), immutableRootObjectiveFingerprint = j.optString("immutable_root_objective_fingerprint"), operationalObjective = j.optString("operational_objective"), unresolvedGaps = j.optJSONArray("unresolved_gaps").toStringList(), retainedConstraints = j.optJSONArray("retained_constraints").toStringList(), evidenceRequirements = j.optJSONArray("evidence_requirements").toStringList(), revisionReason = j.optString("revision_reason"), revisionFingerprint = j.optString("revision_fingerprint"), createdAt = j.optLong("created_at", System.currentTimeMillis()))

    companion object {
        val readCount = AtomicLong(0); val writeCount = AtomicLong(0)
        private const val PREFERENCES_NAME = "openassistant_agent_store"
        const val KEY_SNAPSHOT = "agent_snapshot_v1"
        const val KEY_REVISION = "agent_store_revision_v1"
        private const val KEY_SELECTED_GOAL = "agent_selected_goal_v2"
        private const val KEY_MIGRATED_V2 = "agent_store_migrated_v2"
        private const val KEY_PENDING_DRAFT = "agent_pending_draft_v1"
        private const val GOALS_DIRECTORY_NAME = "agent_runtime_v2/goals"
        private const val GOAL_FILE_SUFFIX = ".goal.json"
        private const val ATOMIC_BACKUP_SUFFIX = ".bak"
        private const val STORAGE_VERSION = 14
        private val STORE_LOCK = Any()
    }
}

private fun JSONObject.optNullableString(n: String): String? = if (!has(n) || isNull(n)) null else optString(n).takeIf { it.isNotBlank() && it != "null" }
private fun JSONObject.optIntOrNull(n: String): Int? = if (!has(n) || isNull(n)) null else optInt(n)
private fun JSONObject.optLongOrNull(n: String): Long? = if (!has(n) || isNull(n)) null else optLong(n)
private fun JSONObject.optDoubleOrNull(n: String): Double? = if (!has(n) || isNull(n)) null else optDouble(n)
private fun JSONArray?.toStringList(): List<String> = if (this == null) emptyList() else buildList { for (i in 0 until length()) optString(i).takeIf { it.isNotBlank() }?.let(::add) }
private fun <T : Any> JSONArray?.decodeList(d: (JSONObject) -> T?): List<T> = if (this == null) emptyList() else buildList { for (i in 0 until length()) optJSONObject(i)?.let { runCatching { d(it) }.getOrNull()?.let(::add) } }
private inline fun <reified T : Enum<T>> JSONObject.optEnum(n: String, f: T): T = runCatching { enumValueOf<T>(optString(n)) }.getOrDefault(f)

private fun appendEvent(events: List<AgentEvent>, message: String): List<AgentEvent> = events + AgentEvent(message = message)
private fun mergeSourceReads(existing: List<SourceRead>, incoming: List<SourceRead>): List<SourceRead> { val ids = existing.map { it.id }.toSet(); return existing + incoming.filter { it.id !in ids } }
private fun calculateClaimFingerprint(taskId: String, type: AgentClaimType, text: String): String = FingerprintUtils.calculateClaimFingerprint(taskId, type, text)
