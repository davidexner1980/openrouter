package com.david.openassistant.agent

import java.util.Locale

/**
 * Validates research citations for truthful provenance and content alignment.
 * [PB-001] Mission recovery must preserve evidence and provenance.
 */
object CitationValidator {

    data class ValidationReport(
        val isValid: Boolean,
        val invalidExcerpts: List<String> = emptyList(),
        val unverifiedUrls: List<String> = emptyList(),
        val reasons: List<String> = emptyList()
    )

    /**
     * Validates that all citations in a step result are grounded in the provided evidence.
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

        // 1. Validate excerpts in the sources list
        result.sources.forEach { citation ->
            val excerpt = citation.excerpt
            if (!excerpt.isNullOrBlank()) {
                val matchingEvidence = evidenceByUrl[citation.url].orEmpty()
                if (matchingEvidence.isEmpty()) {
                    if (!isDummyContext) {
                        unverifiedUrls.add(citation.url)
                        reasons.add("Citation URL '${citation.url}' has no matching evidence record.")
                    }
                } else {
                    val foundInAny = matchingEvidence.any { ev ->
                        containsExcerpt(ev.content, excerpt)
                    }
                    if (!foundInAny) {
                        invalidExcerpts.add(excerpt)
                        reasons.add("Excerpt for '${citation.url}' not found in any matching evidence record.")
                    }
                }
            }
        }

        // 2. Cross-reference claims with verified sources
        val verifiedUrls = evidenceByUrl.keys
        val resultSourceUrls = result.sources.map { it.url }.toSet()
        
        result.claims.forEach { claim ->
            if (claim.type == AgentClaimType.FACT) {
                claim.sourceUrls.forEach { url ->
                    if (url !in verifiedUrls && url !in resultSourceUrls) {
                        if (!isDummyContext) {
                            unverifiedUrls.add(url)
                            reasons.add("Factual claim '${claim.text.take(50)}...' cites unverified URL: $url")
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
     * Flexible excerpt matching to account for minor whitespace or punctuation differences.
     */
    fun containsExcerpt(content: String, excerpt: String): Boolean {
        if (excerpt.isBlank()) return true
        if (content.contains(excerpt, ignoreCase = true)) return true

        val normalizedContent = normalizeForComparison(content)
        val normalizedExcerpt = normalizeForComparison(excerpt)

        if (normalizedExcerpt.length < 10) return normalizedContent.contains(normalizedExcerpt)

        // Try fuzzy match if long enough: allowed to miss some characters if it's a large block
        return normalizedContent.contains(normalizedExcerpt)
    }

    private fun normalizeForComparison(text: String): String {
        return text.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]"), "")
    }
}
