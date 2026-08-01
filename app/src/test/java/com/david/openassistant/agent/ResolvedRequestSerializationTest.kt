package com.david.openassistant.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolvedRequestSerializationTest {

    @Test
    fun resolvedResearchRequestJsonRoundtripPreservesAllFields() {
        val original = ResolvedResearchRequest(
            schemaVersion = 2,
            originalBaseRequest = "Buy take down recurve bow over $500",
            resolvedRequest = "Buy take down recurve bow over $500 (additional requirements: Hunting/3D; Modern ILF)",
            latestLiteralUserMessage = "needs to have Modern ILF",
            sourceMessageIds = listOf("msg-1", "msg-2", "msg-3"),
            sourceFragments = listOf(
                ResearchRequestSource("msg-1", "conv-1", 1, "Buy take down recurve bow over $500", 1000L, RequestSourceRole.BASE_REQUEST),
                ResearchRequestSource("msg-2", "conv-1", 2, "more Hunting/3D", 2000L, RequestSourceRole.ADDITIVE_REFINEMENT),
                ResearchRequestSource("msg-3", "conv-1", 3, "needs to have Modern ILF", 3000L, RequestSourceRole.ADDITIVE_REFINEMENT),
            ),
            requiredConstraints = listOf(
                ResearchConstraint("c-1", "Buy take down recurve bow over $500"),
                ResearchConstraint("c-2", "more Hunting/3D"),
                ResearchConstraint("c-3", "needs to have Modern ILF"),
            ),
            exclusions = emptyList(),
            unresolvedAmbiguities = emptyList(),
            resolutionMethod = "DETERMINISTIC_MULTI_TURN_MERGE",
            contentHash = "hash123456",
        )

        val json = original.toJson()
        val decoded = ResolvedResearchRequest.fromJson(json)

        assertNotNull(decoded)
        assertEquals(original.schemaVersion, decoded?.schemaVersion)
        assertEquals(original.originalBaseRequest, decoded?.originalBaseRequest)
        assertEquals(original.resolvedRequest, decoded?.resolvedRequest)
        assertEquals(original.latestLiteralUserMessage, decoded?.latestLiteralUserMessage)
        assertEquals(original.sourceMessageIds, decoded?.sourceMessageIds)
        assertEquals(original.sourceFragments.size, decoded?.sourceFragments?.size)
        assertEquals(original.requiredConstraints.size, decoded?.requiredConstraints?.size)
        assertEquals(original.contentHash, decoded?.contentHash)
    }

    @Test
    fun legacyGoalJsonWithoutResolvedRequestConstructsFallbackSafely() {
        val legacyJson = JSONObject()
            .put("id", "goal-legacy-1")
            .put("conversation_id", "conv-legacy-1")
            .put("user_request", "Legacy user request only")
            .put("title", "Legacy Title")
            .put("objective", "Legacy Objective")
            .put("final_output_description", "Deliverable")
            .put("status", "RUNNING")
            .put("planner_model_id", "openrouter/auto")
            .put("execution_model_id", "openrouter/auto")
            .put("tasks", org.json.JSONArray())

        val fallbackResolved = ResolvedResearchRequest.fromJson(legacyJson.optJSONObject("resolved_research_request"))
            ?: ResolvedResearchRequest.createFallbackSingleRequest(legacyJson.optString("user_request"))

        assertEquals("Legacy user request only", fallbackResolved.originalBaseRequest)
        assertEquals("Legacy user request only", fallbackResolved.resolvedRequest)
        assertEquals("Legacy user request only", fallbackResolved.latestLiteralUserMessage)
        assertTrue(fallbackResolved.contentHash.isNotBlank())
    }
}
