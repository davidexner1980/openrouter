package com.david.openassistant.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class RabbitHoleAccountingTest {

    @Test
    fun deepFinancialResearchReceivesRabbitHoleBudget() {
        val goal = AgentGoal(
            id = "goal-1",
            conversationId = "conv-1",
            userRequest = "Do a comprehensive current financials, valuation, risk, safest option, and future potential analysis, then recommend what looks best.",
            title = "Financial analysis",
            objective = "Research objective",
            finalOutputDescription = "Research result",
            status = AgentGoalStatus.QUEUED,
            plannerModelId = "openrouter/auto-beta",
            executionModelId = "openrouter/auto-beta",
            tasks = emptyList(),
        )
        val task = AgentTask(
            id = "research_primary",
            order = 0,
            title = "Primary research",
            instructions = "Research the financial evidence.",
            capability = AgentCapability.DEEP_RESEARCH,
        )

        val profile = AgentResearchAllocator.profileForGoal(goal)
        val budget = AgentResearchAllocator.budgetForTask(goal, task, profile)

        assertTrue(profile.targetDistinctSources >= 10)
        assertTrue(budget.maxRabbitHoleIterations >= 8)
        assertTrue(budget.allowModelEscalation)
    }
}
