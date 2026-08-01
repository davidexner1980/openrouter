package com.david.openassistant.agent

import java.util.UUID

enum class MonitorOutboxState {
    PENDING,
    DELIVERED,
}

/**
 * Durable outbox record stored inside AgentGoal to ensure transactional outbox delivery of terminal monitor events.
 */
data class MonitorOutboxRecord(
    val eventId: String = UUID.randomUUID().toString(),
    val exchangeId: String,
    val eventType: String,
    val safePayloadJson: String,
    val state: MonitorOutboxState = MonitorOutboxState.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val deliveredAt: Long? = null,
    val attemptCount: Int = 0,
)
