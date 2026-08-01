package com.david.openassistant.agent

import java.util.UUID

enum class IdempotencyEffectType {
    PROVIDER_ACCOUNTING,
    EVIDENCE_COMMIT,
    CLAIM_COMMIT,
    CONTRADICTION_COMMIT,
    TASK_PROGRESS_COMMIT,
    CHECKPOINT_COMMIT,
    MISSION_STATE_COMMIT,
    MONITOR_TERMINAL_EVENT,
    BODY_BUILDER_PROPOSAL_RESULT,
}

enum class IdempotencyState {
    CLAIMED,
    COMMITTED,
    FAILED_RETRYABLE,
}

/**
 * Durable structured record representing the three-phase lifecycle of an idempotent side effect.
 */
data class IdempotencyRecord(
    val key: String,
    val effectType: IdempotencyEffectType,
    val state: IdempotencyState = IdempotencyState.CLAIMED,
    val claimOwner: String,
    val claimGeneration: Int = 0,
    val effectFingerprint: String? = null,
    val leaseExpiresAt: Long? = null,
    val claimedAt: Long = System.currentTimeMillis(),
    val committedAt: Long? = null,
    val lastAttemptAt: Long = claimedAt,
    val completedBy: String? = null,
    val lastFailure: String? = null,
    val retryCount: Int = 0,
    val targetObjectIds: List<String> = emptyList(),
)
