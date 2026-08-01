package com.david.openassistant.agent

/**
 * Deterministic publication gate. Model verification is evidence, not authority:
 * application code applies these invariants before a goal can be marked complete.
 */
object AgentIntegrityEvaluator {
    private const val MIN_QUALITY_SCORE = 0.80
    private const val MIN_CRITERION_SCORE = 0.75

    fun evaluate(goal: AgentGoal, verification: AgentVerificationResult): AgentIntegrityDecision {
        val reviewedClaims = applyClaimReviews(goal.claims, verification.claimReviews)
        val evidenceById = goal.evidence.associateBy { it.id }
        val knownEvidenceIds = evidenceById.keys
        val knownSourceUrls = goal.evidence
            .flatMapTo(mutableSetOf()) { evidence -> evidence.sources.map { it.url } }
        val linkRelations = goal.evidenceLinks
            .groupBy { it.claimId to it.evidenceId }
            .mapValues { (_, links) -> links.mapTo(mutableSetOf()) { it.relation } }
        // A research evidence label alone is not enough to make a local artifact or
        // computation a web-backed claim. Research is active when the plan actually
        // contains a research milestone or when preserved source URLs are present.
        // This keeps strict citation rules on externally grounded facts without
        // rejecting facts proven by local tools or generated artifacts.
        val researchWasUsed = goal.tasks.any {
            it.capability in setOf(AgentCapability.WEB_RESEARCH, AgentCapability.DEEP_RESEARCH)
        } || goal.evidence.any { it.sources.isNotEmpty() }
        val researchQuality = ResearchQualityGate.evaluateGoal(goal)
        val boundedVerificationAccepted = acceptsEpistemicallyBoundedVerification(goal, verification)

        val reasons = buildList {
            addAll(researchQuality.reasons)
            if (!verification.passed && !boundedVerificationAccepted) {
                add("The independent verifier did not pass the result.")
            }
            if (verification.qualityScore < MIN_QUALITY_SCORE && !boundedVerificationAccepted) {
                add("Quality score ${verification.qualityScore.asPercent()} is below ${MIN_QUALITY_SCORE.asPercent()}.")
            }
            if (verification.finalAnswer.isBlank()) add("The verifier returned no usable final answer.")
            if (goal.tasks.any { it.status != AgentTaskStatus.COMPLETED }) {
                add("One or more planned tasks are not complete.")
            }
            if (verification.missingRequirements.isNotEmpty() && !boundedVerificationAccepted) {
                add("The verifier still reports ${verification.missingRequirements.size} missing requirement(s).")
            }

            val checksById = verification.acceptanceChecks.associateBy { it.criterionId }
            goal.acceptanceCriteria.forEach { criterion ->
                val check = checksById[criterion.id]
                when {
                    check == null -> add("Acceptance criterion '${criterion.description}' was not evaluated.")
                    check.status != AgentAcceptanceCheckStatus.PASS && !boundedVerificationAccepted ->
                        add("Acceptance criterion did not pass: ${criterion.description}")
                    check.score < MIN_CRITERION_SCORE && !boundedVerificationAccepted ->
                        add("Acceptance criterion is below threshold: ${criterion.description}")
                }
            }

            if (researchWasUsed && reviewedClaims.isEmpty()) {
                add("Web research was used, but no structured claims were produced for evidence review.")
            }
            val duplicateClaimIds = reviewedClaims
                .groupingBy { it.id }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            if (duplicateClaimIds.isNotEmpty()) {
                add("Structured claim IDs are not unique; ${duplicateClaimIds.size} duplicate ID(s) make reviews and evidence links ambiguous.")
            }
            derivedMeasurementConsistencyIssues(reviewedClaims).forEach { issue ->
                add(issue.message)
            }

            reviewedClaims.forEach { claim ->
                val validEvidenceIds = claim.supportingEvidenceIds.filter(knownEvidenceIds::contains)
                val invalidEvidenceCount = claim.supportingEvidenceIds.size - validEvidenceIds.size
                val invalidSourceCount = claim.sourceUrls.count { it !in knownSourceUrls }
                val validClaimSourceUrls = claim.sourceUrls.filter(knownSourceUrls::contains).toSet()
                val supportedLinkedEvidenceIds = validEvidenceIds.filter { evidenceId ->
                    AgentEvidenceRelation.SUPPORTS in linkRelations[claim.id to evidenceId].orEmpty()
                }
                val matchingSourceEvidenceIds = validEvidenceIds.filter { evidenceId ->
                    evidenceById[evidenceId]
                        ?.sources
                        ?.any { it.url in validClaimSourceUrls }
                        ?: false
                }

                if (invalidEvidenceCount > 0) {
                    add("Claim '${claim.text.take(80)}' references $invalidEvidenceCount unknown evidence record(s).")
                }
                if (invalidSourceCount > 0) {
                    add("Claim '${claim.text.take(80)}' references $invalidSourceCount unpreserved source URL(s).")
                }
                if (claim.support == AgentClaimSupport.CONTRADICTED) {
                    add("Claim is contradicted by the evidence: ${claim.text.take(120)}")
                }
                if (claim.type == AgentClaimType.FACT || claim.type == AgentClaimType.ORIGINAL_HYPOTHESIS) {
                    if (claim.type == AgentClaimType.FACT && claim.support != AgentClaimSupport.SUPPORTED) {
                        add("A factual claim is not fully supported: ${claim.text.take(120)}")
                    }
                    if (validEvidenceIds.isEmpty() && claim.type == AgentClaimType.FACT) {
                        add("A factual claim has no preserved evidence record: ${claim.text.take(120)}")
                    } else if (researchWasUsed && validClaimSourceUrls.isEmpty() && claim.type == AgentClaimType.FACT) {
                        add("A factual claim in a research-backed goal has no preserved source URL: ${claim.text.take(120)}")
                    } else if (researchWasUsed && matchingSourceEvidenceIds.isEmpty() && claim.type == AgentClaimType.FACT) {
                        add("A factual claim in a research-backed goal has no evidence record containing its cited source: ${claim.text.take(120)}")
                    } else if (
                        researchWasUsed &&
                        matchingSourceEvidenceIds.none(supportedLinkedEvidenceIds::contains) &&
                        claim.type == AgentClaimType.FACT
                    ) {
                        add("A factual research claim is missing a SUPPORTS link to its cited source evidence: ${claim.text.take(120)}")
                    } else if (!researchWasUsed && supportedLinkedEvidenceIds.isEmpty() && claim.type == AgentClaimType.FACT) {
                        add("A factual claim is missing a SUPPORTS claim-to-evidence graph link: ${claim.text.take(120)}")
                    }
                }
            }
        }.distinct()
        return AgentIntegrityDecision(passed = reasons.isEmpty(), reasons = reasons)
    }

