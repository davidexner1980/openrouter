package com.david.openassistant.agent

import java.util.Locale

data class SourceRead(
    val id: String,
    val url: String,
    val canonicalUrl: String,
    val httpCode: Int,
    val contentType: String,
    val content: String,
    val sourceRole: String,
    val authorityScore: Int,
    val readAt: Long = System.currentTimeMillis(),
    val provenance: SourceReadProvenance = SourceReadProvenance.UNVERIFIED_CITATION,
)

data class EvidenceCandidate(
    val id: String,
    val sourceReadId: String,
    val canonicalUrl: String,
    val rawText: String,
    val structuredPath: String? = null,
    val relevanceScore: Int = 0,
)

data class NormalizedFact(
    val id: String,
    val evidenceCandidateId: String,
    val factValue: String,
    val units: String? = null,
    val entityName: String,
    val contentHash: String,
)

data class AcceptedClaim(
    val id: String,
    val taskId: String,
    val claimText: String,
    val sourceReadId: String,
    val evidenceCandidateId: String,
    val canonicalUrl: String,
    val sourceRole: String,
    val normalizedValue: String,
    val units: String? = null,
    val contentHash: String,
)

/**
 * Provider claim IDs are usually generic (for example, `claim-1`) and repeat
 * in every milestone. Scope them to the durable task identity before they
 * enter the goal-wide evidence graph so reviews and links cannot collide.
 */
internal fun scopedClaimId(
    taskId: String,
    requestedId: String,
    fallbackIndex: Int,
): String {
    fun safe(value: String, fallback: String): String = value
        .trim()
        .ifBlank { fallback }
        .replace(Regex("[^A-Za-z0-9_-]"), "_")

    fun bounded(value: String, maximumLength: Int): String {
        if (value.length <= maximumLength) return value
        val hash = value.hashCode().toUInt().toString(16).padStart(8, '0').takeLast(8)
        return "${value.take(maximumLength - hash.length - 1)}_$hash"
    }

    val taskNamespace = bounded(safe(taskId, "task"), 36)
    val fallback = "claim_${fallbackIndex.coerceAtLeast(1)}"
    val rawRequested = safe(requestedId, fallback)
    val prefix = "${taskNamespace}__"
    return if (rawRequested.startsWith(prefix)) {
        rawRequested.take(64)
    } else {
        (prefix + bounded(rawRequested, 26)).take(64)
    }
}

/**
 * Keeps the evidence graph about conclusions, not about the runtime's own
 * planning vocabulary.
 */
internal fun normalizeDurableClaims(
    task: AgentTask,
    claims: List<AgentClaim>,
): List<AgentClaim> = claims.mapNotNull { normalizeDurableClaim(task, it) }

internal fun normalizeDurableClaims(
    tasks: List<AgentTask>,
    claims: List<AgentClaim>,
): List<AgentClaim> {
    val tasksById = tasks.associateBy { it.id }
    return claims.mapNotNull { claim ->
        val task = tasksById[claim.taskId]
        if (task == null) normalizeClaimConfidence(claim) else normalizeDurableClaim(task, claim)
    }
}

internal fun isPlanningArtifactClaim(task: AgentTask, text: String): Boolean {
    val normalized = text.trim().replace(Regex("\\s+"), " ")
    if (normalized.isBlank()) return true
    if (normalized.endsWith("?")) return true
    if (PLANNING_ITEM_PREFIX.containsMatchIn(normalized)) return true

    val identity = "${task.id} ${task.title}".lowercase(Locale.US)
    val planningTask = task.capability == AgentCapability.REASON &&
        PLANNING_TASK_IDENTITY.containsMatchIn(identity)
    if (planningTask && REQUEST_RESTATEMENT_PREFIX.containsMatchIn(normalized)) return true
    return normalized.contains("The user asks for", ignoreCase = true)
}

private fun normalizeDurableClaim(task: AgentTask, claim: AgentClaim): AgentClaim? {
    if (isPlanningArtifactClaim(task, claim.text)) return null
    return normalizeClaimConfidence(claim)
}

internal fun normalizeClaimConfidence(claim: AgentClaim): AgentClaim {
    if (claim.support == AgentClaimSupport.CONTRADICTED && claim.confidence > 0.3) {
        return claim.copy(confidence = 0.2)
    }
    if (claim.support == AgentClaimSupport.UNSUPPORTED && claim.confidence > 0.8) {
        return claim.copy(confidence = 0.5)
    }
    return claim
}

private val PLANNING_ITEM_PREFIX = Regex("""^(?:step|task|phase|criterion|milestone|objective|constraint|decision|question)\s*\d*""", RegexOption.IGNORE_CASE)
private val PLANNING_TASK_IDENTITY = Regex("""(plan|brief|scope|requirements)""", RegexOption.IGNORE_CASE)
private val REQUEST_RESTATEMENT_PREFIX = Regex("""^(?:user\s+request|user\s+objective|user\s+wants|the\s+user\s+asks|the\s+user\s+requested|the\s+user|this\s)""", RegexOption.IGNORE_CASE)
