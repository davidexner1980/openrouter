package com.david.openassistant.agent

import java.util.Locale

/**
 * Normalizes and validates research queries before they are sent to search providers.
 * Enforces conciseness, material anchors, and prevents prose or explanatory bloat.
 */
internal object SearchQueryValidator {
    private const val MAX_QUERY_WORDS = 15
    private const val MAX_QUERY_CHARS = 240

    sealed class ValidationResult {
        data class Valid(
            val executionText: String,
            val canonicalFingerprint: String,
            val matchedSubjectAnchors: List<String> = emptyList(),
            val anchorStrength: Double = 1.0,
            val anchorProvenance: String? = null,
            val informationNeed: String? = null,
            val sourceRole: String? = null,
            val discoveredEntityEvidenceIds: List<String> = emptyList(),
        ) : ValidationResult()
        
        data class Rejected(
            val reason: String,
            val reasonCode: String = "GENERIC_REJECTION",
            val matchedWeakAnchors: List<String> = emptyList()
        ) : ValidationResult()
    }

    private val CURRENT_YEAR: Int
        get() = java.time.Year.now().value

    private val PROSE_MARKERS = listOf(
        "search for", "find out", "tell me", "i want to", "look up", "can you", "please",
        "explanation of", "information about", "details on", "research into",
        "develop your own", "theory tell me", "distinct observational evidence",
        "produce emergent", "comprehensive evidence-grounded", "a database of",
        "scientific theory hypothesis testing", "theory of constraints",
        "material findings preserve", "provide comprehensive evidence", "satisfy acceptance criteria"
    )

    private val META_INSTRUCTION_MARKERS = listOf(
        "primary source",
        "primary-source",
        "document primary-source",
        "needs primary source",
        "framework primary source",
        "evidence needed",
        "needs have document",
        "needs framework",
        "instructions:",
        "role contract:",
        "epistemic role",
        "document primary-source verified",
        "acceptance criteria",
        "milestone instructions"
    )

    fun validate(
        query: String,
        request: String? = null,
        resolvedRequest: ResolvedResearchRequest? = null,
        sourceRole: String? = null,
        informationNeed: String? = null,
    ): ValidationResult {
        var normalized = query.replace(Regex("\\s+"), " ").trim()
        
        // Strip punctuation that suggests sentences rather than keywords
        normalized = normalized.replace(Regex("[.!?;:]"), " ").replace(Regex("\\s+"), " ").trim()

        if (normalized.isBlank()) return ValidationResult.Rejected("Empty query.", "EMPTY_QUERY")

        // Remove quantified acceptance-criteria boilerplate before deciding
        // whether the remaining query is prose or too long.
        normalized = normalized
            .replace(Regex("(?i)\\bat least\\s+\\d+\\b"), "")
            .replace(Regex("(?i)\\bno fewer than\\s+\\d+\\b"), "")
            .replace(Regex("(?i)\\bfor each (?:result|entry|claim|source)\\b"), "")
            .replace(Regex("(?i)\\b(?:provide|include|return|produce)\\s+\\d+\\s+(?:results|entries|claims|sources)\\b"), "")
            .replace(Regex("(?i)\\bconsistent normalized\\b"), "")
            .replace(Regex("\\s+"), " ").trim()

        if (normalized.isBlank()) return ValidationResult.Rejected("Query became empty after boilerplate removal.", "EMPTY_AFTER_BOILERPLATE")
        if (isProse(normalized)) return ValidationResult.Rejected("Query looks like prose or explanation.", "PROSE_DETECTED")
        if (isStaleDate(normalized, request)) return ValidationResult.Rejected("Query contains a stale year for a current intent.", "STALE_DATE")
        
        val anchors = resolvedRequest?.strongSubjectAnchors ?: requestAnchorTokens(request ?: "")
        val lowerQuery = normalized.lowercase(Locale.US)
        
        // Exact match for multi-word anchors or whole-word match for single words
        val matchedAnchors = anchors.filter { anchor ->
            val lowerAnchor = anchor.lowercase(Locale.US)
            if (lowerAnchor.contains(" ")) {
                lowerQuery.contains(lowerAnchor)
            } else {
                val words = lowerQuery.split(Regex("[^a-z0-9]"))
                words.contains(lowerAnchor)
            }
        }

        if (anchors.isNotEmpty() && matchedAnchors.isEmpty()) {
            return ValidationResult.Rejected("Query lacks material anchors ('${anchors.take(3).joinToString(", ")}') from the original request.", "MISSING_STRONG_ANCHORS")
        }
        
        // Remove leading prose markers
        val lower = normalized.lowercase(Locale.US)
        PROSE_MARKERS.forEach { marker ->
            if (lower.startsWith(marker)) {
                normalized = normalized.drop(marker.length).trim()
            }
        }

        // Strip punctuation that suggests sentences rather than keywords
        normalized = normalized.replace(Regex("[.!?;:]"), " ").replace(Regex("\\s+"), " ").trim()

        if (normalized.isBlank()) return ValidationResult.Rejected("Query became empty after prose-marker removal.", "EMPTY_AFTER_PROSE_REMOVAL")

        // Clamp length
        val words = normalized.split(" ")
        if (words.size > MAX_QUERY_WORDS) {
            normalized = words.take(MAX_QUERY_WORDS).joinToString(" ")
        }
        
        val executionText = normalized.take(MAX_QUERY_CHARS).trim()
        val canonicalFingerprint = calculateCanonicalFingerprint(
            subject = resolvedRequest?.canonicalSubject ?: "",
            need = informationNeed ?: "",
            role = sourceRole ?: "",
            queryText = executionText
        )

        return ValidationResult.Valid(
            executionText = executionText,
            canonicalFingerprint = canonicalFingerprint,
            matchedSubjectAnchors = matchedAnchors,
            anchorStrength = if (matchedAnchors.size >= anchors.size) 1.0 else 0.5,
            anchorProvenance = resolvedRequest?.subjectResolutionMethod,
            informationNeed = informationNeed,
            sourceRole = sourceRole
        )
    }

