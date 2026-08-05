package com.david.openassistant.agent

/**
 * Validates research citations for truthful provenance and content alignment.
 * Complies with Citation Non-Self-Authorization Law (Law 5) and Excerpt-Matching Law (Law 7).
 */
object CitationValidator {

    data class ValidationReport(
        val isValid: Boolean,
        val invalidExcerpts: List<String> = emptyList(),
        val unverifiedUrls: List<String> = emptyList(),
        val reasons: List<String> = emptyList()
    )

    /**
     * Confidence levels for excerpt matching.
     */
    enum class MatchConfidence(val score: Double) {
        NONE(0.0),
        MEDIUM(0.5),
        HIGH(0.8),
        EXACT(1.0);

        fun isReliable(): Boolean = this.score >= 0.5
    }

    /**
     * Validates that all citations in a step result are grounded in the provided durable evidence.
     */
    fun validateStepResult(
        result: AgentStepResult,
        evidence: List<AgentEvidence>,
        isDummyContext: Boolean = false
    ): ValidationReport {
        val evidenceByUrl = buildMap<String, MutableList<AgentEvidence>> {
            evidence.forEach { ev ->
                ev.sources.forEach { source ->
                    getOrPut(source.url) { mutableListOf() }.add(ev)
                }
            }
        }

        val invalidExcerpts = mutableListOf<String>()
        val unverifiedUrls = mutableListOf<String>()
        val reasons = mutableListOf<String>()

        val verifiedUrls = evidenceByUrl.keys

        // 1. Validate excerpts and URLs in the sources list
        result.sources.forEach { citation ->
            val matchingEvidence = evidenceByUrl[citation.url].orEmpty()
            if (matchingEvidence.isEmpty()) {
                if (!isDummyContext) {
                    unverifiedUrls.add(citation.url)
                    reasons.add("Source URL '${citation.url}' has no matching record in durable evidence (Law 5).")
                    val excerpt = citation.excerpt
                    if (!excerpt.isNullOrBlank()) {
                        invalidExcerpts.add(excerpt)
                    }
                }
            } else {
                val excerpt = citation.excerpt
                if (!excerpt.isNullOrBlank()) {
                    val bestConfidence = matchingEvidence.maxOf { ev ->
                        containsExcerpt(ev.content, excerpt)
                    }
                    if (!bestConfidence.isReliable()) {
                        invalidExcerpts.add(excerpt)
                        reasons.add("Excerpt for '${citation.url}' failed semantic verification (Confidence: ${bestConfidence.name}) (Law 7).")
                    }
                }
            }
        }

        // 2. Cross-reference claims with verified sources only (Law 5)
        // Prohibit self-authorization: result.sources cannot authorize a URL, it must be in evidence.
        result.claims.forEach { claim ->
            if (claim.type == AgentClaimType.FACT) {
                claim.sourceUrls.forEach { url ->
                    if (url !in verifiedUrls) {
                        if (!isDummyContext) {
                            unverifiedUrls.add(url)
                            reasons.add("Factual claim '${claim.text.take(50)}...' cites URL not present in durable evidence: $url")
                        }
                    }
                }
            }
        }

        return ValidationReport(
            isValid = invalidExcerpts.isEmpty() && unverifiedUrls.isEmpty(),
            invalidExcerpts = invalidExcerpts.distinct(),
            unverifiedUrls = unverifiedUrls.distinct(),
            reasons = reasons.distinct()
        )
    }

    /**
     * Robust excerpt matching (Law 7).
     * Rejects blank inputs, performs exact match, then boundary-preserving normalization match.
     */
    fun containsExcerpt(content: String, excerpt: String): MatchConfidence {
        if (excerpt.isBlank() || content.isBlank()) return MatchConfidence.NONE

        // 1. Exact Unicode-aware substring matching (case-sensitive)
        if (content.contains(excerpt)) return MatchConfidence.EXACT

        // 2. Case-insensitive matching
        if (content.contains(excerpt, ignoreCase = true)) return MatchConfidence.HIGH

        // 3. Token-boundary preserving normalization for flexible matching
        val normalizedContent = normalizeForComparison(content)
        val normalizedExcerpt = normalizeForComparison(excerpt)

        if (normalizedExcerpt.isBlank()) return MatchConfidence.NONE

        // Ensure we don't match across different token boundaries by padding with spaces
        val paddedContent = " $normalizedContent "
        val paddedExcerpt = " $normalizedExcerpt "

        return if (paddedContent.contains(paddedExcerpt)) {
            MatchConfidence.MEDIUM
        } else {
            MatchConfidence.NONE
        }
    }

    /**
     * Normalizes text by preserving Unicode letter/digit boundaries and collapsing whitespace.
     */
    private fun normalizeForComparison(text: String): String {
        return text.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
