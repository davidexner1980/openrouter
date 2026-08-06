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
        val partialBindings = mutableListOf<CitationBinding>()
        val reasons = mutableListOf<String>()

        claim.citationBindings.forEach { binding ->
            val validation = validateBinding(claim, binding, readsById[binding.sourceReadId])
            if (validation.isValid) {
                if (validation.isPublicationGrade) {
                    validBindings.add(binding)
                } else {
                    partialBindings.add(binding)
                    reasons.addAll(validation.reasons)
                }
            } else {
                reasons.addAll(validation.reasons)
            }
        }

        return when {
            claim.support == AgentClaimSupport.CONTRADICTED -> FactualClaimSupportDecision.Contradicted(reasons)
            validBindings.isNotEmpty() -> FactualClaimSupportDecision.Supported(validBindings)
            partialBindings.isNotEmpty() -> FactualClaimSupportDecision.PartiallyBound(partialBindings, reasons)
            reasons.isNotEmpty() -> FactualClaimSupportDecision.Unsupported(reasons)
            else -> FactualClaimSupportDecision.Unsupported(listOf("no_bindings_present"))
        }
    }

    private data class BindingValidation(
        val isValid: Boolean,
        val isPublicationGrade: Boolean = false,
        val reasons: List<String> = emptyList()
    )

    private fun validateBinding(claim: AgentClaim, binding: CitationBinding, read: SourceRead?): BindingValidation {
        val reasons = mutableListOf<String>()
        
        if (read == null) return BindingValidation(false, false, listOf("source_read_missing"))
        
        // Integrity: recompute hash
        val recomputedHash = FingerprintUtils.hash(read.content)
        if (recomputedHash != read.contentHash) {
            reasons.add("source_content_hash_mismatch")
        }
        if (binding.contentHash != read.contentHash) {
            reasons.add("binding_content_hash_mismatch")
        }
        
        // Identity validation (Recompute and verify where practical)
        if (read.documentId != scopedSourceDocumentId(read.canonicalUrl)) {
            reasons.add("source_document_id_malformed")
        }
        if (read.id != scopedSourceReadId(read.canonicalUrl, read.contentHash)) {
            reasons.add("source_read_id_malformed")
        }

        if (binding.claimId != claim.id) reasons.add("claim_id_mismatch")
        if (binding.documentId != read.documentId) reasons.add("document_id_mismatch")
        
        val canonicalUrl = ResearchQualityGate.canonicalSourceUrl(read.url)
        if (claim.sourceUrls.none { ResearchQualityGate.canonicalSourceUrl(it) == canonicalUrl }) {
            reasons.add("canonical_url_mismatch")
        }

        if (binding.citationExcerpt.isBlank()) reasons.add("citation_excerpt_missing")
        if (read.content.isBlank()) reasons.add("source_content_blank")

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
        val isPublicationGradeProvenance = when (read.provenance) {
            SourceReadProvenance.VERIFIED_FETCH -> true
            SourceReadProvenance.PROVIDER_EXTRACT -> true
            SourceReadProvenance.LEGACY_ASSUMED -> {
                reasons.add("legacy_evidence_requires_revalidation")
                false
            }
            SourceReadProvenance.UNVERIFIED_CITATION -> {
                reasons.add("unverified_citation_inadmissible")
                false
            }
        }

        // Claim-to-passage alignment (Fail-closed)
        val alignment = alignClaimToPassage(claim, binding, read)
        if (!alignment.isSupported) {
            reasons.add("claim_passage_alignment_failed")
        }
        if (alignment.isContradicted) {
            reasons.add("claim_passage_contradiction_detected")
            return BindingValidation(false, false, reasons)
        }

        val isValid = reasons.isEmpty() || (reasons.all { it == "legacy_evidence_requires_revalidation" } && alignment.isSupported)
        val isPublicationGrade = isValid && isPublicationGradeProvenance

        return BindingValidation(isValid, isPublicationGrade, reasons)
    }

    private data class AlignmentResult(val isSupported: Boolean, val isContradicted: Boolean = false)

    private fun alignClaimToPassage(claim: AgentClaim, binding: CitationBinding, read: SourceRead): AlignmentResult {
        if (binding.passageStart == null || binding.passageEnd == null) return AlignmentResult(false)
        val passage = read.content.substring(binding.passageStart, binding.passageEnd).trim()
        
        if (passage.isBlank() || passage.length < 4) return AlignmentResult(false)

        // Exact or case-insensitive alignment
        if (passage.contains(claim.text, ignoreCase = true) || claim.text.contains(passage, ignoreCase = true)) {
            return AlignmentResult(true)
        }
        
        // Normalized token alignment
        val normalizedPassage = CitationValidator.normalizeForComparison(passage)
        val normalizedClaim = CitationValidator.normalizeForComparison(claim.text)
        if (normalizedPassage.contains(normalizedClaim) || normalizedClaim.contains(normalizedPassage)) {
            return AlignmentResult(true)
        }

        // Polarity check (Safeguard)
        if (detectPolarityMismatch(claim.text, passage)) {
            return AlignmentResult(false, isContradicted = true)
        }

        return AlignmentResult(false)
    }

    private fun detectPolarityMismatch(claim: String, passage: String): Boolean {
        val pairs = listOf(
            "rose" to "fell",
            "increased" to "decreased",
            "up" to "down",
            "above" to "below",
            "more than" to "less than",
            "before" to "after",
            "approved" to "rejected",
            "present" to "absent",
            "positive" to "negative"
        )
        return pairs.any { (p1, p2) ->
            (claim.contains(p1, ignoreCase = true) && passage.contains(p2, ignoreCase = true)) ||
            (claim.contains(p2, ignoreCase = true) && passage.contains(p1, ignoreCase = true))
        }
    }
}
