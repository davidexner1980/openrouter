package com.david.openassistant

import com.david.openassistant.agent.AgentGoal
import com.david.openassistant.agent.AgentGoalStatus
import com.david.openassistant.agent.AgentEvent
import com.david.openassistant.agent.ProviderRecoveryAction
import com.david.openassistant.agent.ProviderRecoveryDecision
import com.david.openassistant.agent.ProviderRouteKind
import com.david.openassistant.agent.recoverProviderRoute
import com.david.openassistant.agent.restoreAutoRouteAfterLegacyResearchDowngrade
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderRouteRecoveryTest {
    private val goal = AgentGoal(
        conversationId = "conversation",
        userRequest = "what is the best recurve bow you can buy",
        title = "Research",
        objective = "Verify the request",
        finalOutputDescription = "Evidence-backed report",
        status = AgentGoalStatus.PLANNING,
        plannerModelId = "nvidia/nemotron-3-super-120b-a12b:free",
        executionModelId = "cohere/north-mini-code:free",
        tasks = emptyList(),
    )

    @Test
    fun plannerRecoveryPreservesTheSelectedExecutionModel() {
        val recovered = goal.recoverProviderRoute(switchToFree(), ProviderRouteKind.PLANNER)

        assertEquals("openrouter/free", recovered.plannerModelId)
        assertEquals("cohere/north-mini-code:free", recovered.executionModelId)
    }

    @Test
    fun executionRecoveryPreservesThePlannerModel() {
        val recovered = goal.recoverProviderRoute(switchToFree(), ProviderRouteKind.EXECUTION)

        assertEquals("nvidia/nemotron-3-super-120b-a12b:free", recovered.plannerModelId)
        assertEquals("openrouter/free", recovered.executionModelId)
    }

    @Test
    fun retryDecisionDoesNotRewriteEitherRoute() {
        val recovered = goal.recoverProviderRoute(
            ProviderRecoveryDecision(
                action = ProviderRecoveryAction.RETRY_CURRENT_ROUTE,
                nextModelId = "openrouter/free",
                explanation = "Retry later.",
            ),
            ProviderRouteKind.PLANNER,
        )

        assertEquals(goal.plannerModelId, recovered.plannerModelId)
        assertEquals(goal.executionModelId, recovered.executionModelId)
    }

    @Test
    fun savedAutoMissionEscapesTheLegacyFreeRouterDowngrade() {
        val downgraded = goal.copy(
            plannerModelId = "openrouter/auto-beta",
            executionModelId = "openrouter/free",
            events = listOf(
                AgentEvent(
                    message = "OpenRouter Auto returned an incompatible JSON shape. Switched to the free-model router for a different compatibility path.",
                ),
            ),
        )

        val recovered = downgraded.restoreAutoRouteAfterLegacyResearchDowngrade()

        assertEquals("openrouter/auto-beta", recovered.plannerModelId)
        assertEquals("openrouter/auto-beta", recovered.executionModelId)
    }

    @Test
    fun genuineFreeOnlyMissionIsNotPromotedToAuto() {
        val freeOnly = goal.copy(
            plannerModelId = "openrouter/free",
            executionModelId = "openrouter/free",
            events = emptyList(),
        )

        val recovered = freeOnly.restoreAutoRouteAfterLegacyResearchDowngrade()

        assertEquals("openrouter/free", recovered.plannerModelId)
        assertEquals("openrouter/free", recovered.executionModelId)
    }

    private fun switchToFree() = ProviderRecoveryDecision(
        action = ProviderRecoveryAction.SWITCH_TO_FREE,
        nextModelId = "openrouter/free",
        explanation = "Switch route.",
    )
}
