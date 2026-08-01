package com.david.openassistant.agent

internal enum class ProviderRouteKind {
    PLANNER,
    EXECUTION,
}

/** Changes only the route that actually failed. */
internal fun AgentGoal.recoverProviderRoute(
    decision: ProviderRecoveryDecision,
    failedRoute: ProviderRouteKind,
): AgentGoal {
    if (
        decision.action !in setOf(
            ProviderRecoveryAction.SWITCH_TO_FREE,
            ProviderRecoveryAction.ESCALATE_TO_PAID,
            ProviderRecoveryAction.ROUTE_EXHAUSTED,
        )
    ) {
        return this
    }
    val nextStage = when (decision.action) {
        ProviderRecoveryAction.SWITCH_TO_FREE -> AgentRoutingStage.FREE
        ProviderRecoveryAction.ESCALATE_TO_PAID -> AgentRoutingStage.AUTO_BETA
        ProviderRecoveryAction.ROUTE_EXHAUSTED -> AgentRoutingStage.EXHAUSTED
        else -> routingStage
    }
    
    return when (failedRoute) {
        ProviderRouteKind.PLANNER -> copy(plannerModelId = decision.nextModelId, routingStage = nextStage)
        ProviderRouteKind.EXECUTION -> copy(executionModelId = decision.nextModelId, routingStage = nextStage)
    }
}

/**
 * Repairs only the v1.8.20 route downgrade that stranded an Auto mission on
 * the local-search-only free router. A genuinely free-only mission starts with
 * both routes on openrouter/free and has no Auto-to-free recovery event, so it
 * remains untouched.
 */
internal fun AgentGoal.restoreAutoRouteAfterLegacyResearchDowngrade(): AgentGoal {
    val isAnyAuto = plannerModelId == ProviderRecoveryPolicy.AUTO_BETA_ROUTER_MODEL_ID || 
        plannerModelId == "openrouter/auto" || 
        plannerModelId == "openrouter/auto-lite"
    val wasLegacyAutoDowngrade =
        isAnyAuto &&
            executionModelId.equals(ProviderRecoveryPolicy.FREE_ROUTER_MODEL_ID, ignoreCase = true) &&
            events.any { event ->
                event.message.contains("OpenRouter Auto", ignoreCase = true) &&
                    event.message.contains("free-model router", ignoreCase = true)
            }
    return if (wasLegacyAutoDowngrade) {
        copy(
            plannerModelId = ProviderRecoveryPolicy.AUTO_BETA_ROUTER_MODEL_ID,
            executionModelId = ProviderRecoveryPolicy.AUTO_BETA_ROUTER_MODEL_ID
        )
    } else {
        this
    }
}
