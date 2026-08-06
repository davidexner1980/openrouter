package com.david.openassistant.agent

import java.net.URI
import java.util.Locale

data class ResearchQualityDecision(
    val passed: Boolean,
    val reasons: List<String>,
)

/** Stable, content-free categories for diagnostics and compact phone reports. */
internal fun researchQualityFindingCodes(reasons: List<String>): Set<String> =
    reasons.mapTo(linkedSetOf()) { reason ->
        when {
            reason.startsWith("Only ") && reason.contains("completed research pass") ->
                "insufficient_completed_passes"
            reason.startsWith("Only ") && reason.contains("distinct research source") ->
                "insufficient_distinct_sources"
            reason.startsWith("Research spans only ") -> "insufficient_source_domains"
            reason.contains("non-HTTPS source") -> "non_https_source"
            reason.contains("preserved ") && reason.contains("required per pass") ->
                "insufficient_pass_sources"
            reason.contains("lacks source-domain diversity") -> "insufficient_pass_domains"
            reason.contains("too little analyzed evidence") -> "insufficient_analyzed_evidence"
            reason.contains("successful full-source read") -> "insufficient_full_source_reads"
            reason.contains("no structured factual claims") -> "missing_factual_claims"
            reason.contains("failed acceptance check") -> "failed_acceptance_check"
            reason.contains("no explicit evidence-discovery pass") -> "missing_discovery_pass"
            reason.contains("no explicit primary-source verification pass") -> "missing_primary_pass"
            reason.contains("no explicit contradiction or disconfirmation pass") ->
                "missing_contradiction_pass"
            reason.contains("no explicit evidence-gap and freshness audit pass") ->
                "missing_gap_closure_pass"
            reason.contains("counterevidence, limitations, alternatives, or disconfirm") ->
                "missing_counterevidence"
            reason.contains("primary or first-party source use") ||
                reason.contains("primary-source pass did not explicitly document") ->
                "missing_primary_evidence"
            reason.contains("search request(s)") ->
                "insufficient_search_requests"
            reason.contains("derived measurement inconsistency") ->
                "inconsistent_derived_measurement"
            else -> "other_research_quality"
        }
    }

/** Stable role used by prompts and gates. Never infer it from generic instructions. */
internal enum class ResearchPassRole {
    DISCOVERY,
    PRIMARY,
    CONTRADICTION,
    GAP_CLOSURE,
    FORENSIC,
    GENERAL,
}

internal fun researchSynthesisEvidenceDescription(roles: Set<ResearchPassRole>): String? {
    val labels = listOf(
        ResearchPassRole.DISCOVERY to "discovery",
        ResearchPassRole.PRIMARY to "primary-source",
        ResearchPassRole.CONTRADICTION to "contradiction",
        ResearchPassRole.GAP_CLOSURE to "gap-closure",
        ResearchPassRole.FORENSIC to "forensic-reconstruction",
    ).mapNotNull { (role, label) -> label.takeIf { role in roles } }
    if (labels.isEmpty()) return null
    val joined = when (labels.size) {
        1 -> labels.single()
        2 -> "${labels[0]} and ${labels[1]}"
        else -> labels.dropLast(1).joinToString(", ") + ", and ${labels.last()}"
    }
    return "The final result reconciles $joined evidence."
}

