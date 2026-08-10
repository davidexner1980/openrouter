package com.david.openassistant

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.david.openassistant.agent.AutomationRoute
import com.david.openassistant.agent.AutomationRouter
import com.david.openassistant.agent.ProviderActivityStore
import com.david.openassistant.agent.AutonomyPolicy
import com.david.openassistant.ui.AppSection
import com.david.openassistant.ui.ConversationSummary
import com.david.openassistant.ui.RequestDiagnostics
import com.david.openassistant.ui.RequestStatus
import com.david.openassistant.ui.ToolExecutionEvidence
import com.david.openassistant.agent.AgentGoal
import com.david.openassistant.agent.isActivePhase
import com.david.openassistant.agent.isInactive
import com.david.openassistant.agent.AgentGoalStatus
import com.david.openassistant.agent.AgentLeasePolicy
import com.david.openassistant.agent.AgentLifecycleReducer
import com.david.openassistant.agent.AgentSnapshot
import com.david.openassistant.agent.AgentStore
import com.david.openassistant.agent.ResumeReason
import com.david.openassistant.agent.RefreshApplyResult
import com.david.openassistant.agent.RefreshStateApplier
import com.david.openassistant.data.local.AttachmentStore
import com.david.openassistant.data.local.ConversationSnapshot
import com.david.openassistant.data.local.ConversationStore
import com.david.openassistant.data.local.StoredConversation
import com.david.openassistant.data.diagnostics.ResearchMonitor
import com.david.openassistant.data.diagnostics.ResearchMonitorStatus
import com.david.openassistant.data.diagnostics.RuntimeDiagnostics
import com.david.openassistant.data.diagnostics.ExportResult
import com.david.openassistant.data.diagnostics.ExportStatus
import com.david.openassistant.data.diagnostics.ReportKind
import com.david.openassistant.data.diagnostics.PublicExportManager
import com.david.openassistant.data.diagnostics.PublicExportMetadata
import com.david.openassistant.data.diagnostics.toExportResult
import com.david.openassistant.data.local.createConversationTitle
import com.david.openassistant.data.openrouter.ChatAttachment
import com.david.openassistant.data.openrouter.ChatMessage
import com.david.openassistant.data.openrouter.ChatRole
import com.david.openassistant.data.openrouter.ChatStreamListener
import com.david.openassistant.data.openrouter.OpenRouterClient
import com.david.openassistant.data.openrouter.OpenRouterException
import com.david.openassistant.data.openrouter.OpenRouterKeyInfo
import com.david.openassistant.data.openrouter.OpenRouterModel
import com.david.openassistant.data.openrouter.StreamSummary
import com.david.openassistant.data.openrouter.StreamingDeltaAccumulator
import com.david.openassistant.data.network.ResearchWebNetworkConfig
import com.david.openassistant.data.network.ResearchWebSettings
import com.david.openassistant.domain.AgentInteractor
import com.david.openassistant.domain.AuthInteractor
import com.david.openassistant.domain.ConversationInteractor
import com.david.openassistant.domain.model.ModelProfile
import com.david.openassistant.domain.model.ModelProfileSelector
import com.david.openassistant.domain.model.AgentModelSelector
import com.david.openassistant.domain.tools.AutonomousToolRuntime
import com.david.openassistant.agent.compactSummary
import com.david.openassistant.agent.ResearchDraft
import com.david.openassistant.agent.ResearchDraftStatus
import com.david.openassistant.agent.ResearchMissionStartRecovery
import com.david.openassistant.agent.ResearchMissionStartTelemetry
import com.david.openassistant.agent.DebugMissionStartHook
import com.david.openassistant.agent.isFinalTerminalStatus
import com.david.openassistant.domain.BriefingInteractor
import com.david.openassistant.agent.RuntimePacketExporter
import com.david.openassistant.agent.isInactive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

private const val STREAM_UI_FLUSH_INTERVAL_MS = 50L

private fun createBootstrapConversationSnapshot(): ConversationSnapshot {
    val conversation = StoredConversation.empty()
    return ConversationSnapshot(
        conversations = listOf(conversation),
        activeConversationId = conversation.id,
    )
}

private data class RestoredLocalState(
    val conversations: ConversationSnapshot,
    val agents: AgentSnapshot,
    val pendingDraft: ResearchDraft?,
    val interruptedDraftToReplay: ResearchDraft?,
    val recoveredExistingGoalId: String?,
    val apiKey: String?,
    val credentialCouldNotBeDecrypted: Boolean,
    val activeToolRecipeCount: Int,
    val workspaceFileCount: Int,
    val researchWebNetworkConfig: ResearchWebNetworkConfig,
    val recoveryReason: String? = null,
)

sealed class ExportEvent {
    data class RequestLegacyDownloadsPermission(val operationId: String) : ExportEvent()
    object RequestNotificationPermission : ExportEvent()
}

private data class ChatOperation(
    val operationId: String,
    val originConversationId: String,
    val assistantMessageId: String,
    var job: Job? = null,
    var call: Call? = null,
    val safetyFilter: com.david.openassistant.agent.StreamingSafetyFilter = com.david.openassistant.agent.StreamingSafetyFilter()
)

private data class ChatSettlement(
    val operationId: String,
    val originConversationId: String,
    val assistantMessageId: String,
    val settledMessages: List<com.david.openassistant.data.openrouter.ChatMessage>,
    val originSelectedModelId: String?,
    val originModelProfile: com.david.openassistant.domain.model.ModelProfile,
    val originTitle: String,
    val terminalReason: String
)

class OpenAssistantViewModel(application: Application) : AndroidViewModel(application), RefreshStateApplier {
    private val researchMonitor = ResearchMonitor(application)
    private val publicExportManager = PublicExportManager(application)
    private val attachmentStore = AttachmentStore(application) // Still needed for data URL provider
    private val activityStore = ProviderActivityStore(application)
    
    private val _exportEvents = kotlinx.coroutines.flow.MutableSharedFlow<ExportEvent>()
    val exportEvents = _exportEvents.asSharedFlow()
    private val openRouterClient = OpenRouterClient(
        researchMonitor = researchMonitor,
        activityStore = activityStore,
        attachmentDataUrlProvider = attachmentStore::toDataUrl,
    )
    
    private val authInteractor = AuthInteractor(application, openRouterClient)
    private val conversationInteractor = ConversationInteractor(application)
    private val agentInteractor = AgentInteractor(application)
    private val briefingInteractor = BriefingInteractor(application)

    private val researchWebSettings = ResearchWebSettings(application)
    private val diagnostics = RuntimeDiagnostics(application)
    
    private val autonomousToolRuntime = AutonomousToolRuntime(application)
    private val autonomyPolicy = AutonomyPolicy.DEFAULT
    
    private var cachedApiKey: String? = null
    private var agentSnapshot: AgentSnapshot = AgentSnapshot()
    private var interruptedDraftPendingReplay: ResearchDraft? = null
    private var startupRecoveryReason: String? = null
    private var deliveringAgentResults = false
    private var lastProcessedRevision: Long = -1L
    private var hasRequestedNotificationPermission = false

    override suspend fun apply(
        snapshot: AgentSnapshot,
        recipeCount: Int,
        workspaceCount: Int
    ): RefreshApplyResult {
        return runCatching {
            agentSnapshot = snapshot
            emitUiState()
            RefreshApplyResult.Success
        }.getOrElse { error ->
            RefreshApplyResult.Failure(
                com.david.openassistant.agent.RefreshFailure.CallbackApplicationFailure(
                    error.message ?: error.javaClass.simpleName
                )
            )
        }
    }

