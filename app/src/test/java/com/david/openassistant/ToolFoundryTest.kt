package com.david.openassistant

import com.david.openassistant.domain.tools.ToolRecipe
import com.david.openassistant.domain.tools.ToolRecipeEngine
import com.david.openassistant.domain.tools.ToolRecipeOperation
import com.david.openassistant.domain.tools.ToolRecipeParameter
import com.david.openassistant.domain.tools.ToolRecipeStep
import com.david.openassistant.domain.tools.ToolRecipeTest
import com.david.openassistant.domain.tools.ToolRecipeValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolFoundryTest {
    @Test
    fun testedDeterministicRecipeCanBeActivatedAndExecuted() {
        val recipe = ToolRecipe(
            toolName = "clean_heading",
            displayName = "Clean heading",
            description = "Normalize and uppercase a heading.",
            parameters = listOf(ToolRecipeParameter("text", "Heading text")),
            steps = listOf(
                ToolRecipeStep(
                    id = "clean",
                    operation = ToolRecipeOperation.NORMALIZE_WHITESPACE,
                    arguments = mapOf("text" to "${'$'}{input.text}"),
                ),
                ToolRecipeStep(
                    id = "upper",
                    operation = ToolRecipeOperation.UPPERCASE,
                    arguments = mapOf("text" to "${'$'}{step.clean}"),
                ),
            ),
            outputTemplate = "${'$'}{step.upper}",
            tests = listOf(
                ToolRecipeTest(inputs = mapOf("text" to "  hello   world  "), expectedOutput = "HELLO WORLD"),
                ToolRecipeTest(inputs = mapOf("text" to "line one\n\nline two"), expectedContains = "LINE ONE"),
            ),
        )

        val validation = ToolRecipeValidator.validate(recipe)
        val tests = ToolRecipeEngine().runTests(recipe)
        val output = ToolRecipeEngine().execute(recipe, mapOf("text" to "  reusable   tool  "))

        assertTrue(validation.errors.joinToString(), validation.valid)
        assertTrue(tests.all { it.passed })
        assertEquals("REUSABLE TOOL", output)
    }

    @Test
    fun recipeValidatorRejectsForwardStepReferences() {
        val recipe = ToolRecipe(
            toolName = "invalid_forward_reference",
            displayName = "Invalid",
            description = "Should fail validation.",
            parameters = listOf(ToolRecipeParameter("text", "Text")),
            steps = listOf(
                ToolRecipeStep(
                    id = "first",
                    operation = ToolRecipeOperation.TRIM,
                    arguments = mapOf("text" to "${'$'}{step.later}"),
                ),
                ToolRecipeStep(
                    id = "later",
                    operation = ToolRecipeOperation.TRIM,
                    arguments = mapOf("text" to "${'$'}{input.text}"),
                ),
            ),
            outputTemplate = "${'$'}{step.first}",
            tests = listOf(ToolRecipeTest(inputs = mapOf("text" to "x"), expectedOutput = "x")),
        )

        val validation = ToolRecipeValidator.validate(recipe)

        assertFalse(validation.valid)
        assertTrue(validation.errors.any { it.contains("forward step placeholder") })
    }
}