private val DISCOVERY_PATTERN = Regex("\\b(discovery|landscape|map the evidence|query expansion)\\b")
private val PRIMARY_PASS_PATTERN = Regex("\\b(primary|first[- ]party|official source|original source)\\b")
private val CONTRADICTION_PASS_PATTERN = Regex("\\b(contradiction|counterevidence|disconfirm|challenge|adversarial)\\b")
private val GAP_CLOSURE_PATTERN = Regex("\\b(gap closure|close gaps|freshness|stale claims|evidence audit)\\b")
private val FORENSIC_PATTERN = Regex("\\b(forensic|indirect|infer|surrounding clues|reconstruction)\\b")
private val CONTRADICTION_EVIDENCE_PATTERN = Regex(
    "\\b(contradict(?:ion|ory|ed)?|counterevidence|counterargument|limitation|disconfirm(?:ing|ed|ation)?|" +
        "alternative explanation|failure case|disputed|conflict|caveat|uncertain(?:ty)?|unverified|" +
        "not disclosed|not specific|no specific|false[- ]positive|mixed evidence|weak evidence|selection bias|" +
        "unsupported|no evidence|should not be used as evidence)\\b",
)
private val PRIMARY_EVIDENCE_PATTERN = Regex(
    "\\b(primary source|first[- ]party|official (?:documentation|record|records|source|page|site|data|archive)|" +
        "original (?:research|paper|study|dataset|record|records|filing|minutes)|standard(s| body)?|" +
        "government|governmental|agency record|public record|archival record|commissioners(?:'|’) court|" +
        "source code|patent|dataset|direct data|authoritative)\\b",
)
private val GOVERNMENT_SOURCE_PATTERN = Regex(
    "(?:https?://)?(?:[a-z0-9-]+\\.)+(?:gov|mil|gouv|government|gc|go)(?:\\.[a-z]{2,3})?(?:[/\\s.:]|$)|" +
        "(?:https?://)?(?:[a-z0-9-]+\\.)*europa\\.eu(?:[/\\s.:]|$)|" +
        "(?:https?://)?(?:[a-z0-9-]+\\.)*(?:edu|ac\\.[a-z]{2,3})(?:[/\\s.:]|$)",
    RegexOption.IGNORE_CASE,
)

internal val RETAILER_AGGREGATOR_PATTERN = Regex(
    "\\b(amazon|ebay|walmart|bestbuy|target|aliexpress|alibaba|etsy|rakuten|newegg|currys|argos|review|roundup|best[- ]of|top[- ]10|buying[- ]guide)\\b",
    RegexOption.IGNORE_CASE,
)

private val TOPIC_RELEVANCE_PATTERN = Regex(
    "\\b(related to|relevant|regarding|about|discusses|mentions|subject|topic|domain|context|anchor|entity)\\b",
)

/**
 * Separates topic relevance from source authority.
 */
internal fun computeTopicRelevanceScore(text: String, anchors: List<String>): Double {
    if (anchors.isEmpty()) return 1.0
    val lowerText = text.lowercase(Locale.US)
    val matches = anchors.count { anchor -> lowerText.contains(anchor.lowercase(Locale.US)) }
    return (matches.toDouble() / anchors.size).coerceIn(0.0, 1.0)
}

internal fun computeSourceAuthorityScore(url: String, content: String): Int {
    var score = 50
    val lowerUrl = url.lowercase(Locale.US)
    val lowerContent = content.lowercase(Locale.US)
    
    if (GOVERNMENT_SOURCE_PATTERN.containsMatchIn(lowerUrl)) score += 40
    if (PRIMARY_EVIDENCE_PATTERN.containsMatchIn(lowerContent)) score += 20
    if (RETAILER_AGGREGATOR_PATTERN.containsMatchIn(lowerUrl)) score -= 30
    
    if (lowerUrl.contains("wikipedia.org")) score += 10
    if (lowerUrl.contains("archive.org")) score += 15
    
    return score.coerceIn(0, 100)
}

/**
 * Classifies only planner-owned identity fields. Research instructions contain
 * shared protocol language (including "counterevidence") and therefore must
 * not be used to decide which pass-specific gate applies.
 */
internal fun researchPassRole(task: AgentTask): ResearchPassRole {
    return researchPassRole(task.id, task.title)
}

internal fun researchPassRole(task: AgentTaskDraft): ResearchPassRole {
    return researchPassRole(task.id, task.title)
}

