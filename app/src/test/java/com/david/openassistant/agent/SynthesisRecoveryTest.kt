package com.david.openassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SynthesisRecoveryTest {
    @Test
    fun evidenceBoundaryQueuesFocusedResearchAndDeterministicAnalysis() {
        val synthesis = synthesisTask(status = AgentTaskStatus.FAILED, attemptCount = 3)
        val result = boundedSynthesisResult(synthesis)
        val decision = synthesisGapDecision(
            synthesis,
            result,
            ResearchQualityGate.evaluateStep(synthesis, result, null).reasons,
        )

        assertTrue("Should have actionable gap", decision.hasActionableGap)
        assertTrue("Should require deterministic analysis", decision.requiresDeterministicAnalysis)
        assertTrue("Should qualify for bounded publication", decision.qualifiesForBoundedPublication)

        val reason = AgentTask(
            id = "reason",
            order = 0,
            title = "Define the measurement",
            instructions = "Define the requested measurement.",
            capability = AgentCapability.REASON,
            status = AgentTaskStatus.COMPLETED,
        )
        val goal = AgentGoal(
            id = "goal-1",
            conversationId = "conv-1",
            userRequest = " elevation",
            title = "Elevation",
            objective = "Elevation",
            finalOutputDescription = "Report",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "model",
            executionModelId = "model",
            tasks = listOf(synthesis, reason)
        )
        val mutation = insertSynthesisGapRecovery(goal, synthesis.id, decision, reason.id, System.currentTimeMillis())

        // 1. researchTask, 2. analysisTask, 3. resumedSynthesisTask, 4. original reason task
        assertEquals(4, mutation.tasks.size)
        assertTrue(mutation.tasks.any { it.capability == AgentCapability.DEEP_RESEARCH })
        assertTrue(mutation.tasks.any { it.capability == AgentCapability.SYNTHESIZE })
        assertEquals(1, mutation.round) // First recovery pass
    }

    @Test
    fun unavailableHostedAnalysisChangesAngleInsteadOfBlockingTheMission() {
        val synthesis = synthesisTask(status = AgentTaskStatus.FAILED, attemptCount = 1)
        val result = AgentStepResult(
            content = "The requested GIS analysis is unavailable due to server-side constraints.",
            summary = AgentApiSummary(),
            acceptanceChecks = listOf(
                AgentAcceptanceCheck(
                    criterionId = "exact_elevation",
                    status = AgentAcceptanceCheckStatus.PARTIAL,
                    score = 0.5,
                    explanation = "GIS analysis unavailable.",
                ),
            ),
        )

        val decision = synthesisGapDecision(
            synthesis,
            result,
            ResearchQualityGate.evaluateStep(synthesis, result, null).reasons,
        )

        assertFalse("Ungrounded failure should not trigger research gap recovery", decision.hasActionableGap)
    }

    private fun synthesisTask(
        status: AgentTaskStatus = AgentTaskStatus.RUNNING,
        attemptCount: Int = 0,
    ) = AgentTask(
        id = "synthesis",
        order = 10,
        title = "Synthesize the answer",
        instructions = "Provide a cited answer.",
        capability = AgentCapability.SYNTHESIZE,
        status = status,
        attemptCount = attemptCount,
        acceptanceCriteria = listOf(
            AgentAcceptanceCriterion(
                id = "exact_elevation",
                description = "Identify the highest natural-land elevation inside the current municipal boundary.",
            ),
        ),
    )

    private fun sourceRead(url: String): SourceRead {
        val content = "Factual finding from $url."
        val hash = FingerprintUtils.hash(content)
        return SourceRead(
            id = scopedSourceReadId(url, hash),
            url = url,
            canonicalUrl = ResearchQualityGate.canonicalSourceUrl(url),
            documentId = scopedSourceDocumentId(url),
            contentHash = hash,
            httpCode = 200,
            contentType = "text/plain",
            content = content,
            sourceRole = "research",
            authorityScore = 10,
            provenance = SourceReadProvenance.VERIFIED_FETCH
        )
    }

    private fun factClaim(taskId: String, url: String, id: String): AgentClaim {
        val text = "Factual finding from $url."
        val content = "Factual finding from $url."
        val hash = FingerprintUtils.hash(content)
        val readId = scopedSourceReadId(url, hash)
        val docId = scopedSourceDocumentId(url)
        return AgentClaim(
            id = id,
            taskId = taskId,
            text = text,
            type = AgentClaimType.FACT,
            confidence = 0.9,
            support = AgentClaimSupport.SUPPORTED,
            sourceUrls = listOf(url),
            claimFingerprint = "fp-$id",
            citationBindings = listOf(
                CitationBinding.createLegacy(
                    claimId = id,
                    sourceReadId = readId,
                    documentId = docId,
                    contentHash = hash,
                    citationExcerpt = "finding",
                    passageStart = 8,
                    passageEnd = 15,
                    passageHash = FingerprintUtils.hash("finding"),
                    bindingMethod = CitationBindingMethod.EXACT
                )
            )
        )
    }

    private fun boundedSynthesisResult(task: AgentTask) = AgentStepResult(
        content = (
            "The official benchmark record establishes an elevation candidate of 808.59 feet and supplies a traceable datum. " +
                "Boundary metadata and public map layers were examined from several angles. The benchmark is therefore the strongest located candidate, " +
                "but boundary inclusion and natural-ground status cannot be verified without a GIS overlay. "
            ).repeat(10),
        summary = AgentApiSummary(),
        sources = listOf(
            AgentSourceCitation("Official benchmark", SOURCE_URL, "finding"),
            AgentSourceCitation("Municipal boundary service", "https://city.example.gov/city-boundary", "finding"),
        ),
        sourceReads = listOf(
            sourceRead(SOURCE_URL),
            sourceRead("https://city.example.gov/city-boundary"),
            sourceRead("https://records.example.gov/datum")
        ),
        completionScore = 0.90,
        acceptanceChecks = listOf(
            AgentAcceptanceCheck(
                criterionId = task.acceptanceCriteria.single().id,
                status = AgentAcceptanceCheckStatus.PARTIAL,
                score = 0.80,
                explanation = "The 808.59-foot benchmark is the best available candidate, but boundary inclusion cannot be verified without a GIS overlay.",
            ),
        ),
        claims = listOf(
            factClaim(task.id, SOURCE_URL, "benchmark"),
            factClaim(task.id, "https://city.example.gov/city-boundary", "boundary"),
            factClaim(task.id, "https://records.example.gov/datum", "datum")
        ),
    )

    private companion object {
        const val SOURCE_URL = "https://records.example.gov/benchmark/80859"
    }
}