    private fun calculateCanonicalFingerprint(
        subject: String,
        need: String,
        role: String,
        queryText: String
    ): String {
        val base = "$subject|$need|$role|$queryText".lowercase(Locale.US)
            .replace(Regex("[^a-z0-9|]"), "")
        return base
    }

    fun isProse(query: String): Boolean {
        val lower = query.lowercase(Locale.US)
        if (PROSE_MARKERS.any { lower.startsWith(it) }) return true
        if (META_INSTRUCTION_MARKERS.any { lower.contains(it) }) return true
        if (query.contains(".") && query.indexOf(".") < query.length - 1) return true
        if (query.split(" ").size > MAX_QUERY_WORDS) return true
        
        // V22: Check for "Lateral Pivot ... Explanation ..." style output or other instruction leakage
        if (lower.contains("lateral pivot") || 
            lower.contains("explanation:") || 
            lower.contains("reasoning:") ||
            lower.contains("investigation model") ||
            lower.contains("decision target")) return true
            
        return false
    }

    /**
     * Historical dates are allowed when relevant. Only rejects years that are definitively stale 
     * for a 'current' intent (e.g., searching for "president 2020" in 2026).
     */
    fun isStaleDate(query: String, request: String? = null): Boolean {
        val yearPattern = Regex("\\b(20[0-2][0-9])\\b")
        val matches = yearPattern.findAll(query)
        val staleThreshold = CURRENT_YEAR - 2
        val hasStaleYear = matches.any { it.groupValues[1].toInt() < staleThreshold }
        if (!hasStaleYear) return false
        
        // Allow historical years if the request suggests a historical context
        val context = (request ?: "").lowercase(Locale.US)
        val historicalMarkers = listOf("history", "past", "archived", "predecessor", "legacy", "original", "before", "was", "were")
        if (historicalMarkers.any { context.contains(it) }) return false
        
        return true
    }

    fun hasMaterialAnchors(query: String, request: String): Boolean {
        val anchors = requestAnchorTokens(request)
        if (anchors.isEmpty()) return true
        val lowerQuery = query.lowercase(Locale.US)
        return anchors.any { lowerQuery.contains(it.lowercase(Locale.US)) }
    }
}
