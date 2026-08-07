package com.david.openassistant.domain

import android.content.Context
import com.david.openassistant.agent.AgentOpenRouterClient
import com.david.openassistant.agent.ProviderActivityStore
import com.david.openassistant.agent.ResearchDraft
import com.david.openassistant.agent.ResearchDraftStatus
import com.david.openassistant.agent.ResolvedResearchRequest
import com.david.openassistant.data.openrouter.ChatMessage
import com.david.openassistant.data.security.ApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BriefingInteractor(context: Context) {
    private val keyStore = ApiKeyStore(context)
    private val activityStore = ProviderActivityStore(context)
    private val client = AgentOpenRouterClient(activityStore = activityStore)

    suspend fun generateBriefAndStart(
        conversationId: String,
        messages: List<ChatMessage>,
        modelId: String,
        agentInteractor: AgentInteractor,
        monitor: com.david.openassistant.data.diagnostics.ResearchMonitor,
        hasCredential: Boolean,
        keyInfo: com.david.openassistant.data.openrouter.OpenRouterKeyInfo?,
        models: List<com.david.openassistant.data.openrouter.OpenRouterModel>,
        selectedModelId: String?,
        routingProfileName: String,
    ): MissionStartResult = withContext(Dispatchers.IO) {
        val apiKey = keyStore.load() ?: throw IllegalStateException("OpenRouter API key required for briefing.")

        val resolved = ResolvedResearchRequest.resolveFromHistory(messages, conversationId)
        require(resolved.resolvedRequest.isNotBlank()) {
            "A research mission requires an exact nonblank user request."
        }

        val history = messages.joinToString("\n") { message ->
            "${message.role.name}: ${message.content}"
        }

        val (draft, _) = client.generateResearchBrief(apiKey, modelId, history)

        val readyDraft = draft.copy(
            conversationId = conversationId,
            originalUserRequest = resolved.resolvedRequest,
            resolvedResearchRequest = resolved,
            sourceMessageIds = messages.map { it.id },
            status = ResearchDraftStatus.READY,
            updatedAt = System.currentTimeMillis(),
        )
        
        agentInteractor.startMissionFromBrief(
            draft = readyDraft,
            monitor = monitor,
            hasCredential = hasCredential,
            keyInfo = keyInfo,
            models = models,
            selectedModelId = selectedModelId,
            routingProfileName = routingProfileName,
            automaticStart = true
        )
    }
}
