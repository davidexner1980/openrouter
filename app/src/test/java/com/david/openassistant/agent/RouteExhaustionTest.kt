package com.david.openassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class RouteExhaustionTest {

    @Test
    fun exhaustedStageReturnsRouteExhaustedOnRateLimit() {
        val decision = ProviderRecoveryPolicy.decide(
            statusCode = 429,
            currentModelId = "openrouter/auto-beta",
            routingStage = AgentRoutingStage.EXHAUSTED,
            isFreeOnly = false
        )
        
        assertEquals(ProviderRecoveryAction.ROUTE_EXHAUSTED, decision.action)
    }

    @Test
    fun freeOnlyMissionAtFinalStageReturnsRouteExhaustedOnFailure() {
        val decision = ProviderRecoveryPolicy.decide(
            statusCode = null,
            currentModelId = "openrouter/free",
            routingStage = AgentRoutingStage.FREE,
            responseShapeFailure = true,
            isFreeOnly = true
        )
        
        assertEquals(ProviderRecoveryAction.ROUTE_EXHAUSTED, decision.action)
    }

    @Test
    fun autoBetaEscalatesToFree() {
        val decision = ProviderRecoveryPolicy.decide(
            statusCode = 429,
            currentModelId = "openrouter/auto-beta",
            routingStage = AgentRoutingStage.AUTO_BETA,
            isFreeOnly = false
        )
        
        assertEquals(ProviderRecoveryAction.SWITCH_TO_FREE, decision.action)
        assertEquals("openrouter/free", decision.nextModelId)
    }
}
