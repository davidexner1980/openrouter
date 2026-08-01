package com.david.openassistant

import com.david.openassistant.agent.AgentAcceptanceCheck
import com.david.openassistant.agent.AgentAcceptanceCheckStatus
import com.david.openassistant.agent.AgentAcceptanceCriterion
import com.david.openassistant.agent.AgentApiSummary
import com.david.openassistant.agent.AgentAttempt
import com.david.openassistant.agent.AgentAttemptStatus
import com.david.openassistant.agent.AgentCapability
import com.david.openassistant.agent.AgentClaim
import com.david.openassistant.agent.AgentClaimReview
import com.david.openassistant.agent.AgentClaimSupport
import com.david.openassistant.agent.AgentClaimType
import com.david.openassistant.agent.AgentEvidence
import com.david.openassistant.agent.AgentEvidenceKind
import com.david.openassistant.agent.AgentEvidenceLink
import com.david.openassistant.agent.AgentEvidenceRelation
import com.david.openassistant.agent.AgentGoal
import com.david.openassistant.agent.AgentGoalStatus
import com.david.openassistant.agent.AgentIntegrityEvaluator
import com.david.openassistant.agent.AgentLifecycleReducer
import com.david.openassistant.agent.AgentStateMachine
import com.david.openassistant.agent.AgentSourceCitation
import com.david.openassistant.agent.AgentTask
import com.david.openassistant.agent.AgentTaskStatus
import com.david.openassistant.agent.AgentVerificationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CognitiveRuntimeTest {
    @Test
    fun queuedGoalCanMoveDirectlyToFinalVerification() {
        assertTrue(
            AgentStateMachine.canTransition(AgentGoalStatus.QUEUED, AgentGoalStatus.VERIFYING),
        )
    }

    @Test
    fun sourcedEvidenceRequiresStructuredClaimsEvenWithoutResearchTaskLabel() {
        val criterion = AgentAcceptanceCriterion("complete", "The result is complete.", 1.0)
        val goal = baseGoal(
            tasks = listOf(completedTask(capability = AgentCapability.SYNTHESIZE)),
            acceptanceCriteria = listOf(criterion),
            acceptanceChecks = listOf(passingCheck(criterion)),
            evidence = listOf(evidence()),
        )

        val decision = AgentIntegrityEvaluator.evaluate(goal, passingVerification(criterion))

        assertFalse(decision.passed)
        assertTrue(decision.reasons.any { it.contains("no structured claims") })
    }

    @Test
    fun researchFactCannotRelyOnlyOnUnsourcedModelOutput() {
        val criterion = AgentAcceptanceCriterion("complete", "The result is complete.", 1.0)
        val sourceEvidence = evidence(id = "source", sourced = true)
        val modelEvidence = evidence(id = "model", sourced = false)
        val claim = factClaim(modelEvidence, support = AgentClaimSupport.SUPPORTED)
        val goal = baseGoal(
            tasks = listOf(completedTask(capability = AgentCapability.WEB_RESEARCH)),
            acceptanceCriteria = listOf(criterion),
            acceptanceChecks = listOf(passingCheck(criterion)),
            evidence = listOf(sourceEvidence, modelEvidence),
            claims = listOf(claim),
            links = listOf(link(claim, modelEvidence)),
        )

        val decision = AgentIntegrityEvaluator.evaluate(goal, passingVerification(criterion))

        assertFalse(decision.passed)
        assertTrue(decision.reasons.any { it.contains("no preserved source URL") })
    }

    @Test
    fun nonResearchArtifactFactCanPassIndependentVerification() {
        val criterion = AgentAcceptanceCriterion("complete", "The result is complete.", 1.0)
        val modelEvidence = evidence(id = "model", sourced = false)
        val claim = factClaim(modelEvidence, support = AgentClaimSupport.SUPPORTED)
        val goal = baseGoal(
            tasks = listOf(completedTask(capability = AgentCapability.SYNTHESIZE)),
            acceptanceCriteria = listOf(criterion),
            acceptanceChecks = listOf(passingCheck(criterion)),
            evidence = listOf(modelEvidence),
            claims = listOf(claim),
            links = listOf(link(claim, modelEvidence)),
        )

        val decision = AgentIntegrityEvaluator.evaluate(goal, passingVerification(criterion))

        assertTrue(decision.reasons.joinToString(), decision.passed)
    }

    @Test
    fun denseProgressUsesMilestoneWeights() {
        val goal = baseGoal(
            tasks = listOf(
                completedTask(id = "small", weight = 1.0, score = 1.0),
                completedTask(id = "large", weight = 3.0, score = 0.5),
            ),
        )

        assertEquals(0.625, goal.denseProgressScore, 0.000001)
    }

    @Test
    fun publicationRejectsPartiallySupportedFacts() {
        val criterion = AgentAcceptanceCriterion("complete", "The result is complete.", 1.0)
        val evidence = evidence()
        val claim = factClaim(evidence, support = AgentClaimSupport.PARTIAL)
        val goal = baseGoal(
            tasks = listOf(completedTask()),
            acceptanceCriteria = listOf(criterion),
            acceptanceChecks = listOf(passingCheck(criterion)),
            evidence = listOf(evidence),
            claims = listOf(claim),
            links = listOf(link(claim, evidence)),
        )

        val decision = AgentIntegrityEvaluator.evaluate(goal, passingVerification(criterion))

        assertFalse(decision.passed)
        assertTrue(decision.reasons.any { it.contains("not fully supported") })
    }

    @Test
    fun publicationPassesOnlyWhenFactIsPreservedAndLinked() {
        val criterion = AgentAcceptanceCriterion("complete", "The result is complete.", 1.0)
        val evidence = evidence()
        val claim = factClaim(evidence, support = AgentClaimSupport.SUPPORTED)
        val goal = baseGoal(
            tasks = listOf(completedTask()),
            acceptanceCriteria = listOf(criterion),
            acceptanceChecks = listOf(passingCheck(criterion)),
            evidence = listOf(evidence),
            claims = listOf(claim),
            links = listOf(link(claim, evidence)),
        )

        val decision = AgentIntegrityEvaluator.evaluate(goal, passingVerification(criterion))

        assertTrue(decision.reasons.joinToString(), decision.passed)
    }

    @Test
    fun publicationRejectsAnImpossibleDerivedMeasurement() {
        val criterion = AgentAcceptanceCriterion("complete", "The result is complete.", 1.0)
        val sourceEvidence = evidence()
        val claim = factClaim(sourceEvidence, support = AgentClaimSupport.SUPPORTED).copy(
            text = "Testing recorded 154 fps with a 567-grain arrow, delivering roughly 115 ft-lb.",
        )
        val goal = baseGoal(
            tasks = listOf(completedTask()),
            acceptanceCriteria = listOf(criterion),
            acceptanceChecks = listOf(passingCheck(criterion)),
            evidence = listOf(sourceEvidence),
            claims = listOf(claim),
            links = listOf(link(claim, sourceEvidence)),
        )

        val decision = AgentIntegrityEvaluator.evaluate(goal, passingVerification(criterion))

        assertFalse(decision.passed)
        assertTrue(decision.reasons.any { it.contains("derived measurement inconsistency") })
    }

    @Test
    fun publicationRejectsDuplicateClaimIdsBeforeReviewLinksBecomeAmbiguous() {
        val criterion = AgentAcceptanceCriterion("complete", "The result is complete.", 1.0)
        val sourceEvidence = evidence()
        val first = factClaim(sourceEvidence, support = AgentClaimSupport.SUPPORTED)
        val second = first.copy(text = "A different factual statement reused the same ID.")
        val goal = baseGoal(
            tasks = listOf(completedTask()),
            acceptanceCriteria = listOf(criterion),
            acceptanceChecks = listOf(passingCheck(criterion)),
            evidence = listOf(sourceEvidence),
            claims = listOf(first, second),
            links = listOf(link(first, sourceEvidence)),
        )

        val decision = AgentIntegrityEvaluator.evaluate(goal, passingVerification(criterion))

        assertFalse(decision.passed)
        assertTrue(decision.reasons.any { it.startsWith("Structured claim IDs are not unique") })
    }

    @Test
    fun webResearchCannotPublishWithoutStructuredClaims() {
        val criterion = AgentAcceptanceCriterion("complete", "The result is complete.", 1.0)
        val researchTask = completedTask(capability = AgentCapability.WEB_RESEARCH)
        val goal = baseGoal(
            tasks = listOf(researchTask),
            acceptanceCriteria = listOf(criterion),
            acceptanceChecks = listOf(passingCheck(criterion)),
            evidence = listOf(evidence()),
        )

        val decision = AgentIntegrityEvaluator.evaluate(goal, passingVerification(criterion))

        assertFalse(decision.passed)
        assertTrue(decision.reasons.any { it.contains("no structured claims") })
    }

    @Test
    fun independentReviewCanDowngradeAClaim() {
        val evidence = evidence()
        val claim = factClaim(evidence, support = AgentClaimSupport.SUPPORTED)

        val reviewed = AgentIntegrityEvaluator.applyClaimReviews(
            listOf(claim),
            listOf(AgentClaimReview(claim.id, AgentClaimSupport.CONTRADICTED, "A later source conflicts.")),
        )

        assertEquals(AgentClaimSupport.CONTRADICTED, reviewed.single().support)
        assertEquals("A later source conflicts.", reviewed.single().reviewExplanation)
    }

    @Test
    fun researchFactRequiresAnExplicitPreservedSourceUrl() {
        val criterion = AgentAcceptanceCriterion("complete", "The result is complete.", 1.0)
        val sourceEvidence = evidence()
        val claim = factClaim(sourceEvidence, support = AgentClaimSupport.SUPPORTED).copy(sourceUrls = emptyList())
        val goal = baseGoal(
            tasks = listOf(completedTask(capability = AgentCapability.WEB_RESEARCH)),
            acceptanceCriteria = listOf(criterion),
            acceptanceChecks = listOf(passingCheck(criterion)),
            evidence = listOf(sourceEvidence),
            claims = listOf(claim),
            links = listOf(link(claim, sourceEvidence)),
        )

        val decision = AgentIntegrityEvaluator.evaluate(goal, passingVerification(criterion))

        assertFalse(decision.passed)
        assertTrue(decision.reasons.any { it.contains("no preserved source URL") })
    }

    @Test
    fun contradictionEdgeCannotSatisfyFactualSupport() {
        val criterion = AgentAcceptanceCriterion("complete", "The result is complete.", 1.0)
        val sourceEvidence = evidence()
        val claim = factClaim(sourceEvidence, support = AgentClaimSupport.SUPPORTED)
        val goal = baseGoal(
            tasks = listOf(completedTask(capability = AgentCapability.WEB_RESEARCH)),
            acceptanceCriteria = listOf(criterion),
            acceptanceChecks = listOf(passingCheck(criterion)),
            evidence = listOf(sourceEvidence),
            claims = listOf(claim),
            links = listOf(link(claim, sourceEvidence).copy(relation = AgentEvidenceRelation.CONTRADICTS)),
        )

        val decision = AgentIntegrityEvaluator.evaluate(goal, passingVerification(criterion))

        assertFalse(decision.passed)
        assertTrue(decision.reasons.any { it.contains("missing a SUPPORTS link") })
    }

    @Test
    fun independentReviewReconcilesEvidenceEdgeMeaning() {
        val sourceEvidence = evidence()
        val claim = factClaim(sourceEvidence, support = AgentClaimSupport.PARTIAL)
        val qualifiedLink = link(claim, sourceEvidence).copy(relation = AgentEvidenceRelation.QUALIFIES)
        val supportedClaim = claim.copy(support = AgentClaimSupport.SUPPORTED)
        val contradictedClaim = claim.copy(support = AgentClaimSupport.CONTRADICTED)

        val supportedLink = AgentIntegrityEvaluator.reconcileEvidenceLinks(
            listOf(supportedClaim),
            listOf(qualifiedLink),
        ).single()
        val contradictedLink = AgentIntegrityEvaluator.reconcileEvidenceLinks(
            listOf(contradictedClaim),
            listOf(qualifiedLink),
        ).single()

        assertEquals(AgentEvidenceRelation.SUPPORTS, supportedLink.relation)
        assertEquals(AgentEvidenceRelation.CONTRADICTS, contradictedLink.relation)
    }

    @Test
    fun pausingClosesRunningAttemptWithoutSpendingRetry() {
        val runningTask = completedTask(id = "running").copy(
            status = AgentTaskStatus.RUNNING,
            attemptCount = 1,
            progressScore = 0.0,
        )
        val runningAttempt = AgentAttempt(
            id = "attempt",
            taskId = runningTask.id,
            status = AgentAttemptStatus.RUNNING,
            startedAt = 100L,
            modelId = "executor",
        )
        val goal = baseGoal(tasks = listOf(runningTask)).copy(
            status = AgentGoalStatus.RUNNING,
            attempts = listOf(runningAttempt),
        )

        val paused = AgentLifecycleReducer.pause(goal, now = 200L)

        assertEquals(AgentGoalStatus.PAUSED, paused.status)
        assertEquals(AgentTaskStatus.QUEUED, paused.tasks.single().status)
        assertEquals(0, paused.tasks.single().attemptCount)
        assertEquals(AgentAttemptStatus.FAILED, paused.attempts.single().status)
        assertEquals(200L, paused.attempts.single().finishedAt)
    }

    @Test
    fun cancellingClosesRunningAttemptAndCancelsUnfinishedTask() {
        val runningTask = completedTask(id = "running").copy(
            status = AgentTaskStatus.RUNNING,
            attemptCount = 1,
            progressScore = 0.0,
        )
        val runningAttempt = AgentAttempt(
            id = "attempt",
            taskId = runningTask.id,
            status = AgentAttemptStatus.RUNNING,
            startedAt = 100L,
            modelId = "executor",
        )
        val goal = baseGoal(tasks = listOf(runningTask)).copy(
            status = AgentGoalStatus.RUNNING,
            attempts = listOf(runningAttempt),
        )

        val cancelled = AgentLifecycleReducer.cancel(goal, now = 200L)

        assertEquals(AgentGoalStatus.CANCELLED, cancelled.status)
        assertEquals(AgentTaskStatus.CANCELLED, cancelled.tasks.single().status)
        assertEquals(AgentAttemptStatus.FAILED, cancelled.attempts.single().status)
        assertEquals(200L, cancelled.attempts.single().finishedAt)
    }

    @Test
    fun interruptedVerificationReturnsToQueue() {
        val goal = baseGoal(tasks = listOf(completedTask())).copy(
            status = AgentGoalStatus.VERIFYING,
        )

        val recovered = AgentLifecycleReducer.recoverInterruptedWork(goal, now = 200L)

        assertEquals(AgentGoalStatus.QUEUED, recovered.status)
        assertTrue(recovered.events.last().message.contains("verification"))
    }

    @Test
    fun incompleteGoalIsNeverReadyForVerification() {
        val incomplete = baseGoal(
            tasks = listOf(completedTask().copy(status = AgentTaskStatus.QUEUED, progressScore = 0.6)),
        )
        val complete = baseGoal(tasks = listOf(completedTask()))

        assertFalse(incomplete.isReadyForVerification)
        assertTrue(complete.isReadyForVerification)
    }

    private fun baseGoal(
        tasks: List<AgentTask>,
        acceptanceCriteria: List<AgentAcceptanceCriterion> = emptyList(),
        acceptanceChecks: List<AgentAcceptanceCheck> = emptyList(),
        evidence: List<AgentEvidence> = emptyList(),
        claims: List<AgentClaim> = emptyList(),
        links: List<AgentEvidenceLink> = emptyList(),
    ) = AgentGoal(
        conversationId = "conversation",
        userRequest = "Produce a grounded result",
        title = "Grounded result",
        objective = "Produce it",
        finalOutputDescription = "A complete result",
        status = AgentGoalStatus.VERIFYING,
        plannerModelId = "planner",
        executionModelId = "executor",
        tasks = tasks,
        acceptanceCriteria = acceptanceCriteria,
        acceptanceChecks = acceptanceChecks,
        evidence = evidence,
        claims = claims,
        evidenceLinks = links,
    )

    private fun completedTask(
        id: String = "task",
        weight: Double = 1.0,
        score: Double = 1.0,
        capability: AgentCapability = AgentCapability.SYNTHESIZE,
    ) = AgentTask(
        id = id,
        order = 0,
        title = "Complete work",
        instructions = "Complete it",
        capability = capability,
        status = AgentTaskStatus.COMPLETED,
        weight = weight,
        progressScore = score,
    )

    private fun evidence(
        id: String = "evidence-1",
        sourced: Boolean = true,
    ) = AgentEvidence(
        id = id,
        kind = AgentEvidenceKind.WEB_RESEARCH,
        title = "Primary source",
        summary = "Source summary",
        content = "Grounded source content",
        sources = if (sourced) {
            listOf(AgentSourceCitation("Official source", "https://example.com/source/$id"))
        } else {
            emptyList()
        },
    )

    private fun factClaim(evidence: AgentEvidence, support: AgentClaimSupport) = AgentClaim(
        id = "claim-1",
        taskId = "task",
        text = "The factual statement is true.",
        type = AgentClaimType.FACT,
        confidence = 0.9,
        support = support,
        supportingEvidenceIds = listOf(evidence.id),
        sourceUrls = evidence.sources.map { it.url },
    )

    private fun link(claim: AgentClaim, evidence: AgentEvidence) = AgentEvidenceLink(
        claimId = claim.id,
        evidenceId = evidence.id,
        relation = AgentEvidenceRelation.SUPPORTS,
    )

    private fun passingCheck(criterion: AgentAcceptanceCriterion) = AgentAcceptanceCheck(
        criterionId = criterion.id,
        status = AgentAcceptanceCheckStatus.PASS,
        score = 1.0,
        explanation = "Passed.",
    )

    private fun passingVerification(criterion: AgentAcceptanceCriterion) = AgentVerificationResult(
        passed = true,
        qualityScore = 0.95,
        summary = "Verified.",
        missingRequirements = emptyList(),
        acceptanceChecks = listOf(passingCheck(criterion)),
        claimReviews = emptyList(),
        correctionInstructions = null,
        finalAnswer = "Verified final answer.",
        conceptCandidates = emptyList(),
        apiSummary = AgentApiSummary(),
    )
}
