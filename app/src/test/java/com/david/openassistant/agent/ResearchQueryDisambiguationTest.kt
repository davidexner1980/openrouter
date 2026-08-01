package com.david.openassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class ResearchQueryDisambiguationTest {

    @Test
    fun disambiguatesSamickSageWithArcheryContext() {
        val goal = AgentGoal(
            conversationId = "id",
            userRequest = "What is the best Samick Sage takedown recurve bow?",
            title = "Research Samick Sage",
            objective = "Identify verified archery specs for the Samick Sage bow.",
            finalOutputDescription = "Report",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "model",
            executionModelId = "model",
            tasks = emptyList()
        )
        
        val disambiguated = applyDisambiguationLogic("Samick Sage", goal)
        assertEquals("Samick Sage archery", disambiguated)
    }

    @Test
    fun disambiguatesAmbiguousTerms() {
        val goal = AgentGoal(
            conversationId = "id",
            userRequest = "best black hunter takedown recurve bow",
            title = "Research Black Hunter",
            objective = "Find archery specs for the Black Hunter bow.",
            finalOutputDescription = "Report",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "model",
            executionModelId = "model",
            tasks = emptyList()
        )
        
        assertEquals("Black Hunter archery", applyDisambiguationLogic("Black Hunter", goal))
    }

    @Test
    fun doesNotDisambiguateIfAlreadyPresent() {
        val goal = AgentGoal(
            conversationId = "id",
            userRequest = "best black hunter takedown recurve bow",
            title = "Research Black Hunter",
            objective = "Find archery specs for the Black Hunter bow.",
            finalOutputDescription = "Report",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "model",
            executionModelId = "model",
            tasks = emptyList()
        )
        
        assertEquals("Black Hunter bow", applyDisambiguationLogic("Black Hunter bow", goal))
    }

    @Test
    fun disambiguatesPythonProgramming() {
        val goal = AgentGoal(
            conversationId = "id",
            userRequest = "how to use python for data science",
            title = "Python Research",
            objective = "Investigate the Python programming language.",
            finalOutputDescription = "Report",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "model",
            executionModelId = "model",
            tasks = emptyList()
        )
        
        assertEquals("Python programming", applyDisambiguationLogic("Python", goal))
    }

    private fun applyDisambiguationLogic(query: String, goal: AgentGoal): String {
        val normalizedQuery = query.lowercase(java.util.Locale.US)
        val context = "${goal.userRequest} ${goal.title} ${goal.objective}".lowercase(java.util.Locale.US)
        
        val ambiguousTerms = mapOf(
            "sage" to setOf("archery", "bow"),
            "spyder" to setOf("archery", "bow"),
            "hunter" to setOf("archery", "bow", "hunting"),
            "takedown" to setOf("archery", "bow"),
            "recurve" to setOf("archery", "bow"),
            "compound" to setOf("archery", "bow"),
            "mercury" to setOf("planet", "element", "outboard"),
            "delta" to setOf("airline", "river", "math"),
            "python" to setOf("programming", "language", "snake"),
        )
        
        val foundAmbiguousTerm = ambiguousTerms.keys.firstOrNull { it in normalizedQuery } ?: return query
        val requiredContext = ambiguousTerms[foundAmbiguousTerm] ?: return query
        
        val contextMissing = requiredContext.none { it in normalizedQuery }
        val contextAvailable = requiredContext.any { it in context }
        
        if (contextMissing && contextAvailable) {
            val disambiguator = requiredContext.firstOrNull { it in context } ?: requiredContext.first()
            return "$query $disambiguator"
        }
        
        return query
    }
}
