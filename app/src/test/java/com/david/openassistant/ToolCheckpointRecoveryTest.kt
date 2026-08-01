package com.david.openassistant

import com.david.openassistant.agent.AgentToolExecution
import com.david.openassistant.agent.buildIncompleteToolCheckpointJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCheckpointRecoveryTest {
    @Test
    fun successfulToolWorkBecomesIncompleteDurableCheckpointWithoutClaims() {
        val raw = buildIncompleteToolCheckpointJson(
            reason = "Provider ended without structured text.",
            executions = listOf(
                AgentToolExecution("public_web_search", "searched", true),
                AgentToolExecution("public_web_fetch", "fetched", true),
                AgentToolExecution("cached_public_web_fetch", "cache", true),
                AgentToolExecution("public_web_fetch", "failed", false),
            ),
            distinctSourceCount = 7,
        )

        val checkpoint = JSONObject(raw!!)
        assertEquals(0.0, checkpoint.getDouble("completion_score"), 0.0)
        assertEquals(0, checkpoint.getJSONArray("claims").length())
        assertEquals(0, checkpoint.getJSONArray("acceptance_checks").length())
        assertTrue(checkpoint.getString("work_product").contains("Preserved 2 successful"))
        assertTrue(checkpoint.getString("work_product").contains("7 distinct source"))
        assertTrue(checkpoint.getString("work_product").contains("not a completed answer"))
    }

    @Test
    fun failedOrCachedCallsAloneCannotCreateCheckpoint() {
        val raw = buildIncompleteToolCheckpointJson(
            reason = "No final response.",
            executions = listOf(
                AgentToolExecution("public_web_fetch", "failed", false),
                AgentToolExecution("cached_public_web_search", "cache", true),
            ),
            distinctSourceCount = 2,
        )

        assertNull(raw)
    }
}
