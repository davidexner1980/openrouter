package com.david.openassistant

import com.david.openassistant.agent.AgentGoal
import com.david.openassistant.agent.AgentGoalStatus
import com.david.openassistant.agent.AgentLifecycleReducer
import com.david.openassistant.agent.AgentRoutingStage
import com.david.openassistant.agent.ProviderRecoveryAction
import com.david.openassistant.agent.ProviderRecoveryPolicy
import com.david.openassistant.agent.isProviderStallFailure
import com.david.openassistant.agent.isProviderCapacityFailure
import com.david.openassistant.agent.isNetworkResolutionFailure
import com.david.openassistant.agent.isPlanStructureFailure
import com.david.openassistant.agent.isResponseShapeFailure
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ProviderRecoveryPolicyTest {

    @Test
    fun twoStageRoutingChainWorksCorrectly() {
        // Stage 1: AUTO_BETA -> 429 -> FREE
        val decision1 = ProviderRecoveryPolicy.decide(
            statusCode = 429,
            currentModelId = "openrouter/auto-beta",
            routingStage = AgentRoutingStage.AUTO_BETA
        )
        assertEquals(ProviderRecoveryAction.SWITCH_TO_FREE, decision1.action)
        assertEquals("openrouter/free", decision1.nextModelId)

        // Stage 2: FREE -> 429 -> ROUTE_EXHAUSTED (No intelligence escalation for rate limit)
        val decision2 = ProviderRecoveryPolicy.decide(
            statusCode = 429,
            currentModelId = "openrouter/free",
            routingStage = AgentRoutingStage.FREE
        )
        assertEquals(ProviderRecoveryAction.ROUTE_EXHAUSTED, decision2.action)
    }

    @Test
    fun embeddedRateLimitAdvancesStageImmediately() {
        // AUTO_BETA -> capacity failure (embedded 429) -> FREE
        val decision = ProviderRecoveryPolicy.decide(
            statusCode = 200,
            currentModelId = "openrouter/auto-beta",
            routingStage = AgentRoutingStage.AUTO_BETA,
            providerCapacityFailure = true
        )
        assertEquals(ProviderRecoveryAction.SWITCH_TO_FREE, decision.action)
        assertEquals("openrouter/free", decision.nextModelId)
    }

    @Test
    fun freeOnlyMissionStaysOnFreeRouter() {
        val decision = ProviderRecoveryPolicy.decide(
            statusCode = 429,
            currentModelId = "openrouter/free",
            routingStage = AgentRoutingStage.FREE,
            isFreeOnly = true
        )
        assertEquals(ProviderRecoveryAction.ROUTE_EXHAUSTED, decision.action)
        assertEquals("openrouter/free", decision.nextModelId)
    }

    @Test
    fun paidCreditFailureSwitchesToFreeRouter() {
        val decision = ProviderRecoveryPolicy.decide(402, "openai/some-paid-model")

        assertEquals(ProviderRecoveryAction.SWITCH_TO_FREE, decision.action)
        assertEquals("openrouter/free", decision.nextModelId)
    }

    @Test
    fun serverErrorsRemainRetryable() {
        assertEquals(
            ProviderRecoveryAction.RETRY_CURRENT_ROUTE,
            ProviderRecoveryPolicy.decide(503, "openrouter/auto-beta", routingStage = AgentRoutingStage.AUTO_BETA).action,
        )
    }

    @Test
    fun invalidCredentialMovesToDurableWaitState() {
        assertEquals(
            ProviderRecoveryAction.WAIT_FOR_CREDENTIAL,
            ProviderRecoveryPolicy.decide(401, "openrouter/free", routingStage = AgentRoutingStage.FREE).action,
        )
    }

    @Test
    fun autoShapeFailureSwitchesToFree() {
        val decision = ProviderRecoveryPolicy.decide(
            statusCode = null,
            currentModelId = "openrouter/auto-beta",
            routingStage = AgentRoutingStage.AUTO_BETA,
            responseShapeFailure = true,
        )

        assertEquals(ProviderRecoveryAction.SWITCH_TO_FREE, decision.action)
        assertEquals("openrouter/free", decision.nextModelId)
    }

    @Test
    fun shapeFailureInAutoBetaSwitchesToFree() {
        assertEquals(
            ProviderRecoveryAction.SWITCH_TO_FREE,
            ProviderRecoveryPolicy.decide(
                statusCode = null,
                currentModelId = "openrouter/auto-beta",
                routingStage = AgentRoutingStage.AUTO_BETA,
                responseShapeFailure = true,
            ).action,
        )
    }

    @Test
    fun androidArrayToObjectFailureIsClassified() {
        val error = IllegalStateException(
            "Value [] of type org.json.JSONArray cannot be converted to JSONObject",
        )

        assertEquals(true, error.isResponseShapeFailure())
    }

    @Test
    fun providerTimeoutIsClassifiedThroughItsCauseChain() {
        val error = IllegalStateException(
            "Provider request failed",
            SocketTimeoutException("timeout"),
        )

        assertEquals(true, error.isProviderStallFailure())
        assertEquals(true, InterruptedIOException("deadline exceeded").isProviderStallFailure())
    }

    @Test
    fun dnsFailureMovesToDurableNetworkWaitState() {
        val error = IllegalStateException(
            "Provider request failed",
            UnknownHostException("Unable to resolve host openrouter.ai: No address associated with hostname"),
        )

        assertEquals(true, error.isNetworkResolutionFailure())
        val decision = ProviderRecoveryPolicy.decide(
            statusCode = null,
            currentModelId = "openrouter/auto-beta",
            routingStage = AgentRoutingStage.AUTO_BETA,
            networkResolutionFailure = true,
        )

        assertEquals(ProviderRecoveryAction.WAIT_FOR_NETWORK, decision.action)
        assertEquals("openrouter/auto-beta", decision.nextModelId)
    }

    @Test
    fun stalledFixedPaidRouteSwitchesToFree() {
        val paidDecision = ProviderRecoveryPolicy.decide(
            statusCode = null,
            currentModelId = "cohere/north-mini-code",
            routingStage = AgentRoutingStage.AUTO_BETA,
            requestStallFailure = true,
        )
        assertEquals(ProviderRecoveryAction.SWITCH_TO_FREE, paidDecision.action)
        assertEquals("openrouter/free", paidDecision.nextModelId)
    }

    @Test
    fun stalledAutoBetaRouteRetries() {
        val decision = ProviderRecoveryPolicy.decide(
            statusCode = null,
            currentModelId = "openrouter/auto-beta",
            routingStage = AgentRoutingStage.AUTO_BETA,
            requestStallFailure = true,
        )

        assertEquals(ProviderRecoveryAction.SWITCH_TO_FREE, decision.action)
        assertEquals("openrouter/free", decision.nextModelId)
    }

    @Test
    fun stalledFreeRouterAdvancesToAutoBetaWithEscalation() {
        val decision = ProviderRecoveryPolicy.decide(
            statusCode = null,
            currentModelId = "openrouter/free",
            routingStage = AgentRoutingStage.FREE,
            requestStallFailure = true,
            isIntelligenceEscalation = true
        )

        assertEquals(ProviderRecoveryAction.ESCALATE_TO_PAID, decision.action)
        assertEquals("openrouter/auto-beta", decision.nextModelId)
    }

    @Test
    fun progressStallOnFreeModelEscalatesToAutoBetaWithEscalation() {
        val decision = ProviderRecoveryPolicy.decide(
            statusCode = null,
            currentModelId = "openrouter/free",
            routingStage = AgentRoutingStage.FREE,
            progressStallFailure = true,
            isIntelligenceEscalation = true
        )

        assertEquals(ProviderRecoveryAction.ESCALATE_TO_PAID, decision.action)
        assertEquals("openrouter/auto-beta", decision.nextModelId)
    }

    @Test
    fun intelligenceWallOnFreeModelEscalatesToAutoBetaWithEscalation() {
        val decision = ProviderRecoveryPolicy.decide(
            statusCode = null,
            currentModelId = "openrouter/free",
            routingStage = AgentRoutingStage.FREE,
            intelligenceWallReached = true,
            isIntelligenceEscalation = true
        )

        assertEquals(ProviderRecoveryAction.ESCALATE_TO_PAID, decision.action)
        assertEquals("openrouter/auto-beta", decision.nextModelId)
    }

    @Test
    fun credentialWaitWithoutPlanResumesPlanning() {
        val waiting = AgentGoal(
            conversationId = "conversation",
            userRequest = "complete the job",
            title = "job",
            objective = "complete",
            finalOutputDescription = "verified result",
            status = AgentGoalStatus.WAITING_FOR_CREDENTIAL,
            plannerModelId = "openrouter/free",
            executionModelId = "openrouter/free",
            tasks = emptyList(),
        )

        val resumed = AgentLifecycleReducer.resume(waiting)

        assertEquals(AgentGoalStatus.PLANNING, resumed.status)
    }

    @Test
    fun networkWaitResumesCorrectly() {
        val waiting = AgentGoal(
            conversationId = "conversation",
            userRequest = "complete the job",
            title = "job",
            objective = "complete",
            finalOutputDescription = "verified result",
            status = AgentGoalStatus.WAITING_FOR_NETWORK,
            plannerModelId = "openrouter/free",
            executionModelId = "openrouter/free",
            tasks = emptyList(),
        )

        val resumed = AgentLifecycleReducer.resume(waiting)

        assertEquals(AgentGoalStatus.PLANNING, resumed.status)
    }

    @Test
    fun fingerprintNormalizationIgnoresNoise() {
        val t1 = "Analyze the repo - attempt 1"
        val t2 = "Analyze the repo (retry 2)"
        val t3 = "Analyze the repo - pass 3"
        val t4 = "Analyze the repo v4"
        
        val f1 = ProviderRecoveryPolicy.normalizeFingerprintSource(t1)
        val f2 = ProviderRecoveryPolicy.normalizeFingerprintSource(t2)
        val f3 = ProviderRecoveryPolicy.normalizeFingerprintSource(t3)
        val f4 = ProviderRecoveryPolicy.normalizeFingerprintSource(t4)
        
        assertEquals(f1, f2)
        assertEquals(f2, f3)
        assertEquals(f3, f4)
        
        val t5 = "REPO ANALYZE" // Word reordering
        val f5 = ProviderRecoveryPolicy.normalizeFingerprintSource(t5)
        assertEquals(f1, f5)
    }
}
