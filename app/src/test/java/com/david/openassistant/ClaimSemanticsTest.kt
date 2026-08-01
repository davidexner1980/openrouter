package com.david.openassistant

import com.david.openassistant.agent.AgentCapability
import com.david.openassistant.agent.AgentClaim
import com.david.openassistant.agent.AgentClaimSupport
import com.david.openassistant.agent.AgentClaimType
import com.david.openassistant.agent.AgentTask
import com.david.openassistant.agent.normalizeDurableClaims
import com.david.openassistant.agent.scopedClaimId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimSemanticsTest {
    @Test
    fun planningQuestionsConstraintsAndRequestRestatementsDoNotPolluteGraph() {
        val task = planningTask()
        val claims = listOf(
            claim("q", "Research Question 1: Which transit stops are accessible?"),
            claim("c", "Constraint 2: Only publicly served routes may be recommended."),
            claim("d", "Decision Criterion 3: Prefer frequent all-day service."),
            claim("r", "The user asks for accessible transit locations near Riverton."),
            claim("f", "The central transit station is north of Riverton."),
        )

        val normalized = normalizeDurableClaims(task, claims)

        assertEquals(listOf("f"), normalized.map { it.id })
        assertEquals(0.5, normalized.single().confidence, 0.0)
    }

    @Test
    fun genuineUncertaintyAndSupportedAssertionsRemain() {
        val task = planningTask()
        val claims = listOf(
            claim(
                id = "u",
                text = "Exact access conditions remain uncertain.",
                type = AgentClaimType.UNCERTAINTY,
                support = AgentClaimSupport.SUPPORTED,
                confidence = 0.9,
            ),
            claim(
                id = "s",
                text = "The official transit page lists an accessible platform.",
                support = AgentClaimSupport.SUPPORTED,
                confidence = 0.95,
            ),
        )

        val normalized = normalizeDurableClaims(task, claims)

        assertEquals(2, normalized.size)
        assertTrue(normalized.any { it.id == "u" && it.confidence == 0.9 })
        assertFalse(normalized.any { it.text.endsWith("?") })
    }

    @Test
    fun providerClaimIdsAreScopedToTheirDurableMilestone() {
        val discovery = scopedClaimId("research_discovery", "claim-1", 1)
        val synthesis = scopedClaimId("synthesis", "claim-1", 1)

        assertEquals("research_discovery__claim-1", discovery)
        assertEquals("synthesis__claim-1", synthesis)
        assertFalse(discovery == synthesis)
        assertEquals(synthesis, scopedClaimId("synthesis", synthesis, 1))

        val recoveryRound2 = scopedClaimId("verification_discovery_recovery_2", "claim-1", 1)
        val recoveryRound3 = scopedClaimId("verification_discovery_recovery_3", "claim-1", 1)
        assertFalse(recoveryRound2 == recoveryRound3)
        assertTrue(recoveryRound2.length <= 64)
        assertTrue(recoveryRound3.length <= 64)
        assertEquals(
            recoveryRound2,
            scopedClaimId("verification_discovery_recovery_2", recoveryRound2, 1),
        )
    }

    private fun planningTask() = AgentTask(
        id = "map_request",
        order = 0,
        title = "Map the request and research questions",
        instructions = "Define questions and constraints.",
        capability = AgentCapability.REASON,
    )

    private fun claim(
        id: String,
        text: String,
        type: AgentClaimType = AgentClaimType.FACT,
        support: AgentClaimSupport = AgentClaimSupport.UNSUPPORTED,
        confidence: Double = 1.0,
    ) = AgentClaim(
        id = id,
        taskId = "map_request",
        text = text,
        type = type,
        confidence = confidence,
        support = support,
    )
}
