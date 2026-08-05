package com.david.openassistant.agent

import java.util.Locale

object AgentResearchAllocator {

    fun profileForGoal(goal: AgentGoal, @Suppress("UNUSED_PARAMETER") basePolicy: AutonomyPolicy = AutonomyPolicy.DEFAULT): ResearchAllocationProfile {
        val request = goal.userRequest.lowercase(Locale.US)
        
        val isMedical = request.hasAnyTerm("medical", "doctor", "disease", "health", "drug", "treatment", "clinical", "diagnosis", "symptom")
        val isLegal = request.hasAnyTerm("legal", "law", "regulation", "court", "attorney", "statute", "compliance", "contract")
        val isFinancial = request.hasAnyTerm(
            "financial",
            "financials",
            "finance",
            "price",
            "cost",
            "market",
            "stock",
            "invest",
            "investment",
            "portfolio",
            "loan",
            "mortgage",
            "valuation",
            "revenue",
            "earnings",
            "profit",
            "profitability",
            "debt",
            "cashflow",
            "cash",
            "dividend",
            "yield",
            "crypto",
            "fund",
        ) || request.contains("cash flow")
        val isSafety = request.hasAnyTerm("safety", "safe", "safest", "danger", "toxic", "hazard", "risk", "recall")
        
        val isComparison = request.hasAnyTerm("compare", "versus", "vs", "best", "recommend", "recommendation", "review", "rank", "ranking")
        val isFreshnessNeed = request.hasAnyTerm("latest", "current", "today", "recent", "news", "weather", "version", "release", "availability", "schedule", "price")
        val isPrimaryNeed = request.hasAnyTerm("standard", "scientific", "specification", "datasheet", "official", "filing", "dataset", "source code") || request.contains("data sheet") || isLegal
        
        val complexityScore = buildList {
            if (goal.userRequest.length > 500) add(1)
            if (isComparison) add(1)
            if (isMedical || isLegal || isFinancial) add(1)
            if (isComparison && (isMedical || isFinancial)) add(1)
            if (isFinancial && request.hasAnyTerm("financials", "valuation", "revenue", "earnings", "profitability", "debt", "potential", "forecast", "outlook")) add(1)
            if (goal.confirmedConstraints.size > 3) add(1)
            if (goal.unresolvedQuestions.size > 2) add(1)
            if (request.contains("research") || request.contains("investigate") || request.contains("verify")) add(1)
            if (request.contains("great detail") || request.contains("deep dive") || request.contains("comprehensive")) add(1)
        }.sum()
        
        val complexity = when {
            complexityScore >= 5 -> ResearchComplexity.EXTREME
            complexityScore >= 3 -> ResearchComplexity.HIGH
            complexityScore >= 1 -> ResearchComplexity.MEDIUM
            else -> ResearchComplexity.LOW
        }
        
        val riskScore = buildList {
            if (isMedical) add(2)
            if (isLegal || isFinancial) add(1)
            if (isSafety) add(2)
            if (isComparison && (isMedical || isFinancial)) add(2)
        }.sum()
        
        val risk = when {
            riskScore >= 3 -> ResearchRisk.HIGH
            riskScore >= 1 -> ResearchRisk.MEDIUM
            else -> ResearchRisk.LOW
        }
        
        val freshnessNeed = when {
            isFreshnessNeed -> FreshnessNeed.REQUIRED
            complexity == ResearchComplexity.EXTREME || complexity == ResearchComplexity.HIGH -> FreshnessNeed.USEFUL
            else -> FreshnessNeed.NONE
        }
        
        val sourceStrictness = when {
            isPrimaryNeed -> SourceStrictness.PRIMARY_REQUIRED
            risk == ResearchRisk.HIGH || risk == ResearchRisk.MEDIUM -> SourceStrictness.NORMAL
            else -> SourceStrictness.LOW
        }
        
        val contradictionNeed = when {
            isComparison || risk == ResearchRisk.HIGH -> ContradictionNeed.HIGH
            complexity == ResearchComplexity.EXTREME || complexity == ResearchComplexity.HIGH -> ContradictionNeed.NORMAL
            else -> ContradictionNeed.LOW
        }
        
        val targetResearchPasses = when (complexity) {
            ResearchComplexity.EXTREME -> 6
            ResearchComplexity.HIGH -> 4
            ResearchComplexity.MEDIUM -> 3
            ResearchComplexity.LOW -> 1
        }
        
        val targetDistinctSources = when (complexity) {
            ResearchComplexity.EXTREME -> 15
            ResearchComplexity.HIGH -> 10
            ResearchComplexity.MEDIUM -> 6
            ResearchComplexity.LOW -> 2
        }

        val targetDomains = when (complexity) {
            ResearchComplexity.EXTREME -> 6
            ResearchComplexity.HIGH -> 4
            ResearchComplexity.MEDIUM -> 3
            ResearchComplexity.LOW -> 1
        }

        val explanation = buildString {
            append("Assessed as ${complexity.name} complexity and ${risk.name} risk. ")
            if (isComparison) append("Comparison-heavy request requires contradiction search. ")
            if (isFreshnessNeed) append("Time-sensitive request requires fresh evidence. ")
            if (isPrimaryNeed) append("Technical or legal request requires primary sources. ")
        }.trim().ifBlank { "Simple request with bounded research needs." }

        return ResearchAllocationProfile(
            complexity = complexity,
            risk = risk,
            freshnessNeed = freshnessNeed,
            sourceStrictness = sourceStrictness,
            contradictionNeed = contradictionNeed,
            targetResearchPasses = targetResearchPasses,
            targetDistinctSources = targetDistinctSources,
            targetDomains = targetDomains,
            targetSearchQueriesPerPass = when (complexity) {
                ResearchComplexity.EXTREME -> 5
                ResearchComplexity.HIGH -> 4
                else -> 3
            },
            targetFullReadsPerPass = when (complexity) {
                ResearchComplexity.EXTREME -> 4
                ResearchComplexity.HIGH -> 3
                else -> 2
            },
            maxRabbitHoleIterations = when (complexity) {
                ResearchComplexity.EXTREME -> 12
                ResearchComplexity.HIGH -> 8
                ResearchComplexity.MEDIUM -> 4
                ResearchComplexity.LOW -> 2
            },
            maxLocalAttemptsPerTask = if (risk == ResearchRisk.HIGH) 5 else 3,
            synthesisModelStrength = if (risk == ResearchRisk.HIGH || complexity == ResearchComplexity.EXTREME) ModelStrength.STRONG else ModelStrength.NORMAL,
            explanation = explanation
        )
    }

