package com.david.openassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentRoutingPolicyTest {

    @Test
    fun `guardModel allows free-router for free mission`() {
        val goal = freeGoal()
        val result = AgentRoutingPolicy.guardModel(goal, "openrouter/free")
        assertEquals("openrouter/free", result)
    }

    @Test
    fun `guardModel allows free-suffix model for free mission`() {
        val goal = freeGoal()
        val result = AgentRoutingPolicy.guardModel(goal, "google/gemini-pro-1.5:free")
        assertEquals("google/gemini-pro-1.5:free", result)
    }

    @Test
    fun `guardModel repairs auto-beta for free mission`() {
        val goal = freeGoal()
        val result = AgentRoutingPolicy.guardModel(goal, "openrouter/auto-beta")
        assertEquals("openrouter/free", result)
    }

    @Test
    fun `guardModel repairs paid model for free mission`() {
        val goal = freeGoal()
        val result = AgentRoutingPolicy.guardModel(goal, "anthropic/claude-3-opus")
        assertEquals("openrouter/free", result)
    }

    @Test
    fun `guardModel allows any model for non-free mission`() {
        val goal = paidGoal()
        val result = AgentRoutingPolicy.guardModel(goal, "anthropic/claude-3-opus")
        assertEquals("anthropic/claude-3-opus", result)
    }

    @Test
    fun `guardRecovery rejects paid escalation for free mission`() {
        val goal = freeGoal()
        val result = AgentRoutingPolicy.guardRecovery(goal, ProviderRecoveryAction.ESCALATE_TO_PAID)
        assertEquals(ProviderRecoveryAction.ROUTE_EXHAUSTED, result)
    }

    @Test
    fun `guardStage rejects auto-beta stage for free mission`() {
        val goal = freeGoal()
        val result = AgentRoutingPolicy.guardStage(goal, AgentRoutingStage.AUTO_BETA)
        assertEquals(AgentRoutingStage.FREE, result)
    }

    private fun freeGoal() = AgentGoal(
        id = "goal-free",
        conversationId = "conv-1",
        userRequest = "test",
        title = "test",
        objective = "test",
        finalOutputDescription = "test",
        status = AgentGoalStatus.RUNNING,
        plannerModelId = "openrouter/free",
        executionModelId = "openrouter/free",
        freeOnly = true,
        tasks = emptyList()
    )

    private fun paidGoal() = AgentGoal(
        id = "goal-paid",
        conversationId = "conv-1",
        userRequest = "test",
        title = "test",
        objective = "test",
        finalOutputDescription = "test",
        status = AgentGoalStatus.RUNNING,
        plannerModelId = "openrouter/auto-beta",
        executionModelId = "openrouter/auto-beta",
        freeOnly = false,
        tasks = emptyList()
    )
}
