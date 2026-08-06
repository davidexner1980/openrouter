package com.david.openassistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CitationValidationTest {

    @Test
    fun `test integrity evaluator fails on fabricated excerpt`() {
        val url = "https://example.com/source"
        val realContent = "The sky is blue and the grass is green."
        
        val sourceRead = SourceRead(
            id = "src-1",
            url = url,
            canonicalUrl = url,
            documentId = "doc-1",
            contentHash = "hash-1",
            httpCode = 200,
            contentType = "text/plain",
            content = realContent,
            sourceRole = "research",
            authorityScore = 10,
            provenance = SourceReadProvenance.VERIFIED_FETCH
        )

        val claim = AgentClaim(
            id = "c1",
            taskId = "task-1",
            text = "The sky is red.",
            type = AgentClaimType.FACT,
            confidence = 1.0,
            support = AgentClaimSupport.SUPPORTED,
            sourceUrls = listOf(url),
            claimFingerprint = "fp1"
        )

        val goal = AgentGoal(
            id = "goal-1",
            conversationId = "conv-1",
            userRequest = "colors",
            title = "Colors",
            objective = "Colors",
            finalOutputDescription = "Report",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "model",
            executionModelId = "model",
            tasks = listOf(AgentTask(id = "task-1", order = 0, title = "Task", instructions = "Inst", capability = AgentCapability.WEB_RESEARCH)),
            sourceReads = listOf(sourceRead),
            claims = listOf(claim)
        )

        val stepResult = AgentStepResult(
            content = "Fabricated report.",
            summary = AgentApiSummary(),
            sources = listOf(AgentSourceCitation("Source", url, "The sky is red")), // Fabricated excerpt
            claims = listOf(claim)
        )
        
        val qualityDecision = ResearchQualityGate.evaluateStep(
            task = goal.tasks.first(),
            result = stepResult,
            goal = goal
        )
        assertFalse("Expected ResearchQualityGate to detect fabricated excerpt", qualityDecision.passed)
        assertTrue(qualityDecision.reasons.any { it.contains("failed semantic verification") || it.contains("lacks verified source grounding") })
    }
    
    @Test
    fun `test validator detects unverified url`() {
        val url = "https://fabricated.com/fake"
        val claim = AgentClaim(
            id = "c1",
            taskId = "task-1",
            text = "Fake fact",
            type = AgentClaimType.FACT,
            confidence = 1.0,
            support = AgentClaimSupport.SUPPORTED,
            sourceUrls = listOf(url),
            claimFingerprint = "fp1"
        )
        val result = AgentStepResult(
            content = "Fake",
            summary = AgentApiSummary(),
            claims = listOf(claim)
        )
        // No source reads supplied
        val validation = CitationValidator.validateStepResult(result, emptyList())
        assertFalse("Expected validator to detect unverified URL", validation.isValid)
        assertTrue(validation.reasons.any { it.contains("no matching record in durable evidence") || it.contains("cites URL not present in durable evidence") })
    }
}
