package com.david.openassistant.agent

import java.util.Locale

data class SourceRead(
    val id: String,
    val url: String,
    val canonicalUrl: String,
    val documentId: String,
    val contentHash: String,
    val httpCode: Int,
    val contentType: String,
    val content: String,
    val sourceRole: String,
    val authorityScore: Int,
    val retrievedAt: Long = System.currentTimeMillis(),
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
 * Stable ID for a source read based on its canonical URL and content hash.
 * Follows the Immutable Source-Read Law.
 */
internal fun scopedSourceReadId(canonicalUrl: String, contentHash: String): String {
    if (canonicalUrl.isBlank()) return "src_invalid_blank_url"
    if (contentHash.isBlank()) return "src_invalid_missing_hash"
    // Incorporate content hash to ensure immutability if content changes for the same URL
    val identityInput = canonicalUrl + contentHash
    return "src_${FingerprintUtils.hash(identityInput).takeLast(16)}"
}

/**
 * Stable logical identity for a document based on its canonical URL.
 * Follows the Immutable Source-Read Law.
 */
internal fun scopedSourceDocumentId(canonicalUrl: String): String {
    if (canonicalUrl.isBlank()) return "doc_invalid_blank_url"
    return "doc_${FingerprintUtils.hash(canonicalUrl).takeLast(16)}"
}

/**
 * Deterministic fingerprint for a citation binding.
 */
internal fun calculateCitationBindingFingerprint(
    identity: CitationBindingIdentity
): String {
    val encoder = FingerprintUtils.CanonicalEncoder()
    encoder.append("v", identity.schemaVersion.toString())
    encoder.append("clm_fp", identity.claimFingerprint)
    encoder.append("src_id", identity.sourceReadId)
    encoder.append("doc_id", identity.documentId)
    encoder.append("content_hash", identity.contentHash)
    encoder.append("passage_hash", identity.passageHash)
    encoder.append("method", identity.bindingMethod.name)
    return FingerprintUtils.hash(encoder.build())
}

/**
 * Durable ID for a citation binding derived from its logical fingerprint.
 */
internal fun scopedCitationBindingId(fingerprint: String): String {
    return "bnd_${fingerprint.takeLast(16)}"
}

/**
 * Deterministic fingerprint for a task-local claim based on its type and text.
 */
internal fun calculateClaimFingerprint(
    taskId: String,
    type: AgentClaimType,
    text: String,
): String = FingerprintUtils.hash(
    taskId.trim() +
        "\u0000" +
        type.name +
        "\u0000" +
        text.normalizedClaimText()
)

/**
 * Durable ID for a claim derived from its task-local fingerprint.
 */
internal fun scopedClaimId(fingerprint: String): String {
    return "clm_${fingerprint.takeLast(16)}"
}

/**
 * Legacy support for scopedClaimId.
 */
internal fun scopedClaimId(
    taskId: String,
    requestedId: String,
    text: String,
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
    val fallback = if (text.isNotBlank()) {
        "txt_${FingerprintUtils.hash(text.normalizedClaimText()).takeLast(8)}"
    } else {
        "claim_${fallbackIndex.coerceAtLeast(1)}"
    }
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

/**
 * Merges equivalent claim records based on their ID (fingerprint-derived).
 * Uses FactualClaimSupportPolicy to reconcile support state following Law 5.
 */
internal fun mergeClaims(
    existing: List<AgentClaim>,
    incoming: List<AgentClaim>,
    sourceReads: List<SourceRead>
): List<AgentClaim> {
    if (incoming.isEmpty()) return existing
    
    val result = existing.associateBy { it.id }.toMutableMap()
    
    incoming.forEach { incomingClaim ->
        val existingClaim = result[incomingClaim.id]
        if (existingClaim == null) {
            // New claim: re-evaluate with current source reads to ensure grounding
            val decision = FactualClaimSupportPolicy.evaluate(incomingClaim, sourceReads)
            val support = when (decision) {
                is FactualClaimSupportDecision.Supported -> AgentClaimSupport.SUPPORTED
                is FactualClaimSupportDecision.PartiallyBound -> AgentClaimSupport.PARTIAL
                is FactualClaimSupportDecision.Contradicted -> AgentClaimSupport.CONTRADICTED
                else -> AgentClaimSupport.UNSUPPORTED
            }
            val validBindings = when (decision) {
                is FactualClaimSupportDecision.Supported -> decision.validBindings
                is FactualClaimSupportDecision.PartiallyBound -> decision.validBindings
                else -> emptyList()
            }
            result[incomingClaim.id] = incomingClaim.copy(support = support, citationBindings = validBindings)
        } else {
            // Existing claim: Merge evidence and reconcile support
            val mergedEvidenceIds = (existingClaim.supportingEvidenceIds + incomingClaim.supportingEvidenceIds).distinct()
            val mergedSourceUrls = (existingClaim.sourceUrls + incomingClaim.sourceUrls).distinct()
            val mergedBindings = mergeCitationBindings(existingClaim.citationBindings, incomingClaim.citationBindings)
            
            val mergedBase = existingClaim.copy(
                supportingEvidenceIds = mergedEvidenceIds,
                sourceUrls = mergedSourceUrls,
                citationBindings = mergedBindings
            )
            
            val decision = FactualClaimSupportPolicy.evaluate(mergedBase, sourceReads)
            val support = when {
                existingClaim.support == AgentClaimSupport.CONTRADICTED -> AgentClaimSupport.CONTRADICTED
                incomingClaim.support == AgentClaimSupport.CONTRADICTED -> AgentClaimSupport.CONTRADICTED
                decision is FactualClaimSupportDecision.Supported -> AgentClaimSupport.SUPPORTED
                decision is FactualClaimSupportDecision.PartiallyBound -> AgentClaimSupport.PARTIAL
                else -> AgentClaimSupport.UNSUPPORTED
            }
            val validBindings = when (decision) {
                is FactualClaimSupportDecision.Supported -> decision.validBindings
                is FactualClaimSupportDecision.PartiallyBound -> decision.validBindings
                else -> emptyList()
            }
            
            result[existingClaim.id] = mergedBase.copy(support = support, citationBindings = validBindings)
        }
    }
    
    return result.values.toList()
}

/**
 * Merges citation bindings deterministically and idempotently.
 */
internal fun mergeCitationBindings(
    existing: List<CitationBinding>,
    incoming: List<CitationBinding>
): List<CitationBinding> {
    if (incoming.isEmpty()) return existing
    
    // Key by logical fingerprint if available, otherwise by ID
    fun bindingKey(b: CitationBinding): String = b.logicalFingerprint ?: b.id
    
    val result = existing.associateBy { bindingKey(it) }.toMutableMap()
    
    incoming.forEach { incomingBinding ->
        val key = bindingKey(incomingBinding)
        val existingBinding = result[key]
        if (existingBinding == null) {
            result[key] = incomingBinding
        } else {
            // Reconcile bindings: favor those with identity metadata over legacy
            if (existingBinding.identitySchemaVersion == 0 && incomingBinding.identitySchemaVersion > 0) {
                result[key] = incomingBinding
            } else if (existingBinding.identitySchemaVersion == incomingBinding.identitySchemaVersion) {
                // If same version, favor higher confidence or preserve existing if equal
                if (incomingBinding.confidence > existingBinding.confidence) {
                    result[key] = incomingBinding
                }
            }
        }
    }
    
    return result.values.toList().sortedBy { it.id }
}

/**
 * Merges source reads following the Source-Merge Law.
 * Preserves distinct snapshots of the same URL if content differs, 
 * resolves provenance conflicts, and ensures ID uniqueness.
 */
internal fun mergeSourceReads(existing: List<SourceRead>, incoming: List<SourceRead>): List<SourceRead> {
    if (incoming.isEmpty()) return existing
    
    val result = existing.associateBy { it.id }.toMutableMap()
    
    incoming.forEach { incomingRead ->
        val existingRead = result[incomingRead.id]
        if (existingRead == null) {
            result[incomingRead.id] = incomingRead
        } else {
            // IMMUTABILITY: If it exists, do not change identity-bearing or historical fields.
            // However, we can preserve the strongest provenance if it's the same content snapshot.
            val existingStrength = existingRead.provenance.provenanceStrength()
            val incomingStrength = incomingRead.provenance.provenanceStrength()
            
            if (incomingStrength > existingStrength) {
                // Update provenance but KEEP existing retrievedAt and identity fields
                result[incomingRead.id] = existingRead.copy(
                    provenance = incomingRead.provenance
                )
            }
        }
    }
    
    val mergedList = result.values.toList()
    // Assert no duplicate IDs in the final list
    val distinctIds = mergedList.map { it.id }.distinct()
    if (mergedList.size != distinctIds.size) {
        throw IllegalStateException("Duplicate SourceRead IDs detected after merge")
    }
    
    return mergedList
}

private fun SourceReadProvenance.provenanceStrength(): Int = when (this) {
    SourceReadProvenance.VERIFIED_FETCH -> 4
    SourceReadProvenance.PROVIDER_EXTRACT -> 3
    SourceReadProvenance.LEGACY_ASSUMED -> 2
    SourceReadProvenance.UNVERIFIED_CITATION -> 1
}

private val PLANNING_ITEM_PREFIX = Regex("""^(?:step|task|phase|criterion|milestone|objective|constraint|decision|question)\s*\d*""", RegexOption.IGNORE_CASE)
private val PLANNING_TASK_IDENTITY = Regex("""(plan|brief|scope|requirements)""", RegexOption.IGNORE_CASE)
private val REQUEST_RESTATEMENT_PREFIX = Regex("""^(?:user\s+request|user\s+objective|user\s+wants|the\s+user\s+asks|the\s+user\s+requested|the\s+user|this\s)""", RegexOption.IGNORE_CASE)
