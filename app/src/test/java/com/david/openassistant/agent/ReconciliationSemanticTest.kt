package com.david.openassistant.agent

import com.david.openassistant.data.openrouter.OpenRouterException
import com.david.openassistant.data.openrouter.OpenRouterFailureClass
import org.junit.Assert.*
import org.junit.Test

class ReconciliationSemanticTest {

    @Test
    fun testRECONCILIATION_CONFLICT_Classification() {
        val error = OpenRouterException(
            statusCode = null,
            userMessage = "Logical conflict",
            failureClass = OpenRouterFailureClass.RECONCILIATION_CONFLICT,
            originalPayloadFingerprint = "fp"
        )
        
        val descriptor = FailureClassifier.classify(
            error = error,
            goalId = "g",
            operationId = "op"
        )
        
        assertEquals("PROVIDER_RECONCILIATION_CONFLICT", descriptor.failureClass)
        assertEquals(RetryPolicy.REQUIRES_USER_RECOVERY_ACTION, descriptor.retryPolicy)
        assertEquals(FailureDomain.APPLICATION, descriptor.domain)
    }
}
