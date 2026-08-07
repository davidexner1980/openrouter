package com.david.openassistant.agent

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

enum class RequestState {
    ACTIVE,
    COMPLETED,
    FAILED,
    CANCELLED,
    CANCELLATION_TIMEOUT,
    TIMEOUT
}

/**
 * Centralized, thread-safe, generation-aware request ledger for multi-stage provider accounting.
 * 
 * Manages Stage 1 (Exchange Outcome), Stage 2 (Provider Accounting), and Stage 3 (Mission Domain Commit)
 * ensuring zero duplicate token/cost recording and zero duplicate mission mutations across races and process loss.
 */
object ProviderRequestLedger {
    private val requests = ConcurrentHashMap<String, AtomicReference<RequestState>>()
    private val activeExchangeMetadata = ConcurrentHashMap<String, ProviderRequestAttempt>()

    /** Records the start of a new provider exchange. */
    fun start(exchangeId: String, attempt: ProviderRequestAttempt? = null) {
        requests[exchangeId] = AtomicReference(RequestState.ACTIVE)
        if (attempt != null) {
            activeExchangeMetadata[exchangeId] = attempt
        }
    }

    /**
     * Atomically transitions a request exchange to a terminal state.
     * Returns true if this call performed the transition from ACTIVE.
     */
    fun terminalize(exchangeId: String, newState: RequestState): Boolean {
        require(newState != RequestState.ACTIVE) { "Cannot terminalize to ACTIVE state." }
        val stateRef = requests[exchangeId] ?: run {
            return false
        }
        return stateRef.compareAndSet(RequestState.ACTIVE, newState)
    }

    /** True if the request exchange has reached any terminal state. */
    fun isTerminal(exchangeId: String): Boolean {
        val state = requests[exchangeId]?.get() ?: return false
        return state != RequestState.ACTIVE
    }

    /** Returns the current exchange state, or null if untracked. */
    fun getState(exchangeId: String): RequestState? {
        return requests[exchangeId]?.get()
    }

    /**
     * Atomically claims an IdempotencyRecord in AgentStore.
     * Returns true if successfully transitioned to CLAIMED.
     */
    fun claimIdempotencyRecord(
        store: AgentStore,
        goalId: String,
        key: String,
        effectType: IdempotencyEffectType,
        claimOwner: String,
        claimGeneration: Int = 0,
        effectFingerprint: String? = null,
        targetObjectIds: List<String> = emptyList(),
        ticket: AgentOwnershipTicket? = null,
    ): Boolean {
        var claimed = false
        val now = System.currentTimeMillis()
        store.updateGoalAtomic(goalId, ticket) { goal ->
            val existing = goal.idempotencyRecords.firstOrNull { it.key == key }
            if (existing != null) {
                if (existing.state == IdempotencyState.COMMITTED) {
                    claimed = false
                    goal
                } else if (existing.state == IdempotencyState.FAILED_RETRYABLE || (existing.state == IdempotencyState.CLAIMED && existing.leaseExpiresAt != null && now > existing.leaseExpiresAt)) {
                    claimed = true
                    val updated = existing.copy(
                        state = IdempotencyState.CLAIMED,
                        claimOwner = claimOwner,
                        claimGeneration = claimGeneration,
                        effectFingerprint = effectFingerprint,
                        lastAttemptAt = now,
                        targetObjectIds = targetObjectIds,
                    )
                    goal.copy(idempotencyRecords = goal.idempotencyRecords.map { if (it.key == key) updated else it })
                } else {
                    claimed = false
                    goal
                }
            } else {
                claimed = true
                val newRecord = IdempotencyRecord(
                    key = key,
                    effectType = effectType,
                    state = IdempotencyState.CLAIMED,
                    claimOwner = claimOwner,
                    claimGeneration = claimGeneration,
                    effectFingerprint = effectFingerprint,
                    claimedAt = now,
                    lastAttemptAt = now,
                    targetObjectIds = targetObjectIds,
                )
                goal.copy(idempotencyRecords = goal.idempotencyRecords + newRecord)
            }
        }
        return claimed
    }

    /**
     * Reconciles stale ACTIVE exchanges after app restart or process loss.
     * Inspects durable effect idempotency keys to resume or finalize safely.
     */
    fun reconcileStaleExchanges(
        store: AgentStore,
        goalId: String,
        reconciliationOwner: String,
        ticket: AgentOwnershipTicket? = null,
    ) {
        val now = System.currentTimeMillis()
        store.updateGoalAtomic(goalId, ticket) { goal ->
            var modified = false
            val updatedAttempts = goal.requestAttempts.map { attempt ->
                if (attempt.exchangeOutcome == ExchangeOutcome.ACTIVE) {
                    modified = true
                    val accountingKey = "${goalId}:${attempt.exchangeId}:PROVIDER_ACCOUNTING"
                    val accountingRecord = goal.idempotencyRecords.firstOrNull { it.key == accountingKey }
                    val isAccountingCommitted = accountingRecord?.state == IdempotencyState.COMMITTED

                    attempt.copy(
                        exchangeOutcome = ExchangeOutcome.INTERRUPTED_OUTCOME_UNKNOWN,
                        providerAccountingOutcome = if (isAccountingCommitted) ProviderAccountingOutcome.COMMITTED else ProviderAccountingOutcome.UNKNOWN_AFTER_PROCESS_LOSS,
                        domainCommitOutcome = MissionDomainCommitOutcome.REJECTED_OBSOLETE_GENERATION,
                        usageSource = if (isAccountingCommitted) attempt.usageSource else UsageSource.UNKNOWN_PROCESS_LOSS,
                        finishedAt = now,
                        reconciliationClaimOwner = reconciliationOwner,
                        reconciliationClaimedAt = now,
                        safeDiagnosticSummary = "Reconciled stale ACTIVE exchange after process loss. Remote provider completion status unknown.",
                    )
                } else {
                    attempt
                }
            }
            if (modified) goal.copy(requestAttempts = updatedAttempts, updatedAt = now) else goal
        }
    }

    /**
     * Waits for a limited time for any pending ACTIVE requests to terminalize.
     * Used before final monitor report commit.
     */
    suspend fun waitForSettlement(timeoutMs: Long = 5000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val hasActive = requests.values.any { it.get() == RequestState.ACTIVE }
            if (!hasActive) break
            kotlinx.coroutines.delay(100)
        }
    }

    /** Returns true if all tracked requests are in a terminal state. */
    fun isSettled(): Boolean = requests.values.none { it.get() == RequestState.ACTIVE }

    /** Returns IDs of all requests that are still in ACTIVE state. */
    fun activeRequestIds(): List<String> = requests.entries
        .filter { it.value.get() == RequestState.ACTIVE }
        .map { it.key }

    /** Removes a request from the in-memory tracking ledger. */
    fun clear(exchangeId: String) {
        requests.remove(exchangeId)
        activeExchangeMetadata.remove(exchangeId)
    }
}
