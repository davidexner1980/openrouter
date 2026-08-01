package com.david.openassistant

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.david.openassistant.ui.OpenAssistantApp
import com.david.openassistant.ui.theme.OpenAssistantTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val assistantViewModel: OpenAssistantViewModel by viewModels()

    private val legacyDownloadsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        assistantViewModel.onLegacyDownloadsPermissionResult(granted)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                assistantViewModel.exportEvents.collect { event ->
                    when (event) {
                        is ExportEvent.RequestLegacyDownloadsPermission -> {
                            legacyDownloadsPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }
                    }
                }
            }
        }

        setContent {
            val state by assistantViewModel.uiState.collectAsStateWithLifecycle()
            OpenAssistantTheme {
                OpenAssistantApp(
                    state = state,
                    onKeyInputChange = assistantViewModel::updateKeyInput,
                    onToggleKeyVisibility = assistantViewModel::toggleKeyVisibility,
                    onConnect = assistantViewModel::connectAndSaveKey,
                    onSelectSection = assistantViewModel::selectSection,
                    onRefreshModels = assistantViewModel::refreshModels,
                    onSelectModel = assistantViewModel::selectModel,
                    onSelectModelProfile = assistantViewModel::selectModelProfile,
                    onAttachImage = assistantViewModel::attachImage,
                    onRemovePendingImage = assistantViewModel::removePendingImage,
                    onNewConversation = assistantViewModel::newConversation,
                    onOpenConversation = assistantViewModel::openConversation,
                    onRenameConversation = assistantViewModel::renameConversation,
                    onDeleteConversation = assistantViewModel::deleteConversation,
                    onRefreshAgentGoals = assistantViewModel::refreshAgentGoals,
                    onSelectAgentGoal = assistantViewModel::selectAgentGoal,
                    onPauseAgentGoal = assistantViewModel::pauseAgentGoal,
                    onResumeAgentGoal = assistantViewModel::resumeAgentGoal,
                    onCancelAgentGoal = assistantViewModel::cancelAgentGoal,
                    onDeleteAgentGoal = assistantViewModel::deleteAgentGoal,
                    onRefineAgentGoal = assistantViewModel::refineAgentGoal,
                    onExportResearchReport = assistantViewModel::exportResearchReport,
                    onSendMessage = assistantViewModel::sendMessage,
                    onStopGeneration = assistantViewModel::stopGeneration,
                    onStartResearchBriefing = assistantViewModel::startResearchBriefing,
                    onUpdateResearchBrief = assistantViewModel::updateResearchBrief,
                    onCancelResearchBrief = assistantViewModel::cancelResearchBrief,
                    onStartResearchMission = assistantViewModel::startResearchMission,
                    onAddDirectionToMission = assistantViewModel::addDirectionToMission,
                    onRequestBriefEdit = assistantViewModel::requestBriefEdit,
                    onClearConversation = assistantViewModel::clearConversation,
                    onDeleteCredential = assistantViewModel::deleteCredential,
                    onStartResearchMonitor = assistantViewModel::startResearchMonitor,
                    onRefreshResearchMonitor = assistantViewModel::refreshResearchMonitorStatus,
                    onCreateResearchMonitorSnapshot = assistantViewModel::createResearchMonitorSnapshotReport,
                    onStopResearchMonitor = assistantViewModel::stopResearchMonitorAndCreateReport,
                    onCreateOverseerRuntimePacket = assistantViewModel::createOverseerRuntimePacket,
                    onRuntimePacketConsumed = assistantViewModel::onRuntimePacketConsumed,
                    onSearxngBaseUrlChange = assistantViewModel::updateSearxngBaseUrlInput,
                    onSaveResearchWebSettings = assistantViewModel::saveResearchWebSettings,
                    onRetryPublicExport = assistantViewModel::retryPublicExport,
                    onOpenExportedReport = assistantViewModel::openExportedReport,
                    onShareExportedReport = assistantViewModel::shareExportedReport,
                )
            }
        }
    }
}
