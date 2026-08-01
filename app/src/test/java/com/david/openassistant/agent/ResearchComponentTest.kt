package com.david.openassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ResearchComponentTest {

    @Test
    fun queryExecutionStripsBoilerplateAndPreservesMeaningfulTerms() {
        val request = "What are the coordinates for Denali? At least 15 sources needed."
        val validation = SearchQueryValidator.validate("is single most visited spot in Denali at least 15", request)
        
        assertTrue("Validation should be Valid", validation is SearchQueryValidator.ValidationResult.Valid)
        val text = (validation as SearchQueryValidator.ValidationResult.Valid).executionText
        assertFalse(text.contains("at least 15"))
        assertTrue(text.lowercase(Locale.US).contains("denali"))
        assertTrue(text.lowercase(Locale.US).contains("visited"))
    }

    @Test
    fun sourceAuthorityRankingPrioritizesOfficialRecords() {
        val role = ResearchPassRole.PRIMARY
        val govSource = AgentSourceCitation("USGS Denali Elevation", "https://www.usgs.gov/denali", "Official geodetic record.")
        val aggregatorSource = AgentSourceCitation("Mountain Guide", "https://www.mountains.com/denali", "Top 10 highest mountains.")
        
        val govScore = computeDomainAuthorityScore(govSource.url, govSource.excerpt.orEmpty())
        val aggScore = computeDomainAuthorityScore(aggregatorSource.url, aggregatorSource.excerpt.orEmpty())
        
        assertTrue("Government source score ($govScore) should be higher than aggregator ($aggScore)", govScore > aggScore)
    }

    @Test
    fun semantic404IsRejectedInActualFetchPath() {
        val content = "404 Page Not Found - Denali Data missing."
        val result = validateSourceRead("https://usgs.gov/denali", 200, content)
        
        assertFalse(result.isValid)
        assertEquals(SourceReadRejectionReason.SEMANTIC_404, result.rejectionReason)
    }

    @Test
    fun globalWindowBoundPreventsEndlessLoops() {
        val task = AgentTask(
            id = "task-1",
            order = 0,
            title = "Research",
            instructions = "Find data",
            capability = AgentCapability.WEB_RESEARCH,
            globalAutomaticWindowReopenCount = MAX_GLOBAL_AUTOMATIC_RESEARCH_REOPENS
        )
        
        val now = 1000L
        val preciseFailure = "No data found"
        val result = task.reopenAutomaticResearchWindow(
            preciseFailure = preciseFailure,
            madeMeaningfulProgress = false,
            now = now
        )
        
        assertEquals(AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE, result.status)
        assertEquals(MAX_GLOBAL_AUTOMATIC_RESEARCH_REOPENS, result.globalAutomaticWindowReopenCount)
        assertEquals(now, result.finishedAt)
        assertTrue(result.lastError.orEmpty().contains("Exhausted", ignoreCase = true))
        assertTrue(result.lastError.orEmpty().contains(MAX_GLOBAL_AUTOMATIC_RESEARCH_REOPENS.toString()))
        assertTrue(result.lastError.orEmpty().contains(preciseFailure))
    }

    @Test
    fun globalWindowBoundStopsEvenWhenFinalWindowMadeSomeProgress() {
        val task = AgentTask(
            id = "task-progress-at-bound",
            order = 0,
            title = "Research",
            instructions = "Find authoritative data",
            capability = AgentCapability.DEEP_RESEARCH,
            globalAutomaticWindowReopenCount = MAX_GLOBAL_AUTOMATIC_RESEARCH_REOPENS,
        )

        val now = 2000L
        val preciseFailure = "One required primary source role remains unresolved."
        val result = task.reopenAutomaticResearchWindow(
            preciseFailure = preciseFailure,
            madeMeaningfulProgress = true,
            now = now,
        )

        assertEquals(AgentTaskStatus.BLOCKED_WITH_PARTIAL_EVIDENCE, result.status)
        assertEquals(MAX_GLOBAL_AUTOMATIC_RESEARCH_REOPENS, result.globalAutomaticWindowReopenCount)
        assertEquals(now, result.finishedAt)
        assertTrue(result.lastError.orEmpty().contains("Exhausted", ignoreCase = true))
        assertTrue(result.lastError.orEmpty().contains(MAX_GLOBAL_AUTOMATIC_RESEARCH_REOPENS.toString()))
        assertTrue(result.lastError.orEmpty().contains(preciseFailure))
        assertTrue(result.lastError.orEmpty().contains("acceptance gate is still unresolved"))
    }

}
