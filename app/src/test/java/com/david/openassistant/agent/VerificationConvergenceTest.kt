package com.david.openassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationConvergenceTest {
    @Test
    fun publicationGraphKeepsOnlyPublishableClaimsAndTheirLinks() {
        val supportedFact = claim("supported_fact", AgentClaimType.FACT, AgentClaimSupport.SUPPORTED)
        val partialFact = claim("partial_fact", AgentClaimType.FACT, AgentClaimSupport.PARTIAL)
        val partialInference = claim("partial_inference", AgentClaimType.INFERENCE, AgentClaimSupport.PARTIAL)
        val unsupportedRecommendation = claim(
            "unsupported_recommendation",
            AgentClaimType.RECOMMENDATION,
            AgentClaimSupport.UNSUPPORTED,
        )
        val contradictedUncertainty = claim(
            "contradicted_uncertainty",
            AgentClaimType.UNCERTAINTY,
            AgentClaimSupport.CONTRADICTED,
        )
        val claims = listOf(
            supportedFact,
            partialFact,
            partialInference,
            unsupportedRecommendation,
            contradictedUncertainty,
        )
        val links = claims.map { item ->
            AgentEvidenceLink(
                id = "link_${item.id}",
                claimId = item.id,
                evidenceId = "evidence",
                relation = AgentEvidenceRelation.SUPPORTS,
            )
        }

        val compacted = compactPublicationGraph(claims, links)

        assertEquals(listOf("supported_fact", "partial_inference"), compacted.claims.map { it.id })
        assertEquals(setOf("supported_fact", "partial_inference"), compacted.evidenceLinks.map { it.claimId }.toSet())
        assertEquals(3, compacted.excludedClaimCount)
    }

    @Test
    fun evidenceCorrectionCannotReplaceTheGraphWithOnlyPartialFacts() {
        val partialFact = claim("partial", AgentClaimType.FACT, AgentClaimSupport.PARTIAL)
        val supportedFact = claim("supported", AgentClaimType.FACT, AgentClaimSupport.SUPPORTED)

        assertFalse(hasPublishableCorrectionClaims(listOf(partialFact)))
        assertTrue(hasPublishableCorrectionClaims(listOf(supportedFact)))
    }

    @Test
    fun verificationCorrectionWindowIsStrictlyBounded() {
        assertTrue(canQueueVerificationCorrection(0))
        assertTrue(canQueueVerificationCorrection(MAX_VERIFICATION_CORRECTION_PASSES - 1))
        assertFalse(canQueueVerificationCorrection(MAX_VERIFICATION_CORRECTION_PASSES))
        assertFalse(canQueueVerificationCorrection(MAX_VERIFICATION_CORRECTION_PASSES + 1))
    }

    @Test
    fun synthesisAndCorrectionReplaceTheActivePublicationClaimGraph() {
        assertTrue(replacesPublicationClaimGraph(AgentCapability.SYNTHESIZE))
        assertTrue(replacesPublicationClaimGraph(AgentCapability.CORRECT))
        assertFalse(replacesPublicationClaimGraph(AgentCapability.DEEP_RESEARCH))
        assertFalse(replacesPublicationClaimGraph(AgentCapability.REASON))
    }

    @Test
    fun verifierReceivesResearchReasoningAndOnlyTheNewestPublicationDraft() {
        val reason = AgentTask(
            id = "reason",
            order = 0,
            title = "Define the decision",
            instructions = "Define it.",
            capability = AgentCapability.REASON,
            status = AgentTaskStatus.COMPLETED,
            outputEvidenceId = "reason-evidence",
        )
        val synthesis = AgentTask(
            id = "synthesis",
            order = 5,
            title = "Synthesize",
            instructions = "Synthesize.",
            capability = AgentCapability.SYNTHESIZE,
            status = AgentTaskStatus.COMPLETED,
            outputEvidenceId = "old-publication",
        )
        val correction = AgentTask(
            id = "correction_1",
            order = 6,
            title = "Correct",
            instructions = "Correct.",
            capability = AgentCapability.CORRECT,
            status = AgentTaskStatus.COMPLETED,
            outputEvidenceId = "current-publication",
        )
        val evidence = listOf(
            evidence("reason-evidence", AgentEvidenceKind.MODEL_OUTPUT),
            evidence("research-evidence", AgentEvidenceKind.DEEP_RESEARCH),
            evidence("old-publication", AgentEvidenceKind.MODEL_OUTPUT),
            evidence("old-verification", AgentEvidenceKind.VERIFICATION),
            evidence("current-publication", AgentEvidenceKind.MODEL_OUTPUT),
            evidence("system-event", AgentEvidenceKind.SYSTEM_EVENT),
        )
        val goal = AgentGoal(
            conversationId = "conversation",
            userRequest = "request",
            title = "title",
            objective = "objective",
            finalOutputDescription = "answer",
            status = AgentGoalStatus.QUEUED,
            plannerModelId = "model",
            executionModelId = "model",
            tasks = listOf(reason, synthesis, correction),
            evidence = evidence,
        )

        val selected = selectVerificationEvidence(goal)

        assertEquals(
            listOf("reason-evidence", "research-evidence", "current-publication"),
            selected.map { it.id },
        )
    }

    @Test
    fun repeatedNonImprovingVerificationStopsBeforeAnotherCorrection() {
        val previous = VerificationConvergenceSnapshot(
            qualityScore = 0.60,
            findingCodes = setOf("verifier_rejected", "criterion_failed"),
            excludedClaimCount = 5,
        )
        val persisted = verificationEvidenceContent(
            content = "Verifier critique.",
            snapshot = previous,
            maximumCharacters = 1_000,
        )
        val recovered = latestVerificationConvergenceSnapshot(
            listOf(
                AgentEvidence(
                    id = "verification",
                    kind = AgentEvidenceKind.VERIFICATION,
                    title = "Independent verification",
                    summary = "Failed.",
                    content = persisted,
                ),
            ),
        )
        val stalled = previous.copy(qualityScore = 0.59)
        val fewerExcludedClaims = previous.copy(qualityScore = 0.59, excludedClaimCount = 4)

        assertEquals(previous, recovered)
        assertTrue(hasVerificationConvergenceStalled(recovered, stalled))
        assertFalse(hasVerificationConvergenceStalled(recovered, fewerExcludedClaims))
        assertFalse(hasVerificationConvergenceStalled(recovered, previous.copy(qualityScore = 0.62)))
    }

    @Test
    fun correctionCriteriaExcludeVerdictMetadataAndKeepActionableFindings() {
        val findings = actionableVerificationFindings(
            missingRequirements = listOf("Use the exact product source URL."),
            integrityReasons = listOf(
                "The independent verifier did not pass the result.",
                "Quality score 59% is below 80%.",
                "The verifier still reports 1 missing requirement(s).",
                "A factual claim has no preserved evidence record: claim",
            ),
        )

        assertEquals(
            listOf(
                "Use the exact product source URL.",
                "A factual claim has no preserved evidence record: claim",
            ),
            findings,
        )
    }

    @Test
    fun evidenceCorrectionHasABoundedLocalAttemptWindow() {
        val correction = AgentTask(
            id = "correction",
            order = 0,
            title = "Correct",
            instructions = "Correct from evidence.",
            capability = AgentCapability.CORRECT,
            attemptCount = MAX_CORRECTION_MILESTONE_ATTEMPTS,
        )

        assertTrue(hasExhaustedCorrectionAttemptWindow(correction, qualityAccepted = false))
        assertFalse(hasExhaustedCorrectionAttemptWindow(correction, qualityAccepted = true))
        assertFalse(
            hasExhaustedCorrectionAttemptWindow(
                correction.copy(attemptCount = MAX_CORRECTION_MILESTONE_ATTEMPTS - 1),
                qualityAccepted = false,
            ),
        )
        assertFalse(
            hasExhaustedCorrectionAttemptWindow(
                correction.copy(capability = AgentCapability.DEEP_RESEARCH),
                qualityAccepted = false,
            ),
        )
    }

    @Test
    fun compactionMakesALegacyMixedGraphSafeForPublication() {
        val criterion = AgentAcceptanceCriterion("complete", "The verified result is complete.")
        val evidence = AgentEvidence(
            id = "evidence",
            kind = AgentEvidenceKind.MODEL_OUTPUT,
            title = "Supported work",
            summary = "summary",
            content = "A fully supported result.",
            sources = listOf(AgentSourceCitation("Source", "https://example.com/supported")),
        )
        val supported = claim("supported", AgentClaimType.FACT, AgentClaimSupport.SUPPORTED)
            .copy(sourceUrls = listOf("https://example.com/supported"))
        val partial = claim("partial", AgentClaimType.FACT, AgentClaimSupport.PARTIAL)
        val links = listOf(supported, partial).map { item ->
            AgentEvidenceLink(
                claimId = item.id,
                evidenceId = evidence.id,
                relation = if (item.support == AgentClaimSupport.SUPPORTED) {
                    AgentEvidenceRelation.SUPPORTS
                } else {
                    AgentEvidenceRelation.QUALIFIES
                },
            )
        }
        val goal = AgentGoal(
            conversationId = "conversation",
            userRequest = "request",
            title = "goal",
            objective = "objective",
            finalOutputDescription = "answer",
            status = AgentGoalStatus.VERIFYING,
            plannerModelId = "model",
            executionModelId = "model",
            tasks = listOf(
                AgentTask(
                    id = "task",
                    order = 0,
                    title = "Synthesize",
                    instructions = "Synthesize.",
                    capability = AgentCapability.SYNTHESIZE,
                    status = AgentTaskStatus.COMPLETED,
                    progressScore = 1.0,
                ),
            ),
            acceptanceCriteria = listOf(criterion),
            evidence = listOf(evidence),
            claims = listOf(supported, partial),
            evidenceLinks = links,
        )
        val verification = AgentVerificationResult(
            passed = true,
            qualityScore = 0.97,
            summary = "Verified.",
            missingRequirements = emptyList(),
            acceptanceChecks = listOf(
                AgentAcceptanceCheck(
                    criterionId = criterion.id,
                    status = AgentAcceptanceCheckStatus.PASS,
                    score = 1.0,
                    explanation = "Passed.",
                ),
            ),
            claimReviews = emptyList(),
            correctionInstructions = null,
            finalAnswer = "A fully supported result.",
            conceptCandidates = emptyList(),
            apiSummary = AgentApiSummary(),
        )

        assertFalse(AgentIntegrityEvaluator.evaluate(goal, verification).passed)
        val compacted = compactPublicationGraph(goal.claims, goal.evidenceLinks)
        val safeGoal = goal.copy(claims = compacted.claims, evidenceLinks = compacted.evidenceLinks)
        assertTrue(AgentIntegrityEvaluator.evaluate(safeGoal, verification).passed)
    }

    @Test
    fun diagnosticsUseContentFreeFindingCategories() {
        val codes = verificationFindingCodes(
            listOf(
                "The independent verifier did not pass the result.",
                "A factual claim is not fully supported: private response text",
                "A factual claim in a research-backed goal has no preserved source URL: private response text",
            ),
        )

        assertEquals(setOf("verifier_rejected", "fact_not_supported", "fact_missing_source"), codes)
        assertFalse(codes.joinToString().contains("private response text"))
    }

    @Test
    fun publishedAnswerExpandsInternalMarkersWithUserFacingSourceLinks() {
        val supported = claim("c2", AgentClaimType.FACT, AgentClaimSupport.SUPPORTED)
        val second = claim("gc-02", AgentClaimType.FACT, AgentClaimSupport.SUPPORTED)
        val rejected = claim("rejected", AgentClaimType.FACT, AgentClaimSupport.UNSUPPORTED)
        val unsafe = claim("unsafe", AgentClaimType.FACT, AgentClaimSupport.SUPPORTED).copy(
            sourceUrls = listOf(
                "https://attacker@example.com/credential-confusion",
                "https://example.com/good\n- [injected](https://attacker.example)",
                "http://example.com/not-https",
            ),
        )

        val published = publicationAnswerWithSourceLinks(
            answer = "The verified recommendation is model A【c2】【gc-02】.",
            claims = listOf(supported, second, rejected, unsafe),
        )

        assertTrue(published.contains("### Supporting sources"))
        assertTrue(published.contains("[example.com](https://example.com/c2)"))
        assertTrue(published.contains("[example.com](https://example.com/gc-02)"))
        assertFalse(published.contains("https://example.com/rejected"))
        assertFalse(published.contains("attacker"))
        assertFalse(published.contains("http://"))
    }

    @Test
    fun publicationLinkAppendixRespectsTheResultSizeLimit() {
        val supported = claim("bounded", AgentClaimType.FACT, AgentClaimSupport.SUPPORTED)

        val published = publicationAnswerWithSourceLinks(
            answer = "A long verified answer. ".repeat(20),
            claims = listOf(supported),
            maximumCharacters = 96,
        )

        assertTrue(published.length <= 96)
        assertTrue(published.contains("https://example.com/bounded"))
    }

    @Test
    fun verificationRecoveryUsesResearchOnlyForDeterministicResearchGaps() {
        val evidenceCorrection = selectVerificationRecoveryRoutes(emptyList()).single()
        assertEquals(AgentCapability.CORRECT, evidenceCorrection.capability)
        assertTrue(evidenceCorrection.evidenceOnly)

        val primaryRoutes = selectVerificationRecoveryRoutes(
            listOf("The plan contains no explicit primary-source verification pass."),
        )
        val primaryRecovery = primaryRoutes.first()
        assertEquals(AgentCapability.DEEP_RESEARCH, primaryRecovery.capability)
        assertEquals("verification_primary_recovery", primaryRecovery.taskIdPrefix)
        assertEquals(
            listOf("The plan contains no explicit primary-source verification pass."),
            primaryRecovery.targetFindings,
        )
        assertFalse(primaryRecovery.evidenceOnly)
        assertEquals(AgentCapability.CORRECT, primaryRoutes.last().capability)
        assertTrue(primaryRoutes.last().evidenceOnly)

        val sourceGapRoutes = selectVerificationRecoveryRoutes(
            listOf("Only 2 distinct research source(s) were preserved; 8 are required for this research depth."),
        )
        val sourceGapRecovery = sourceGapRoutes.first()
        assertEquals("verification_research_recovery", sourceGapRecovery.taskIdPrefix)
        assertEquals(
            listOf("Only 2 distinct research source(s) were preserved; 8 are required for this research depth."),
            sourceGapRecovery.targetFindings,
        )

        val missingGapPassRecovery = selectVerificationRecoveryRoutes(
            listOf("The plan contains no explicit evidence-gap and freshness audit pass."),
        ).first()
        assertEquals("verification_gap_closure", missingGapPassRecovery.taskIdPrefix)
    }

    @Test
    fun verifierEvidenceDeficitsQueueFocusedResearchBeforePublicationCorrection() {
        val routes = selectVerificationRecoveryRoutes(
            researchGateReasons = emptyList(),
            verificationMissingRequirements = listOf(
                "Inline citations to exact, dated source pages for material claims are missing.",
                "A full explanation of constitutional powers versus practical institutional authority is missing.",
                "A reliable biography and career chronology is required.",
                "Triangulated rights evidence with documented cases and official denials is missing.",
                "Quantitative policy outcomes need datasets, periods, and methodologies.",
                "Add a glossary and disambiguate the neighboring office.",
            ),
        )

        assertTrue(routes.size in 2..MAX_RECOVERY_TASKS_PER_PASS)
        assertTrue(routes.dropLast(1).all { it.capability == AgentCapability.DEEP_RESEARCH })
        assertEquals(AgentCapability.CORRECT, routes.last().capability)
        assertTrue(routes.last().evidenceOnly)
        assertTrue(routes.any { it.taskIdPrefix == "verification_discovery_recovery" })
        assertTrue(routes.any { it.taskIdPrefix == "verification_primary_recovery" })
        assertTrue(routes.any { it.taskIdPrefix == "verification_contradiction_recovery" })
        assertTrue(routes.any { it.taskIdPrefix == "verification_gap_closure" })
        assertTrue(
            routes.single { it.taskIdPrefix == "verification_contradiction_recovery" }
                .targetFindings
                .any { it.startsWith("Triangulated rights evidence") },
        )
    }

    @Test
    fun purelyEditorialVerifierFindingStaysEvidenceOnly() {
        assertFalse(verificationFindingRequiresResearch("Remove the unsupported sentence."))
        assertFalse(verificationFindingRequiresResearch("Add a glossary and define the acronym."))
        assertFalse(verificationFindingRequiresResearch("Correction of an institutional inaccuracy."))

        val routes = selectVerificationRecoveryRoutes(
            researchGateReasons = emptyList(),
            verificationMissingRequirements = listOf("Remove the unsupported sentence."),
        )

        assertEquals(1, routes.size)
        assertEquals(AgentCapability.CORRECT, routes.single().capability)
    }

    @Test
    fun oneBoundedPassBundlesEveryMissingResearchRoleWithoutCollapsingRoleIdentity() {
        val sourceDeficit = "Only 2 distinct research source(s) were preserved; 8 are required for this research depth."
        val routes = selectVerificationRecoveryRoutes(
            listOf(
                "The plan contains no explicit evidence-discovery pass.",
                "The plan contains no explicit primary-source verification pass.",
                "The plan contains no explicit contradiction or disconfirmation pass.",
                "The plan contains no explicit evidence-gap and freshness audit pass.",
                sourceDeficit,
            ),
        )

        assertEquals(MAX_RECOVERY_TASKS_PER_PASS, routes.size)
        assertEquals(
            listOf(
                "verification_discovery_recovery",
                "verification_primary_recovery",
                "verification_contradiction_recovery",
                "verification_gap_closure",
                "correction",
            ),
            routes.map { it.taskIdPrefix },
        )
        assertTrue(routes.dropLast(1).all { it.capability == AgentCapability.DEEP_RESEARCH && !it.evidenceOnly })
        assertEquals(AgentCapability.CORRECT, routes.last().capability)
        assertTrue(sourceDeficit in routes.dropLast(1).last().targetFindings)
    }

    @Test
    fun resumingAStoppedGoalStartsAFreshBoundedCorrectionWindow() {
        val failed = AgentGoal(
            id = "goal",
            conversationId = "conversation",
            userRequest = "request",
            title = "title",
            objective = "objective",
            finalOutputDescription = "answer",
            status = AgentGoalStatus.FAILED,
            plannerModelId = "model",
            executionModelId = "model",
            tasks = listOf(
                AgentTask(
                    id = "task",
                    order = 0,
                    title = "task",
                    instructions = "work",
                    capability = AgentCapability.CORRECT,
                    status = AgentTaskStatus.COMPLETED,
                ),
            ),
            verificationCorrectionStreak = MAX_VERIFICATION_CORRECTION_PASSES,
            terminalResultDelivered = true,
        )

        val resumed = AgentLifecycleReducer.resume(failed)

        assertEquals(AgentGoalStatus.QUEUED, resumed.status)
        assertEquals(0, resumed.verificationCorrectionStreak)
        assertFalse(resumed.terminalResultDelivered)
    }

    @Test
    fun automaticCredentialRecoveryDoesNotResetTheCorrectionWindow() {
        val waiting = AgentGoal(
            conversationId = "conversation",
            userRequest = "request",
            title = "title",
            objective = "objective",
            finalOutputDescription = "answer",
            status = AgentGoalStatus.WAITING_FOR_CREDENTIAL,
            plannerModelId = "model",
            executionModelId = "model",
            tasks = listOf(
                AgentTask(
                    id = "task",
                    order = 0,
                    title = "task",
                    instructions = "work",
                    capability = AgentCapability.CORRECT,
                ),
            ),
            verificationCorrectionStreak = 2,
        )

        val resumed = AgentLifecycleReducer.resume(waiting)

        assertEquals(2, resumed.verificationCorrectionStreak)
    }

    @Test
    fun resumingAnExhaustedCorrectionResetsOnlyItsLocalAttemptWindow() {
        val correction = AgentTask(
            id = "correction",
            order = 0,
            title = "Correct",
            instructions = "Correct from evidence.",
            capability = AgentCapability.CORRECT,
            status = AgentTaskStatus.FAILED,
            attemptCount = 6,
        )
        val failed = AgentGoal(
            conversationId = "conversation",
            userRequest = "request",
            title = "title",
            objective = "objective",
            finalOutputDescription = "answer",
            status = AgentGoalStatus.FAILED,
            plannerModelId = "model",
            executionModelId = "model",
            tasks = listOf(correction),
        )

        val resumed = AgentLifecycleReducer.resume(failed)

        assertEquals(AgentTaskStatus.QUEUED, resumed.tasks.single().status)
        assertEquals(0, resumed.tasks.single().attemptCount)
    }

    private fun claim(
        id: String,
        type: AgentClaimType,
        support: AgentClaimSupport,
    ): AgentClaim = AgentClaim(
        id = id,
        taskId = "task",
        text = id,
        type = type,
        confidence = 0.8,
        support = support,
        supportingEvidenceIds = listOf("evidence"),
        sourceUrls = listOf("https://example.com/$id"),
    )

    private fun evidence(id: String, kind: AgentEvidenceKind) = AgentEvidence(
        id = id,
        kind = kind,
        title = id,
        summary = id,
        content = id,
    )
}
