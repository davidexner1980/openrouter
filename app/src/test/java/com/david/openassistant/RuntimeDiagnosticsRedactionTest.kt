package com.david.openassistant

import com.david.openassistant.data.diagnostics.redactDiagnosticText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDiagnosticsRedactionTest {
    @Test
    fun redactsBearerAndOpenRouterStyleCredentials() {
        val secret = "sk-or-v1-" + "abcdefghijklmnopqrstuvwxyz123456"
        val result = redactDiagnosticText("Authorization failed: Bearer $secret key=$secret")

        assertFalse(result.contains(secret))
        assertTrue(result.contains("[REDACTED]"))
    }

    @Test
    fun preservesOrdinaryDiagnosticMetadata() {
        val result = redactDiagnosticText("HTTP 429 after 812 ms on model vendor/model")

        assertTrue(result.contains("HTTP 429"))
        assertTrue(result.contains("812 ms"))
        assertTrue(result.contains("vendor/model"))
    }
}
