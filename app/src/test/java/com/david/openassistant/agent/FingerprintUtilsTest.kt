package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Test

class FingerprintUtilsTest {

    @Test
    fun normalizeTextEnsuresSemanticStability() {
        val t1 = "Who is the CEO of OpenAI?"
        val t2 = "who is the ceo of openai"
        val t3 = "WHO  IS THE  CEO OF OPENAI? "
        
        val n1 = FingerprintUtils.normalizeText(t1)
        val n2 = FingerprintUtils.normalizeText(t2)
        val n3 = FingerprintUtils.normalizeText(t3)
        
        assertEquals(n1, n2)
        assertEquals(n1, n3)
        assertEquals("who is the ceo of openai", n1)
    }

    @Test
    fun strategyFingerprintRejectsCosmeticChanges() {
        val s1 = "{\"queries\": [\"query 1\", \"query 2\"], \"logic\": \"approach A\"}"
        val s2 = "{\"logic\": \"approach A\", \"queries\": [\"query 1\", \"query 2\"]}"
        val s3 = "{\"logic\": \"APPROACH A\", \"queries\": [\"QUERY 1\", \"query 2\"]}"
        
        val f1 = FingerprintUtils.computeStrategyFingerprint(s1)
        val f2 = FingerprintUtils.computeStrategyFingerprint(s2)
        val f3 = FingerprintUtils.computeStrategyFingerprint(s3)
        
        assertEquals(f1, f2)
        assertEquals(f1, f3)
    }

    @Test
    fun rootObjectiveFingerprintProtectsFidelity() {
        val goal1 = AgentGoal(
            conversationId = "c1",
            userRequest = "Analyze AAPL",
            title = "A",
            objective = "O",
            finalOutputDescription = "D",
            status = AgentGoalStatus.QUEUED,
            plannerModelId = "m1",
            executionModelId = "m2",
            tasks = emptyList(),
            confirmedConstraints = listOf("C1", "C2")
        )
        val goal2 = goal1.copy(confirmedConstraints = listOf("C2", "C1"))
        val goal3 = goal1.copy(userRequest = "analyze aapl")
        
        val f1 = FingerprintUtils.computeRootObjectiveFingerprint(goal1)
        val f2 = FingerprintUtils.computeRootObjectiveFingerprint(goal2)
        val f3 = FingerprintUtils.computeRootObjectiveFingerprint(goal3)
        
        assertEquals(f1, f2)
        assertEquals(f1, f3)
    }
}