internal fun canAdvancePreservedResearch(
    task: AgentTask,
    evidence: AgentEvidence,
    claims: List<AgentClaim>,
    policy: AutonomyPolicy = AutonomyPolicy.DEFAULT,
    minimumCompletionScore: Double = 0.68,
): Boolean = recoverPreservedResearchAssessment(
    task = task,
    evidence = evidence,
    claims = claims,
    policy = policy,
    minimumCompletionScore = minimumCompletionScore,
) != null

internal fun recoverPreservedResearchAssessment(
    task: AgentTask,
    evidence: AgentEvidence,
    claims: List<AgentClaim>,
    policy: AutonomyPolicy = AutonomyPolicy.DEFAULT,
    minimumCompletionScore: Double = 0.68,
): AgentStepResult? {
    if (
        task.status != AgentTaskStatus.FAILED ||
        task.capability !in setOf(AgentCapability.WEB_RESEARCH, AgentCapability.DEEP_RESEARCH)
    ) {
        return null
    }
    if (task.acceptanceChecks.any { it.status == AgentAcceptanceCheckStatus.FAIL && it.score < 0.25 }) {
        return null
    }
    val preservedToolExecutions = recoverResearchToolAudit(listOf(evidence), task.id)
    val preservedSearchRequests = successfulResearchSearchCount(preservedToolExecutions)
    
    // Law 2 & 5: Synthesize legacy-assumed snapshots to allow re-verification of the preserved audit.
    val syntheticSourceReads = evidence.sources.map { citation ->
        SourceRead(
            id = scopedSourceReadId(citation.url, "h1"),
            url = citation.url,
            canonicalUrl = ResearchQualityGate.canonicalSourceUrl(citation.url),
            documentId = scopedSourceDocumentId(citation.url),
            contentHash = "h1",
            httpCode = 200,
            contentType = "text/plain",
            content = citation.excerpt ?: "Recovered content placeholder.",
            sourceRole = "research",
            authorityScore = 10,
            provenance = SourceReadProvenance.LEGACY_ASSUMED
        )
    }

    val preservedResult = AgentStepResult(
        content = evidence.content,
        summary = AgentApiSummary(
            webSearchRequests = preservedSearchRequests.takeIf { it > 0 },
        ),
        sources = evidence.sources,
        sourceReads = syntheticSourceReads,
        completionScore = task.progressScore,
        acceptanceChecks = task.acceptanceChecks,
        claims = normalizeDurableClaims(task, claims),
        toolExecutions = preservedToolExecutions,
    )
    val recovered = recoverResearchAssessment(
        task = task,
        result = preservedResult,
        policy = policy,
        metadataWasRepaired = evidence.content.contains(
            "provider's useful work was preserved and its response envelope was repaired",
            ignoreCase = true,
        ),
    )
    val effectiveScore = maxOf(task.progressScore, recovered.completionScore)
    if (effectiveScore < minimumCompletionScore) return null
    if (!ResearchQualityGate.evaluateStep(task, recovered, null, policy).passed) return null
    return recovered.copy(completionScore = effectiveScore)
}

private fun researchPassRole(id: String, title: String): ResearchPassRole {
    val identity = "$id $title".lowercase(Locale.US)
    return when {
        FORENSIC_PATTERN.containsMatchIn(identity) -> ResearchPassRole.FORENSIC
        GAP_CLOSURE_PATTERN.containsMatchIn(identity) -> ResearchPassRole.GAP_CLOSURE
        PRIMARY_PASS_PATTERN.containsMatchIn(identity) -> ResearchPassRole.PRIMARY
        CONTRADICTION_PASS_PATTERN.containsMatchIn(identity) -> ResearchPassRole.CONTRADICTION
        DISCOVERY_PATTERN.containsMatchIn(identity) -> ResearchPassRole.DISCOVERY
        else -> ResearchPassRole.GENERAL
    }
}

