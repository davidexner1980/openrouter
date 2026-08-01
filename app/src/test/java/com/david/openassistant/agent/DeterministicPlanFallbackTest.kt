package com.david.openassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicPlanFallbackTest {

    @Test
    fun speculativeScienceRequestGetsExecutableDeepResearchPlan() {
        val goal = testGoal(
            request = "searching great detail about dark matter and come up with your own theory about what it is",
            title = "Dark Matter Deep Dive and Novel Theory Development",
        )
        val policy = AgentResearchPolicy(AgentResearchDepth.DEEP)

        val draft = DeterministicPlanFallback.build(goal, policy)

        assertTrue(draft.title.contains("Dark Matter", ignoreCase = true))
        assertEquals(1, draft.tasks.count { it.capability == AgentCapability.REASON })
        assertEquals(policy.minimumPasses, draft.tasks.count { it.capability == AgentCapability.DEEP_RESEARCH })
        assertEquals(1, draft.tasks.count { it.capability == AgentCapability.SYNTHESIZE })
        assertTrue(draft.tasks.first().instructions.contains("dark matter", ignoreCase = true))
        assertTrue(draft.tasks.all { it.acceptanceCriteria.isNotEmpty() })
    }

    @Test
    fun financialRequestRequiresCurrentEvidenceAndNoPersonalizedAdvice() {
        val goal = testGoal(
            request = "Compare the current financials and potential of the safest investment platforms and recommend what looks best.",
            title = "Investment Platform Financial Research",
        )
        val policy = AgentResearchPolicy(AgentResearchDepth.DEEP)

        val draft = DeterministicPlanFallback.build(goal, policy)
        val text = (draft.acceptanceCriteria.joinToString(" ") { it.description } + " " +
            draft.tasks.joinToString(" ") { task ->
                task.title + " " + task.instructions + " " + task.acceptanceCriteria.joinToString(" ") { it.description }
            }).lowercase()

        assertTrue(text.contains("financial"))
        assertTrue(text.contains("dated"))
        assertTrue(text.contains("risk"))
        assertTrue(text.contains("personalized financial advice"))
        assertTrue(text.contains("stale"))
    }

    private fun testGoal(
        request: String,
        title: String,
    ) = AgentGoal(
        id = "goal-1",
        conversationId = "conv-1",
        userRequest = request,
        title = title,
        objective = "Research objective",
        finalOutputDescription = "Research result",
        status = AgentGoalStatus.PLANNING,
        plannerModelId = "openrouter/auto-beta",
        executionModelId = "openrouter/auto-beta",
        tasks = emptyList(),
    )
}
