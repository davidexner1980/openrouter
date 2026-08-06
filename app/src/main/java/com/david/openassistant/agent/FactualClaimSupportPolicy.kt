package com.david.openassistant.agent

sealed interface FactualClaimSupportDecision {
    data class Supported(
        val validBindings: List<CitationBinding>,
    ) : FactualClaimSupportDecision

    data class PartiallyBound(
        val validBindings: List<CitationBinding>,
        val reasons: List<String>,
    ) : FactualClaimSupportDecision

    data class Unsupported(
        val reasons: List<String>,
    ) : FactualClaimSupportDecision

    data class Contradicted(
        val reasons: List<String>,
    ) : FactualClaimSupportDecision
}

/**
 * Authoritative policy for factual claim support.
 * Complies with Laws 5, 6, and 7.
 */
object FactualClaimSupportPolicy {

    /**
     * Re-verifies all bindings for a claim and returns the authoritative support decision.
     */
    fun evaluate(
        claim: AgentClaim,
        sourceReads: List<SourceRead>,
    ): FactualClaimSupportDecision {
        if (claim.type != AgentClaimType.FACT) {
            // Non-factual claims follow their established support status
            return when (claim.support) {
                AgentClaimSupport.SUPPORTED -> FactualClaimSupportDecision.Supported(emptyList())
                AgentClaimSupport.PARTIAL -> FactualClaimSupportDecision.PartiallyBound(emptyList(), emptyList())
                AgentClaimSupport.CONTRADICTED -> FactualClaimSupportDecision.Contradicted(emptyList())
                else -> FactualClaimSupportDecision.Unsupported(emptyList())
            }
        }

        val readsById = sourceReads.associateBy { it.id }
        val validBindings = mutableListOf<CitationBinding>()
        val reasons = mutableListOf<String>()

        claim.citationBindings.forEach { binding ->
            val validation = validateBinding(claim, binding, readsById[binding.sourceReadId])
            if (validation.isValid) {
                validBindings.add(binding)
            } else {
                reasons.addAll(validation.reasons)
            }
        }

        return when {
            claim.support == AgentClaimSupport.CONTRADICTED -> FactualClaimSupportDecision.Contradicted(reasons)
            validBindings.isNotEmpty() -> FactualClaimSupportDecision.Supported(validBindings)
            reasons.isNotEmpty() -> FactualClaimSupportDecision.Unsupported(reasons)
            else -> FactualClaimSupportDecision.Unsupported(listOf("no_bindings_present"))
        }
    }

    private data class BindingValidation(val isValid: Boolean, val reasons: List<String> = emptyList())

    private fun validateBinding(claim: AgentClaim, binding: CitationBinding, read: SourceRead?): BindingValidation {
        val reasons = mutableListOf<String>()
        
        if (read == null) return BindingValidation(false, listOf("source_read_missing"))
        
        if (binding.claimId != claim.id) reasons.add("claim_id_mismatch")
        if (binding.documentId != read.documentId) reasons.add("document_id_mismatch")
        if (binding.contentHash != read.contentHash) reasons.add("content_hash_mismatch")
        
        val canonicalUrl = ResearchQualityGate.canonicalSourceUrl(read.url)
        if (claim.sourceUrls.none { ResearchQualityGate.canonicalSourceUrl(it) == canonicalUrl }) {
            reasons.add("canonical_url_mismatch")
        }

        if (binding.citationExcerpt.isBlank()) reasons.add("citation_excerpt_missing")

        // Re-match excerpt
        val matchResult = CitationValidator.containsExcerpt(read.content, binding.citationExcerpt)
        if (!matchResult.confidence.isReliable()) {
            reasons.add("binding_confidence_insufficient")
        }
        if (binding.bindingMethod != CitationBindingMethod.LEGACY_UNKNOWN && matchResult.bindingMethod != binding.bindingMethod) {
            reasons.add("binding_method_mismatch")
        }
        
        // Range validation
        if (binding.passageStart != null && binding.passageEnd != null) {
            if (binding.passageStart < 0 || binding.passageEnd > read.content.length || binding.passageStart >= binding.passageEnd) {
                reasons.add("passage_range_invalid")
            } else {
                val rawPassage = read.content.substring(binding.passageStart, binding.passageEnd)
                val rawHash = FingerprintUtils.hash(rawPassage)
                if (binding.passageHash != null && rawHash != binding.passageHash) {
                    reasons.add("passage_hash_mismatch")
                }
            }
        }

        // Admissibility
        if (read.provenance !in setOf(SourceReadProvenance.VERIFIED_FETCH, SourceReadProvenance.PROVIDER_EXTRACT, SourceReadProvenance.LEGACY_ASSUMED)) {
            reasons.add("inadmissible_provenance")
        }

        // Claim-to-passage alignment (Fail-closed)
        if (!alignClaimToPassage(claim, binding, read)) {
            reasons.add("claim_passage_alignment_failed")
        }

        return BindingValidation(reasons.isEmpty(), reasons)
    }

    private fun alignClaimToPassage(claim: AgentClaim, binding: CitationBinding, read: SourceRead): Boolean {
        if (binding.passageStart == null || binding.passageEnd == null) return false
        val passage = read.content.substring(binding.passageStart, binding.passageEnd).trim()
        
        if (passage.isBlank() || passage.length < 4) return false

        // Exact or case-insensitive alignment
        if (passage.contains(claim.text, ignoreCase = true) || claim.text.contains(passage, ignoreCase = true)) {
            return true
        }
        
        // Normalized token alignment
        val normalizedPassage = CitationValidator.normalizeForComparison(passage)
        val normalizedClaim = CitationValidator.normalizeForComparison(claim.text)
        if (normalizedPassage.contains(normalizedClaim) || normalizedClaim.contains(normalizedPassage)) {
            return true
        }

        // Material identifiers check (numbers, dates)
        val materialIds = extractMaterialIdentifiers(claim.text)
        if (materialIds.isNotEmpty()) {
            return materialIds.all { passage.contains(it, ignoreCase = true) }
        }

        return false
    }

    private fun extractMaterialIdentifiers(text: String): List<String> {
        // Simple regex for numbers, dates-like, and potential units
        return Regex("""\b(\d+[\d.,/]*|[A-Z][a-z]+ \d{1,2},? \d{4})\b""").findAll(text).map { it.value }.toList()
    }
}
