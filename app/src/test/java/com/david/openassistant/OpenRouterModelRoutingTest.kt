package com.david.openassistant

import com.david.openassistant.data.openrouter.OpenRouterModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterModelRoutingTest {
    @Test
    fun lyriaCannotBecomeAResearchPlannerWhenCatalogMetadataIsMissing() {
        val model = model(
            id = "google/lyria-3-clip-preview",
            name = "Lyria 3 Clip Preview",
            input = emptyList(),
            output = emptyList(),
        )

        assertTrue(model.isDedicatedMediaGenerator)
        assertFalse(model.supportsTextChat)
        assertFalse(model.supportsAgentPlanning)
    }

    @Test
    fun ordinaryTextModelRemainsEligible() {
        val model = model(
            id = "nvidia/nemotron-3-super-120b-a12b:free",
            name = "Nemotron Super",
            input = listOf("text"),
            output = listOf("text"),
        )

        assertFalse(model.isDedicatedMediaGenerator)
        assertTrue(model.supportsTextChat)
        assertTrue(model.supportsAgentPlanning)
    }

    private fun model(
        id: String,
        name: String,
        input: List<String>,
        output: List<String>,
    ) = OpenRouterModel(
        id = id,
        name = name,
        description = "",
        contextLength = 128_000,
        inputModalities = input,
        outputModalities = output,
        supportedParameters = setOf("response_format", "tools"),
        promptPricePerToken = 0.0,
        completionPricePerToken = 0.0,
    )
}