/**
 * Deterministic checks that prevent a deep-research milestone or goal from
 * being accepted after one shallow lookup. Model self-reports are useful but
 * do not replace these source-count, pass-count, and diversity invariants.
 */
object ResearchQualityGate {
    fun evaluateStep(
        task: AgentTask,
        result: AgentStepResult,
        goal: AgentGoal?,
        policy: AutonomyPolicy = AutonomyPolicy.DEFAULT,
        allocation: ResearchAllocationProfile? = null,
    ): ResearchQualityDecision {
        if (task.capability in setOf(AgentCapability.SYNTHESIZE, AgentCapability.CORRECT)) {
            val evalGoal = goal ?: AgentGoal(id = "DUMMY_EVAL_GOAL", conversationId = "", userRequest = "", title = "", objective = "", finalOutputDescription = "", status = AgentGoalStatus.QUEUED, plannerModelId = "", executionModelId = "", tasks = emptyList())
            return evaluatePublicationStep(task, result, evalGoal, allocation)
        }
        if (task.capability !in setOf(AgentCapability.WEB_RESEARCH, AgentCapability.DEEP_RESEARCH)) {
            return ResearchQualityDecision(true, emptyList())
        }
        val deep = task.capability == AgentCapability.DEEP_RESEARCH
        val minimumSources = if (deep) {
            allocation
                ?.let { profile ->
                    val passes = profile.targetResearchPasses.coerceAtLeast(1)
                    ((profile.targetDistinctSources + passes - 1) / passes)
                        .coerceAtLeast(policy.minimumNovelSourcesPerResearchPass)
                }
                ?: policy.minimumSourcesPerResearchPass
        } else {
            1
        }
        val minimumSearchQueries = if (deep) {
            allocation?.targetSearchQueriesPerPass ?: policy.minimumSearchQueriesPerResearchPass
        } else {
            1
        }
        val minimumReadUnits = if (deep) {
            allocation?.targetFullReadsPerPass ?: policy.targetFullSourceReadsPerResearchPass
        } else {
            0
        }
        val minimumFactClaims = if (deep) {
            allocation?.let { if (it.complexity == ResearchComplexity.EXTREME) 5 else MIN_DEEP_RESEARCH_FACTS }
                ?: MIN_DEEP_RESEARCH_FACTS
        } else {
            1
        }
        val minimumContentChars = if (deep) MIN_DEEP_RESEARCH_CONTENT_CHARS else MIN_STANDARD_RESEARCH_CONTENT_CHARS
        val sources = result.sources
            .map { it.url.trim() }
            .filter(String::isNotBlank)
            .distinct()
        val domains = sources.mapNotNull(::domainOf).distinct()
        val role = researchPassRole(task)
        val normalizedContent = buildString {
            appendLine(result.content)
            result.claims.forEach { appendLine(it.text) }
            result.unresolvedQuestions.forEach(::appendLine)
        }.lowercase(Locale.US)
        val availableSourceReads = (goal?.sourceReads ?: emptyList()) + result.sourceReads
        val citationReport = CitationValidator.validateStepResult(result, goal?.evidence ?: emptyList(), availableSourceReads)
        val label = if (deep) "Deep-research pass" else "Research step"
        val reasons = buildList {
            addAll(citationReport.reasons)
            val successfulAuditedSearches = successfulResearchSearchCount(result.toolExecutions)
            val searchRequests = maxOf(result.summary.webSearchRequests ?: 0, successfulAuditedSearches)
            if (sources.size < minimumSources) {
                add("$label '${task.title}' preserved ${sources.size} distinct source(s); at least $minimumSources are required.")
            }
            if (sources.any { !it.startsWith("https://") }) {
                add("$label '${task.title}' preserved a non-HTTPS source URL.")
            }
            if (deep && sources.size >= 2 && domains.size < 2) {
                add("$label '${task.title}' lacks source-domain diversity.")
            }
            if (result.content.length < minimumContentChars) {
                add("$label '${task.title}' returned too little analyzed evidence.")
            }
            if (deep && searchRequests < minimumSearchQueries) {
                add("$label '${task.title}' recorded $searchRequests search request(s); at least $minimumSearchQueries distinct query angles are required. Deep research requires exploring lateral pivots if direct queries fail.")
            }
            val readAccounting = successfulResearchReadAccounting(result.toolExecutions)
            if (deep && readAccounting.equivalentReadUnits < minimumReadUnits) {
                add(
                    "$label '${task.title}' recorded ${readAccounting.fullReads} successful full-source read(s) " +
                        "and ${readAccounting.providerSubstantialExtracts} substantial provider extract(s); " +
                        "at least $minimumReadUnits full reads or " +
                        "${minimumReadUnits * PROVIDER_EXTRACTS_PER_READ_UNIT} independent substantial extracts are required. Human-like research requires reading the full text of important pages, not just snippets.",
                )
            }
            val factualClaimCount = result.claims.count { it.type == AgentClaimType.FACT }
            if (factualClaimCount < minimumFactClaims) {
                add("$label '${task.title}' produced $factualClaimCount structured factual claim(s); at least $minimumFactClaims are required. A real researcher extracts specific details, versions, dates, and measurements.")
            }

            // Goal 6: Separate topic relevance from source authority
            val anchors = goal?.objectiveContract?.strongAnchors.orEmpty()
            if (anchors.isNotEmpty()) {
                val relevance = computeTopicRelevanceScore(normalizedContent, anchors)
                if (relevance < 0.2) {
                    add("$label '${task.title}' lacks sufficient relevance to the mission's strong anchors ($anchors).")
                }
            }

            if (result.acceptanceChecks.any { it.status == AgentAcceptanceCheckStatus.FAIL }) {
                add("$label '${task.title}' contains a failed acceptance check.")
            }
            derivedMeasurementConsistencyIssues(result.claims).forEach { issue ->
                add(issue.message)
            }
            if (
                deep &&
                role == ResearchPassRole.CONTRADICTION &&
                (allocation?.contradictionNeed ?: ContradictionNeed.NORMAL) != ContradictionNeed.LOW &&
                !CONTRADICTION_EVIDENCE_PATTERN.containsMatchIn(normalizedContent)
            ) {
                add("The contradiction pass did not document counterevidence, limitations, alternatives, or disconfirming findings.")
            }
            if (
                deep &&
                role == ResearchPassRole.PRIMARY &&
                (allocation == null || allocation.sourceStrictness == SourceStrictness.PRIMARY_REQUIRED) &&
                !hasPrimarySourceEvidence(normalizedContent, result.toolExecutions)
            ) {
                add("The primary-source pass did not explicitly document first-party, official, original, standards, government, or dataset evidence.")
            }
        }.distinct()
        return ResearchQualityDecision(reasons.isEmpty(), reasons)
    }

