package com.david.openassistant

import com.david.openassistant.agent.MAX_AGENT_COMPLETION_TOKENS
import com.david.openassistant.agent.applyAgentCompletionLimit
import com.david.openassistant.agent.isStructuredOutputCompatibilityError
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCompatibilityTest {
    @Test
    fun geminiSchemaStateExplosionFallsBackToJsonMode() {
        val message = "The specified schema produces a constraint that has too many states for serving."

        assertTrue(isStructuredOutputCompatibilityError(400, message))
        assertFalse(isStructuredOutputCompatibilityError(500, message))
        assertFalse(isStructuredOutputCompatibilityError(400, "ordinary invalid request"))
    }

    @Test
    fun eachProviderCallHasAContinuationFriendlyOutputCeiling() {
        val payload = applyAgentCompletionLimit(JSONObject().put("model", "openrouter/auto"))

        assertEquals(MAX_AGENT_COMPLETION_TOKENS, payload.getInt("max_tokens"))
    }
}
