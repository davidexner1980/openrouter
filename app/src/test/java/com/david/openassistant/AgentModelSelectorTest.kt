package com.david.openassistant

import com.david.openassistant.data.openrouter.OpenRouterModel
import com.david.openassistant.domain.model.AgentModelSelector
import com.david.openassistant.agent.AgentRoutingStage
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentModelSelectorTest {
    @Test
    fun fallbackChainIsOrderedCorrectlyForAutoBeta() {
        val models = listOf(
            model(id = "openrouter/auto-beta", structured = true),
            model(id = "openrouter/free", structured = true, free = true)
        )
        
        val fallbacks = AgentModelSelector.getFallbackModels(
            models = models,
            primaryModelId = "openrouter/auto-beta",
            capability = com.david.openassistant.agent.AgentCapability.REASON,
            freeOnly = false,
            routingStage = AgentRoutingStage.AUTO_BETA
        )
        
        assertEquals(2, fallbacks.size)
        assertEquals("openrouter/auto-beta", fallbacks[0])
        assertEquals("openrouter/free", fallbacks[1])
    }

    @Test
    fun fallbackChainIsFreeOnlyForFreeStage() {
        val fallbacks = AgentModelSelector.getFallbackModels(
            models = emptyList(),
            primaryModelId = "openrouter/free",
            capability = com.david.openassistant.agent.AgentCapability.REASON,
            freeOnly = false,
            routingStage = AgentRoutingStage.FREE
        )
        
        assertEquals(listOf("openrouter/free"), fallbacks)
    }

    @Test
    fun audioOnlyStructuredModelCannotBecomePlanner() {
        val execution = model(
            id = "cohere/north-mini-code:free",
            tools = true,
            free = true,
        )
        val audioOnlyPlanner = model(
            id = "google/lyria-3-clip-preview",
            structured = true,
            outputModalities = listOf("audio"),
        )

        val route = AgentModelSelector.choose(
            models = listOf(audioOnlyPlanner, execution),
            selectedModelId = execution.id,
            freeOnly = false,
        )

        assertEquals(false, audioOnlyPlanner.supportsTextChat)
        assertEquals(execution.id, route.executionModelId)
        assertEquals(execution.id, route.plannerModelId)
    }

    @Test
    fun lyriaCannotBecomePlannerWhenCatalogModalitiesAreMissingOrWrong() {
        val execution = model(
            id = "cohere/north-mini-code:free",
            tools = true,
            free = true,
        )
        val misleadingLyria = model(
            id = "google/lyria-3-clip-preview",
            structured = true,
            inputModalities = emptyList(),
            outputModalities = listOf("text"),
        )

        val route = AgentModelSelector.choose(
            models = listOf(misleadingLyria, execution),
            selectedModelId = execution.id,
            freeOnly = false,
        )

        assertEquals(true, misleadingLyria.isDedicatedMediaGenerator)
        assertEquals(false, misleadingLyria.supportsTextChat)
        assertEquals(execution.id, route.plannerModelId)
    }

    @Test
    fun textStructuredPlannerPrefersAutoBeta() {
        val execution = model(id = "vendor/tool-model:free", tools = true, free = true)
        val autoBeta = model(id = "openrouter/auto-beta", structured = true)
        val freePlanner = model(id = "vendor/free-planner:free", structured = true, free = true)

        val route = AgentModelSelector.choose(
            models = listOf(execution, autoBeta, freePlanner),
            selectedModelId = execution.id,
            freeOnly = false,
        )

        assertEquals(execution.id, route.executionModelId)
        assertEquals(autoBeta.id, route.plannerModelId)
    }

    @Test
    fun freeOnlyAccountUsesOpenRouterFreeForBothRoles() {
        val route = AgentModelSelector.choose(
            models = emptyList(),
            selectedModelId = null,
            freeOnly = true,
        )

        assertEquals("openrouter/free", route.executionModelId)
        assertEquals("openrouter/free", route.plannerModelId)
    }

    @Test
    fun modelOnCooldownIsExcluded() {
        val modelA = model(id = "vendor/model-a", tools = true)
        val modelB = model(id = "vendor/model-b", tools = true)
        val now = System.currentTimeMillis()

        // Without cooldown, model-a is selected if it matches the selectedModelId
        val route1 = AgentModelSelector.choose(
            models = listOf(modelA, modelB),
            selectedModelId = modelA.id,
            freeOnly = false,
        )
        assertEquals(modelA.id, route1.executionModelId)

        // With model-a on cooldown, model-b should be selected
        val route2 = AgentModelSelector.choose(
            models = listOf(modelA, modelB),
            selectedModelId = modelA.id,
            freeOnly = false,
            modelCooldowns = mapOf(modelA.id to now + 60_000)
        )
        assertEquals(modelB.id, route2.executionModelId)
    }

    @Test
    fun fallbackListRespectsThreeStageChain() {
        val toolModel = model(id = "vendor/tool-model", tools = true)
        val planningModel = model(id = "vendor/planning-model", structured = true)
        
        val models = listOf(toolModel, planningModel)

        // For AUTO_BETA stage, should return the full chain regardless of the models list
        // because these are OpenRouter-managed routers.
        val fallbacksForTool = AgentModelSelector.getFallbackModels(
            models = models,
            primaryModelId = "openrouter/auto-beta",
            capability = com.david.openassistant.agent.AgentCapability.TOOL_USE,
            freeOnly = false,
            routingStage = AgentRoutingStage.AUTO_BETA
        )
        assertEquals(listOf("openrouter/auto-beta", "openrouter/free"), fallbacksForTool)
    }

    @Test
    fun fallbackListLimitsToTwoAndIncludesRouters() {
        val models = listOf(
            model(id = "openrouter/auto-beta", structured = true),
            model(id = "openrouter/free", structured = true, free = true)
        )
        
        val fallbacks = AgentModelSelector.getFallbackModels(
            models = models,
            primaryModelId = "openrouter/auto-beta",
            capability = com.david.openassistant.agent.AgentCapability.REASON,
            freeOnly = false,
            routingStage = AgentRoutingStage.AUTO_BETA
        )
        
        assertEquals(2, fallbacks.size)
        assertEquals("openrouter/auto-beta", fallbacks[0])
        assertEquals("openrouter/free", fallbacks[1])
    }

    private fun model(
        id: String,
        tools: Boolean = false,
        structured: Boolean = false,
        free: Boolean = false,
        inputModalities: List<String> = listOf("text"),
        outputModalities: List<String> = listOf("text"),
    ) = OpenRouterModel(
        id = id,
        name = id,
        description = "",
        contextLength = 128_000,
        inputModalities = inputModalities,
        outputModalities = outputModalities,
        supportedParameters = buildSet {
            if (tools) add("tools")
            if (structured) add("structured_outputs")
        },
        promptPricePerToken = if (free) 0.0 else 0.000001,
        completionPricePerToken = if (free) 0.0 else 0.000002,
    )
}
