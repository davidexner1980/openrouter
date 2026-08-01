package com.david.openassistant

import com.david.openassistant.agent.AgentCapability
import com.david.openassistant.agent.AgentRoutingStage
import com.david.openassistant.agent.ProviderRecoveryPolicy
import com.david.openassistant.domain.model.AgentModelSelector
import com.david.openassistant.data.openrouter.OpenRouterModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterArchitectureTest {

    @Test
    fun onlyAllowedRoutersAreRecognized() {
        assertTrue(ProviderRecoveryPolicy.isAutoRouter("openrouter/auto-beta"))
        assertFalse(ProviderRecoveryPolicy.isAutoRouter("openrouter/auto"))
        assertFalse(ProviderRecoveryPolicy.isAutoRouter("openrouter/auto-lite"))
    }

    @Test
    fun legacyAutoRouterSelectionIsExcluded() {
        val models = listOf(
            model(id = "openrouter/auto-beta", tools = true, structured = true),
            model(id = "openrouter/auto", tools = true, structured = true),
            model(id = "google/gemini-2.0-flash-001", tools = true, structured = true)
        )
        
        val route = AgentModelSelector.choose(
            models = models,
            selectedModelId = "openrouter/auto",
            freeOnly = false
        )
        
        // It should fallback to the first available text model if the selected one is legacy auto
        assertEquals("openrouter/auto-beta", route.plannerModelId)
        assertEquals("openrouter/auto-beta", route.executionModelId)
    }

    @Test
    fun fallbackOrderIsStrictlyAutoBetaToFree() {
        val models = listOf(
            model(id = "openrouter/auto-beta"),
            model(id = "openrouter/free"),
            model(id = "openai/gpt-4")
        )
        
        val fallbacks = AgentModelSelector.getFallbackModels(
            models = models,
            primaryModelId = "openrouter/auto-beta",
            capability = AgentCapability.REASON,
            freeOnly = false,
            routingStage = AgentRoutingStage.AUTO_BETA
        )
        
        assertEquals(listOf("openrouter/auto-beta", "openrouter/free"), fallbacks)
        assertFalse(fallbacks.contains("openai/gpt-4"))
    }

    @Test
    fun bodyBuilderIsNeverInFallbackArray() {
        val models = listOf(
            model(id = "openrouter/auto-beta"),
            model(id = "openrouter/free"),
            model(id = "openrouter/bodybuilder")
        )
        
        val fallbacks = AgentModelSelector.getFallbackModels(
            models = models,
            primaryModelId = "openrouter/auto-beta",
            capability = AgentCapability.REASON,
            freeOnly = false,
            routingStage = AgentRoutingStage.AUTO_BETA
        )
        
        assertFalse(fallbacks.contains("openrouter/bodybuilder"))
    }

    @Test
    fun freeOnlyWorkNeverSelectsPaidRoute() {
        val route = AgentModelSelector.choose(
            models = emptyList(),
            selectedModelId = "openrouter/auto-beta",
            freeOnly = true
        )
        
        assertEquals("openrouter/free", route.plannerModelId)
        assertEquals("openrouter/free", route.executionModelId)
    }

    private fun model(
        id: String,
        tools: Boolean = false,
        structured: Boolean = false,
        free: Boolean = false
    ) = OpenRouterModel(
        id = id,
        name = id,
        description = "",
        contextLength = 128_000,
        inputModalities = listOf("text"),
        outputModalities = listOf("text"),
        supportedParameters = buildSet {
            if (tools) add("tools")
            if (structured) {
                add("structured_outputs")
                add("response_format")
            }
        },
        promptPricePerToken = if (free) 0.0 else 0.000001,
        completionPricePerToken = if (free) 0.0 else 0.000002
    )
}