    fun evaluateGaps(goal: AgentGoal, profile: ResearchAllocationProfile): ResearchAllocationGaps {
        val activeCycleId = goal.activeResearchCycleId
        val activeCycle = goal.researchCycles.firstOrNull { it.id == activeCycleId }
        val carryForwardIds = activeCycle?.learningSummary?.carryForwardEvidenceIds?.toSet() ?: emptySet()

        val researchEvidence = goal.evidence.filter {
            (activeCycleId == null || it.cycleId == activeCycleId || it.id in carryForwardIds) &&
            it.kind in setOf(AgentEvidenceKind.WEB_RESEARCH, AgentEvidenceKind.DEEP_RESEARCH)
        }
        val sourceUrls = researchEvidence
            .flatMap { it.sources.map { s -> ResearchQualityGate.canonicalSourceUrl(s.url) } }
            .filter { it.isNotBlank() }
            .distinct()
        
        val domains = sourceUrls.mapNotNull { url ->
            ResearchQualityGate.domainOf(url)
        }.distinct()
        
        val remainingSourceGap = (profile.targetDistinctSources - sourceUrls.size).coerceAtLeast(0)
        val remainingDomainGap = (profile.targetDomains - domains.size).coerceAtLeast(0)
        
        val researchTasks = goal.tasks.filter { 
            (activeCycleId == null || it.cycleId == activeCycleId) &&
            it.capability in setOf(AgentCapability.WEB_RESEARCH, AgentCapability.DEEP_RESEARCH) 
        }
        val completedRoles = researchTasks
            .filter { it.status == AgentTaskStatus.COMPLETED }
            .map { researchPassRole(it) }
            .toSet()
        
        val remainingPrimarySourceGap = profile.sourceStrictness == SourceStrictness.PRIMARY_REQUIRED && 
            ResearchPassRole.PRIMARY !in completedRoles
        
        val remainingContradictionGap = profile.contradictionNeed == ContradictionNeed.HIGH && 
            ResearchPassRole.CONTRADICTION !in completedRoles
            
        val remainingGapClosureGap = profile.complexity == ResearchComplexity.EXTREME && 
            ResearchPassRole.GAP_CLOSURE !in completedRoles

        val effortLabel = when (profile.complexity) {
            ResearchComplexity.LOW -> "Minimal Research"
            ResearchComplexity.MEDIUM -> "Standard Investigation"
            ResearchComplexity.HIGH -> "Deep Investigation"
            ResearchComplexity.EXTREME -> "Exhaustive Investigation"
        }

        return ResearchAllocationGaps(
            goalId = goal.id,
            profile = profile,
            remainingSourceGap = remainingSourceGap,
            remainingDomainGap = remainingDomainGap,
            remainingPrimarySourceGap = remainingPrimarySourceGap,
            remainingContradictionGap = remainingContradictionGap,
            remainingGapClosureGap = remainingGapClosureGap,
            estimatedEffortLabel = effortLabel
        )
    }

