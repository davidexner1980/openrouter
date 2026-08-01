package com.david.openassistant.agent

import com.david.openassistant.data.openrouter.OpenRouterModel
import com.david.openassistant.domain.model.AgentModelSelector
import org.junit.Assert.*
import org.junit.Test

class RoutingResilienceTest {

    private val models = listOf(
        model(id = "openrouter/auto-beta"),
        model(id = "openrouter/free"),
        model(id = "openrouter/bodybuilder"),
        model(id = "openrouter/auto"),
        model(id = "anthropic/claude-3-opus")
    )

    @Test
    fun `test fallback order is always auto-beta then free`() {
        val fallbacks = AgentModelSelector.getFallbackModels(models, "openrouter/auto-beta", AgentCapability.REASON, false)
        assertEquals(2, fallbacks.size)
        assertEquals("openrouter/auto-beta", fallbacks[0])
        assertEquals("openrouter/free", fallbacks[1])
    }

    @Test
    fun `test regular auto router is never selected automatically`() {
        val route = AgentModelSelector.choose(models, null, false)
        assertNotEquals("openrouter/auto", route.plannerModelId)
        assertNotEquals("openrouter/auto", route.executionModelId)
    }

    @Test
    fun `test body builder is never in fallback array`() {
        val fallbacks = AgentModelSelector.getFallbackModels(models, "openrouter/auto-beta", AgentCapability.REASON, false)
        assertFalse(fallbacks.contains("openrouter/bodybuilder"))
    }

    @Test
    fun `test recovery from auto-beta 429 goes to free`() {
        val decision = ProviderRecoveryPolicy.decide(
            statusCode = 429,
            currentModelId = "openrouter/auto-beta",
            routingStage = AgentRoutingStage.AUTO_BETA
        )
        assertEquals(ProviderRecoveryAction.SWITCH_TO_FREE, decision.action)
        assertEquals("openrouter/free", decision.nextModelId)
    }

    @Test
    fun `test free model intelligence wall returns to auto-beta`() {
        val decision = ProviderRecoveryPolicy.decide(
            statusCode = null,
            currentModelId = "openrouter/free",
            routingStage = AgentRoutingStage.FREE,
            intelligenceWallReached = true
        )
        assertEquals(ProviderRecoveryAction.ESCALATE_TO_PAID, decision.action)
        assertEquals("openrouter/auto-beta", decision.nextModelId)
    }

    @Test
    fun `test only auto-beta is recognized as auto-router`() {
        assertFalse(ProviderRecoveryPolicy.isAutoRouter("openrouter/auto"))
        assertTrue(ProviderRecoveryPolicy.isAutoRouter("openrouter/auto-beta"))
        assertFalse(ProviderRecoveryPolicy.isAutoRouter("openrouter/auto-lite"))
    }

    private fun model(id: String) = OpenRouterModel(
        id = id,
        name = id,
        description = "",
        contextLength = 128000,
        inputModalities = listOf("text"),
        outputModalities = listOf("text"),
        supportedParameters = setOf("tools", "structured_outputs"),
        promptPricePerToken = 0.0,
        completionPricePerToken = 0.0
    )
}
