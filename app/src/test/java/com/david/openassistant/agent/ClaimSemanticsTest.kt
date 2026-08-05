package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Test

class ClaimSemanticsTest {

    @Test
    fun testScopedClaimIdStabilityWithTextFallback() {
        val taskId = "task-1"
        val text = "This is a stable claim."
        
        // 1. No requested ID, different indices
        val id1 = scopedClaimId(taskId, "", text, 1)
        val id2 = scopedClaimId(taskId, "  ", text, 2)
        
        assertEquals("IDs should be identical for identical text when no requested ID is provided. Got $id1 and $id2", id1, id2)
        assertTrue("ID should contain task namespace. Got $id1", id1.startsWith("task-1__"))
        assertTrue("ID should contain text hash. Got $id1", id1.contains("txt_"))
    }

    @Test
    fun testScopedClaimIdStabilityWithRequestedId() {
        val taskId = "task-1"
        val text1 = "Claim text A"
        val text2 = "Claim text B"
        
        // Model provides explicit ID
        val id1 = scopedClaimId(taskId, "c-1", text1, 1)
        val id2 = scopedClaimId(taskId, "c-1", text2, 2)
        
        assertEquals("Explicit ID should override text-based hash for stability across mutations. Got $id1 and $id2", id1, id2)
        assertTrue("ID should contain task namespace. Got $id1", id1.startsWith("task-1__"))
        assertTrue("ID should contain requested ID. Got $id1", id1.contains("c-1"))
    }

    @Test
    fun testMergeClaimsUpsertsById() {
        val taskId = "task-1"
        val claimId = "id-1"
        
        val existing = listOf(
            AgentClaim(
                id = claimId,
                taskId = taskId,
                text = "Old text",
                type = AgentClaimType.FACT,
                confidence = 0.5,
                support = AgentClaimSupport.UNSUPPORTED
            )
        )
        
        val incoming = listOf(
            AgentClaim(
                id = claimId,
                taskId = taskId,
                text = "Updated text",
                type = AgentClaimType.FACT,
                confidence = 0.9,
                support = AgentClaimSupport.SUPPORTED
            )
        )
        
        val result = mergeClaims(existing, incoming)
        
        assertEquals(1, result.size)
        assertEquals("Updated text", result[0].text)
        assertEquals(0.9, result[0].confidence, 0.01)
        assertEquals(AgentClaimSupport.SUPPORTED, result[0].support)
    }

    @Test
    fun testLegacyTestsRestoredWithNewSignatures() {
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
    }

    @Test
    fun providerClaimIdsAreScopedToTheirDurableMilestone() {
        val discovery = scopedClaimId("research_discovery", "claim-1", "text", 1)
        val synthesis = scopedClaimId("synthesis", "claim-1", "text", 1)

        assertEquals("research_discovery__claim-1", discovery)
        assertEquals("synthesis__claim-1", synthesis)
        assertFalse(discovery == synthesis)
        assertEquals(synthesis, scopedClaimId("synthesis", synthesis, "text", 1))

        val recoveryRound2 = scopedClaimId("verification_discovery_recovery_2", "claim-1", "text", 1)
        val recoveryRound3 = scopedClaimId("verification_discovery_recovery_3", "claim-1", "text", 1)
        assertFalse(recoveryRound2 == recoveryRound3)
        assertTrue(recoveryRound2.length <= 64)
        assertTrue(recoveryRound3.length <= 64)
        assertEquals(
            recoveryRound2,
            scopedClaimId("verification_discovery_recovery_2", recoveryRound2, "text", 1),
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
