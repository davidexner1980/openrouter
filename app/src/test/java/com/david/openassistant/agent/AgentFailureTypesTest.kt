package com.david.openassistant.agent

import com.david.openassistant.data.openrouter.OpenRouterException
import com.david.openassistant.data.openrouter.OpenRouterFailureClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

class AgentFailureTypesTest {

    @Test
    fun localSchemaValidationHasHighestPrecedence() {
        val ex = OpenRouterException(
            statusCode = 400,
            userMessage = "Schema error",
            failureClass = OpenRouterFailureClass.LOCAL_REQUEST_SCHEMA_FAILURE,
            fieldPath = "reasoning",
            validationReason = "Must be JSON object",
            originalPayloadFingerprint = "hash123",
        )

        val descriptor = FailureClassifier.classify(
            error = ex,
            statusCode = 400,
            localValidatorFailed = true,
            fieldPath = "reasoning",
            validationReason = "Must be JSON object",
        )

        assertEquals(FailureDomain.APPLICATION, descriptor.domain)
        assertEquals("LOCAL_REQUEST_SCHEMA_FAILURE", descriptor.failureClass)
        assertEquals(FailureScope.REQUEST, descriptor.scope)
        assertEquals(RetryPolicy.IMMEDIATE_AFTER_LOCAL_REPAIR, descriptor.retryPolicy)
        assertEquals("reasoning", descriptor.fieldPath)
    }

    @Test
    fun dnsFailureClassifiedAsTransport() {
        val ex = UnknownHostException("unable to resolve host openrouter.ai")

        val descriptor = FailureClassifier.classify(
            error = ex,
        )

        assertEquals(FailureDomain.TRANSPORT, descriptor.domain)
        assertEquals("NETWORK_DNS_FAILURE", descriptor.failureClass)
        assertEquals(FailureScope.DEVICE_NETWORK, descriptor.scope)
        assertEquals(RetryPolicy.AFTER_NETWORK_RESTORED, descriptor.retryPolicy)
    }

    @Test
    fun rateLimitWithRetryAfterRespectsPolicy() {
        val ex = IOException("429 Too Many Requests")

        val descriptor = FailureClassifier.classify(
            error = ex,
            statusCode = 429,
            retryAfterMs = 5000L,
        )

        assertEquals(FailureDomain.PROVIDER, descriptor.domain)
        assertEquals("PROVIDER_RATE_LIMIT", descriptor.failureClass)
        assertEquals(RetryPolicy.AFTER_RETRY_AFTER, descriptor.retryPolicy)
        assertEquals(5000L, descriptor.retryAfterMs)
    }
}
