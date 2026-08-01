package com.david.openassistant

import com.david.openassistant.agent.MAX_LOCAL_TOOL_RESULT_PROMPT_CHARS
import com.david.openassistant.agent.MAX_LOCAL_TOOL_TRANSCRIPT_CHARS
import com.david.openassistant.agent.MAX_PROVIDER_TOOL_CALL_RECORDS_PER_ROUND
import com.david.openassistant.agent.allowedLocalToolCalls
import com.david.openassistant.agent.boundedLocalToolResult
import com.david.openassistant.agent.finalToolFreeCompletionPayload
import com.david.openassistant.agent.localToolBudgetExhausted
import com.david.openassistant.agent.normalizedProviderToolCallId
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolBudgetTest {
    @Test
    fun burstAndTotalCallLimitsAreBothEnforced() {
        assertEquals(4, allowedLocalToolCalls(requestedCalls = 9, totalAcceptedCalls = 0))
        assertEquals(4, MAX_PROVIDER_TOOL_CALL_RECORDS_PER_ROUND)
        assertEquals(2, allowedLocalToolCalls(requestedCalls = 4, totalAcceptedCalls = 8))
        assertEquals(0, allowedLocalToolCalls(requestedCalls = 1, totalAcceptedCalls = 10))
    }

    @Test
    fun roundCallAndTranscriptCeilingsRequestFinalization() {
        assertTrue(localToolBudgetExhausted(completedRounds = 4, totalAcceptedCalls = 0, transcriptCharacters = 0))
        assertTrue(localToolBudgetExhausted(completedRounds = 0, totalAcceptedCalls = 10, transcriptCharacters = 0))
        assertTrue(
            localToolBudgetExhausted(
                completedRounds = 0,
                totalAcceptedCalls = 0,
                transcriptCharacters = MAX_LOCAL_TOOL_TRANSCRIPT_CHARS,
            ),
        )
        assertFalse(localToolBudgetExhausted(completedRounds = 3, totalAcceptedCalls = 9, transcriptCharacters = 1_000))
    }

    @Test
    fun toolResultsAreBoundedPerResultAndByRemainingTranscript() {
        val perResult = boundedLocalToolResult("x".repeat(10_000), remainingTranscriptCharacters = 20_000)
        val remaining = boundedLocalToolResult("y".repeat(1_000), remainingTranscriptCharacters = 200)

        assertEquals(MAX_LOCAL_TOOL_RESULT_PROMPT_CHARS, perResult.length)
        assertTrue(perResult.contains("tool output truncated"))
        assertEquals(200, remaining.length)
    }

    @Test
    fun missingAndDuplicateProviderIdsBecomeBoundedAndUnique() {
        val used = mutableSetOf<String>()
        val first = normalizedProviderToolCallId("", "tool_call_0_0", used)
        val second = normalizedProviderToolCallId(first, "tool_call_0_1", used)
        val long = normalizedProviderToolCallId("z".repeat(500), "fallback", used)

        assertEquals("tool_call_0_0", first)
        assertNotEquals(first, second)
        assertTrue(long.length <= 160)
    }

    @Test
    fun finalCompletionPayloadRemovesEveryToolSurface() {
        val payload = JSONObject()
            .put("tools", JSONArray().put(JSONObject().put("type", "function")))
            .put("tool_choice", "required")
            .put("parallel_tool_calls", true)
            .put("plugins", JSONArray().put(JSONObject().put("id", "web")))
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "work")))

        val finalPayload = finalToolFreeCompletionPayload(payload)

        assertFalse(finalPayload.has("tools"))
        assertFalse(finalPayload.has("tool_choice"))
        assertFalse(finalPayload.has("parallel_tool_calls"))
        assertFalse(finalPayload.has("plugins"))
        assertEquals("user", finalPayload.getJSONArray("messages").getJSONObject(1).getString("role"))
    }
}
