package com.david.openassistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class CitationValidationTest {

    @Test
    fun `test integrity evaluator fails on fabricated excerpt`() {
        val evidenceId = UUID.randomUUID().toString()
        val url = "https://example.com/source"
        val realContent = "The sky is blue and the grass is green."
        
        val evidence = AgentEvidence(
            id = evidenceId,
            kind = AgentEvidenceKind.WEB_RESEARCH,
            title = "Source",
            summary = "Summary",
            content = realContent,
            sources = listOf(AgentSourceCitation("Source", url))
        )

        val claim = AgentClaim(
            taskId = "task-1",
            text = "The sky is red according to the source.",
            type = AgentClaimType.FACT,
            confidence = 1.0,
            support = AgentClaimSupport.SUPPORTED,
            supportingEvidenceIds = listOf(evidenceId),
            sourceUrls = listOf(url)
        )

        val goal = AgentGoal(
            id = "goal-1",
            conversationId = "conv-1",
            userRequest = "Find colors",
            title = "Colors",
            objective = "Find colors",
            finalOutputDescription = "Report",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "model-1",
            executionModelId = "model-1",
            tasks = listOf(
                AgentTask(
                    id = "task-1",
                    title = "Task 1",
                    instructions = "Search for colors",
                    order = 0,
                    capability = AgentCapability.WEB_RESEARCH,
                    status = AgentTaskStatus.COMPLETED
                )
            ),
            attempts = listOf(
                AgentAttempt(
                    taskId = "task-1",
                    modelId = "model-1",
                    webSearchRequests = 1,
                    status = AgentAttemptStatus.SUCCEEDED,
                    startedAt = System.currentTimeMillis()
                )
            ),
            evidence = listOf(evidence),
            evidenceLinks = listOf(AgentEvidenceLink(claimId = claim.id, evidenceId = evidenceId, relation = AgentEvidenceRelation.SUPPORTS)),
            claims = listOf(claim)
        )

        val verification = AgentVerificationResult(
            passed = true,
            qualityScore = 1.0,
            summary = "OK",
            missingRequirements = emptyList(),
            acceptanceChecks = emptyList(),
            claimReviews = emptyList(),
            correctionInstructions = null,
            finalAnswer = "The sky is red.",
            conceptCandidates = emptyList(),
            apiSummary = AgentApiSummary()
        )

        // Current implementation: This should PASS because it doesn't check the content
        val decision = AgentIntegrityEvaluator.evaluate(goal, verification)
        assertTrue("Expected current implementation to pass despite fabricated claim content", decision.passed)

        // New implementation: Validate via ResearchQualityGate (which uses CitationValidator)
        val result = AgentStepResult(
            content = verification.finalAnswer,
            summary = AgentApiSummary(),
            sources = evidence.sources.map { it.copy(excerpt = "The sky is red") }, // Fabricated excerpt
            claims = listOf(claim)
        )
        
        val qualityDecision = ResearchQualityGate.evaluateStep(
            task = goal.tasks.first(),
            result = result,
            goal = goal
        )
        assertFalse("Expected ResearchQualityGate to detect fabricated excerpt", qualityDecision.passed)
        assertTrue(qualityDecision.reasons.any { it.contains("failed semantic verification") })
    }
    
    @Test
    fun `test validator detects unverified url`() {
        val url = "https://fabricated.com/fake"
        val claim = AgentClaim(
            taskId = "task-1",
            text = "Fake fact",
            type = AgentClaimType.FACT,
            confidence = 1.0,
            support = AgentClaimSupport.SUPPORTED,
            sourceUrls = listOf(url)
        )
        val result = AgentStepResult(
            content = "Fake",
            summary = AgentApiSummary(),
            claims = listOf(claim)
        )
        val validation = CitationValidator.validateStepResult(result, emptyList())
        assertFalse("Expected validator to detect unverified URL", validation.isValid)
        assertTrue(validation.reasons.any { it.contains("cites URL not present in durable evidence") })
    }
}
