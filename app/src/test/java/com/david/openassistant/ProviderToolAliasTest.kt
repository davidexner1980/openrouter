package com.david.openassistant

import com.david.openassistant.agent.canonicalizeProviderToolCall
import com.david.openassistant.domain.tools.OpenRouterToolCall
import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderToolAliasTest {
    @Test
    fun providerSearchAndFetchAliasesResolveToGuardedPublicWebTools() {
        val search = canonicalizeProviderToolCall(
            OpenRouterToolCall("one", "search", "{\"query\":\"request-specific lead\"}"),
        )
        val fetch = canonicalizeProviderToolCall(
            OpenRouterToolCall("two", "open_url", "{\"url\":\"https://example.com/source\"}"),
        )

        assertEquals("public_web_search", search.name)
        assertEquals("public_web_fetch", fetch.name)
        assertEquals("{\"query\":\"request-specific lead\"}", search.argumentsJson)
    }

    @Test
    fun providerRunAliasResolvesOnlyUnambiguousGuardedWebArguments() {
        val search = canonicalizeProviderToolCall(
            OpenRouterToolCall("three", "run", "{\"query\":\"Donald Trump current president official\"}"),
        )
        val fetch = canonicalizeProviderToolCall(
            OpenRouterToolCall("four", "run", "{\"url\":\"https://www.whitehouse.gov/administration/\"}"),
        )
        val ambiguous = OpenRouterToolCall(
            "five",
            "run",
            "{\"query\":\"one\",\"url\":\"https://example.com\"}",
        )
        val unrelated = OpenRouterToolCall("six", "run", "{\"command\":\"do something\"}")

        assertEquals("public_web_search", search.name)
        assertEquals("public_web_fetch", fetch.name)
        assertEquals(ambiguous, canonicalizeProviderToolCall(ambiguous))
        assertEquals(unrelated, canonicalizeProviderToolCall(unrelated))
    }

    @Test
    fun unknownToolNamesStillFailClosedInsteadOfBeingGuessed() {
        val unknown = OpenRouterToolCall("seven", "control_other_app", "{}")

        assertEquals(unknown, canonicalizeProviderToolCall(unknown))
    }
}
