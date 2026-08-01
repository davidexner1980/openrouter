package com.david.openassistant.ui

import com.david.openassistant.domain.model.ModelProfile

enum class AppSection {
    CHAT,
    WORK,
    CONVERSATIONS,
    MODELS,
    SETTINGS,
}

enum class RequestStatus {
    IDLE,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

data class RequestDiagnostics(
    val operation: String = "None",
    val status: RequestStatus = RequestStatus.IDLE,
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val httpStatus: Int? = null,
    val modelId: String? = null,
    val resolvedModel: String? = null,
    val responseId: String? = null,
    val totalTokens: Int? = null,
    val cost: Double? = null,
    val message: String? = null,
    val note: String? = null,
) {
    val durationMillis: Long?
        get() = if (startedAt != null && finishedAt != null) finishedAt - startedAt else null

    companion object {
        fun running(operation: String, modelId: String? = null) = RequestDiagnostics(
            operation = operation,
            status = RequestStatus.RUNNING,
            startedAt = System.currentTimeMillis(),
            modelId = modelId,
        )

        fun succeeded(
            operation: String,
            startedAt: Long,
            httpStatus: Int?,
            modelId: String? = null,
            resolvedModel: String? = null,
            responseId: String? = null,
            totalTokens: Int? = null,
            cost: Double? = null,
            note: String? = null,
        ) = RequestDiagnostics(
            operation = operation,
            status = RequestStatus.SUCCEEDED,
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
            httpStatus = httpStatus,
            modelId = modelId,
            resolvedModel = resolvedModel,
            responseId = responseId,
            totalTokens = totalTokens,
            cost = cost,
            note = note,
        )

        fun failed(
            operation: String,
            startedAt: Long,
            httpStatus: Int?,
            modelId: String? = null,
            message: String,
        ) = RequestDiagnostics(
            operation = operation,
            status = RequestStatus.FAILED,
            startedAt = startedAt,
            finishedAt = System.currentTimeMillis(),
            httpStatus = httpStatus,
            modelId = modelId,
            message = message,
        )

        fun cancelled(
            operation: String,
            modelId: String?,
            note: String? = null,
        ) = RequestDiagnostics(
            operation = operation,
            status = RequestStatus.CANCELLED,
            startedAt = System.currentTimeMillis(),
            finishedAt = System.currentTimeMillis(),
            modelId = modelId,
            note = note,
        )
    }
}

data class ConversationSummary(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
    val selectedModelId: String?,
    val modelProfile: ModelProfile,
)

data class ToolExecutionEvidence(
    val displayName: String,
    val summary: String,
    val executedAt: Long,
)