    private fun evaluatePublicationStep(
        task: AgentTask,
        result: AgentStepResult,
        goal: AgentGoal,
        allocation: ResearchAllocationProfile? = null,
    ): ResearchQualityDecision {
        val synthesis = task.capability == AgentCapability.SYNTHESIZE
        val label = if (synthesis) "Synthesis" else "Correction"
        val risk = allocation?.risk ?: ResearchRisk.LOW
        
        val minimumContentChars = if (synthesis) {
            if (risk == ResearchRisk.HIGH) 1_500 else MIN_SYNTHESIS_CONTENT_CHARS
        } else MIN_CORRECTION_CONTENT_CHARS
        
        val minimumClaims = if (synthesis) {
            if (risk == ResearchRisk.HIGH) 5 else MIN_SYNTHESIS_CLAIMS
        } else MIN_CORRECTION_CLAIMS

        val evidenceById = goal.evidence.associateBy { it.id }
        val sourceReadsByCanonicalUrl = goal.sourceReads.associateBy { it.canonicalUrl }

        val availableSourceReads = goal.sourceReads + result.sourceReads
        val groundedClaims = result.claims.filter { claim ->
            FactualClaimSupportPolicy.evaluate(claim, availableSourceReads) is FactualClaimSupportDecision.Supported
        }
        val groundedFacts = groundedClaims.count { it.type == AgentClaimType.FACT }
        val citationReport = CitationValidator.validateStepResult(result, goal.evidence, availableSourceReads)
        val reasons = buildList {
            addAll(citationReport.reasons)
            if (result.content.length < minimumContentChars) {
                add("$label '${task.title}' returned too little publication-ready analysis; at least $minimumContentChars characters are required.")
            }
            if (result.claims.size < minimumClaims) {
                add("$label '${task.title}' produced ${result.claims.size} structured claim(s); at least $minimumClaims are required.")
            }
            if (groundedClaims.size < minimumClaims) {
                add("$label '${task.title}' grounded ${groundedClaims.size} claim(s) in preserved evidence IDs or HTTPS sources; at least $minimumClaims are required.")
            }
            if (groundedFacts < 1) {
                add("$label '${task.title}' produced no grounded factual claim from the preserved evidence.")
            }
            derivedMeasurementConsistencyIssues(result.claims).forEach { issue ->
                add(issue.message)
            }
            if (task.acceptanceCriteria.isNotEmpty()) {
                val expectedIds = task.acceptanceCriteria.map { it.id }
                val suppliedIds = result.acceptanceChecks.map { it.criterionId }
                if (suppliedIds.size != suppliedIds.distinct().size) {
                    add("$label '${task.title}' contains duplicate acceptance checks.")
                }
                val checksById = result.acceptanceChecks.associateBy { it.criterionId }
                val nonPassing = expectedIds.filter { criterionId ->
                    checksById[criterionId]?.status != AgentAcceptanceCheckStatus.PASS
                }
                if (nonPassing.isNotEmpty()) {
                    add("$label '${task.title}' must pass every acceptance criterion; ${nonPassing.size} criterion check(s) were partial, failed, missing, or not evaluated.")
                }
            }
        }.distinct()
        return ResearchQualityDecision(reasons.isEmpty(), reasons)
    }

