package com.david.openassistant.agent

import java.util.Locale

data class SelectedContext(
    val evidence: List<AgentEvidence>,
    val omittedCount: Int,
    val deduplicatedCount: Int,
    val estimatedBytes: Int,
    val fingerprint: String
)

/**
 * Selects evidence by relevance and coverage instead of blindly keeping only
 * the newest records. This reduces long-horizon context drift while bounding
 * every model request.
 */
object EvidenceContextSelector {
    fun select(
        goal: AgentGoal,
        task: AgentTask,
        maxItems: Int = 16,
        maxCharacters: Int = 72_000,
    ): SelectedContext {
        val activeCycleId = goal.activeResearchCycleId
        val activeCycle = goal.researchCycles.firstOrNull { it.id == activeCycleId }
        val carryForwardIds = activeCycle?.learningSummary?.carryForwardEvidenceIds?.toSet() ?: emptySet()

        val cycleEvidence = if (activeCycleId == null) {
            goal.evidence
        } else {
            goal.evidence.filter { it.cycleId == activeCycleId || it.id in carryForwardIds }
        }

        if (cycleEvidence.isEmpty() || maxItems <= 0 || maxCharacters <= 0) {
            return SelectedContext(emptyList(), 0, 0, 0, "")
        }
        
        val queryTerms = tokenize(
            listOf(goal.userRequest, goal.objective, task.title, task.instructions, task.lastRecoveryStrategy ?: "")
                .joinToString(" "),
        )
        
        // Deduplicate evidence by canonical source URL or content hash
        val uniqueEvidence = cycleEvidence
            .filter { it.kind != AgentEvidenceKind.SYSTEM_EVENT }
            .distinctBy { evidence ->
                val sourceKey = evidence.sources.firstOrNull()?.url?.let { ResearchQualityGate.canonicalSourceUrl(it) }
                sourceKey ?: FingerprintUtils.hash("v1:content:${evidence.content}")
            }
        
        val deduplicatedCount = cycleEvidence.size - uniqueEvidence.size

        val ranked = uniqueEvidence
            .mapIndexed { index, evidence ->
                val textTerms = tokenize("${evidence.title} ${evidence.summary} ${evidence.content.take(10_000)}")
                val overlap = queryTerms.count(textTerms::contains)
                val score = overlap * 3.0 +
                    (if (evidence.taskId == task.id) 8.0 else 0.0) +
                    (if (evidence.sources.isNotEmpty()) 3.5 else 0.0) +
                    (if (evidence.kind == AgentEvidenceKind.PLAN) 1.5 else 0.0) +
                    (if (evidence.kind in setOf(AgentEvidenceKind.WEB_RESEARCH, AgentEvidenceKind.DEEP_RESEARCH, AgentEvidenceKind.RESEARCH_HIT)) 2.0 else 0.0) +
                    (index.toDouble() / uniqueEvidence.size.coerceAtLeast(1))
                RankedEvidence(evidence, score, index)
            }
            .sortedWith(compareByDescending<RankedEvidence> { it.score }.thenByDescending { it.index })

        val selected = mutableListOf<AgentEvidence>()
        var usedCharacters = 0
        fun selectIfItFits(evidence: AgentEvidence) {
            if (evidence in selected || selected.size >= maxItems) return
            val estimated = evidence.content.length.coerceAtMost(14_000) + 800
            if (selected.isNotEmpty() && usedCharacters + estimated > maxCharacters) return
            selected += evidence
            usedCharacters += estimated
        }

        // Protocol 42.6: Prioritize direct dependencies and the absolute latest discovery
        // record to ensure context continuity across milestone boundaries.
        val dependencyEvidence = goal.tasks
            .asSequence()
            .filter { it.id in task.dependsOn }
            .sortedByDescending { it.order }
            .mapNotNull { dependency ->
                dependency.outputEvidenceId?.let { evidenceId ->
                    uniqueEvidence.firstOrNull { it.id == evidenceId }
                } ?: uniqueEvidence.lastOrNull { it.taskId == dependency.id }
            }
            .distinctBy { it.id }
            .toList()
        dependencyEvidence.forEach(::selectIfItFits)

        // Protocol 42.6: Prioritize the absolute latest discovery record if the budget allows
        // for multiple items. This ensures context continuity in research chains while
        // allowing tight (maxItems=1) filters to pick the single most lexically relevant item.
        if (maxItems > 1) {
            uniqueEvidence.lastOrNull()?.let { selectIfItFits(it) }
        }

        ranked.forEach { rankedEvidence -> selectIfItFits(rankedEvidence.evidence) }

        // Final attempt for the newest record if not already selected.
        uniqueEvidence.lastOrNull()?.let { selectIfItFits(it) }
        
        val finalSelection = selected.distinctBy { it.id }.sortedBy { it.createdAt }
        val omittedCount = uniqueEvidence.size - finalSelection.size
        
        val fingerprint = FingerprintUtils.hash("v1:context_selection:" + finalSelection.joinToString("|") { it.id })
        
        return SelectedContext(
            evidence = finalSelection,
            omittedCount = omittedCount,
            deduplicatedCount = deduplicatedCount,
            estimatedBytes = usedCharacters,
            fingerprint = fingerprint
        )
    }

    private fun tokenize(text: String): Set<String> = TOKEN_PATTERN
        .findAll(text.lowercase(Locale.US))
        .map { it.value }
        .filter { it.length >= 3 && it !in STOP_WORDS }
        .take(700)
        .toSet()

    private data class RankedEvidence(
        val evidence: AgentEvidence,
        val score: Double,
        val index: Int,
    )

    private val TOKEN_PATTERN = Regex("[a-z0-9][a-z0-9_-]+")
    private val STOP_WORDS = setOf(
        "the", "and", "for", "with", "that", "this", "from", "into", "have", "will", "your", "user",
        "request", "task", "goal", "result", "work", "only", "must", "should", "could", "would", "about",
    )
}
