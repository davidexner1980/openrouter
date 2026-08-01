package com.david.openassistant.handoff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecurityScannerTest {
    
    private val scanner = SecurityScanner()

    @Test
    fun testSecretRedaction() {
        val raw = "My key is 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 and I use Bearer token.xyz.123"
        val (redacted, result) = scanner.redact(raw, ScannerScope.RUNTIME_PROVIDER_DATA)
        
        assertTrue(result.secretFindings >= 1)
        assertTrue(redacted.contains("[REDACTED_SECRET]"))
        assertTrue(!redacted.contains("sk-or-v1-"))
    }

    @Test
    fun testReasoningRedaction() {
        val json = """
            {
                "id": "123",
                "content": "Hello",
                "reasoning": "I thought about saying hi",
                "details": {
                    "analysis": "deep analysis here"
                }
            }
        """.trimIndent()
        
        val (redacted, result) = scanner.redact(json, ScannerScope.RUNTIME_PROVIDER_DATA)
        
        assertEquals(2, result.reasoningFieldsRemoved)
        assertTrue(redacted.contains("[INTERNAL_REASONING_REDACTED]"))
        assertTrue(!redacted.contains("I thought about saying hi"))
        assertTrue(!redacted.contains("deep analysis here"))
    }
}