    fun evaluateGoal(
        goal: AgentGoal,
        policy: AutonomyPolicy = AutonomyPolicy.DEFAULT,
        allocation: ResearchAllocationProfile? = null,
    ): ResearchQualityDecision {
        val researchTasks = goal.tasks.filter {
            it.capability in setOf(AgentCapability.WEB_RESEARCH, AgentCapability.DEEP_RESEARCH)
        }
        if (researchTasks.isEmpty()) return ResearchQualityDecision(true, emptyList())
        val profile = allocation ?: AgentResearchAllocator.profileForGoal(goal, policy)
        
        val plannedRoles = researchTasks.map(::researchPassRole).toSet()
        val legacySingleWebStep = researchTasks.size == 1 &&
            researchTasks.single().capability == AgentCapability.WEB_RESEARCH
        val originalResearchTasks = researchTasks.filterNot { task ->
            task.id.startsWith("verification_") || task.id.startsWith("correction_")
        }
        val originalRoles = originalResearchTasks.map(::researchPassRole).toSet()
        
        val requestedDepth = AgentResearchPolicy.forRequest(
            request = goal.userRequest,
            deepResearchByDefault = policy.deepResearchByDefault,
        ).depth

        val inferredOriginalDepth = when {
            ResearchPassRole.GAP_CLOSURE in originalRoles ||
                originalResearchTasks.size >= AgentResearchDepth.DEEP.minimumPasses -> AgentResearchDepth.DEEP
            ResearchPassRole.PRIMARY in originalRoles || originalResearchTasks.size > 1 -> AgentResearchDepth.STANDARD
            else -> AgentResearchDepth.NONE
        }

        // Recovery milestones must close a fixed gate, not silently make that
        // gate stricter merely because the task list became longer.
        @Suppress("UNUSED_VARIABLE")
        val effectiveDepth = when {
            legacySingleWebStep -> AgentResearchDepth.NONE
            requestedDepth != AgentResearchDepth.NONE -> requestedDepth
            else -> inferredOriginalDepth
        }
        
        val requiredPasses = if (legacySingleWebStep) 1 else profile.targetResearchPasses
        val requiredSources = if (legacySingleWebStep) 1 else profile.targetDistinctSources
        val requiredDomains = if (legacySingleWebStep) 1 else profile.targetDomains

        val researchEvidence = goal.evidence.filter {
            it.kind in setOf(AgentEvidenceKind.WEB_RESEARCH, AgentEvidenceKind.DEEP_RESEARCH)
        }
        val sourceUrls = researchEvidence
            .flatMap { evidence -> evidence.sources.map { it.url.trim() } }
            .map(::canonicalSourceUrl)
            .filter(String::isNotBlank)
            .distinct()
        val domains = sourceUrls.mapNotNull(::domainOf).distinct()
        val completedResearchTasks = researchTasks.count { it.status == AgentTaskStatus.COMPLETED }
        val evidenceText = researchEvidence.joinToString(" ") { "${it.title} ${it.summary} ${it.content}" }
            .lowercase(Locale.US)
        val totalSearchRequests = goal.attempts.mapNotNull { it.webSearchRequests }.sum()
        val recoveredResearchTools = recoverResearchToolAudit(researchEvidence)

        val reasons = buildList {
            if (completedResearchTasks < requiredPasses) {
                add("Only $completedResearchTasks completed research pass(es); $requiredPasses are required for this research depth.")
            }
            if (sourceUrls.size < requiredSources) {
                add("Only ${sourceUrls.size} distinct research source(s) were preserved; $requiredSources are required for this research depth.")
            }
            if (domains.size < requiredDomains) {
                add("Research spans only ${domains.size} source domain(s); $requiredDomains are required for this research depth.")
            }
            if (sourceUrls.any { !it.startsWith("https://") }) {
                add("The research graph contains one or more non-HTTPS source URLs.")
            }

            if (!legacySingleWebStep && requiredPasses > 1 && ResearchPassRole.DISCOVERY !in plannedRoles) {
                add("The plan contains no explicit evidence-discovery pass.")
            }
            if (
                !legacySingleWebStep &&
                profile.sourceStrictness == SourceStrictness.PRIMARY_REQUIRED &&
                ResearchPassRole.PRIMARY !in plannedRoles
            ) {
                add("The plan contains no explicit primary-source verification pass.")
            }
            if (
                !legacySingleWebStep &&
                profile.contradictionNeed == ContradictionNeed.HIGH &&
                ResearchPassRole.CONTRADICTION !in plannedRoles
            ) {
                add("The plan contains no explicit contradiction or disconfirmation pass.")
            }
            if (!legacySingleWebStep && profile.complexity == ResearchComplexity.EXTREME && ResearchPassRole.GAP_CLOSURE !in plannedRoles) {
                add("The plan contains no explicit evidence-gap and freshness audit pass.")
            }
            if (
                ResearchPassRole.CONTRADICTION in plannedRoles &&
                profile.contradictionNeed == ContradictionNeed.HIGH &&
                !CONTRADICTION_EVIDENCE_PATTERN.containsMatchIn(evidenceText)
            ) {
                add("The preserved evidence does not document counterevidence, limitations, alternatives, or disconfirmation.")
            }
            if (
                ResearchPassRole.PRIMARY in plannedRoles &&
                profile.sourceStrictness == SourceStrictness.PRIMARY_REQUIRED &&
                !hasPrimarySourceEvidence(evidenceText, recoveredResearchTools)
            ) {
                add("The preserved evidence does not explicitly document primary or first-party source use.")
            }
            val requiredSearchRequests = if (legacySingleWebStep) 1 else requiredPasses * profile.targetSearchQueriesPerPass
            if (totalSearchRequests < requiredSearchRequests) {
                add("Only $totalSearchRequests web-search request(s) were recorded across $requiredPasses required passes; at least $requiredSearchRequests are required for this research depth.")
            }
            
            // Law 5: Validate every factual claim against its bindings and source reads
            goal.claims.forEach { claim ->
                if (claim.type == AgentClaimType.FACT) {
                    val decision = FactualClaimSupportPolicy.evaluate(claim, goal.sourceReads)
                    if (decision !is FactualClaimSupportDecision.Supported) {
                        add("The research graph contains a factual claim with invalid or insufficient source grounding: '${claim.text.take(50)}...'")
                    }
                }
            }
        }.distinct()
        return ResearchQualityDecision(reasons.isEmpty(), reasons)
    }

