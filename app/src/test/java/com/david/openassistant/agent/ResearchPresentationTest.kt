package com.david.openassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchPresentationTest {
    @Test
    fun canonicalPresentationUrlRemovesTrackingButPreservesRecordIdentity() {
        val canonical = canonicalPresentationUrl(
            "https://Example.GOV:443/record?id=TT6450&utm_source=test#section",
        )

        assertEquals("https://example.gov/record?id=TT6450", canonical)
    }

    @Test
    fun activitySummaryRecoversFetchesAndBranchesFromDurableToolAudit() {
        val evidence = AgentEvidence(
            taskId = "research",
            kind = AgentEvidenceKind.DEEP_RESEARCH,
            title = "Research",
            summary = "summary",
            content = """
                Result

                Autonomous local tool activity:
                - [PASS] public_web_search: Primary search
                - [PASS] public_web_fetch: Research full-source read
                - [PASS] evidence_driven_follow_up_strategy: Rabbit-hole branch [1]: trace citation
                - [PASS] public_web_search: Rabbit-hole search [1]: citation query
                - [PASS] public_web_fetch: Rabbit-hole read [1]: source
            """.trimIndent(),
            sources = listOf(
                AgentSourceCitation("Official", "https://example.gov/record?id=1&utm_source=test"),
                AgentSourceCitation("Duplicate", "https://example.gov/record?id=1"),
            ),
        )
        val goal = AgentGoal(
            conversationId = "conversation",
            userRequest = "request",
            title = "title",
            objective = "objective",
            finalOutputDescription = "output",
            status = AgentGoalStatus.COMPLETED,
            plannerModelId = "planner",
            executionModelId = "executor",
            tasks = emptyList(),
            evidence = listOf(evidence),
        )

        val summary = goal.researchActivitySummary()

        assertEquals(2, summary.searches)
        assertEquals(2, summary.fetches)
        assertEquals(1, summary.uniqueSources)
        assertEquals(1, summary.rabbitHoleBranches)
    }

    @Test
    fun descriptiveLabelsPreferMeaningfulTitlesAndClassifyOfficialSources() {
        val source = AgentSourceCitation(
            title = "NGS Datasheet TT6450",
            url = "https://www.ngs.noaa.gov/cgi-bin/ds_mark.prl?PidBox=TT6450",
        )

        assertEquals("NGS Datasheet TT6450", descriptiveSourceLabel(source))
        assertEquals("Official geodetic record", sourceRoleLabel(source.url))
        assertTrue(confidenceExplanation(
            AgentClaim(
                taskId = "task",
                text = "Denali is 20,310 ft",
                type = AgentClaimType.FACT,
                confidence = 0.98,
                support = AgentClaimSupport.SUPPORTED,
                supportingEvidenceIds = listOf("evidence"),
                sourceUrls = emptyList(),
            ),
            mapOf("evidence" to AgentEvidence(
                id = "evidence",
                kind = AgentEvidenceKind.RESEARCH_HIT,
                title = "NGS",
                summary = "summary",
                content = "content",
                sources = listOf(source),
            )),
        ).any { it.contains("Official geodetic record") })
        assertTrue(descriptiveUrlLabel(source.url).contains("ngs.noaa.gov"))
    }
}
