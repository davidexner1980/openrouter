package com.david.openassistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchQueryIntegrityTest {

    @Test
    fun quantifiedAcceptanceBoilerplateIsRemovedButRealEntitiesAndMetricRemain() {
        val request = "Identify the highest and lowest elevation points in the United States."
        val raw = "United States highest lowest elevation at least 15 provide 3 sources"

        val result = SearchQueryValidator.validate(raw, request)
        assertTrue(result is SearchQueryValidator.ValidationResult.Valid)
        val text = (result as SearchQueryValidator.ValidationResult.Valid).executionText.lowercase()
        assertTrue(text.contains("united states"))
        assertTrue(text.contains("elevation"))
        assertFalse(text.contains("at least"))
        assertFalse(text.contains("provide 3 sources"))
    }

    @Test
    fun compactAnchorPreservesLateNamedEntityInsteadOfOnlyLeadingQuestionWords() {
        val anchor = extractCompactAnchor("What is the single most visited spot in America as of today?")
            .lowercase()
        assertTrue(anchor.contains("america"))
        assertTrue(anchor.contains("visited"))
        assertTrue(anchor.contains("spot"))
    }

    @Test
    fun elevationIsNotClassifiedAsInstructionBoilerplate() {
        val tokens = requestAnchorTokens("Find exact elevation and coordinates for Denali and Badwater Basin")
            .map(String::lowercase)
        assertTrue("elevation" in tokens)
        assertTrue("denali" in tokens)
        assertTrue("badwater" in tokens)
    }

    @Test
    fun rejectsMetaInstructionAndPromptLeakingQueries() {
        val bowRequest = "Find the best modern ILF takedown recurve bow or bow setup over $500, optimized for hunting and 3D archery."
        
        val rejectedQueries = listOf(
            "needs primary source evidence",
            "Modern ILF needs have Document primary-source verified",
            "modern needs framework primary source",
        )

        for (q in rejectedQueries) {
            val result = SearchQueryValidator.validate(q, bowRequest)
            assertTrue("Query '$q' must be rejected as prose/meta-instruction", result is SearchQueryValidator.ValidationResult.Rejected)
        }
    }

    @Test
    fun rejectsSemanticDriftAndLackingStrongAnchors() {
        val resolved = ResolvedResearchRequest.createFallbackSingleRequest("tell me everything you can about dark matter include your theory behind what you think it might be")
        // Overwrite fallback subject for exact test
        val target = resolved.copy(
            canonicalSubject = "dark matter",
            strongSubjectAnchors = listOf("dark matter")
        )
        
        val rejected = listOf(
            "Theory tell me everything you distinct observational evidence",
            "Theory of Constraints",
            "scientific theory hypothesis testing"
        )
        
        for (q in rejected) {
            val result = SearchQueryValidator.validate(q, null, target)
            assertTrue("Query '$q' must be rejected as lacking strong anchors or semantic drift", result is SearchQueryValidator.ValidationResult.Rejected)
        }
        
        val accepted = listOf(
            "dark matter observational evidence galaxy rotation lensing CMB",
            "dark matter direct detection limits XENONnT LZ PandaX",
            "ultralight dark matter Lyman-alpha constraints"
        )
        
        for (q in accepted) {
            val result = SearchQueryValidator.validate(q, null, target)
            assertTrue("Query '$q' should be valid", result is SearchQueryValidator.ValidationResult.Valid)
        }
    }
}