    internal fun canonicalSourceUrl(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return ""
        return runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.US) ?: return@runCatching trimmed.removeSuffix("/")
            val host = uri.host?.lowercase(Locale.US)?.removePrefix("www.")
                ?: return@runCatching trimmed.removeSuffix("/")
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val path = uri.rawPath.orEmpty().ifBlank { "/" }.removeSuffix("/").ifBlank { "/" }
            val query = uri.rawQuery?.let { "?$it" }.orEmpty()
            "$scheme://$host$port$path$query"
        }.getOrDefault(trimmed.removeSuffix("/"))
    }

    internal fun domainOf(url: String): String? = runCatching {
        URI(url).host?.lowercase(Locale.US)?.removePrefix("www.")
    }.getOrNull()?.takeIf(String::isNotBlank)

    /**
     * Primary-source proof is semantic when the work explicitly describes its
     * provenance, or structural when a successful full-page fetch came from a
     * government/official host. A search-result lead alone never satisfies it.
     */
    private fun hasPrimarySourceEvidence(
        normalizedContent: String,
        toolExecutions: List<AgentToolExecution>,
    ): Boolean {
        val successfulSourceReads = toolExecutions.filter { execution ->
            execution.succeeded && execution.toolName in RESEARCH_SOURCE_READ_TOOL_NAMES
        }
        
        // Positive signals: .gov, .edu, .mil, official archives
        val officialHostWasRead = successfulSourceReads.any { execution ->
            GOVERNMENT_SOURCE_PATTERN.containsMatchIn(execution.summary)
        }
        
        // Negative signals: retailers, review roundups, aggregators
        val excessiveAggregatorNoise = successfulSourceReads.all { execution ->
            RETAILER_AGGREGATOR_PATTERN.containsMatchIn(execution.summary)
        } && successfulSourceReads.isNotEmpty()

        val provenanceWasDocumented = PRIMARY_EVIDENCE_PATTERN.containsMatchIn(normalizedContent)
        
        // If it's all retailers, it's not primary unless it explicitly documents 
        // official provenance (e.g. citing a manual found on a retailer page, which is rare).
        if (excessiveAggregatorNoise && !provenanceWasDocumented) return false
        
        return officialHostWasRead || (provenanceWasDocumented && successfulSourceReads.isNotEmpty())
    }

    internal const val MIN_STANDARD_RESEARCH_CONTENT_CHARS = 250
    internal const val MIN_DEEP_RESEARCH_CONTENT_CHARS = 1_200
    internal const val MIN_DEEP_RESEARCH_FACTS = 3
    internal const val MIN_SYNTHESIS_CONTENT_CHARS = 800
    internal const val MIN_SYNTHESIS_CLAIMS = 3
    internal const val MIN_CORRECTION_CONTENT_CHARS = 800
    internal const val MIN_CORRECTION_CLAIMS = 3
}
