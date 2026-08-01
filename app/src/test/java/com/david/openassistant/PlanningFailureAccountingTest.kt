package com.david.openassistant

import com.david.openassistant.agent.AgentApiSummary
import com.david.openassistant.agent.AgentGoal
import com.david.openassistant.agent.AgentGoalStatus
import com.david.openassistant.agent.accountAgentFailureUsage
import com.david.openassistant.agent.accountPlanningFailureUsage
import com.david.openassistant.agent.isProviderStallFailure
import com.david.openassistant.agent.withAgentUsage
import com.david.openassistant.agent.withPlanningUsage
import com.david.openassistant.data.openrouter.OpenRouterException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InterruptedIOException
import java.util.concurrent.CancellationException

class PlanningFailureAccountingTest {
    @Test
    fun successfulInitialPlanUsageSurvivesRefinementProviderFailure() {
        val original = OpenRouterException(
            statusCode = 502,
            userMessage = "ResourceExhausted: Worker local total request limit reached (32/32)",
        )

        val accounted = original.withPlanningUsage(
            AgentApiSummary(
                promptTokens = 692,
                completionTokens = 2_603,
                totalTokens = 3_295,
                costUsd = 0.0,
            ),
        )

        assertEquals(502, accounted.statusCode)
        assertEquals(original.userMessage, accounted.userMessage)
        assertEquals(3_295, accounted.totalTokens)
        assertEquals(original, accounted.cause)
    }

    @Test
    fun failedPlanUsageIsAddedToDurableGoalLedger() {
        val goal = AgentGoal(
            conversationId = "conversation",
            userRequest = "what is the best recurve bow you can buy",
            title = "Recurve bow research",
            objective = "Produce a verified recommendation",
            finalOutputDescription = "Evidence-backed report",
            status = AgentGoalStatus.PLANNING,
            plannerModelId = "openrouter/free",
            executionModelId = "openrouter/free",
            tasks = emptyList(),
            totalTokens = 100,
            totalCostUsdMicros = 250_000L,
        )
        val error = OpenRouterException(
            statusCode = null,
            userMessage = "The planner returned an incomplete investigation plan.",
            totalTokens = 4_485,
            costUsd = 0.0,
        )

        val accounted = goal.accountPlanningFailureUsage(error)

        assertEquals(4_585, accounted.totalTokens)
        assertEquals(0.25, accounted.totalCostUsd, 0.000001)
    }

    @Test
    fun completedMilestoneSubcallUsageSurvivesALaterTimeout() {
        val timeout = InterruptedIOException("timeout")

        val accounted = timeout.withAgentUsage(
            AgentApiSummary(
                promptTokens = 1_672,
                completionTokens = 3_724,
                totalTokens = 5_396,
                costUsd = 0.0,
                webSearchRequests = 3,
            ),
        )

        assertTrue(accounted is OpenRouterException)
        accounted as OpenRouterException
        assertEquals(5_396, accounted.totalTokens)
        assertEquals(3, accounted.webSearchRequests)
        assertEquals(timeout, accounted.cause)
        assertTrue(accounted.isProviderStallFailure())
    }

    @Test
    fun coroutineCancellationIsNeverConvertedIntoAProviderFailure() {
        val cancellation = CancellationException("stopped")

        val accounted = cancellation.withAgentUsage(
            AgentApiSummary(totalTokens = 5_396),
        )

        assertTrue(accounted === cancellation)
    }

    @Test
    fun failedMilestoneUsageIsAddedToGoalTotals() {
        val goal = AgentGoal(
            conversationId = "conversation",
            userRequest = "research a current product",
            title = "Research",
            objective = "Produce verified work",
            finalOutputDescription = "Report",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "openrouter/free",
            executionModelId = "openrouter/free",
            tasks = emptyList(),
            totalTokens = 6_528,
            totalCostUsdMicros = 100_000L,
        )
        val error = OpenRouterException(
            statusCode = null,
            userMessage = "The provider request timed out.",
            totalTokens = 5_396,
            costUsd = 0.0,
        )

        val accounted = goal.accountAgentFailureUsage(error)

        assertEquals(11_924, accounted.totalTokens)
        assertEquals(0.10, accounted.totalCostUsd, 0.000001)
    }
}