    fun applyClaimReviews(
        claims: List<AgentClaim>,
        reviews: List<AgentClaimReview>,
    ): List<AgentClaim> {
        if (reviews.isEmpty()) return claims
        val reviewsById = reviews.associateBy { it.claimId }
        return claims.map { claim ->
            val review = reviewsById[claim.id] ?: return@map claim
            claim.copy(
                support = review.support,
                reviewExplanation = review.explanation,
            )
        }
    }

    /**
     * Keeps graph semantics aligned with an independent review. A verifier that
     * upgrades or downgrades a claim must not leave stale SUPPORTS or
     * CONTRADICTS edges behind.
     */
    fun reconcileEvidenceLinks(
        claims: List<AgentClaim>,
        links: List<AgentEvidenceLink>,
    ): List<AgentEvidenceLink> {
        val claimsById = claims.associateBy { it.id }
        return links.mapNotNull { link ->
            val claim = claimsById[link.claimId] ?: return@mapNotNull null
            link.copy(
                relation = when (claim.support) {
                    AgentClaimSupport.SUPPORTED -> AgentEvidenceRelation.SUPPORTS
                    AgentClaimSupport.CONTRADICTED -> AgentEvidenceRelation.CONTRADICTS
                    AgentClaimSupport.PARTIAL,
                    AgentClaimSupport.UNSUPPORTED,
                    -> AgentEvidenceRelation.QUALIFIES
                },
            )
        }.distinctBy { Triple(it.claimId, it.evidenceId, it.relation) }
    }

    fun mergeChecks(
        criteria: List<AgentAcceptanceCriterion>,
        checks: List<AgentAcceptanceCheck>,
    ): List<AgentAcceptanceCheck> {
        val byId = checks.associateBy { it.criterionId }
        return criteria.map { criterion ->
            byId[criterion.id] ?: AgentAcceptanceCheck(
                criterionId = criterion.id,
                status = AgentAcceptanceCheckStatus.NOT_EVALUATED,
                score = 0.0,
                explanation = "The verifier did not evaluate this criterion.",
            )
        }
    }

    private fun Double.asPercent(): String = "${(coerceIn(0.0, 1.0) * 100).toInt()}%"
}
