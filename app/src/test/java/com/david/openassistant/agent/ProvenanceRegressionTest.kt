package com.david.openassistant.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvenanceRegressionTest {

    @Test
    fun rejectsUnsupportedModelInventedConstraints() {
        val request = "Research modern smartphone processors."
        val invented = listOf("<50 ns", "<10 pJ/bit", "<5 mm²", "Apple A18", "Snapdragon 8 Gen 4", "LPDDR6X", "2026-ready")
        
        invented.forEach { constraint ->
            assertFalse(
                "Constraint '$constraint' should be rejected as not grounded in request '$request'",
                ConstraintValidator.isGrounded(constraint, request)
            )
        }
    }

    @Test
    fun acceptsExplicitUserConstraints() {
        val request = "Find a processor with less than 5nm process node and support for LPDDR5X."
        
        assertTrue(
            "Explicit constraint should be accepted",
            ConstraintValidator.isGrounded("must be less than 5nm", request)
        )
        assertTrue(
            "Explicit product name should be accepted",
            ConstraintValidator.isGrounded("supports LPDDR5X", request)
        )
    }
}