    fun chooseNextTask(goal: AgentGoal, profile: ResearchAllocationProfile, now: Long = System.currentTimeMillis()): AllocatedTaskSelection {
        val gaps = evaluateGaps(goal, profile)
        val activeCycleId = goal.activeResearchCycleId
        
        val completedIds = goal.tasks
            .filter { it.status == AgentTaskStatus.COMPLETED }
            .mapTo(mutableSetOf()) { it.id }
        
        val relevantTasks = if (activeCycleId == null) goal.tasks else goal.tasks.filter { it.cycleId == activeCycleId }

        val dependencySatisfiedTasks = relevantTasks
            .filter { it.status != AgentTaskStatus.COMPLETED && it.status != AgentTaskStatus.CANCELLED && it.status != AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE }
            .filter { it.branchExhaustionReason == null }
            .filter { it.dependsOn.all(completedIds::contains) }
            .sortedBy { it.order }

        if (dependencySatisfiedTasks.isEmpty()) {
            return AllocatedTaskSelection(null, "No dependency-satisfied tasks remaining.")
        }

        val readyTasks = dependencySatisfiedTasks.filterNot { task ->
            val inCooldown = task.cooldownUntil != null && now < task.cooldownUntil
            val inNetworkWait = task.failureClass == "network_resolution" && task.waitCondition != null
            inCooldown || inNetworkWait
        }

        if (readyTasks.isEmpty()) {
            return AllocatedTaskSelection(null, "All dependency-satisfied tasks are in cooldown or network wait.", retryAfterCooldown = true)
        }

        // Among ready tasks, pick one that addresses the most important open allocation gap.
        val prioritizedTask = readyTasks.firstOrNull { task ->
            val role = researchPassRole(task)
            when (role) {
                ResearchPassRole.PRIMARY -> gaps.remainingPrimarySourceGap
                ResearchPassRole.CONTRADICTION -> gaps.remainingContradictionGap
                ResearchPassRole.GAP_CLOSURE -> gaps.remainingGapClosureGap
                else -> false
            }
        } ?: readyTasks.first()

        return AllocatedTaskSelection(prioritizedTask.id, "Selected ready task based on allocation gaps and dependency order.")
    }

    fun budgetForTask(goal: AgentGoal, task: AgentTask, profile: ResearchAllocationProfile): ResearchTaskBudget {
        val tactic = chooseNextEscalationTactic(goal, task)
        return ResearchTaskBudget(
            searchQueriesTarget = profile.targetSearchQueriesPerPass,
            fullReadsTarget = profile.targetFullReadsPerPass,
            distinctSourcesTarget = profile.targetDistinctSources,
            novelSourcesTarget = 1,
            minFactClaims = if (profile.complexity == ResearchComplexity.EXTREME) 5 else 3,
            maxRabbitHoleIterations = profile.maxRabbitHoleIterations,
            allowModelEscalation = (profile.risk == ResearchRisk.HIGH || tactic != EscalationTactic.NONE) && !goal.freeOnly,
            forcedTactic = tactic
        )
    }

    private fun chooseNextEscalationTactic(goal: AgentGoal, task: AgentTask): EscalationTactic {
        val count = task.consecutiveNoProgressCount
        return when {
            count <= 0 -> EscalationTactic.NONE
            count == 1 -> EscalationTactic.REFORMULATE_QUERY
            count == 2 -> EscalationTactic.DECOMPOSE_QUESTION
            count == 3 -> EscalationTactic.SEARCH_AUTHORITATIVE_DOMAINS
            count == 4 -> EscalationTactic.INSPECT_SITEMAPS_INDEXES
            count == 5 -> EscalationTactic.FOLLOW_RELEVANT_LINKS
            count == 6 -> EscalationTactic.ALTERNATIVE_DISCOVERY_ADAPTER
            count == 7 -> EscalationTactic.LOCAL_EVIDENCE_INDEX_SEARCH
            count == 8 -> EscalationTactic.ALTERNATE_MODEL_PROVIDER
            count == 9 -> EscalationTactic.RE_EVALUATE_ASSUMPTIONS
            count == 10 -> EscalationTactic.SMALLEST_MISSING_FACT
            else -> EscalationTactic.ASK_USER
        }
    }

    fun shouldEscalateModel(goal: AgentGoal, task: AgentTask, gaps: ResearchAllocationGaps): Boolean {
        if (goal.freeOnly) return false
        val profile = gaps.profile
        return (profile.risk == ResearchRisk.HIGH || profile.complexity == ResearchComplexity.EXTREME) && 
            task.attemptCount >= 2
    }

    fun recoveryStrategy(goal: AgentGoal, task: AgentTask, @Suppress("UNUSED_PARAMETER") lastFailure: String?): AllocationRecoveryDecision {
        return when {
            task.attemptCount >= 5 -> AllocationRecoveryDecision.MARK_EXHAUSTED
            task.attemptCount >= 2 && !goal.freeOnly -> AllocationRecoveryDecision.ESCALATE_MODEL
            else -> AllocationRecoveryDecision.RETRY_WITH_STRATEGY_CHANGE
        }
    }

    private fun String.hasAnyTerm(vararg terms: String): Boolean {
        return terms.any { term ->
            val escaped = Regex.escape(term.lowercase(Locale.US))
            Regex("(^|[^a-z0-9])$escaped([^a-z0-9]|$)").containsMatchIn(this)
        }
    }
}
