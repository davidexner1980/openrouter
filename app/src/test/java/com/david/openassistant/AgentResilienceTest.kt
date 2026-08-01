package com.david.openassistant

import com.david.openassistant.agent.cloneResearchRequestPayload
import com.david.openassistant.agent.isLegacyMissionBudgetStop
import com.david.openassistant.agent.normalizeAgentFailureMessage
import com.david.openassistant.agent.relaxRequiredFunctionToolChoice
import com.david.openassistant.agent.recoverHttpsSourceCitations
import com.david.openassistant.agent.requiredFunctionToolChoice
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentResilienceTest {
    @Test
    fun focusedRecoveryForcesOneSpecificFunctionThenRelaxesTheChoice() {
        val payload = JSONObject()
            .put("tool_choice", requiredFunctionToolChoice("calculate"))

        val choice = payload.getJSONObject("tool_choice")
        assertEquals("function", choice.getString("type"))
        assertEquals("calculate", choice.getJSONObject("function").getString("name"))

        relaxRequiredFunctionToolChoice(payload)
        assertFalse(payload.has("tool_choice"))
    }

    @Test
    fun researchCompatibilityClonePreservesTheJsonObjectInsteadOfSerializingANestedListReceiver() {
        val original = JSONObject()
            .put("model", "openrouter/auto")
            .put(
                "tools",
                JSONArray().put(JSONObject().put("type", "openrouter:web_search")),
            )

        val cloned = cloneResearchRequestPayload(original)

        assertEquals("openrouter/auto", cloned.getString("model"))
        assertEquals("openrouter:web_search", cloned.getJSONArray("tools").getJSONObject(0).getString("type"))
        cloned.put("model", "changed")
        assertEquals("openrouter/auto", original.getString("model"))
    }

    @Test
    fun androidJsonCastFailureBecomesActionableMissionMessage() {
        val message = normalizeAgentFailureMessage(
            "Value [] of type org.json.JSONArray cannot be converted to JSONObject",
        )

        assertTrue(message.contains("incompatible JSON response shape"))
        assertFalse(message.contains("org.json"))
    }

    @Test
    fun legacyCastTextIsScrubbedWithoutDiscardingRecoveryContext() {
        val message = normalizeAgentFailureMessage(
            "Switched to OpenRouter Auto. Last milestone error: Value [] of type org.json.JSONArray cannot be converted to JSONObject",
        )

        assertTrue(message.startsWith("Switched to OpenRouter Auto"))
        assertTrue(message.contains("incompatible JSON response shape"))
        assertFalse(message.contains("org.json"))
    }

    @Test
    fun detectsOnlyObsoleteMissionBudgetStops() {
        assertTrue("The goal reached its token budget of 250000.".isLegacyMissionBudgetStop())
        assertFalse("OpenRouter reported that account credit is unavailable.".isLegacyMissionBudgetStop())
    }

    @Test
    fun dnsFailureBecomesAConnectivityRecoveryMessage() {
        val message = normalizeAgentFailureMessage(
            "Unable to resolve host \"openrouter.ai\": No address associated with hostname",
        )

        assertTrue(message.contains("Network name resolution"))
        assertTrue(message.contains("retry automatically"))
        assertFalse(message.contains("openrouter.ai"))
    }

    @Test
    fun genericProviderAndReadTimeoutErrorsBecomeActionable() {
        val generic = normalizeAgentFailureMessage("Provider returned error")
        val timeout = normalizeAgentFailureMessage("Read timed out")

        assertTrue(generic.contains("without a usable error response"))
        assertTrue(generic.contains("compatibility request"))
        assertTrue(timeout.contains("timed out before a complete response"))
    }

    @Test
    fun promotesMarkdownAndBareHttpsUrlsWithoutInventingHttpSources() {
        val sources = recoverHttpsSourceCitations(
            """
                [Official guide](https://example.gov/guide)
                Supporting result: https://research.example.org/report?id=7.
                Duplicate https://example.gov/guide, unsafe http://private.example/item,
                and local https://127.0.0.1/private.
            """.trimIndent(),
        )

        assertEquals(
            listOf("https://example.gov/guide", "https://research.example.org/report?id=7"),
            sources.map { it.url },
        )
        assertEquals("Official guide", sources.first().title)
    }
}
