package com.david.openassistant.agent

import java.util.Locale

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

    data class CitationMatchResult(
        val confidence: MatchConfidence,
        val bindingMethod: CitationBindingMethod? = null,
        val passageStart: Int? = null,
        val passageEnd: Int? = null,
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

    data class PositionedToken(
        val normalized: String,
        val sourceStart: Int,
        val sourceEndExclusive: Int,
    )

    /**
     * Validates that all citations in a step result are grounded in the provided durable evidence.
     */
    fun validateStepResult(
        result: AgentStepResult,
        evidence: List<AgentEvidence>,
        sourceReads: List<SourceRead> = emptyList()
    ): ValidationReport {
        val readsByUrl = sourceReads.associateBy { ResearchQualityGate.canonicalSourceUrl(it.url) }
        val evidenceByUrl = buildMap<String, MutableList<AgentEvidence>> {
            evidence.forEach { ev ->
                ev.sources.forEach { source ->
                    getOrPut(ResearchQualityGate.canonicalSourceUrl(source.url)) { mutableListOf() }.add(ev)
                }
            }
        }
        
        val invalidExcerpts = mutableListOf<String>()
        val unverifiedUrls = mutableListOf<String>()
        val reasons = mutableListOf<String>()

        // 1. Validate excerpts in the sources list against source reads or evidence
        result.sources.forEach { citation ->
            val canonicalUrl = ResearchQualityGate.canonicalSourceUrl(citation.url)
            val matchingRead = readsByUrl[canonicalUrl]
            
            if (matchingRead == null) {
                val matchingEvidence = evidenceByUrl[canonicalUrl].orEmpty()
                if (matchingEvidence.isEmpty()) {
                    unverifiedUrls.add(citation.url)
                    reasons.add("Source URL '${citation.url}' has no matching record in durable evidence (Law 5).")
                    val excerpt = citation.excerpt
                    if (!excerpt.isNullOrBlank()) {
                        invalidExcerpts.add(excerpt)
                    }
                } else {
                    val excerpt = citation.excerpt
                    if (!excerpt.isNullOrBlank()) {
                        val bestMatch = matchingEvidence.map { ev ->
                            containsExcerpt(ev.content, excerpt)
                        }.maxByOrNull { it.confidence.score } ?: CitationMatchResult(MatchConfidence.NONE)
                        
                        if (!bestMatch.confidence.isReliable()) {
                            invalidExcerpts.add(excerpt)
                            reasons.add("Excerpt for '${citation.url}' failed semantic verification against historical evidence (Law 7).")
                        }
                    }
                }
            } else {
                val excerpt = citation.excerpt
                if (!excerpt.isNullOrBlank()) {
                    val match = containsExcerpt(matchingRead.content, excerpt)
                    if (!match.confidence.isReliable()) {
                        invalidExcerpts.add(excerpt)
                        reasons.add("Excerpt for '${citation.url}' failed semantic verification (Confidence: ${match.confidence.name}) (Law 7).")
                    }
                }
            }
        }

        // 2. Cross-reference factual claims with verified source reads or evidence (Law 5)
        result.claims.forEach { claim ->
            if (claim.type == AgentClaimType.FACT) {
                claim.sourceUrls.forEach { url ->
                    val canonicalUrl = ResearchQualityGate.canonicalSourceUrl(url)
                    if (canonicalUrl !in readsByUrl && canonicalUrl !in evidenceByUrl) {
                        unverifiedUrls.add(url)
                        reasons.add("Factual claim '${claim.text.take(50)}...' cites URL not present in durable evidence: $url")
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
     */
    fun containsExcerpt(content: String, excerpt: String): CitationMatchResult {
        if (excerpt.isBlank() || content.isBlank()) return CitationMatchResult(MatchConfidence.NONE)

        // 1. Exact Unicode-aware substring matching (case-sensitive)
        val exactIdx = content.indexOf(excerpt)
        if (exactIdx != -1) {
            return CitationMatchResult(MatchConfidence.EXACT, CitationBindingMethod.EXACT, exactIdx, exactIdx + excerpt.length)
        }

        // 2. Case-insensitive matching
        val lowerContent = content.lowercase(Locale.ROOT)
        val lowerExcerpt = excerpt.lowercase(Locale.ROOT)
        val ciIdx = lowerContent.indexOf(lowerExcerpt)
        if (ciIdx != -1) {
            return CitationMatchResult(MatchConfidence.HIGH, CitationBindingMethod.CASE_INSENSITIVE, ciIdx, ciIdx + excerpt.length)
        }

        // 3. Token-boundary preserving normalization
        val sourceTokens = tokenizeWithOffsets(content)
        val excerptTokens = tokenizeWithOffsets(excerpt)

        if (excerptTokens.isEmpty()) return CitationMatchResult(MatchConfidence.NONE)

        val match = findContiguousTokenMatch(sourceTokens, excerptTokens)
        return if (match != null) {
            CitationMatchResult(MatchConfidence.MEDIUM, CitationBindingMethod.NORMALIZED_TOKEN_BOUNDARY, match.first, match.second)
        } else {
            CitationMatchResult(MatchConfidence.NONE)
        }
    }

    private fun findContiguousTokenMatch(source: List<PositionedToken>, query: List<PositionedToken>): Pair<Int, Int>? {
        if (query.isEmpty() || source.size < query.size) return null
        
        for (i in 0..source.size - query.size) {
            var allMatch = true
            for (j in query.indices) {
                if (source[i + j].normalized != query[j].normalized) {
                    allMatch = false
                    break
                }
            }
            if (allMatch) {
                return source[i].sourceStart to source[i + query.size - 1].sourceEndExclusive
            }
        }
        return null
    }

    private fun tokenizeWithOffsets(text: String): List<PositionedToken> {
        val tokens = mutableListOf<PositionedToken>()
        val matcher = Regex("[\\p{L}\\p{N}]+").findAll(text)
        for (match in matcher) {
            tokens.add(PositionedToken(match.value.lowercase(Locale.ROOT), match.range.first, match.range.last + 1))
        }
        return tokens
    }

    internal fun normalizeForComparison(text: String): String {
        return text
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ") // Keep only Unicode letters and numbers
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
