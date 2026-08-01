package com.david.openassistant

import com.david.openassistant.domain.tools.ToolValidationException
import com.david.openassistant.domain.tools.calculateExpression
import com.david.openassistant.domain.tools.validateSafeRegexPattern
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class SafeToolsTest {
    @Test
    fun calculatorRespectsPrecedenceAndParentheses() {
        assertEquals(26.0, calculateExpression("(4 + 9) * 2"), 0.0000001)
    }

    @Test
    fun calculatorSupportsExponentiationAndUnaryMinus() {
        assertEquals(-8.0, calculateExpression("-(2 ^ 3)"), 0.0000001)
    }

    @Test
    fun calculatorRejectsDivisionByZero() {
        try {
            calculateExpression("10 / 0")
            fail("Expected ToolValidationException")
        } catch (_: ToolValidationException) {
            // Expected.
        }
    }

    @Test
    fun calculatorRejectsTrailingGarbage() {
        try {
            calculateExpression("2 + 2 apples")
            fail("Expected ToolValidationException")
        } catch (_: ToolValidationException) {
            // Expected.
        }
    }

    @Test
    fun autonomousRegexRejectsBackreferencesAndNestedQuantifiers() {
        val unsafePatterns = listOf("(a+)\\1", "(a+)+", "(a|aa)+")
        unsafePatterns.forEach { pattern ->
            try {
                validateSafeRegexPattern(pattern)
                fail("Expected ToolValidationException for $pattern")
            } catch (_: ToolValidationException) {
                // Expected.
            }
        }
    }

    @Test
    fun autonomousRegexAllowsBoundedLiteralExtraction() {
        assertEquals("part-[0-9]{3}", validateSafeRegexPattern("part-[0-9]{3}"))
    }
}
