package com.david.openassistant.agent

interface AgentRefreshSource {
    suspend fun loadStableSnapshot(): AgentSnapshotWithRevision
    fun getLatestRevision(): Long
}

data class ToolCounts(
    val activeRecipeCount: Int,
    val workspaceFileCount: Int,
)

interface ToolCountSource {
    suspend fun loadToolCounts(): ToolCounts
}

sealed class RefreshFailure {
    object TransientStableReadChurn : RefreshFailure()
    data class TransientIO(val cause: Throwable) : RefreshFailure()
    data class CallbackApplicationFailure(val message: String) : RefreshFailure()
    data class PersistenceFailure(val cause: Throwable) : RefreshFailure()
    data class PermanentConfigurationFailure(val message: String) : RefreshFailure()
    object Cancelled : RefreshFailure()
    data class Unknown(val cause: Throwable) : RefreshFailure()
}

sealed class RefreshApplyResult {
    object Success : RefreshApplyResult()
    data class Failure(val failure: RefreshFailure) : RefreshApplyResult()
}

interface RefreshStateApplier {
    suspend fun apply(
        snapshot: AgentSnapshot,
        recipeCount: Int,
        workspaceCount: Int
    ): RefreshApplyResult
}

interface RefreshDiagnostics {
    fun info(event: String, fields: Map<String, Any?> = emptyMap())
    fun error(event: String, throwable: Throwable, fields: Map<String, Any?> = emptyMap())
}
