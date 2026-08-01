package com.david.openassistant.domain.model

import com.david.openassistant.data.openrouter.OpenRouterModel
import com.david.openassistant.agent.AgentCapability
import com.david.openassistant.agent.AgentRoutingStage
import com.david.openassistant.agent.ProviderRecoveryPolicy
import java.util.Locale

data class AgentModelRoute(
    val plannerModelId: String,
    val executionModelId: String,
)

/** Chooses text-capable models for durable planning and execution. */
object AgentModelSelector {
    const val AUTO_BETA_ROUTER_MODEL_ID = "openrouter/auto-beta"
    const val FREE_ROUTER_MODEL_ID = "openrouter/free"
    const val BODY_BUILDER_MODEL_ID = "openrouter/bodybuilder"

    fun choose(
        models: List<OpenRouterModel>,
        selectedModelId: String?,
        freeOnly: Boolean,
        modelCooldowns: Map<String, Long> = emptyMap(),
    ): AgentModelRoute {
        return choose(models, selectedModelId, freeOnly, modelCooldowns, AgentRoutingStage.AUTO_BETA)
    }

    fun choose(
        models: List<OpenRouterModel>,
        selectedModelId: String?,
        freeOnly: Boolean,
        modelCooldowns: Map<String, Long> = emptyMap(),
        routingStage: AgentRoutingStage = AgentRoutingStage.AUTO_BETA,
    ): AgentModelRoute {
        val now = System.currentTimeMillis()
        val textModels = models.filter { model ->
            model.supportsTextChat && (modelCooldowns[model.id] ?: 0L) < now
        }
        
        if (freeOnly || routingStage == AgentRoutingStage.FREE) {
            return AgentModelRoute(
                plannerModelId = FREE_ROUTER_MODEL_ID,
                executionModelId = FREE_ROUTER_MODEL_ID,
            )
        }

        val normalizedSelectedId = when (selectedModelId) {
            "openrouter/auto", "openrouter/auto-lite" -> AUTO_BETA_ROUTER_MODEL_ID
            else -> selectedModelId
        }
        val selected = textModels.firstOrNull { it.id == normalizedSelectedId }
        val execution = selected
            ?.takeIf { it.id == AUTO_BETA_ROUTER_MODEL_ID || !ProviderRecoveryPolicy.isAutoRouter(it.id) }
            ?.takeIf(OpenRouterModel::supportsAgentTools)
            ?: textModels.firstOrNull { it.id == AUTO_BETA_ROUTER_MODEL_ID }
            ?: textModels.firstOrNull()

        val executionModelId = execution?.id ?: AUTO_BETA_ROUTER_MODEL_ID
        val planner = execution
            ?.takeIf(OpenRouterModel::supportsAgentPlanning)
            ?: textModels.firstOrNull { it.id == AUTO_BETA_ROUTER_MODEL_ID }
            ?: execution

        return AgentModelRoute(
            plannerModelId = planner?.id ?: executionModelId,
            executionModelId = executionModelId,
        )
    }

    /** Returns a prioritized list of compatible models for OpenRouter request-level fallback. */
    fun getFallbackModels(
        models: List<OpenRouterModel>,
        primaryModelId: String,
        capability: AgentCapability,
        freeOnly: Boolean,
        modelCooldowns: Map<String, Long> = emptyMap(),
        routingStage: AgentRoutingStage = AgentRoutingStage.AUTO_BETA,
    ): List<String> {
        if (freeOnly || routingStage == AgentRoutingStage.FREE) {
            return listOf(FREE_ROUTER_MODEL_ID)
        }

        // Strict three-system allowlist for automatic fallback.
        // Body Builder is never included in the fallback array.
        val chain = listOf(AUTO_BETA_ROUTER_MODEL_ID, FREE_ROUTER_MODEL_ID)

        return chain.filter { id -> 
            (modelCooldowns[id] ?: 0L) < System.currentTimeMillis() 
        }.distinct()
    }
}