    private suspend fun emitUiState() {
        val toolCounts = autonomousToolRuntime.loadToolCounts()
        val activeGoals = agentSnapshot.goals.filter { !it.status.isInactive() }
        val runningStates = if (activeGoals.isEmpty()) {
            emptyMap()
        } else {
            activeGoals.associate { goal ->
                goal.id to agentInteractor.isWorkRunning(goal.id, goal.executionGeneration)
            }
        }

        _uiState.update {
            it.copy(
                agentGoals = agentSnapshot.goals,
                selectedAgentGoalId = agentSnapshot.selectedGoalId,
                activeToolRecipeCount = toolCounts.activeRecipeCount,
                workspaceFileCount = toolCounts.workspaceFileCount,
                activeWorkRunningStates = runningStates,
            )
        }
        
        // Request notification permission once when an active mission appears
        if (!hasRequestedNotificationPermission && agentSnapshot.goals.any { !it.status.isInactive() }) {
            hasRequestedNotificationPermission = true
            _exportEvents.emit(ExportEvent.RequestNotificationPermission)
        }
    }
    private val researchMonitorStatusRefreshPending = AtomicBoolean(false)
    private val agentPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AgentStore.KEY_REVISION) {
            refreshAgentSnapshot()
        }
    }
    @Volatile
    private var currentChatOperation: ChatOperation? = null
    private val stopRequested = AtomicBoolean(false)
    private val goalsBeingFinalized = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val streamingDeltaAccumulator = StreamingDeltaAccumulator()
    private val streamFlushScheduled = AtomicBoolean(false)
    private var streamFlushJob: Job? = null
    private val streamDeltaCount = AtomicInteger(0)
    private val streamCharacterCount = AtomicInteger(0)
    private val firstStreamDeltaLogged = AtomicBoolean(false)

    private val conversationLock = Any()
    private var conversationSnapshot = createBootstrapConversationSnapshot()
    private val initialConversation = conversationSnapshot.activeConversation
    private val _uiState = MutableStateFlow(
        OpenAssistantUiState(
            isRestoringLocalState = true,
            messages = initialConversation.messages,
            selectedModelId = initialConversation.selectedModelId,
            selectedModelProfile = initialConversation.modelProfile,
            conversations = conversationSnapshot.toSummaries(),
            activeConversationId = initialConversation.id,
            currentConversationTitle = initialConversation.title,
            diagnosticLogPath = diagnostics.activeLogFile().absolutePath,
            researchMonitorStatus = researchMonitor.status(),
        ),
    )
    val uiState: StateFlow<OpenAssistantUiState> = _uiState.asStateFlow()

    init {
        val startupStartedAt = System.currentTimeMillis()
        diagnostics.info("startup_begin", mapOf("version" to BuildConfig.VERSION_NAME))
        agentInteractor.registerListener(agentPreferenceListener)
        agentInteractor.schedulePeriodicRecovery()

        viewModelScope.launch {
            val lastExport = withContext(Dispatchers.IO) { publicExportManager.loadLastExportMetadata() }
            if (lastExport != null && lastExport.exportStatus == ExportStatus.EXPORTED) {
                val exists = withContext(Dispatchers.IO) {
                    publicExportManager.validateExportedItem(lastExport)
                }
                if (exists) {
                    _uiState.update { it.copy(lastExportResult = lastExport.toExportResult()) }
                }
            } else {
                _uiState.update { it.copy(lastExportResult = lastExport?.toExportResult()) }
            }

            runCatching {
                withContext(Dispatchers.IO) {
                    val conversations = conversationInteractor.loadSnapshot()
                    val loadedAgents = agentInteractor.loadSnapshot()
                    val pendingDraftRecovery = ResearchMissionStartRecovery.decide(
                        rawDraft = agentInteractor.loadPendingDraft(),
                        goals = loadedAgents.goals,
                    )
                    when (pendingDraftRecovery.action) {
                        ResearchMissionStartRecovery.Action.CLEAR_DRAFT_FOR_EXISTING_GOAL -> {
                            agentInteractor.savePendingDraft(null)
                        }
                        ResearchMissionStartRecovery.Action.REPLAY_INTERRUPTED_START -> {
                            agentInteractor.savePendingDraft(pendingDraftRecovery.draftForUi)
                        }
                        ResearchMissionStartRecovery.Action.NONE,
                        ResearchMissionStartRecovery.Action.KEEP_DRAFT,
                        -> Unit
                    }
                    val agents = pendingDraftRecovery.existingGoalId
                        ?.let { agentInteractor.selectGoal(it) }
                        ?: loadedAgents
                    val hadEncryptedCredential = authInteractor.hasEncryptedCredential()
                    val apiKey = authInteractor.loadApiKey()
                    if (hadEncryptedCredential && (apiKey == null)) authInteractor.clearCredential()
                    val toolCounts = autonomousToolRuntime.loadToolCounts()
                    RestoredLocalState(
                        conversations = conversations,
                        agents = agents,
                        pendingDraft = pendingDraftRecovery.draftForUi,
                        interruptedDraftToReplay = pendingDraftRecovery.draftForUi
                            ?.takeIf { pendingDraftRecovery.shouldReplayStart },
                        recoveredExistingGoalId = pendingDraftRecovery.existingGoalId,
                        apiKey = apiKey,
                        credentialCouldNotBeDecrypted = hadEncryptedCredential && (apiKey == null),
                        activeToolRecipeCount = toolCounts.activeRecipeCount,
                        workspaceFileCount = toolCounts.workspaceFileCount,
                        researchWebNetworkConfig = researchWebSettings.load(),
                        recoveryReason = pendingDraftRecovery.recoveryReason,
                    )
                }
            }.onSuccess { restored ->
                conversationSnapshot = restored.conversations
                cachedApiKey = restored.apiKey
                interruptedDraftPendingReplay = restored.interruptedDraftToReplay
                val active = restored.conversations.activeConversation
                apply(restored.agents, restored.activeToolRecipeCount, restored.workspaceFileCount)
                _uiState.update { state ->
                    state.copy(
                        isRestoringLocalState = false,
                        hasStoredKey = restored.apiKey != null,
                        connectionError = if (restored.credentialCouldNotBeDecrypted) {
                            "The saved credential could not be decrypted. Enter it again."
                        } else {
                            null
                        },
                        messages = active.messages,
                        selectedModelId = active.selectedModelId,
                        selectedModelProfile = active.modelProfile,
                        conversations = restored.conversations.toSummaries(),
                        activeConversationId = active.id,
                        currentConversationTitle = active.title,
                        agentError = restored.agents.quarantinedMissions
                            .takeIf { it.isNotEmpty() }
                            ?.let { "${it.size} unreadable mission file(s) were quarantined. Healthy missions remain available." },
                        researchDraft = restored.pendingDraft,
                        searxngBaseUrlInput = restored.researchWebNetworkConfig.searxngBaseUrl.orEmpty(),
                    )
                }
                interruptedDraftPendingReplay = restored.interruptedDraftToReplay
                startupRecoveryReason = restored.recoveryReason
                diagnostics.info(
                    "startup_restore_complete",
                    mapOf(
                        "duration_ms" to (System.currentTimeMillis() - startupStartedAt),
                        "conversation_count" to restored.conversations.conversations.size,
                        "goal_count" to restored.agents.goals.size,
                        "quarantined_goal_count" to restored.agents.quarantinedMissions.size,
                        "recipe_count" to restored.activeToolRecipeCount,
                        "workspace_file_count" to restored.workspaceFileCount,
                        "credential_available" to (restored.apiKey != null),
                    ),
                )
                restored.recoveredExistingGoalId?.let { goalId ->
                    ensureResearchMonitorActive()
                    researchMonitor.record(
                        category = "mission",
                        event = "interrupted_mission_start_recovered_existing_goal",
                        correlationId = goalId,
                        fields = mapOf("goal_id" to goalId),
                    )
                }
                viewModelScope.launch(Dispatchers.IO) {
                    conversationInteractor.pruneAttachments(restored.conversations.attachmentFileNames())
                    if (restored.apiKey != null) {
                        val now = System.currentTimeMillis()
                        restored.agents.goals
                            .filter { goal -> goal.status.isActivePhase() }
                            .forEach { goal ->
                                val hasFreshLease = goal.executionLease
                                    ?.let { lease -> !AgentLeasePolicy.isStale(lease, now) }
                                    ?: false
                                if (hasFreshLease) {
                                    agentInteractor.enqueue(goal.id, executionGeneration = goal.executionGeneration)
                                } else {
                                    val executionGeneration = goal.executionGeneration
                                    agentInteractor.updateGoal(goal.id) { current ->
                                        AgentLifecycleReducer.resume(
                                            current,
                                            reason = ResumeReason.PROCESS_RECOVERY,
                                            message = "The app reopened and automatically recovered a mission with no active worker lease.",
                                        )
                                    }
                                    agentInteractor.enqueue(goal.id, replace = true, executionGeneration = executionGeneration)
                                }
                            }
                    }
                }
                refreshAgentSnapshot()
                restored.interruptedDraftToReplay?.let { interruptedDraft ->
                    ensureResearchMonitorActive()
                    researchMonitor.record(
                        category = "mission",
                        event = "interrupted_mission_start_replay_requested",
                        correlationId = interruptedDraft.id,
                        fields = mapOf(
                            "draft_id" to interruptedDraft.id,
                            "linked_goal_id" to interruptedDraft.linkedGoalId,
                            "reason" to restored.recoveryReason,
                        ),
                    )
                }
                if (restored.apiKey == null) {
                    replayInterruptedResearchStartIfNeeded()
                } else {
                    reconnectWithApiKey(restored.apiKey)
                }
            }.onFailure { error ->
                diagnostics.error("startup_restore_failed", error)
                _uiState.update {
                    it.copy(
                        isRestoringLocalState = false,
                        hasStoredKey = false,
                        connectionError = "Local app state could not be restored: ${error.message.orEmpty().ifBlank { error::class.java.simpleName }}",
                    )
                }
            }
        }
    }

    fun updateKeyInput(value: String) {
        _uiState.update { it.copy(keyInput = value, connectionError = null) }
    }

    fun toggleKeyVisibility() {
        _uiState.update { it.copy(keyVisible = !it.keyVisible) }
    }

    fun connectAndSaveKey() {
        val apiKey = _uiState.value.keyInput.trim()
        if (apiKey.isBlank()) {
            _uiState.update { it.copy(connectionError = "Enter an OpenRouter API key first.") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isConnecting = true,
                    connectionError = null,
                    diagnostics = RequestDiagnostics.running("Validate API key"),
                )
            }
            val startedAt = System.currentTimeMillis()
            authInteractor.validateAndSaveKey(apiKey)
                .onSuccess { keyInfo ->
                    cachedApiKey = apiKey
                    diagnostics.info(
                        "credential_validated",
                        mapOf("duration_ms" to (System.currentTimeMillis() - startedAt)),
                    )
                    _uiState.update {
                        it.copy(
                            hasStoredKey = true,
                            keyInput = "",
                            keyVisible = false,
                            isConnecting = false,
                            keyInfo = keyInfo,
                            diagnostics = RequestDiagnostics.succeeded(
                                operation = "Validate API key",
                                startedAt = startedAt,
                                httpStatus = 200,
                            ),
                        )
                    }
                    replayInterruptedResearchStartIfNeeded()
                    loadModels(apiKey)
                    scheduleActiveAgentGoals()
                }.onFailure { error ->
                    val openRouterError = error.asOpenRouterException()
                    diagnostics.error(
                        event = "credential_validation_failed",
                        throwable = error,
                        fields = mapOf("http_status" to openRouterError.statusCode),
                    )
                    _uiState.update {
                        it.copy(
                            isConnecting = false,
                            connectionError = openRouterError.userMessage,
                            diagnostics = RequestDiagnostics.failed(
                                operation = "Validate API key",
                                startedAt = startedAt,
                                httpStatus = openRouterError.statusCode,
                                message = openRouterError.userMessage,
                            ),
                        )
                    }
                }
        }
    }

    fun reconnectAndLoadModels() {
        viewModelScope.launch {
            val apiKey = cachedApiKey ?: authInteractor.loadApiKey()
            if (apiKey == null) {
                cachedApiKey = null
                authInteractor.clearCredential()
                _uiState.update {
                    it.copy(
                        hasStoredKey = false,
                        connectionError = "The saved credential could not be decrypted. Enter it again.",
                    )
                }
                return@launch
            }
            cachedApiKey = apiKey
            reconnectWithApiKey(apiKey)
        }
    }

    private fun reconnectWithApiKey(apiKey: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isConnecting = true,
                    connectionError = null,
                    diagnostics = RequestDiagnostics.running("Reconnect"),
                )
            }
            val startedAt = System.currentTimeMillis()
            authInteractor.validateKey(apiKey)
                .onSuccess { keyInfo ->
                    cachedApiKey = apiKey
                    diagnostics.info(
                        "openrouter_reconnected",
                        mapOf("duration_ms" to (System.currentTimeMillis() - startedAt)),
                    )
                    _uiState.update {
                        it.copy(
                            hasStoredKey = true,
                            isConnecting = false,
                            keyInfo = keyInfo,
                            diagnostics = RequestDiagnostics.succeeded(
                                operation = "Reconnect",
                                startedAt = startedAt,
                                httpStatus = 200,
                            ),
                        )
                    }
                    replayInterruptedResearchStartIfNeeded()
                    loadModels(apiKey)
                    scheduleActiveAgentGoals()
                }.onFailure { error ->
                    val openRouterError = error.asOpenRouterException()
                    diagnostics.error(
                        event = "openrouter_reconnect_failed",
                        throwable = error,
                        fields = mapOf("http_status" to openRouterError.statusCode),
                    )
                    if (openRouterError.statusCode == 401) {
                        cachedApiKey = null
                        authInteractor.clearCredential()
                        _uiState.update {
                            it.copy(
                                hasStoredKey = false,
                                isConnecting = false,
                                connectionError = "The saved OpenRouter key was rejected. Enter a valid key.",
                                models = emptyList(),
                                diagnostics = RequestDiagnostics.failed(
                                    operation = "Reconnect",
                                    startedAt = startedAt,
                                    httpStatus = openRouterError.statusCode,
                                    message = openRouterError.userMessage,
                                ),
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                isConnecting = false,
                                connectionError = openRouterError.userMessage,
                                modelError = openRouterError.userMessage,
                                diagnostics = RequestDiagnostics.failed(
                                    operation = "Reconnect",
                                    startedAt = startedAt,
                                    httpStatus = openRouterError.statusCode,
                                    message = openRouterError.userMessage,
                                ),
                            )
                        }
                    }
                }
        }
    }

    fun refreshModels() {
        val apiKey = cachedApiKey
        if (apiKey == null) {
            reconnectAndLoadModels()
            return
        }
        loadModels(apiKey)
    }

    private fun loadModels(apiKey: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingModels = true,
                    modelError = null,
                    diagnostics = RequestDiagnostics.running("Load model catalog"),
                )
            }
            val startedAt = System.currentTimeMillis()
            runCatching {
                withContext(Dispatchers.IO) { openRouterClient.fetchModels(apiKey) }
            }.onSuccess { models ->
                val state = _uiState.value
                val selectedModel = ModelProfileSelector.choose(
                    profile = state.selectedModelProfile,
                    models = models,
                    currentModelId = state.selectedModelId,
                ) ?: chooseInitialModel(models)
                val selectedId = selectedModel?.id
                val summaries = persistConversation(
                    conversationId = state.activeConversationId,
                    messages = state.messages,
                    selectedModelId = selectedId,
                    modelProfile = state.selectedModelProfile,
                    title = state.currentConversationTitle,
                    touchUpdatedAt = false,
                )

                _uiState.update {
                    it.copy(
                        models = models,
                        selectedModelId = selectedId,
                        conversations = summaries,
                        isLoadingModels = false,
                        modelError = null,
                        attachmentError = if (it.pendingImageAttachment != null && (selectedModel?.supportsVision != true)) {
                            "Choose the Vision profile or an image-capable model before sending the attached image."
                        } else {
                            null
                        },
                        diagnostics = RequestDiagnostics.succeeded(
                            operation = "Load model catalog",
                            startedAt = startedAt,
                            httpStatus = 200,
                            note = "Loaded ${models.size} models.",
                        ),
                    )
                }
            }.onFailure { error ->
                val openRouterError = error.asOpenRouterException()
                _uiState.update {
                    it.copy(
                        isLoadingModels = false,
                        modelError = openRouterError.userMessage,
                        diagnostics = RequestDiagnostics.failed(
                            operation = "Load model catalog",
                            startedAt = startedAt,
                            httpStatus = openRouterError.statusCode,
                            message = openRouterError.userMessage,
                        ),
                    )
                }
            }
        }
    }

    fun selectSection(section: AppSection) {
        _uiState.update { it.copy(section = section, isResearchBriefEditRequested = false) }
        if (section in setOf(AppSection.WORK, AppSection.SETTINGS)) {
            refreshResearchMonitorStatus()
        }
    }

    fun selectModel(modelId: String) {
        val model = _uiState.value.models.firstOrNull { it.id == modelId } ?: return
        if (!model.supportsTextChat) {
            _uiState.update {
                it.copy(
                            modelError = "${model.name} is an audio/image-only model and cannot be used for text research or autonomous investigations.",
                )
            }
            return
        }
        val summaries = persistConversation(
            conversationId = _uiState.value.activeConversationId,
            messages = _uiState.value.messages,
            selectedModelId = modelId,
            modelProfile = ModelProfile.MANUAL,
            title = _uiState.value.currentConversationTitle,
            touchUpdatedAt = false,
        )
        _uiState.update {
            it.copy(
                selectedModelId = modelId,
                selectedModelProfile = ModelProfile.MANUAL,
                conversations = summaries,
                modelError = null,
                attachmentError = if (it.pendingImageAttachment != null && !model.supportsVision) {
                    "Choose an image-capable model before sending the attached image."
                } else {
                    null
                },
                section = AppSection.CHAT,
            )
        }
    }

    fun selectModelProfile(profile: ModelProfile) {
        val state = _uiState.value
        val model = ModelProfileSelector.choose(profile, state.models, state.selectedModelId)
        if (profile != ModelProfile.MANUAL && model == null) {
            _uiState.update { it.copy(modelError = "No model is available for the ${profile.displayName} profile.") }
            return
        }
        val selectedId = model?.id ?: state.selectedModelId
        val summaries = persistConversation(
            conversationId = state.activeConversationId,
            messages = state.messages,
            selectedModelId = selectedId,
            modelProfile = profile,
            title = state.currentConversationTitle,
            touchUpdatedAt = false,
        )
        _uiState.update {
            it.copy(
                selectedModelId = selectedId,
                selectedModelProfile = profile,
                conversations = summaries,
                modelError = null,
                attachmentError = if (it.pendingImageAttachment != null && model?.supportsVision != true) {
                    "No image-capable model is selected for the pending image."
                } else {
                    null
                },
            )
        }
    }

    fun updateSearxngBaseUrlInput(value: String) {
        _uiState.update {
            it.copy(
                searxngBaseUrlInput = value.take(2_048),
                researchWebSettingsMessage = null,
            )
        }
    }

    fun saveResearchWebSettings() {
        val state = _uiState.value
        runCatching {
            researchWebSettings.save(searxngBaseUrl = state.searxngBaseUrlInput)
        }.onSuccess { saved ->
            diagnostics.info(
                "research_web_settings_saved",
                mapOf(
                    "searxng_configured" to (saved.searxngBaseUrl != null),
                    "scope" to "public_web_search_only",
                ),
            )
            _uiState.update {
                it.copy(
                    searxngBaseUrlInput = saved.searxngBaseUrl.orEmpty(),
                    researchWebSettingsMessage = "Research web settings saved.",
                )
            }
        }.onFailure { error ->
            _uiState.update {
                it.copy(
                    researchWebSettingsMessage = error.message.orEmpty().ifBlank {
                        "The research web settings could not be saved."
                    },
                )
            }
        }
    }

    fun refreshAgentGoals() {
        refreshAgentSnapshot()
    }

    fun startResearchMonitor() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPreparingResearchMonitorReport = true, researchMonitorError = null) }
            runCatching { withContext(Dispatchers.IO) { researchMonitor.start() } }
                .onSuccess { status ->
                    _uiState.update {
                        it.copy(
                            researchMonitorStatus = status,
                            isPreparingResearchMonitorReport = false,
                            researchMonitorError = null,
                        )
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isPreparingResearchMonitorReport = false,
                            researchMonitorError = error.message.orEmpty().ifBlank {
                                "The research monitor could not start."
                            },
                        )
                    }
                }
        }
    }

    fun refreshResearchMonitorStatus() {
        if (!researchMonitorStatusRefreshPending.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                val status = withContext(Dispatchers.IO) { researchMonitor.status() }
                _uiState.update { it.copy(researchMonitorStatus = status) }
            } finally {
                researchMonitorStatusRefreshPending.set(false)
            }
        }
    }

    fun createResearchMonitorSnapshotReport() {
        createResearchMonitorReport(stopAfterReport = false)
    }

    fun stopResearchMonitorAndCreateReport() {
        createResearchMonitorReport(stopAfterReport = true)
    }

    fun toggleDetailedContentCapture(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val status = if (enabled) {
                researchMonitor.enableDetailedContentCapture()
            } else {
                researchMonitor.disableDetailedContentCapture()
            }
            _uiState.update { it.copy(researchMonitorStatus = status) }
        }
    }

    fun createOverseerRuntimePacket() {
        viewModelScope.launch {
            _uiState.update { it.copy(isPreparingRuntimePacket = true, researchMonitorError = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val exporter = RuntimePacketExporter(getApplication())
                    exporter.export()
                }
            }.onSuccess { result ->
                _uiState.update { 
                    it.copy(
                        isPreparingRuntimePacket = false, 
                        runtimePacketReady = result,
                        researchMonitorError = null
                    )
                }
            }.onFailure { error ->
                _uiState.update { 
                    it.copy(
                        isPreparingRuntimePacket = false, 
                        researchMonitorError = "Failed to create runtime packet: ${error.message}"
                    ) 
                }
            }
        }
    }

    fun onRuntimePacketConsumed() {
        _uiState.update { it.copy(runtimePacketReady = null) }
    }

    private fun createResearchMonitorReport(stopAfterReport: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isPreparingResearchMonitorReport = true, researchMonitorError = null) }
            val reportData = withContext(Dispatchers.IO) {
                runCatching { researchMonitor.createReport(stopAfterReport) }.getOrNull()
            }
            val latestStatus = researchMonitor.status()
            
            if (reportData == null) {
                _uiState.update {
                    it.copy(
                        researchMonitorStatus = latestStatus,
                        isPreparingResearchMonitorReport = false,
                        researchMonitorError = "The research monitor report could not be created."
                    )
                }
                return@launch
            }

            val (reportFile, _) = reportData
            val reportKind = if (stopAfterReport) ReportKind.FINAL else ReportKind.SNAPSHOT
            
            val metadata = withContext(Dispatchers.IO) {
                publicExportManager.prepareExport(
                    sessionId = latestStatus.sessionId ?: "unknown",
                    reportFile = reportFile,
                    reportKind = reportKind,
                    sha256 = latestStatus.lastReportSha256 ?: "unknown",
                    bytes = latestStatus.lastReportBytes
                )
            }

            performPublicExport(metadata)
        }
    }

    private fun performPublicExport(metadata: PublicExportMetadata) {
        viewModelScope.launch {
            _uiState.update { it.copy(isPreparingResearchMonitorReport = true) }
            
            // Handle legacy permission on API 26-28
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P && 
                androidx.core.content.ContextCompat.checkSelfPermission(
                    getApplication(), 
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                
                _uiState.update { 
                    it.copy(
                        isPreparingResearchMonitorReport = false,
                        lastExportResult = ExportResult(
                            status = ExportStatus.PERMISSION_REQUIRED,
                            reportKind = metadata.reportKind,
                            retryable = true
                        ),
                        pendingLegacyPermissionOperationId = metadata.operationId
                    )
                }
                _exportEvents.emit(ExportEvent.RequestLegacyDownloadsPermission(metadata.operationId))
                return@launch
            }

            val result = withContext(Dispatchers.IO) {
                publicExportManager.executeExport(metadata)
            }
            
            _uiState.update {
                it.copy(
                    isPreparingResearchMonitorReport = false,
                    lastExportResult = result,
                    researchMonitorStatus = researchMonitor.status()
                )
            }
        }
    }

    fun retryPublicExport() {
        val lastResult = _uiState.value.lastExportResult ?: return
        if (!lastResult.retryable) return
        
        viewModelScope.launch {
            val metadata = withContext(Dispatchers.IO) {
                publicExportManager.loadLastExportMetadata()
            } ?: return@launch
            
            performPublicExport(metadata)
        }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        diagnostics.info("notification_permission_result", mapOf("granted" to granted))
    }

    fun onLegacyDownloadsPermissionResult(granted: Boolean) {
        val operationId = _uiState.value.pendingLegacyPermissionOperationId ?: return
        _uiState.update { it.copy(pendingLegacyPermissionOperationId = null) }
        
        if (granted) {
            viewModelScope.launch {
                val metadata = withContext(Dispatchers.IO) {
                    publicExportManager.loadLastExportMetadata()
                }
                if (metadata != null && metadata.operationId == operationId) {
                    performPublicExport(metadata)
                }
            }
        } else {
            _uiState.update {
                it.copy(
                    lastExportResult = it.lastExportResult?.copy(
                        status = ExportStatus.FAILED_RETRYABLE,
                        failureCategory = "PERMISSION_DENIED",
                        failureMessage = "Storage permission is required to save the report to Downloads."
                    )
                )
            }
        }
    }

    fun openExportedReport(context: Context) {
        val result = _uiState.value.lastExportResult ?: return
        val uriString = result.contentUri ?: return
        
        val uri = if (uriString.startsWith("content://")) {
            uriString.toUri()
        } else {
            // Legacy path, convert to content URI via FileProvider
            val file = File(uriString)
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/markdown")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        runCatching {
            context.startActivity(intent)
        }.onFailure {
            intent.setDataAndType(uri, "text/plain")
            runCatching { context.startActivity(intent) }
        }
    }

    fun shareExportedReport(context: Context) {
        val result = _uiState.value.lastExportResult ?: return
        val uriString = result.contentUri ?: return
        
        val uri = if (uriString.startsWith("content://")) {
            uriString.toUri()
        } else {
            // Legacy path, convert to content URI via FileProvider
            val file = File(uriString)
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        val chooser = Intent.createChooser(intent, "Share Research Report")
        runCatching { context.startActivity(chooser) }
    }

    private fun ensureResearchMonitorActive(): ResearchMonitorStatus {
        val current = _uiState.value.researchMonitorStatus
        val active = runCatching {
            if (researchMonitor.isActive()) current.copy(active = true) else researchMonitor.start()
        }.getOrElse { error ->
            _uiState.update {
                it.copy(
                    researchMonitorError = error.message.orEmpty().ifBlank {
                        "The passive monitor could not start; the research request will still continue."
                    },
                )
            }
            current
        }
        _uiState.update {
            it.copy(
                researchMonitorStatus = active,
                researchMonitorError = if (active.active) null else it.researchMonitorError,
            )
        }
        return active
    }

    fun selectAgentGoal(goalId: String) {
        viewModelScope.launch {
            val snapshot = agentInteractor.selectGoal(goalId)
            val counts = autonomousToolRuntime.loadToolCounts()
            apply(snapshot, counts.activeRecipeCount, counts.workspaceFileCount)
            _uiState.update { it.copy(section = AppSection.WORK) }
        }
    }

    fun refineAgentGoal(goalId: String, refinement: String) {
        if (refinement.isBlank()) return
        viewModelScope.launch {
            agentInteractor.updateGoal(goalId) { current ->
                current.copy(
                    refinements = current.refinements + refinement,
                    status = current.status,
                    updatedAt = System.currentTimeMillis()
                )
            }
            refreshAgentSnapshot()
            val snapshot = withContext(Dispatchers.IO) { agentInteractor.loadSnapshot() }
            val goal = snapshot.goals.firstOrNull { it.id == goalId }
            if (goal?.status in setOf(
                    AgentGoalStatus.PAUSED,
                    AgentGoalStatus.FAILED,
                    AgentGoalStatus.WAITING_FOR_CREDENTIAL,
                    AgentGoalStatus.REQUIRES_USER_CLARIFICATION,
                )
            ) {
                resumeAgentGoal(goalId)
            }
        }
    }

    fun pauseAgentGoal(goalId: String) {
        val goal = agentSnapshot.goals.firstOrNull { it.id == goalId } ?: return
        if (!goal.status.isActivePhase()) return
        viewModelScope.launch {
            agentInteractor.updateGoal(goalId) { current -> AgentLifecycleReducer.pause(current) }
            agentInteractor.cancel(goalId)
            diagnostics.info("agent_goal_paused", mapOf("goal_id" to goalId))
            refreshAgentSnapshot()
        }
    }

    fun resumeAgentGoal(goalId: String) {
        viewModelScope.launch {
            // Re-query current state to ensure we don't act on stale snapshot
            val snapshot = withContext(Dispatchers.IO) { agentInteractor.loadSnapshot() }
            val goal = snapshot.goals.firstOrNull { it.id == goalId } ?: return@launch
            val now = System.currentTimeMillis()
            
            val isWorkRunning = withContext(Dispatchers.IO) { 
                agentInteractor.isWorkRunning(goalId, goal.executionGeneration) 
            }
            val hasActiveLease = goal.executionLease?.let { !AgentLeasePolicy.isStale(it, now) } ?: false
            
            val isStranded = !hasActiveLease && !isWorkRunning && (
                goal.status == AgentGoalStatus.PLANNING ||
                    goal.status == AgentGoalStatus.QUEUED ||
                    goal.status == AgentGoalStatus.RUNNING ||
                    goal.status == AgentGoalStatus.VERIFYING
                )
            
            val canResume = goal.status in setOf(
                AgentGoalStatus.PAUSED,
                AgentGoalStatus.FAILED,
                AgentGoalStatus.WAITING_FOR_CREDENTIAL,
                AgentGoalStatus.WAITING_FOR_NETWORK,
                AgentGoalStatus.BLOCKED,
                AgentGoalStatus.REQUIRES_USER_CLARIFICATION,
            ) || isStranded

            if (!canResume) {
                if (goal.status == AgentGoalStatus.CANCELLED) {
                    restartAgentGoal(goalId)
                } else {
                    diagnostics.info("agent_goal_resume_skipped", mapOf("goal_id" to goalId, "reason" to "consensus_active", "status" to goal.status.name, "is_stranded" to isStranded))
                }
                return@launch
            }
            
            val updatedGoal = agentInteractor.updateGoal(goalId) { current -> 
                AgentLifecycleReducer.resume(current, reason = ResumeReason.USER_RESUME) 
            }.goals.firstOrNull { it.id == goalId }
            
            agentInteractor.enqueue(goalId, replace = true, executionGeneration = updatedGoal?.executionGeneration ?: 1)
            diagnostics.info("agent_goal_resumed", mapOf("goal_id" to goalId, "is_stranded" to isStranded))
            refreshAgentSnapshot()
        }
    }

    fun restartAgentGoal(goalId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(agentMessage = "Restarting mission...") }
            val result = agentInteractor.restartAgentGoal(goalId)
            if (result is com.david.openassistant.agent.SchedulingResult.EnqueueFailed) {
                _uiState.update { it.copy(agentError = "Failed to restart mission: ${result.error.message}") }
            } else {
                _uiState.update { it.copy(agentMessage = "Mission restarted.", agentError = null) }
            }
            refreshAgentSnapshot()
        }
    }

    fun cancelAgentGoal(goalId: String) {
        val goal = agentSnapshot.goals.firstOrNull { it.id == goalId } ?: return
        if (goal.status.isFinalTerminalStatus() || goal.status == AgentGoalStatus.FINALIZING) return
        if (!goalsBeingFinalized.add(goalId)) return

        ensureResearchMonitorActive()
        researchMonitor.record(
            category = "user_action",
            event = "mission_stop_requested",
            level = "WARN",
            correlationId = goalId,
            fields = mapOf(
                "goal_id" to goalId,
                "goal_title" to goal.title,
                "goal_status_before_stop" to goal.status.name,
                "reason" to "The user requested to stop and finalize the mission.",
            ),
        )
        viewModelScope.launch {
            try {
                _uiState.update {
                    it.copy(isPreparingResearchMonitorReport = true, researchMonitorError = null)
                }

                // Phase 1: Transition to FINALIZING and stop the scheduler/workers
                runCatching {
                    agentInteractor.finalize(goalId)
                }.onFailure { error ->
                    diagnostics.error("agent_goal_finalize_transition_failed", error, mapOf("goal_id" to goalId))
                }
                refreshAgentSnapshot()

                // Phase 2: Perform the actual cancellation mutation
                val cancelledResult = runCatching {
                    agentInteractor.updateGoal(goalId) { current ->
                        AgentLifecycleReducer.cancel(current, reason = "Goal stopped and finalized by the user.")
                    }
                    agentInteractor.loadSnapshot()
                }

                cancelledResult.getOrNull()?.let { snapshot ->
                    val counts = autonomousToolRuntime.loadToolCounts()
                    apply(snapshot, counts.activeRecipeCount, counts.workspaceFileCount)
                }

                // Phase 3: Create the final monitor report
                val reportResult = runCatching {
                    withContext(Dispatchers.IO) {
                        researchMonitor.createReport(stopAfterReport = true)
                    }
                }
                val reportStatus = reportResult.getOrNull()?.second ?: researchMonitor.status()

                val failure = reportResult.exceptionOrNull() ?: cancelledResult.exceptionOrNull()
                _uiState.update { state ->
                    state.copy(
                        researchMonitorStatus = reportStatus,
                        isPreparingResearchMonitorReport = false,
                        researchMonitorError = failure?.message?.takeIf { it.isNotBlank() }
                    )
                }

                if (reportResult.isSuccess) {
                    diagnostics.info("agent_goal_finalized_and_reported", mapOf("goal_id" to goalId))
                }
            } finally {
                goalsBeingFinalized.remove(goalId)
                _uiState.update { it.copy(isPreparingResearchMonitorReport = false) }
            }
        }
    }

    fun deleteAgentGoal(goalId: String) {
        viewModelScope.launch {
            runCatching {
                agentInteractor.cancel(goalId)
                val snapshot = agentInteractor.deleteGoal(goalId)
                diagnostics.warning("agent_goal_deleted", mapOf("goal_id" to goalId))
                val counts = autonomousToolRuntime.loadToolCounts()
                apply(snapshot, counts.activeRecipeCount, counts.workspaceFileCount)
            }.onFailure { error ->
                _uiState.update { it.copy(agentError = "Failed to delete mission: ${error.message}") }
            }
        }
    }

    fun exportResearchReport(goalId: String) {
        val goal = agentSnapshot.goals.firstOrNull { it.id == goalId } ?: return
        viewModelScope.launch {
            val lastExport = withContext(Dispatchers.IO) { publicExportManager.loadLastExportMetadata() }
            if (lastExport != null && lastExport.exportStatus == ExportStatus.EXPORTED) {
                val exists = withContext(Dispatchers.IO) {
                    publicExportManager.validateExportedItem(lastExport)
                }
                if (exists) {
                    _uiState.update { it.copy(lastExportResult = lastExport.toExportResult()) }
                }
            } else {
                _uiState.update { it.copy(lastExportResult = lastExport?.toExportResult()) }
            }

            runCatching {
                val report = buildString {
                    appendLine("# Research Mission Report: ${goal.title}")
                    appendLine()
                    appendLine("## Metadata")
                    appendLine("- **Status**: ${goal.status}")
                    appendLine("- **Objective**: ${goal.objective}")
                    appendLine("- **Cost**: $${"%.3f".format(goal.totalCostUsd)}")
                    appendLine("- **Progress**: ${(goal.progressFraction * 100).toInt()}%")
                    appendLine("- **Created At**: ${DateFormat.getDateTimeInstance().format(Date(goal.createdAt))}")
                    appendLine()
                    appendLine("## Findings")
                    appendLine(goal.result ?: "No final result generated yet.")
                    appendLine()
                    appendLine("## Claim Graph")
                    goal.claims.forEach { claim ->
                        appendLine("- [${claim.type.wireName.uppercase()}] ${claim.text} (Confidence: ${(claim.confidence * 100).toInt()}%)")
                    }
                    appendLine()
                    appendLine("## Evidence Trail")
                    goal.evidence.forEach { evidence ->
                        appendLine("### ${evidence.title}")
                        appendLine(evidence.summary)
                        if (evidence.sources.isNotEmpty()) {
                            appendLine("Sources:")
                            evidence.sources.forEach { source ->
                                appendLine("- [${source.title}](${source.url})")
                            }
                        }
                        appendLine()
                    }
                }
                val file = File(getApplication<Application>().filesDir, "RESEARCH_REPORT_${goal.id.take(8)}.md")
                val atomicFile = androidx.core.util.AtomicFile(file)
                var stream: java.io.FileOutputStream? = null
                try {
                    stream = atomicFile.startWrite()
                    stream.write(report.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                    atomicFile.finishWrite(stream)
                } catch (e: Exception) {
                    atomicFile.failWrite(stream)
                    throw e
                }
                _uiState.update { it.copy(agentMessage = "Report exported to internal storage: ${file.name}", agentError = null) }
            }.onFailure { error ->
                _uiState.update { it.copy(agentError = "Failed to export report: ${error.message}", agentMessage = null) }
            }
        }
    }

    fun attachImage(uri: Uri) {
        val state = _uiState.value
        if (state.isGenerating || state.isImportingImage) return
        val targetConversationId = state.activeConversationId
        _uiState.update {
            it.copy(
                isImportingImage = true,
                attachmentError = null,
                chatError = null,
            )
        }

        viewModelScope.launch {
            val attachment = try {
                conversationInteractor.importImage(uri)
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        isImportingImage = false,
                        attachmentError = error.message.orEmpty().ifBlank {
                            "The selected image could not be prepared."
                        },
                    )
                }
                return@launch
            }

            val current = _uiState.value
            if (
                current.activeConversationId != targetConversationId ||
                current.isGenerating
            ) {
                conversationInteractor.deleteAttachment(attachment)
                _uiState.update { it.copy(isImportingImage = false) }
                return@launch
            }

            current.pendingImageAttachment?.let { previous ->
                conversationInteractor.deleteAttachment(previous)
            }
            val warning = if (current.selectedModel?.supportsVision == true) {
                null
            } else {
                "Image ready. Choose the Vision profile or an image-capable model before sending."
            }
            _uiState.update {
                it.copy(
                    pendingImageAttachment = attachment,
                    isImportingImage = false,
                    attachmentError = warning,
                )
            }
        }
    }

    fun removePendingImage() {
        discardPendingImage()
    }

    private fun discardPendingImage() {
        val pending = _uiState.value.pendingImageAttachment
        _uiState.update {
            it.copy(
                pendingImageAttachment = null,
                isImportingImage = false,
                attachmentError = null,
            )
        }
        if (pending != null) {
            viewModelScope.launch { conversationInteractor.deleteAttachment(pending) }
        }
    }

    fun newConversation() {
        stopGeneration()
        discardPendingImage()
        val state = _uiState.value
        val conversation = StoredConversation.empty(
            selectedModelId = state.selectedModelId,
            modelProfile = state.selectedModelProfile,
        )
        synchronized(conversationLock) {
            conversationSnapshot = ConversationSnapshot(
                conversations = listOf(conversation) + conversationSnapshot.conversations,
                activeConversationId = conversation.id,
            )
            conversationInteractor.saveSnapshot(conversationSnapshot)
        }
        _uiState.update {
            it.copy(
                messages = emptyList(),
                activeConversationId = conversation.id,
                currentConversationTitle = conversation.title,
                conversations = conversationSnapshot.toSummaries(),
                chatError = null,
                lastToolExecution = null,
                section = AppSection.CHAT,
                isResearchBriefEditRequested = false,
            )
        }
    }

    fun openConversation(conversationId: String) {
        val conversation = synchronized(conversationLock) {
             conversationSnapshot.conversations.firstOrNull { it.id == conversationId } 
        } ?: return
        stopGeneration()
        discardPendingImage()
        val selectedModelId = resolveConversationModelId(
            preferredModelId = conversation.selectedModelId,
            profile = conversation.modelProfile,
        )
        synchronized(conversationLock) {
            conversationSnapshot = conversationSnapshot.copy(
                conversations = conversationSnapshot.conversations.map { stored ->
                    if (stored.id == conversation.id) {
                        stored.copy(selectedModelId = selectedModelId)
                    } else {
                        stored
                    }
                },
                activeConversationId = conversation.id,
            )
            conversationInteractor.saveSnapshot(conversationSnapshot)
        }
        _uiState.update {
            it.copy(
                messages = conversation.messages,
                selectedModelId = selectedModelId,
                selectedModelProfile = conversation.modelProfile,
                activeConversationId = conversation.id,
                currentConversationTitle = conversation.title,
                conversations = conversationSnapshot.toSummaries(),
                chatError = null,
                lastToolExecution = null,
                section = AppSection.CHAT,
                isResearchBriefEditRequested = false,
            )
        }
    }

    fun renameConversation(conversationId: String, rawTitle: String) {
        val title = rawTitle.replace(Regex("\\s+"), " ").trim().take(80)
        if (title.isBlank()) return
        synchronized(conversationLock) {
            conversationSnapshot = conversationSnapshot.copy(
                conversations = conversationSnapshot.conversations.map { conversation ->
                    if (conversation.id == conversationId) {
                        conversation.copy(title = title, updatedAt = System.currentTimeMillis())
                    } else {
                        conversation
                    }
                },
            )
            conversationInteractor.saveSnapshot(conversationSnapshot)
        }
        _uiState.update {
            it.copy(
                currentConversationTitle = if (conversationId == conversationSnapshot.activeConversationId) {
                    title
                } else {
                    it.currentConversationTitle
                },
                conversations = conversationSnapshot.toSummaries(),
            )
        }
    }

    fun deleteConversation(conversationId: String) {
        cancelAgentGoals(
            predicate = { it.conversationId == conversationId },
            reason = "Goal cancelled because its conversation was deleted.",
        )
        val removedConversation = synchronized(conversationLock) {
             conversationSnapshot.conversations.firstOrNull { it.id == conversationId }
        }
        if (conversationId == conversationSnapshot.activeConversationId) {
            stopGeneration()
            discardPendingImage()
        }
        synchronized(conversationLock) {
            val remaining = conversationSnapshot.conversations.filterNot { it.id == conversationId }
            val nextConversations = remaining.ifEmpty {
                listOf(
                    StoredConversation.empty(
                        selectedModelId = _uiState.value.selectedModelId,
                        modelProfile = _uiState.value.selectedModelProfile,
                    ),
                )
            }
            val nextActiveId = if (conversationId == conversationSnapshot.activeConversationId) {
                nextConversations.first().id
            } else {
                conversationSnapshot.activeConversationId
            }
            conversationSnapshot = ConversationSnapshot(nextConversations, nextActiveId)
            val active = conversationSnapshot.activeConversation
            val resolvedModelId = resolveConversationModelId(active.selectedModelId, active.modelProfile)
            conversationSnapshot = conversationSnapshot.copy(
                conversations = conversationSnapshot.conversations.map { stored ->
                    if (stored.id == active.id) stored.copy(selectedModelId = resolvedModelId) else stored
                },
            )
            conversationInteractor.saveSnapshot(conversationSnapshot)
        }
        val active = conversationSnapshot.activeConversation
        removedConversation?.messages
            ?.flatMap { it.attachments }
            ?.takeIf { it.isNotEmpty() }
            ?.let { removedAttachments ->
                viewModelScope.launch {
                    removedAttachments.forEach { conversationInteractor.deleteAttachment(it) }
                }
            }
        _uiState.update {
            it.copy(
                messages = active.messages,
                selectedModelId = active.selectedModelId,
                selectedModelProfile = active.modelProfile,
                activeConversationId = active.id,
                currentConversationTitle = active.title,
                conversations = conversationSnapshot.toSummaries(),
                chatError = null,
                lastToolExecution = null,
                section = AppSection.CONVERSATIONS,
            )
        }
    }


    fun sendMessage(rawText: String) {
        val enteredText = rawText.trim()
        val state = _uiState.value
        if (state.isRestoringLocalState) return

        val safetyResult = com.david.openassistant.agent.SafetyClassifier.classifyRequest(enteredText)
        if (safetyResult == com.david.openassistant.agent.SafetyResult.BLOCKED) {
            _uiState.update { it.copy(chatError = "This request could not be processed due to safety policies.") }
            return
        }

        val pendingImage = state.pendingImageAttachment
        if (enteredText.isNotBlank() || pendingImage != null) {
            val monitorStatus = ensureResearchMonitorActive()
            researchMonitor.record(
                category = "user_action",
                event = "request_submitted",
                correlationId = state.activeConversationId,
                fields = mapOf(
                    "conversation_id" to state.activeConversationId,
                    "request_text" to enteredText,
                    "image_attached" to (pendingImage != null),
                    "image_name" to pendingImage?.displayName,
                    "selected_model" to state.selectedModelId,
                    "selected_profile" to state.selectedModelProfile.name,
                    "monitor_session_id" to monitorStatus.sessionId,
                ),
            )
        }
        val automaticToolModel = state.selectedModel?.takeIf { it.supportsTools }
            ?: state.models
                .asSequence()
                .filter { it.supportsTools }
                .sortedWith(
                    compareByDescending<OpenRouterModel> { it.supportsStructuredOutputs }
                        .thenByDescending { it.contextLength }
                        .thenByDescending { it.isFree },
                )
                .firstOrNull()
        val automationDecision = AutomationRouter.decide(
            request = enteredText,
            hasImage = pendingImage != null,
            modelSupportsTools = automaticToolModel != null,
            policy = autonomyPolicy,
        )

        if (
            (enteredText.isBlank() && pendingImage == null) ||
            state.isGenerating ||
            state.isImportingImage
        ) {
            return
        }

        val modelId = if (automationDecision.route == AutomationRoute.TOOL_ASSISTED_CHAT) {
            automaticToolModel?.id
        } else {
            state.selectedModelId
        }
        if (modelId == null) {
            _uiState.update { it.copy(chatError = "Choose a model before starting the investigation.") }
            return
        }
        if (pendingImage != null && state.selectedModel?.supportsVision != true) {
            _uiState.update {
                it.copy(
                    chatError = "The selected model cannot receive images. Choose the Vision profile or a model with the Vision badge.",
                    attachmentError = "Image not sent.",
                )
            }
            return
        }

        val apiKey = cachedApiKey
        if (apiKey == null) {
            reconnectAndLoadModels()
            return
        }

        val routeToRecord = if (automationDecision.route == AutomationRoute.AUTONOMOUS_GOAL) {
            // If the router suggests AUTONOMOUS_GOAL but we are in the conversational 
            // flow (manual Start Deep Research), we should probably record it as 
            // TOOL_ASSISTED_CHAT or DIRECT_CHAT unless we actually trigger a goal.
            // Currently sendMessage NEVER triggers a goal directly, it only uses tools.
            AutomationRoute.TOOL_ASSISTED_CHAT
        } else {
            automationDecision.route
        }

        diagnostics.info(
            "automation_route_selected",
            mapOf(
                "route" to routeToRecord.name,
                "reason" to automationDecision.reason,
                "request_characters" to enteredText.length,
                "has_image" to (pendingImage != null),
                "selected_model" to modelId,
            ),
        )
        val messageText = enteredText.ifBlank { "Describe this image carefully." }
        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.USER,
            content = messageText,
            attachments = listOfNotNull(pendingImage),
        )
        val requestMessages = state.messages.filterNot { it.isStreaming } + userMessage
        val conversationTitle = if (state.currentConversationTitle == DEFAULT_CONVERSATION_TITLE) {
            createConversationTitle(
                enteredText.ifBlank { pendingImage?.displayName ?: "Image question" },
            )
        } else {
            state.currentConversationTitle
        }
        val summaries = persistConversation(
            conversationId = state.activeConversationId,
            messages = requestMessages,
            selectedModelId = modelId,
            modelProfile = state.selectedModelProfile,
            title = conversationTitle,
        )
        stopRequested.set(false)

        val assistantMessageId = UUID.randomUUID().toString()
        val operationId = UUID.randomUUID().toString()
        val chatOperation = ChatOperation(
            operationId = operationId,
            originConversationId = state.activeConversationId,
            assistantMessageId = assistantMessageId
        )
        currentChatOperation = chatOperation

        _uiState.update {
            it.copy(
                messages = requestMessages,
                currentConversationTitle = conversationTitle,
                conversations = summaries,
                selectedModelId = if (automationDecision.route == AutomationRoute.TOOL_ASSISTED_CHAT) modelId else it.selectedModelId,
                isGenerating = true,
                chatError = null,
                agentError = null,
                agentMessage = null,
                lastToolExecution = null,
                pendingImageAttachment = null,
                attachmentError = null,
            )
        }

        val freeOnly = state.selectedModelProfile == com.david.openassistant.domain.model.ModelProfile.FREE
        val hasTools = com.david.openassistant.agent.AgentToolRegistry.hasOperationalTools(
            runtime = autonomousToolRuntime,
            networkAvailable = autonomousToolRuntime.isNetworkAvailable(),
            credentialsAvailable = com.david.openassistant.agent.AgentOperationalState.areCredentialsAvailable(apiKey),
            publicWebConfigured = researchWebSettings.load().searxngBaseUrl != null,
            isFreeOnly = freeOnly
        )
        
        val effectiveUseTools = hasTools && automationDecision.route != AutomationRoute.DIRECT_CHAT
        
        if (effectiveUseTools) {
            val supportsBoth = state.selectedModel?.supportsTools == true && state.selectedModel?.supportsVision == true
            if (pendingImage != null && !supportsBoth) {
                startStagedMultimodalToolLoop(chatOperation, apiKey, modelId, requestMessages, pendingImage, freeOnly)
            } else {
                startAutomaticToolLoop(chatOperation, apiKey, modelId, requestMessages, freeOnly)
            }
        } else {
            startNormalStream(chatOperation, apiKey, modelId, requestMessages, freeOnly)
        }
    }

    private fun startStagedMultimodalToolLoop(
        chatOperation: ChatOperation,
        apiKey: String,
        modelId: String,
        requestMessages: List<ChatMessage>,
        pendingImage: ChatAttachment,
        freeOnly: Boolean
    ) {
        val job = viewModelScope.launch {
            _uiState.update { it.copy(
                isGenerating = true,
                diagnostics = RequestDiagnostics.running("Multimodal Stage 1: Vision Interpretation", modelId)
            )}
            
            val visionResult = try {
                Result.success(withContext(Dispatchers.IO) {
                    openRouterClient.visionChat(apiKey, modelId, requestMessages)
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            
            if (currentChatOperation?.operationId != chatOperation.operationId) return@launch

            visionResult.onSuccess { description ->
                val enrichedMessages = requestMessages.map { message ->
                    if (message.attachments.contains(pendingImage)) {
                        message.copy(
                            content = "${message.content}\n\n[Image Description: $description]",
                            attachments = emptyList()
                        )
                    } else message
                }
                
                _uiState.update { it.copy(
                    diagnostics = RequestDiagnostics.running("Multimodal Stage 2: Tool-Assisted Reasoning", modelId)
                )}
                
                startAutomaticToolLoop(chatOperation, apiKey, modelId, enrichedMessages, freeOnly)
            }.onFailure { error ->
                failToolFlow(chatOperation, error.message ?: "Vision Stage failed", modelId, System.currentTimeMillis())
            }
        }
        chatOperation.job = job
    }

    fun startResearchBriefing(manualRequest: String? = null) {
        val state = _uiState.value
        if (state.isGenerating || state.isGeneratingBrief) return

        viewModelScope.launch {
            _uiState.update { it.copy(isGeneratingBrief = true, chatError = null) }
            
            // Fix: ensure manualRequest is trimmed and strictly non-blank before use
            val trimmedRequest = manualRequest?.trim()?.takeIf { it.isNotBlank() }

            val effectiveMessages = if (trimmedRequest != null) {
                val userMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = ChatRole.USER,
                    content = trimmedRequest,
                )
                // Persist the manual request message so the briefing interactor can see it
                appendMessageToConversation(state.activeConversationId, userMessage)
                state.messages.filterNot { it.isStreaming } + userMessage
            } else {
                state.messages.filterNot { it.isStreaming }
            }

            if (effectiveMessages.none { it.role == ChatRole.USER && it.content.isNotBlank() }) {
                _uiState.update { it.copy(isGeneratingBrief = false, chatError = "Enter a research goal first.") }
                return@launch
            }

            val result = try {
                // Stall-Proofing: Add a hard timeout to the briefing generation
                Result.success(kotlinx.coroutines.withTimeout(90_000L) {
                    briefingInteractor.generateBrief(
                        conversationId = state.activeConversationId,
                        messages = effectiveMessages,
                        modelId = state.selectedModelId ?: com.david.openassistant.domain.model.AgentModelSelector.AUTO_BETA_ROUTER_MODEL_ID,
                    )
                })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }


            
            _uiState.update { it.copy(isGeneratingBrief = false) }

            result.onSuccess { draft ->
                updateResearchBrief(draft)
                _uiState.update { it.copy(isResearchBriefEditRequested = true) }
            }.onFailure { error ->
                val message = when (error) {
                    is kotlinx.coroutines.TimeoutCancellationException -> "Briefing timed out. The provider may be slow."
                    else -> "Briefing failed: ${error.message}"
                }
                _uiState.update { it.copy(chatError = message) }
            }
        }
    }


    fun updateResearchBrief(draft: ResearchDraft) {
        _uiState.update { it.copy(researchDraft = draft) }
        viewModelScope.launch {
            runCatching { agentInteractor.savePendingDraft(draft) }
                .onSuccess {
                    ensureResearchMonitorActive()
                    researchMonitor.record(
                        category = "mission",
                        event = "research_brief_edited",
                        correlationId = draft.id,
                        fields = mapOf("version" to draft.version),
                    )
                }
                .onFailure { error ->
                    diagnostics.error("research_brief_persistence_failed", error, mapOf("draft_id" to draft.id))
                    _uiState.update {
                        it.copy(chatError = "The research brief could not be saved: ${error.message}")
                    }
                }
        }
    }

    fun cancelResearchBrief() {
        _uiState.update { it.copy(researchDraft = null, isResearchBriefEditRequested = false) }
        viewModelScope.launch {
            runCatching { agentInteractor.savePendingDraft(null) }
                .onFailure { error ->
                    diagnostics.error("research_brief_clear_failed", error)
                    _uiState.update {
                        it.copy(chatError = "The saved research brief could not be cleared: ${error.message}")
                    }
                }
        }
    }

    fun requestBriefEdit() {
        _uiState.update { it.copy(isResearchBriefEditRequested = true) }
    }

    fun startResearchMission(draft: ResearchDraft, recoveryReason: String? = null) {
        val state = _uiState.value
        if (state.isPlanningAgentGoal) return
        ensureResearchMonitorActive()
        _uiState.update { it.copy(isPlanningAgentGoal = true, researchDraft = draft) }

        viewModelScope.launch {
            val result = agentInteractor.startMissionFromBrief(
                draft = draft,
                monitor = researchMonitor,
                hasCredential = cachedApiKey != null,
                keyInfo = state.keyInfo,
                models = state.models,
                selectedModelId = state.selectedModelId,
                routingProfileName = state.selectedModelProfile.name,
                automaticStart = recoveryReason != null,
                recoveryReason = recoveryReason,
            )
            handleMissionStartResult(result)
        }
    }

    private suspend fun handleMissionStartResult(result: com.david.openassistant.domain.MissionStartResult) {
        val state = _uiState.value
        when (result) {
            is com.david.openassistant.domain.MissionStartResult.InvalidMissionData -> {
                _uiState.update {
                    it.copy(
                        isPlanningAgentGoal = false,
                        chatError = "Research mission was not started because durable case data is incomplete: ${result.reason}",
                    )
                }
            }

            is com.david.openassistant.domain.MissionStartResult.CreatedAndScheduled -> {
                val counts = autonomousToolRuntime.loadToolCounts()
                apply(result.snapshot, counts.activeRecipeCount, counts.workspaceFileCount)
                _uiState.update {
                    it.copy(
                        isPlanningAgentGoal = false,
                        section = AppSection.WORK,
                        researchDraft = null,
                        isResearchBriefEditRequested = false,
                        agentError = null,
                        agentMessage = null,
                    )
                }
                val goal = result.snapshot.goals.firstOrNull { it.id == result.goalId }
                val confirmation = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    role = ChatRole.ASSISTANT,
                    content = "### Research mission started\n\n**${goal?.title ?: "Research Mission"}**\n\n[Open Mission](mission://${result.goalId})",
                )
                appendMessageToConversation(goal?.conversationId ?: state.activeConversationId, confirmation)
            }

            is com.david.openassistant.domain.MissionStartResult.ReusedActiveMission -> {
                val counts = autonomousToolRuntime.loadToolCounts()
                apply(result.snapshot, counts.activeRecipeCount, counts.workspaceFileCount)
                _uiState.update {
                    it.copy(
                        isPlanningAgentGoal = false,
                        section = AppSection.WORK,
                        researchDraft = null,
                        isResearchBriefEditRequested = false,
                        agentError = null,
                        agentMessage = null,
                    )
                }
            }

            is com.david.openassistant.domain.MissionStartResult.CreatedWaitingForCredential -> {
                val counts = autonomousToolRuntime.loadToolCounts()
                apply(result.snapshot, counts.activeRecipeCount, counts.workspaceFileCount)
                _uiState.update {
                    it.copy(
                        isPlanningAgentGoal = false,
                        section = AppSection.WORK,
                        researchDraft = null,
                        isResearchBriefEditRequested = false,
                        chatError = "Research mission created and saved. Waiting for a valid OpenRouter credential to start execution.",
                    )
                }
            }

            is com.david.openassistant.domain.MissionStartResult.RecoveredAndScheduled -> {
                val counts = autonomousToolRuntime.loadToolCounts()
                apply(result.snapshot, counts.activeRecipeCount, counts.workspaceFileCount)
                _uiState.update {
                    it.copy(
                        isPlanningAgentGoal = false,
                        section = AppSection.WORK,
                        researchDraft = null,
                        isResearchBriefEditRequested = false,
                        agentError = null,
                        agentMessage = null,
                    )
                }
            }

            is com.david.openassistant.domain.MissionStartResult.SchedulingFailed -> {
                val counts = autonomousToolRuntime.loadToolCounts()
                apply(result.snapshot, counts.activeRecipeCount, counts.workspaceFileCount)
                _uiState.update {
                    it.copy(
                        isPlanningAgentGoal = false,
                        chatError = "Research mission created, but WorkManager scheduling is pending: ${result.exception.message}",
                    )
                }
            }
            
            is com.david.openassistant.domain.MissionStartResult.DraftPersistenceFailed -> {
                _uiState.update {
                    it.copy(
                        isPlanningAgentGoal = false,
                        chatError = "Brief could not be saved: ${result.error.message}",
                    )
                }
            }

            is com.david.openassistant.domain.MissionStartResult.GoalPersistenceFailed -> {
                _uiState.update {
                    it.copy(
                        isPlanningAgentGoal = false,
                        chatError = "Goal persistence failed: ${result.error.message}",
                    )
                }
            }

            is com.david.openassistant.domain.MissionStartResult.SchedulingStatePersistenceFailed -> {
                val counts = autonomousToolRuntime.loadToolCounts()
                apply(result.snapshot, counts.activeRecipeCount, counts.workspaceFileCount)
                _uiState.update {
                    it.copy(
                        isPlanningAgentGoal = false,
                        chatError = "Goal created, but scheduling state write failed: ${result.error.message}",
                    )
                }
            }

            is com.david.openassistant.domain.MissionStartResult.CleanupFailedAfterScheduled -> {
                val counts = autonomousToolRuntime.loadToolCounts()
                apply(result.snapshot, counts.activeRecipeCount, counts.workspaceFileCount)
                _uiState.update {
                    it.copy(
                        isPlanningAgentGoal = false,
                        section = AppSection.WORK,
                        researchDraft = null,
                        isResearchBriefEditRequested = false,
                        chatError = "Mission scheduled successfully, but transient draft cleanup failed: ${result.error.message}",
                    )
                }
            }
        }
    }

    fun addDirectionToMission(goalId: String, direction: String) {
        if (direction.isBlank()) return
        val state = _uiState.value
        val conversationId = state.activeConversationId

        refineAgentGoal(goalId, direction)

        // Also persist user message in chat history for context
        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = ChatRole.USER,
            content = direction,
        )
        viewModelScope.launch {
            appendMessageToConversation(conversationId, userMessage)
            val systemNote = ChatMessage(
                id = UUID.randomUUID().toString(),
                role = ChatRole.ASSISTANT,
                content = "_Added direction to active mission: \"$direction\"_",
            )
            appendMessageToConversation(conversationId, systemNote)
        }
    }

    private fun startNormalStream(
        chatOperation: ChatOperation,
        apiKey: String,
        modelId: String,
        requestMessages: List<ChatMessage>,
        freeOnly: Boolean = false,
    ) {
        val assistantMessage = ChatMessage(
            id = chatOperation.assistantMessageId,
            role = ChatRole.ASSISTANT,
            content = "",
            isStreaming = true,
        )
        resetStreamingBuffer(chatOperation)
        streamDeltaCount.set(0)
        streamCharacterCount.set(0)
        firstStreamDeltaLogged.set(false)
        val startedAt = System.currentTimeMillis()
        diagnostics.info(
            "chat_stream_started",
            mapOf(
                "model_id" to modelId,
                "request_message_count" to requestMessages.size,
                "attachment_count" to requestMessages.sumOf { it.attachments.size },
                "free_only" to freeOnly,
                "op_id" to chatOperation.operationId,
            ),
        )
        _uiState.update {
            it.copy(
                messages = requestMessages + assistantMessage,
                isGenerating = true,
                diagnostics = RequestDiagnostics.running("Completion", modelId),
            )
        }

        chatOperation.call = openRouterClient.streamChat(
            apiKey = apiKey,
            modelId = modelId,
            messages = requestMessages,
            freeOnly = freeOnly,
            listener = createStreamListener(chatOperation, startedAt, modelId),
        )
    }

    private fun startAutomaticToolLoop(
        chatOperation: ChatOperation,
        apiKey: String,
        modelId: String,
        requestMessages: List<ChatMessage>,
        freeOnly: Boolean = false,
    ) {
        val assistantMessage = ChatMessage(
            id = chatOperation.assistantMessageId,
            role = ChatRole.ASSISTANT,
            content = "",
            isStreaming = true,
        )
        resetStreamingBuffer(chatOperation)
        streamDeltaCount.set(0)
        streamCharacterCount.set(0)
        firstStreamDeltaLogged.set(false)
        val startedAt = System.currentTimeMillis()
        diagnostics.info(
            "automatic_tool_loop_started",
            mapOf(
                "model_id" to modelId,
                "request_message_count" to requestMessages.size,
                "free_only" to freeOnly,
                "op_id" to chatOperation.operationId,
            ),
        )
        _uiState.update {
            it.copy(
                messages = requestMessages + assistantMessage,
                isGenerating = true,
                diagnostics = RequestDiagnostics.running("Autonomous tools", modelId),
            )
        }

        val job = viewModelScope.launch {
            try {
                val loopResult = withContext(Dispatchers.IO) {
                    openRouterClient.runAutomaticToolLoop(
                        apiKey = apiKey,
                        modelId = modelId,
                        messages = requestMessages,
                        freeOnly = freeOnly,
                        toolDefinitions = {
                            com.david.openassistant.agent.AgentToolRegistry.attachedToolsPayload(
                                runtime = autonomousToolRuntime,
                                networkAvailable = autonomousToolRuntime.isNetworkAvailable(),
                                credentialsAvailable = com.david.openassistant.agent.AgentOperationalState.areCredentialsAvailable(apiKey),
                                publicWebConfigured = researchWebSettings.load().searxngBaseUrl != null,
                                isFreeOnly = freeOnly
                            )
                        },
                        executeTool = { call ->
                            withContext(Dispatchers.Main) {
                                _uiState.update {
                                    it.copy(
                                        lastToolExecution = com.david.openassistant.ui.ToolExecutionEvidence(
                                            displayName = call.name,
                                            summary = "Executing ${call.name}...",
                                            executedAt = System.currentTimeMillis(),
                                        ),
                                    )
                                }
                            }
                            val result = autonomousToolRuntime.execute(call, apiKey, modelId)
                            withContext(Dispatchers.Main) {
                                _uiState.update {
                                    it.copy(
                                        lastToolExecution = com.david.openassistant.ui.ToolExecutionEvidence(
                                            displayName = call.name,
                                            summary = result.displaySummary,
                                            executedAt = System.currentTimeMillis(),
                                        ),
                                    )
                                }
                            }
                            result
                        },
                        onCallStarted = { call -> chatOperation.call = call },
                        shouldStop = { stopRequested.get() || currentChatOperation?.operationId != chatOperation.operationId },
                    )
                }
                
                if (currentChatOperation?.operationId != chatOperation.operationId) return@launch
                val duration = System.currentTimeMillis() - startedAt
                diagnostics.info(
                    "automatic_tool_loop_completed",
                    mapOf(
                        "duration_ms" to duration,
                        "model_id" to modelId,
                        "total_tokens" to (loopResult.summary.totalTokens ?: 0),
                        "successful_tool_count" to loopResult.executions.count { it.succeeded },
                        "tool_request_count" to loopResult.executions.size,
                        "op_id" to chatOperation.operationId,
                    ),
                )
                _uiState.update {
                    it.copy(
                        messages = it.messages.map { msg ->
                            if (msg.id == chatOperation.assistantMessageId) {
                                msg.copy(content = loopResult.content, isStreaming = false)
                            } else {
                                msg
                            }
                        },
                        isGenerating = false,
                        diagnostics = RequestDiagnostics.succeeded(
                            operation = "Autonomous tools",
                            startedAt = startedAt,
                            httpStatus = 200,
                            note = loopResult.summary.toString(),
                        ),
                    )
                }
                persistConversation(
                    conversationId = chatOperation.originConversationId,
                    messages = _uiState.value.messages,
                    selectedModelId = modelId,
                    modelProfile = _uiState.value.selectedModelProfile,
                    title = _uiState.value.currentConversationTitle,
                )
                if (currentChatOperation?.operationId == chatOperation.operationId) {
                    currentChatOperation = null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (error: Throwable) {
                if (currentChatOperation?.operationId != chatOperation.operationId) return@launch
                failToolFlow(chatOperation, error.message ?: "Tool loop failed", modelId, startedAt)
            }
        }
        chatOperation.job = job
    }

    private fun failToolFlow(chatOperation: ChatOperation, message: String, modelId: String, startedAt: Long) {
        val openRouterError = OpenRouterException(null, message)
        diagnostics.error("tool_flow_failed", openRouterError, mapOf("op_id" to chatOperation.operationId))
        _uiState.update {
            it.copy(
                messages = it.messages.map { msg ->
                    if (msg.id == chatOperation.assistantMessageId) msg.copy(isStreaming = false) else msg
                },
                isGenerating = false,
                chatError = openRouterError.userMessage,
                diagnostics = RequestDiagnostics.failed(
                    operation = "Autonomous tools",
                    startedAt = startedAt,
                    httpStatus = openRouterError.statusCode,
                    message = openRouterError.userMessage,
                ),
            )
        }
        if (currentChatOperation?.operationId == chatOperation.operationId) {
            currentChatOperation = null
        }
    }

    fun stopGeneration() {
        stopRequested.set(true)
        val op = currentChatOperation ?: return
        op.call?.cancel()
        op.job?.cancel()
        
        val finalContent = op.safetyFilter.finish().let { lastLine ->
            if (lastLine.isNotEmpty()) {
                streamingDeltaAccumulator.append(lastLine)
            }
            streamingDeltaAccumulator.content()
        }

        // CAPTURE SETTLEMENT SYNCHRONOUSLY
        val snapshot = _uiState.value
        val settledMessages = snapshot.messages.map { 
            if (it.id == op.assistantMessageId) {
                it.copy(content = finalContent, isStreaming = false)
            } else it 
        }
        
        val settlement = ChatSettlement(
            operationId = op.operationId,
            originConversationId = op.originConversationId,
            assistantMessageId = op.assistantMessageId,
            settledMessages = settledMessages,
            originSelectedModelId = snapshot.selectedModelId,
            originModelProfile = snapshot.selectedModelProfile,
            originTitle = snapshot.currentConversationTitle,
            terminalReason = "STOP"
        )

        _uiState.update { state ->
            state.copy(
                isGenerating = false,
                messages = settledMessages
            )
        }
        
        viewModelScope.launch(Dispatchers.IO) {
            persistSettlement(settlement)
        }
        
        currentChatOperation = null
    }

    private fun persistSettlement(settlement: ChatSettlement) {
        persistConversation(
            conversationId = settlement.originConversationId,
            messages = settlement.settledMessages,
            selectedModelId = settlement.originSelectedModelId,
            modelProfile = settlement.originModelProfile,
            title = settlement.originTitle
        )
    }

    private fun createStreamListener(
        chatOperation: ChatOperation,
        startedAt: Long,
        modelId: String,
    ) = object : ChatStreamListener {
        override fun onDelta(text: String) {
            if (!stopRequested.get() && currentChatOperation?.operationId == chatOperation.operationId) {
                enqueueStreamDelta(chatOperation, text)
            }
        }

        override fun onComplete(summary: StreamSummary) {
            if (currentChatOperation?.operationId != chatOperation.operationId) return
            
            val finalContent = chatOperation.safetyFilter.finish().let { lastLine ->
                if (lastLine.isNotEmpty()) {
                    streamingDeltaAccumulator.append(lastLine)
                }
                streamingDeltaAccumulator.content()
            }

            val duration = System.currentTimeMillis() - startedAt
            diagnostics.info(
                "chat_stream_completed",
                mapOf(
                    "duration_ms" to duration,
                    "model_id" to modelId,
                    "total_tokens" to (summary.totalTokens ?: 0),
                    "op_id" to chatOperation.operationId,
                ),
            )

            // CAPTURE SETTLEMENT SYNCHRONOUSLY
            val snapshot = _uiState.value
            val settledMessages = snapshot.messages.map { msg ->
                if (msg.id == chatOperation.assistantMessageId) {
                    msg.copy(content = finalContent, isStreaming = false)
                } else {
                    msg
                }
            }

            val settlement = ChatSettlement(
                operationId = chatOperation.operationId,
                originConversationId = chatOperation.originConversationId,
                assistantMessageId = chatOperation.assistantMessageId,
                settledMessages = settledMessages,
                originSelectedModelId = modelId,
                originModelProfile = snapshot.selectedModelProfile,
                originTitle = snapshot.currentConversationTitle,
                terminalReason = "COMPLETE"
            )

            _uiState.update {
                it.copy(
                    messages = settledMessages,
                    isGenerating = false,
                    diagnostics = RequestDiagnostics.succeeded(
                        operation = "Completion",
                        startedAt = startedAt,
                        httpStatus = 200,
                        note = summary.toString(),
                    ),
                )
            }
            viewModelScope.launch(Dispatchers.IO) {
                persistSettlement(settlement)
            }
            if (currentChatOperation?.operationId == chatOperation.operationId) {
                currentChatOperation = null
            }
        }

        override fun onError(error: OpenRouterException) {
            if (currentChatOperation?.operationId != chatOperation.operationId) return
            diagnostics.error("chat_stream_failed", error, mapOf("op_id" to chatOperation.operationId))
            
            val finalContent = chatOperation.safetyFilter.finish().let { lastLine ->
                if (lastLine.isNotEmpty()) {
                    streamingDeltaAccumulator.append(lastLine)
                }
                streamingDeltaAccumulator.content()
            }

            // CAPTURE SETTLEMENT SYNCHRONOUSLY
            val snapshot = _uiState.value
            val settledMessages = snapshot.messages.map { msg ->
                if (msg.id == chatOperation.assistantMessageId) {
                    msg.copy(content = finalContent, isStreaming = false)
                } else {
                    msg
                }
            }

            val settlement = ChatSettlement(
                operationId = chatOperation.operationId,
                originConversationId = chatOperation.originConversationId,
                assistantMessageId = chatOperation.assistantMessageId,
                settledMessages = settledMessages,
                originSelectedModelId = modelId,
                originModelProfile = snapshot.selectedModelProfile,
                originTitle = snapshot.currentConversationTitle,
                terminalReason = "ERROR"
            )

            _uiState.update {
                it.copy(
                    messages = settledMessages,
                    isGenerating = false,
                    chatError = error.userMessage,
                    diagnostics = RequestDiagnostics.failed(
                        operation = "Completion",
                        startedAt = startedAt,
                        httpStatus = error.statusCode,
                        message = error.userMessage,
                    ),
                )
            }
            viewModelScope.launch(Dispatchers.IO) {
                persistSettlement(settlement)
            }
            if (currentChatOperation?.operationId == chatOperation.operationId) {
                currentChatOperation = null
            }
        }
    }

    private fun enqueueStreamDelta(chatOperation: ChatOperation, delta: String) {
        val filtered = chatOperation.safetyFilter.filter(delta)
        if (filtered.isEmpty()) return
        
        streamingDeltaAccumulator.append(filtered)
        streamDeltaCount.incrementAndGet()
        streamCharacterCount.addAndGet(filtered.length)
        if (!firstStreamDeltaLogged.getAndSet(true)) {
            diagnostics.debug("first_chat_delta_received")
        }
        if (streamFlushScheduled.compareAndSet(false, true)) {
            streamFlushJob = viewModelScope.launch {
                delay(STREAM_UI_FLUSH_INTERVAL_MS)
                if (currentChatOperation?.operationId == chatOperation.operationId) {
                    flushStreamingBuffer(chatOperation.assistantMessageId)
                }
            }
        }
    }

    private fun resetStreamingBuffer(chatOperation: ChatOperation) {
        streamFlushJob?.cancel()
        streamFlushJob = null
        streamFlushScheduled.set(false)
        streamingDeltaAccumulator.clear()
        chatOperation.safetyFilter.finish()
    }

    private fun flushStreamingBuffer(messageId: String) {
        val content = streamingDeltaAccumulator.content()
        _uiState.update { state ->
            state.copy(
                messages = state.messages.map { msg ->
                    if (msg.id == messageId) msg.copy(content = content) else msg
                },
            )
        }
        streamFlushScheduled.set(false)
    }

    private fun persistConversation(
        conversationId: String,
        messages: List<ChatMessage>,
        selectedModelId: String?,
        modelProfile: ModelProfile,
        title: String,
        touchUpdatedAt: Boolean = true,
    ): List<ConversationSummary> = synchronized(conversationLock) {
        val updatedConversations = conversationSnapshot.conversations.map { conversation ->
            if (conversation.id == conversationId) {
                conversation.copy(
                    messages = messages,
                    selectedModelId = selectedModelId,
                    modelProfile = modelProfile,
                    title = title,
                    updatedAt = if (touchUpdatedAt) System.currentTimeMillis() else conversation.updatedAt,
                )
            } else {
                conversation
            }
        }
        conversationSnapshot = conversationSnapshot.copy(conversations = updatedConversations)
        conversationInteractor.saveSnapshot(conversationSnapshot)
        return conversationSnapshot.toSummaries()
    }

    private suspend fun appendMessageToConversation(conversationId: String, message: ChatMessage) {
        withContext(Dispatchers.IO) {
            val snapshot = conversationInteractor.loadSnapshot()
            val updated = snapshot.conversations.map { conversation ->
                if (conversation.id == conversationId) {
                    conversation.copy(
                        messages = conversation.messages + message,
                        updatedAt = System.currentTimeMillis(),
                    )
                } else {
                    conversation
                }
            }
            val nextSnapshot = snapshot.copy(conversations = updated)
            conversationInteractor.saveSnapshot(nextSnapshot)
            
            if (conversationId == conversationSnapshot.activeConversationId) {
                withContext(Dispatchers.Main) {
                    conversationSnapshot = nextSnapshot
                    _uiState.update {
                        it.copy(
                            messages = nextSnapshot.activeConversation.messages,
                            conversations = nextSnapshot.toSummaries(),
                        )
                    }
                }
            }
        }
    }

    private fun resolveConversationModelId(preferredModelId: String?, profile: ModelProfile): String? {
        return ModelProfileSelector.choose(profile, _uiState.value.models, preferredModelId)?.id
    }

    private fun chooseInitialModel(models: List<OpenRouterModel>): OpenRouterModel? {
        return models.firstOrNull { it.id == "google/gemma-7b-it:free" }
            ?: models.firstOrNull { it.isFree }
            ?: models.firstOrNull()
    }

    private fun refreshAgentSnapshot() {
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = agentInteractor.loadSnapshot()
            val counts = autonomousToolRuntime.loadToolCounts()
            withContext(Dispatchers.Main) {
                apply(snapshot, counts.activeRecipeCount, counts.workspaceFileCount)
            }
        }
    }

    fun clearConversation() {
        newConversation()
    }

    fun deleteCredential() {
        viewModelScope.launch {
            authInteractor.clearCredential()
            cachedApiKey = null
            _uiState.update { it.copy(hasStoredKey = false, keyInfo = null, models = emptyList()) }
        }
    }
    
    private fun scheduleActiveAgentGoals() {
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = agentInteractor.loadSnapshot()
            snapshot.goals
                .filter { goal -> goal.status.isActivePhase() }
                .forEach { goal -> agentInteractor.enqueue(goal.id, executionGeneration = goal.executionGeneration) }
        }
    }

    private fun cancelAgentGoals(predicate: (AgentGoal) -> Boolean, reason: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = agentInteractor.loadSnapshot()
            snapshot.goals.filter(predicate).forEach { goal ->
                agentInteractor.updateGoal(goal.id) { current ->
                    AgentLifecycleReducer.cancel(current, reason = reason)
                }
                agentInteractor.cancel(goal.id, executionGeneration = goal.executionGeneration)
            }
            refreshAgentSnapshot()
        }
    }

    private fun replayInterruptedResearchStartIfNeeded() {
        val draft = interruptedDraftPendingReplay ?: return
        interruptedDraftPendingReplay = null
        startResearchMission(draft, recoveryReason = startupRecoveryReason ?: "INTERRUPTED_START")
    }

    private fun Throwable.asOpenRouterException(): OpenRouterException {
        return when (this) {
            is OpenRouterException -> this
            is java.io.IOException -> OpenRouterException(null, "Network request failed: ${this.message}")
            else -> OpenRouterException(null, this.message ?: "An unexpected error occurred.")
        }
    }

    private fun Exception.asOpenRouterException(): OpenRouterException {
        return when (this) {
            is OpenRouterException -> this
            is java.io.IOException -> OpenRouterException(null, "Network request failed: ${this.message}")
            else -> OpenRouterException(null, this.message ?: "An unexpected error occurred.")
        }
    }
}

private fun ConversationSnapshot.toSummaries(): List<ConversationSummary> =
    conversations.map { conversation ->
        ConversationSummary(
            id = conversation.id,
            title = conversation.title,
            updatedAt = conversation.updatedAt,
            messageCount = conversation.messages.size,
            selectedModelId = conversation.selectedModelId,
            modelProfile = conversation.modelProfile,
        )
    }

