package com.david.openassistant.agent

import com.david.openassistant.domain.model.AgentModelSelector

/**
 * Central invariant guard for routing policies. Ensures that a goal marked as free-only
 * never escalates to a paid model or uses an unauthorized routing stage.
 */
internal object AgentRoutingPolicy {
    
    private const val FREE_ROUTER_MODEL_ID = "openrouter/free"

    /**
     * Determines the effective routing profile for a goal.
     */
    fun profileForGoal(goal: AgentGoal): RoutingProfile {
        return when {
            goal.freeOnly -> RoutingProfile.FREE_MODELS_ROUTER
            goal.requestedModelProfileName == "AUTO" -> RoutingProfile.AUTO_ROUTER_BETA
            goal.requestedModelProfileName == "MANUAL" -> RoutingProfile.MANUAL
            // Default to AUTO if ambiguous
            else -> RoutingProfile.AUTO_ROUTER_BETA
        }
    }

    /**
     * Rejects or repairs a selected model ID based on the goal's routing policy.
     */
    fun guardModel(goal: AgentGoal, selectedModelId: String): String {
        val profile = profileForGoal(goal)
        val normalized = selectedModelId.lowercase(java.util.Locale.US)
        
        return when (profile) {
            RoutingProfile.FREE_MODELS_ROUTER -> {
                if (normalized == FREE_ROUTER_MODEL_ID || normalized.endsWith(":free")) {
                    selectedModelId
                } else {
                    FREE_ROUTER_MODEL_ID
                }
            }
            RoutingProfile.AUTO_ROUTER_BETA -> {
                // Auto router is always allowed in AUTO mode
                selectedModelId
            }
            RoutingProfile.MANUAL -> {
                // In manual mode, we strictly follow the user's selection
                selectedModelId
            }
        }
    }

    /**
     * Validates an entire OpenRouter payload against a free-only requirement.
     */
    fun guardPayload(freeOnly: Boolean, payload: org.json.JSONObject) {
        if (!freeOnly) return
        
        val primary = payload.optString("model")
        if (primary.isNotBlank()) validateIsFree(primary)
        
        val fallbacks = payload.optJSONArray("models")
        if (fallbacks != null) {
            for (i in 0 until fallbacks.length()) {
                val model = fallbacks.optString(i)
                if (model.isNotBlank()) validateIsFree(model)
            }
        }
    }

    /**
     * Validates an entire OpenRouter payload against the goal's routing policy.
     */
    fun guardPayload(goal: AgentGoal, payload: org.json.JSONObject) {
        guardPayload(goal.freeOnly, payload)
    }

    private fun validateIsFree(modelId: String) {
        val normalized = modelId.lowercase(java.util.Locale.US)
        val isFreeRouter = normalized == FREE_ROUTER_MODEL_ID
        val isFreeModel = normalized.endsWith(":free")
        
        if (!isFreeRouter && !isFreeModel) {
            throw IllegalStateException("FREE_ROUTING_VIOLATION: model '$modelId' is not authorized for this FREE mission. Auto-beta and paid models are strictly prohibited.")
        }
    }

    /**
     * Rejects or repairs a selected recovery action based on the goal's routing policy.
     */
    fun guardRecovery(goal: AgentGoal, action: ProviderRecoveryAction): ProviderRecoveryAction {
        if (goal.freeOnly && action == ProviderRecoveryAction.ESCALATE_TO_PAID) {
            return ProviderRecoveryAction.ROUTE_EXHAUSTED
        }
        return action
    }

    /**
     * Ensures the routing stage is consistent with the free-only policy.
     */
    fun guardStage(goal: AgentGoal, stage: AgentRoutingStage): AgentRoutingStage {
        if (goal.freeOnly && stage != AgentRoutingStage.FREE) {
            return AgentRoutingStage.FREE
        }
        return stage
    }
}
