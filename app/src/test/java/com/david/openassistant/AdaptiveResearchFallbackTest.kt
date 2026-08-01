package com.david.openassistant

import com.david.openassistant.agent.AgentCapability
import com.david.openassistant.agent.AgentGoal
import com.david.openassistant.agent.AgentGoalStatus
import com.david.openassistant.agent.AgentTask
import com.david.openassistant.agent.ResearchPassRole
import com.david.openassistant.agent.buildRequestSpecificStrategyFallback
import com.david.openassistant.agent.extractCompactAnchor
import com.david.openassistant.agent.normalizeQueryForComparison
import com.david.openassistant.agent.stripBoilerplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveResearchFallbackTest {

    @Test
    fun extractCompactAnchorStripsBoilerplateAndStopWords() {
        val request = "What are exact GPS coordinates for the highest elevation point and lowest point in the United States? At least one official datasheet must be provided."
        val anchor = extractCompactAnchor(request)

        assertFalse(anchor.contains("at least one"))
        assertFalse(anchor.contains("must be provided"))
        assertTrue(anchor.contains("GPS") || anchor.contains("coordinates") || anchor.contains("highest"))
    }

    @Test
    fun stripBoilerplateRemovesCommonAcceptancePhrases() {
        val text = "at least one official datasheet must include exact gps coordinates for the elevation for Denali"
        val cleaned = stripBoilerplate(text)

        assertFalse(cleaned.contains("at least one"))
        assertFalse(cleaned.contains("must include"))
        assertFalse(cleaned.contains("exact gps coordinates"))
        assertTrue(cleaned.contains("Denali"))
    }

    @Test
    fun buildFallbackStrategyProducesDistinctNormalizedQueries() {
        val goal = AgentGoal(
            id = "goal-1",
            conversationId = "conv-1",
            userRequest = "Identify the highest and lowest elevation points in the United States.",
            title = "Elevation test",
            objective = "Source-traceable elevations",
            finalOutputDescription = "Final report",
            status = AgentGoalStatus.PLANNING,
            plannerModelId = "auto-beta",
            executionModelId = "auto-beta",
            tasks = emptyList(),
        )

        val task = AgentTask(
            id = "task-1",
            order = 0,
            title = "High and Low Points",
            instructions = "Find USGS or NGS coordinates for Denali and Badwater Basin.",
            capability = AgentCapability.WEB_RESEARCH,
            acceptanceCriteria = emptyList(),
        )

        val strategy = buildRequestSpecificStrategyFallback(
            goal = goal,
            task = task,
            role = ResearchPassRole.PRIMARY,
            minimumQueries = 3,
        )

        val queryTexts = strategy.queries.map { it.query }
        val normalizedQueries = queryTexts.map { normalizeQueryForComparison(it) }

        assertEquals(queryTexts.size, normalizedQueries.distinct().size)
    }
}
