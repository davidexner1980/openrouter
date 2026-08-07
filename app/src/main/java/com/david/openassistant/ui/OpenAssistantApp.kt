package com.david.openassistant.ui

import android.net.Uri
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.david.openassistant.OpenAssistantUiState
import com.david.openassistant.domain.model.ModelProfile
import com.david.openassistant.ui.components.AppHeader
import com.david.openassistant.ui.components.AppNavigationBar
import com.david.openassistant.ui.screens.*

@Composable
fun OpenAssistantApp(
    state: OpenAssistantUiState,
    onKeyInputChange: (String) -> Unit,
    onToggleKeyVisibility: () -> Unit,
    onConnect: () -> Unit,
    onSelectSection: (AppSection) -> Unit,
    onRefreshModels: () -> Unit,
    onSelectModel: (String) -> Unit,
    onSelectModelProfile: (ModelProfile) -> Unit,
    onAttachImage: (Uri) -> Unit,
    onRemovePendingImage: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onRefreshAgentGoals: () -> Unit,
    onSelectAgentGoal: (String) -> Unit,
    onPauseAgentGoal: (String) -> Unit,
    onResumeAgentGoal: (String) -> Unit,
    onCancelAgentGoal: (String) -> Unit,
    onDeleteAgentGoal: (String) -> Unit,
    onRefineAgentGoal: (String, String) -> Unit,
    onExportResearchReport: (String) -> Unit,
    onSendMessage: (String) -> Unit,
    onStopGeneration: () -> Unit,
    onStartResearchBriefing: (String?) -> Unit,
    onUpdateResearchBrief: (com.david.openassistant.agent.ResearchDraft) -> Unit,
    onCancelResearchBrief: () -> Unit,
    onStartResearchMission: (com.david.openassistant.agent.ResearchDraft) -> Unit,
    onAddDirectionToMission: (String, String) -> Unit,
    onRequestBriefEdit: () -> Unit,
    onClearConversation: () -> Unit,
    onDeleteCredential: () -> Unit,
    onStartResearchMonitor: () -> Unit,
    onRefreshResearchMonitor: () -> Unit,
    onCreateResearchMonitorSnapshot: () -> Unit,
    onStopResearchMonitor: () -> Unit,
    onCreateOverseerRuntimePacket: () -> Unit,
    onRuntimePacketConsumed: () -> Unit,
    onSearxngBaseUrlChange: (String) -> Unit,
    onSaveResearchWebSettings: () -> Unit,
    onRetryPublicExport: () -> Unit,
    onOpenExportedReport: (android.content.Context) -> Unit,
    onShareExportedReport: (android.content.Context) -> Unit,
    onToggleDetailedContentCapture: (Boolean) -> Unit = {},
) {
    if (state.isRestoringLocalState) {
        RestoringLocalStateScreen()
        return
    }

    if (!state.hasStoredKey) {
        SetupScreen(
            state = state,
            onKeyInputChange = onKeyInputChange,
            onToggleKeyVisibility = onToggleKeyVisibility,
            onConnect = onConnect,
        )
        return
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            AppHeader(
                title = when (state.section) {
                    AppSection.CHAT -> state.currentConversationTitle.ifBlank { "New Research" }
                    AppSection.WORK -> "Research Missions"
                    AppSection.CONVERSATIONS -> "Research Archive"
                    AppSection.MODELS -> "Model Catalog"
                    AppSection.SETTINGS -> "Settings & Diagnostics"
                },
                subtitle = if (state.section == AppSection.CHAT || state.section == AppSection.MODELS) {
                    state.selectedModel?.name ?: "No model selected"
                } else null,
                monitorActive = state.researchMonitorStatus.active,
                showMonitorStatus = state.section == AppSection.SETTINGS || state.researchMonitorStatus.active,
            )
        },
        bottomBar = {
            AppNavigationBar(
                selected = state.section,
                onSelected = onSelectSection,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Crossfade(targetState = state.section, label = "screen_transition") { section ->
                when (section) {
                    AppSection.CHAT -> ChatScreen(
                        state = state,
                        onOpenModels = { onSelectSection(AppSection.MODELS) },
                        onOpenConversations = { onSelectSection(AppSection.CONVERSATIONS) },
                        onSendMessage = onSendMessage,
                        onStopGeneration = onStopGeneration,
                        onAttachImage = onAttachImage,
                        onRemovePendingImage = onRemovePendingImage,
                        onStartResearchBriefing = onStartResearchBriefing,
                        onUpdateResearchBrief = onUpdateResearchBrief,
                        onCancelResearchBrief = onCancelResearchBrief,
                        onStartResearchMission = onStartResearchMission,
                        onAddDirectionToMission = onAddDirectionToMission,
                        onOpenMission = onSelectAgentGoal,
                        onOpenReport = onExportResearchReport,
                        onRequestBriefEdit = onRequestBriefEdit,
                    )

                    AppSection.WORK -> AgentWorkScreen(
                        state = state,
                        onRefresh = onRefreshAgentGoals,
                        onSelectGoal = onSelectAgentGoal,
                        onPauseGoal = onPauseAgentGoal,
                        onResumeGoal = onResumeAgentGoal,
                        onCancelGoal = onCancelAgentGoal,
                        onDeleteGoal = onDeleteAgentGoal,
                        onRefineGoal = onRefineAgentGoal,
                        onExportReport = onExportResearchReport,
                    )

                    AppSection.CONVERSATIONS -> ArchiveScreen(
                        state = state,
                        onNewConversation = onNewConversation,
                        onOpenConversation = onOpenConversation,
                        onRenameConversation = onRenameConversation,
                        onDeleteConversation = onDeleteConversation,
                    )

                    AppSection.MODELS -> ModelsScreen(
                        state = state,
                        onRefresh = onRefreshModels,
                        onSelectModel = onSelectModel,
                        onSelectModelProfile = onSelectModelProfile,
                    )

                    AppSection.SETTINGS -> SettingsScreen(
                        state = state,
                        onRefreshModels = onRefreshModels,
                        onClearConversation = onClearConversation,
                        onDeleteCredential = onDeleteCredential,
                        onStartResearchMonitor = onStartResearchMonitor,
                        onRefreshResearchMonitor = onRefreshResearchMonitor,
                        onCreateResearchMonitorSnapshot = onCreateResearchMonitorSnapshot,
                        onStopResearchMonitor = onStopResearchMonitor,
                        onRetryPublicExport = onRetryPublicExport,
                        onOpenExportedReport = onOpenExportedReport,
                        onShareExportedReport = onShareExportedReport,
                        onCreateOverseerRuntimePacket = onCreateOverseerRuntimePacket,
                        onRuntimePacketConsumed = onRuntimePacketConsumed,
                        onSearxngBaseUrlChange = onSearxngBaseUrlChange,
                        onSaveResearchWebSettings = onSaveResearchWebSettings,
                        onToggleDetailedContentCapture = onToggleDetailedContentCapture,
                    )
                }
            }
        }
    }
}
