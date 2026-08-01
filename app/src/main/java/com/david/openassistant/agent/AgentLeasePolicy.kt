package com.david.openassistant.agent

object AgentLeasePolicy {
    const val LEASE_STALE_THRESHOLD_MS: Long = 5 * 60_000L // 5 minutes

    fun isStale(lease: AgentExecutionLease?, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (lease == null) return true
        val age = nowMs - lease.heartbeatAt
        return age > LEASE_STALE_THRESHOLD_MS
    }
}
