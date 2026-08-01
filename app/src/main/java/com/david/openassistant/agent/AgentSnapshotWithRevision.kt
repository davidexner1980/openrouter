package com.david.openassistant.agent

data class AgentSnapshotWithRevision(
    val snapshot: AgentSnapshot,
    val revision: Long,
)
