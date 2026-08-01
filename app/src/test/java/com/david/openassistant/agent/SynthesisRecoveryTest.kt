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
            ResearchQualityGate.evaluateStep(synthesis, result).reasons,
        )

        assertTrue(decision.hasActionableGap)
        assertTrue(decision.requiresDeterministicAnalysis)
        assertTrue(decision.qualifiesForBoundedPublication)

        val reason = AgentTask(
            id = "reason",
            order = 0,
            title = "Define the measurement",
            instructions = "Define the requested measurement.",
            capability = AgentCapability.REASON,
            status = AgentTaskStatus.COMPLETED,
        )
        val finalTask = AgentTask(
            id = "post_synthesis",
            order = 2,
            title = "Prepare the final result",
            instructions = "Prepare it.",
            capability = AgentCapability.REASON,
            dependsOn = listOf(synthesis.id),
        )
        val goal = goal(
            tasks = listOf(
                reason,
                synthesis.copy(order = 1, dependsOn = listOf(reason.id)),
                finalTask,
            ),
        )

        val mutation = insertSynthesisGapRecovery(
            goal = goal,
            synthesisTaskId = synthesis.id,
            decision = decision,
            preciseFailure = "The exact elevation was not fully verified.",
            now = 123L,
        )
        val updated = mutation.tasks
        val research = updated.single { it.id.startsWith(SYNTHESIS_GAP_RESEARCH_PREFIX) }
        val analysis = updated.single { it.id.startsWith(SYNTHESIS_GAP_ANALYSIS_PREFIX) }
        val resumed = updated.single { it.id == synthesis.id }

        assertEquals(1, mutation.round)
        assertEquals(AgentCapability.DEEP_RESEARCH, research.capability)
        assertEquals(AgentCapability.TOOL_USE, analysis.capability)
        assertTrue(analysis.dependsOn.contains(research.id))
        assertTrue(resumed.dependsOn.contains(analysis.id))
        assertEquals(0, resumed.attemptCount)
        assertEquals(AgentTaskStatus.FAILED, resumed.status)
        assertEquals(research.id, goal.copy(tasks = updated).nextRunnableTask?.id)

        val orderById = updated.associate { it.id to it.order }
        assertTrue(
            updated.all { task ->
                task.dependsOn.all { dependency ->
                    (orderById[dependency] ?: Int.MAX_VALUE) < task.order
                }
            },
        )
    }

    @Test
    fun boundedConclusionPassesOnlyAfterRecoveryAndKeepsIntegrityStrict() {
        val criterion = AgentAcceptanceCriterion(
            id = "exact_elevation",
            description = "Identify the best-supported highest land elevation and its evidentiary precision.",
        )
        val evidence = AgentEvidence(
            id = "publication_evidence",
            taskId = "synthesis",
            kind = AgentEvidenceKind.MODEL_OUTPUT,
            title = "Bounded synthesis",
            summary = "Best-supported conclusion after alternate-angle recovery.",
            content = "The official benchmark is the strongest located candidate, while the available records do not establish natural-ground and municipal-boundary inclusion.",
            sources = listOf(AgentSourceCitation("Official benchmark record", SOURCE_URL)),
        )
        val claim = AgentClaim(
            id = "highest_candidate",
            taskId = "synthesis",
            text = "The official benchmark is the strongest located elevation candidate.",
            type = AgentClaimType.FACT,
            confidence = 0.88,
            support = AgentClaimSupport.SUPPORTED,
            supportingEvidenceIds = listOf(evidence.id),
            sourceUrls = listOf(SOURCE_URL),
        )
        val completedSynthesis = synthesisTask().copy(
            status = AgentTaskStatus.COMPLETED,
            progressScore = 0.90,
            acceptanceCriteria = listOf(criterion),
            outputEvidenceId = evidence.id,
        )
        val goal = goal(
            tasks = listOf(completedSynthesis),
            acceptanceCriteria = listOf(criterion),
            evidence = listOf(evidence),
            claims = listOf(claim),
            evidenceLinks = listOf(
                AgentEvidenceLink(
                    claimId = claim.id,
                    evidenceId = evidence.id,
                    relation = AgentEvidenceRelation.SUPPORTS,
                ),
            ),
            events = listOf(
                AgentEvent(message = "$EPISTEMIC_BOUNDARY_EVENT_PREFIX focused recovery completed"),
            ),
        )
        val partialCheck = AgentAcceptanceCheck(
            criterionId = criterion.id,
            status = AgentAcceptanceCheckStatus.PARTIAL,
            score = 0.80,
            explanation = "The best available record is supported, but exact natural-ground status cannot be confirmed from the published dataset.",
        )
        val verification = AgentVerificationResult(
            passed = false,
            qualityScore = 0.90,
            summary = "The strongest supportable result is useful and appropriately bounded.",
            missingRequirements = listOf(
                "Exact natural-ground status cannot be confirmed from the published dataset.",
            ),
            acceptanceChecks = listOf(partialCheck),
            claimReviews = listOf(
                AgentClaimReview(claim.id, AgentClaimSupport.SUPPORTED, "The cited record supports it."),
            ),
            correctionInstructions = null,
            finalAnswer = "The cited benchmark is the best-supported candidate; its natural-ground status remains unverified.",
            conceptCandidates = emptyList(),
            apiSummary = AgentApiSummary(),
        )

        assertTrue(acceptsEpistemicallyBoundedVerification(goal, verification))
        assertTrue(AgentIntegrityEvaluator.evaluate(goal, verification).passed)

        val exhaustedSearch = verification.copy(
            qualityScore = 0.66,
            missingRequirements = listOf(
                "A consensus of at least five independent sources is required.",
                "Specific measured performance metrics are required.",
            ),
            acceptanceChecks = listOf(
                partialCheck.copy(
                    status = AgentAcceptanceCheckStatus.FAIL,
                    score = 0.30,
                    explanation = "No independent consensus was found after documented alternate-angle research.",
                ),
            ),
        )
        assertTrue(acceptsEpistemicallyBoundedVerification(goal, exhaustedSearch))
        assertTrue(AgentIntegrityEvaluator.evaluate(goal, exhaustedSearch).passed)

        val missingCitation = verification.copy(
            missingRequirements = listOf("The answer is missing a source citation."),
        )
        assertFalse(acceptsEpistemicallyBoundedVerification(goal, missingCitation))
        assertFalse(AgentIntegrityEvaluator.evaluate(goal, missingCitation).passed)

        val unsupported = verification.copy(
            claimReviews = listOf(
                AgentClaimReview(claim.id, AgentClaimSupport.UNSUPPORTED, "The source does not support it."),
            ),
        )
        assertFalse(AgentIntegrityEvaluator.evaluate(goal, unsupported).passed)
    }

    @Test
    fun weakOrMalformedSynthesisCannotSpendTheGapRecoveryBudget() {
        val synthesis = synthesisTask()
        val weak = AgentStepResult(
            content = "I could not determine the answer.",
            summary = AgentApiSummary(),
            completionScore = 0.90,
            acceptanceChecks = listOf(
                AgentAcceptanceCheck(
                    criterionId = synthesis.acceptanceCriteria.single().id,
                    status = AgentAcceptanceCheckStatus.PARTIAL,
                    score = 0.80,
                    explanation = "The exact result is uncertain.",
                ),
            ),
            unresolvedQuestions = listOf("The result is uncertain."),
        )

        val decision = synthesisGapDecision(
            synthesis,
            weak,
            ResearchQualityGate.evaluateStep(synthesis, weak).reasons,
        )

        assertFalse(decision.hasActionableGap)
        assertFalse(decision.qualifiesForBoundedPublication)
    }

    @Test
    fun unavailableHostedAnalysisChangesAngleInsteadOfBlockingTheMission() {
        val synthesis = synthesisTask(status = AgentTaskStatus.FAILED, attemptCount = 3)
        val result = boundedSynthesisResult(synthesis)
        val decision = synthesisGapDecision(
            synthesis,
            result,
            ResearchQualityGate.evaluateStep(synthesis, result).reasons,
        )
        val freeRouteGoal = goal(
            tasks = listOf(synthesis.copy(order = 0)),
            executionModelId = "cohere/north-mini-code:free",
        )

        val freeRouteMutation = insertSynthesisGapRecovery(
            goal = freeRouteGoal,
            synthesisTaskId = synthesis.id,
            decision = decision,
            preciseFailure = "A GIS overlay is still needed.",
        )

        assertTrue(freeRouteMutation.tasks.none(::isSynthesisGapAnalysisTask))
        val freeResearch = freeRouteMutation.tasks.single {
            it.id.startsWith(SYNTHESIS_GAP_RESEARCH_PREFIX)
        }
        assertTrue(freeResearch.instructions.contains("official GIS/REST"))

        val unavailableAnalysis = AgentTask(
            id = "${SYNTHESIS_GAP_ANALYSIS_PREFIX}1",
            order = 2,
            title = "Analyze with the hosted workbench",
            instructions = "Perform the GIS overlay.",
            capability = AgentCapability.TOOL_USE,
            attemptCount = MAX_REQUIRED_TOOL_MILESTONE_ATTEMPTS,
        )
        val rerouted = unavailableAnalysis.rerouteUnavailableSynthesisAnalysis(
            preciseFailure = "The selected provider did not support the hosted shell.",
            now = 456L,
        )

        assertEquals(AgentCapability.DEEP_RESEARCH, rerouted.capability)
        assertEquals(AgentTaskStatus.FAILED, rerouted.status)
        assertEquals(0, rerouted.attemptCount)
        assertTrue(rerouted.instructions.contains("do not stop the mission", ignoreCase = true))
        assertTrue(rerouted.acceptanceCriteria.size >= 2)
    }

    private fun synthesisTask(
        status: AgentTaskStatus = AgentTaskStatus.PLANNED,
        attemptCount: Int = 0,
    ) = AgentTask(
        id = "synthesis",
        order = 4,
        title = "Synthesize the verified elevation result",
        instructions = "Give the best-supported elevation inside the current municipal boundary.",
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

    private fun boundedSynthesisResult(task: AgentTask) = AgentStepResult(
        content = (
            "The official benchmark record establishes an elevation candidate of 808.59 feet and supplies a traceable datum. " +
                "Boundary metadata and public map layers were examined from several angles. The benchmark is therefore the strongest located candidate, " +
                "but the available records do not establish both present municipal-boundary inclusion and natural-ground status. A GIS overlay of the " +
                "benchmark coordinates, current city polygon, and a bare-earth elevation model is required for an exact answer. The conclusion is stated " +
                "at that precision so the supported measurement is not converted into a stronger claim than the evidence permits. "
            ).repeat(2),
        summary = AgentApiSummary(),
        sources = listOf(
            AgentSourceCitation("Official benchmark", SOURCE_URL),
            AgentSourceCitation("Municipal boundary service", "https://gis.example.gov/city-boundary"),
        ),
        completionScore = 0.90,
        acceptanceChecks = listOf(
            AgentAcceptanceCheck(
                criterionId = task.acceptanceCriteria.single().id,
                status = AgentAcceptanceCheckStatus.PARTIAL,
                score = 0.80,
                explanation = "The 808.59-foot benchmark is the best available candidate, but boundary inclusion and natural-ground status cannot be verified without a GIS overlay.",
            ),
        ),
        claims = listOf(
            AgentClaim(
                id = "benchmark",
                taskId = task.id,
                text = "The official record lists the benchmark at 808.59 feet.",
                type = AgentClaimType.FACT,
                confidence = 0.95,
                support = AgentClaimSupport.SUPPORTED,
                sourceUrls = listOf(SOURCE_URL),
            ),
            AgentClaim(
                id = "boundary",
                taskId = task.id,
                text = "Its natural-ground and present municipal-boundary status remain unresolved.",
                type = AgentClaimType.UNCERTAINTY,
                confidence = 0.90,
                support = AgentClaimSupport.PARTIAL,
                sourceUrls = listOf("https://gis.example.gov/city-boundary"),
            ),
            AgentClaim(
                id = "datum",
                taskId = task.id,
                text = "The datum used is NAVD88 as recorded in the official survey.",
                type = AgentClaimType.FACT,
                confidence = 0.98,
                support = AgentClaimSupport.SUPPORTED,
                sourceUrls = listOf(SOURCE_URL),
            ),
        ),
        unresolvedQuestions = listOf(
            "A GIS overlay is required to confirm boundary inclusion and natural-ground status.",
        ),
    )

    private fun goal(
        tasks: List<AgentTask>,
        acceptanceCriteria: List<AgentAcceptanceCriterion> = emptyList(),
        evidence: List<AgentEvidence> = emptyList(),
        claims: List<AgentClaim> = emptyList(),
        evidenceLinks: List<AgentEvidenceLink> = emptyList(),
        events: List<AgentEvent> = emptyList(),
        executionModelId: String = "executor",
    ) = AgentGoal(
        conversationId = "conversation",
        userRequest = "What is the highest land elevation in Denton, Texas?",
        title = "Denton elevation",
        objective = "Find the strongest supportable answer.",
        finalOutputDescription = "A cited answer with stated precision.",
        status = AgentGoalStatus.RUNNING,
        plannerModelId = "planner",
        executionModelId = executionModelId,
        tasks = tasks,
        acceptanceCriteria = acceptanceCriteria,
        evidence = evidence,
        claims = claims,
        evidenceLinks = evidenceLinks,
        events = events,
    )

    private companion object {
        const val SOURCE_URL = "https://records.example.gov/benchmark/80859"
    }
}
