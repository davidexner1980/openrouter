package com.david.openassistant.agent

/**
 * Explicit request context partition preventing non-mission provider requests
 * from polluting mission accounting or creating fake goals.
 */
sealed class ProviderRequestContext {
    data class Mission(
        val goalId: String,
        val workerId: String,
        val taskId: String? = null,
        val attemptId: String,
        val executionGeneration: Int,
        val role: AgentTaskRole? = null,
        val operation: MissionOperation,
        val parentOperationId: String,
    ) : ProviderRequestContext() {
        fun forChildOperation(
            operation: MissionOperation,
            role: AgentTaskRole,
            taskId: String? = this.taskId,
        ): Mission {
            require(!operation.taskBound || taskId != null) {
                "Task-bound operation ${operation.operationName} requires a non-null taskId"
            }
            require(operation.taskBound || taskId == null) {
                "Goal-bound operation ${operation.operationName} must have a null taskId"
            }
            return copy(
                operation = operation,
                role = role,
                taskId = taskId,
            )
        }
    }

    data class Conversation(
        val conversationId: String,
        val operation: String = "chat_completion",
    ) : ProviderRequestContext()

    data class Infrastructure(
        val operationId: String,
        val operationType: String = "system_check",
    ) : ProviderRequestContext()
}
