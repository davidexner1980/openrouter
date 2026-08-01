package com.david.openassistant

import com.david.openassistant.agent.AgentGoal
import com.david.openassistant.data.diagnostics.ResearchMonitorStatus
import com.david.openassistant.data.openrouter.OpenRouterKeyInfo
import com.david.openassistant.data.openrouter.OpenRouterModel
import com.david.openassistant.domain.model.ModelProfile
import com.david.openassistant.agent.RuntimePacketExporter
import com.david.openassistant.agent.ResearchDraft
import com.david.openassistant.data.openrouter.ChatMessage
import com.david.openassistant.data.diagnostics.ExportResult
import com.david.openassistant.ui.AppSection
import com.david.openassistant.ui.ConversationSummary
import com.david.openassistant.ui.RequestDiagnostics
import com.david.openassistant.ui.ToolExecutionEvidence

const val DEFAULT_CONVERSATION_TITLE = "New Research"

data class OpenAssistantUiState(
    val isRestoringLocalState: Boolean = false,
    val hasStoredKey: Boolean = false,
    val keyInput: String = "",
    val keyVisible: Boolean = false,
    val isConnecting: Boolean = false,
    val connectionError: String? = null,
    val keyInfo: OpenRouterKeyInfo? = null,
    val models: List<OpenRouterModel> = emptyList(),
    val selectedModelId: String? = null,
    val selectedModelProfile: ModelProfile = ModelProfile.MANUAL,
    val isLoadingModels: Boolean = false,
    val modelError: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val conversations: List<ConversationSummary> = emptyList(),
    val activeConversationId: String = "",
    val currentConversationTitle: String = DEFAULT_CONVERSATION_TITLE,
    val isGenerating: Boolean = false,
    val chatError: String? = null,
    val isPlanningAgentGoal: Boolean = false,
    val agentGoals: List<AgentGoal> = emptyList(),
    val selectedAgentGoalId: String? = null,
    val agentError: String? = null,
    val lastToolExecution: ToolExecutionEvidence? = null,
    val activeToolRecipeCount: Int = 0,
    val workspaceFileCount: Int = 0,
    val searxngBaseUrlInput: String = "",
    val researchWebSettingsMessage: String? = null,
    val pendingImageAttachment: com.david.openassistant.data.openrouter.ChatAttachment? = null,
    val isImportingImage: Boolean = false,
    val attachmentError: String? = null,
    val isGeneratingBrief: Boolean = false,
    val isResearchBriefEditRequested: Boolean = false,
    val researchDraft: ResearchDraft? = null,
    val section: AppSection = AppSection.CHAT,
    val diagnostics: RequestDiagnostics = RequestDiagnostics(),
    val diagnosticLogPath: String = "",
    val researchMonitorStatus: ResearchMonitorStatus = ResearchMonitorStatus(),
    val isPreparingResearchMonitorReport: Boolean = false,
    val researchMonitorError: String? = null,
    val lastExportResult: ExportResult? = null,
    val pendingLegacyPermissionOperationId: String? = null,
    val isPreparingRuntimePacket: Boolean = false,
    val runtimePacketReady: RuntimePacketExporter.ExportResult? = null,
    val activeWorkRunningStates: Map<String, Boolean> = emptyMap(),
) {
    val selectedModel: OpenRouterModel?
        get() = models.firstOrNull { it.id == selectedModelId }

    val selectedAgentGoal: AgentGoal?
        get() = selectedAgentGoalId?.let { selectedId -> agentGoals.firstOrNull { it.id == selectedId } }
            ?: agentGoals.maxByOrNull { it.updatedAt }
}
