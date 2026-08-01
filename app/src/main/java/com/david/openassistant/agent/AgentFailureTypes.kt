package com.david.openassistant.agent

import com.david.openassistant.data.openrouter.OpenRouterException
import java.io.IOException

enum class FailureDomain {
    PROVIDER,
    TRANSPORT,
    TOOL,
    RESEARCH,
    APPLICATION
}

enum class FailureScope {
    ACCOUNT,
    OPENROUTER,
    UPSTREAM_PROVIDER,
    MODEL,
    ROUTE,
    REQUEST,
    TOOL,
    SOURCE,
    DEVICE_NETWORK
}

enum class RetryPolicy {
    NEVER,
    IMMEDIATE_AFTER_LOCAL_REPAIR,
    AFTER_RETRY_AFTER,
    AFTER_NETWORK_RESTORED,
    AFTER_PROVIDER_COOLDOWN,
    ON_DIFFERENT_COMPATIBLE_ROUTE,
    AFTER_USER_CREDENTIAL_ACTION,
    AFTER_MATERIAL_STRATEGY_CHANGE
}

data class FailureDescriptor(
    val domain: FailureDomain,
    val failureClass: String,
    val scope: FailureScope,
    val retryPolicy: RetryPolicy,
    val statusCode: Int? = null,
    val retryAfterMs: Long? = null,
    val fieldPath: String? = null,
    val requestFingerprint: String? = null,
    val goalId: String? = null,
    val taskId: String? = null,
    val operationId: String? = null,
    val safeDiagnosticSummary: String,
)

/**
 * Signal-based deterministic failure precedence classifier.
 * Evaluates overlapping failure signals in strict priority order.
 */
object FailureClassifier {

    fun classify(
        error: Throwable?,
        statusCode: Int? = null,
        retryAfterMs: Long? = null,
        requestFingerprint: String? = null,
        goalId: String? = null,
        taskId: String? = null,
        operationId: String? = null,
        localValidatorFailed: Boolean = false,
        fieldPath: String? = null,
        validationReason: String? = null,
        emptyModelOutput: Boolean = false,
        toolArgumentFailure: Boolean = false,
        researchStall: Boolean = false,
    ): FailureDescriptor {
        val message = error?.message.orEmpty().lowercase()
        val openRouterEx = error as? OpenRouterException

        // 1. Local request schema validation defect (Application defect before dispatch)
        if (localValidatorFailed || openRouterEx?.failureClass == com.david.openassistant.data.openrouter.OpenRouterFailureClass.LOCAL_REQUEST_SCHEMA_FAILURE) {
            return FailureDescriptor(
                domain = FailureDomain.APPLICATION,
                failureClass = "LOCAL_REQUEST_SCHEMA_FAILURE",
                scope = FailureScope.REQUEST,
                retryPolicy = RetryPolicy.IMMEDIATE_AFTER_LOCAL_REPAIR,
                statusCode = statusCode,
                fieldPath = fieldPath ?: openRouterEx?.fieldPath,
                requestFingerprint = requestFingerprint ?: openRouterEx?.originalPayloadFingerprint,
                goalId = goalId,
                taskId = taskId,
                operationId = operationId,
                safeDiagnosticSummary = validationReason ?: openRouterEx?.validationReason ?: "Local request schema validation failed.",
            )
        }

        // 2. Provider authentication / permission failure (401 / 403)
        val isCloudflareChallenge = statusCode == 403 && (
            message.contains("cloudflare") || 
            message.contains("challenge") || 
            message.contains("captcha") || 
            message.contains("interstitial")
        )
        
        if (isCloudflareChallenge) {
            return FailureDescriptor(
                domain = FailureDomain.RESEARCH,
                failureClass = "SOURCE_ACCESS_CHALLENGE",
                scope = FailureScope.SOURCE,
                retryPolicy = RetryPolicy.AFTER_MATERIAL_STRATEGY_CHANGE,
                statusCode = statusCode,
                goalId = goalId,
                taskId = taskId,
                operationId = operationId,
                safeDiagnosticSummary = "Source access blocked by an interstitial or challenge (e.g. Cloudflare).",
            )
        }

        if (statusCode == 401 || (statusCode == 403 && (message.contains("api key") || message.contains("unauthorized") || message.contains("invalid key")))) {
            return FailureDescriptor(
                domain = FailureDomain.PROVIDER,
                failureClass = "PROVIDER_AUTHENTICATION",
                scope = FailureScope.ACCOUNT,
                retryPolicy = RetryPolicy.AFTER_USER_CREDENTIAL_ACTION,
                statusCode = statusCode,
                goalId = goalId,
                taskId = taskId,
                operationId = operationId,
                safeDiagnosticSummary = "Authentication failed. The stored API key is invalid or unauthorized.",
            )
        }

        if (statusCode == 403 && (message.contains("model") || message.contains("access denied") || message.contains("permission"))) {
            return FailureDescriptor(
                domain = FailureDomain.PROVIDER,
                failureClass = "PROVIDER_MODEL_INCOMPATIBILITY",
                scope = FailureScope.MODEL,
                retryPolicy = RetryPolicy.ON_DIFFERENT_COMPATIBLE_ROUTE,
                statusCode = statusCode,
                goalId = goalId,
                taskId = taskId,
                operationId = operationId,
                safeDiagnosticSummary = "Access denied for requested model or route.",
            )
        }

        // 3. Network offline / DNS transport failure
        if (error is java.net.UnknownHostException || message.contains("unable to resolve host") || message.contains("no address associated") || message.contains("dns_probe_finished_nxdomain")) {
            return FailureDescriptor(
                domain = FailureDomain.TRANSPORT,
                failureClass = "NETWORK_DNS_FAILURE",
                scope = FailureScope.DEVICE_NETWORK,
                retryPolicy = RetryPolicy.AFTER_NETWORK_RESTORED,
                statusCode = statusCode,
                goalId = goalId,
                taskId = taskId,
                operationId = operationId,
                safeDiagnosticSummary = "Network DNS resolution failed.",
            )
        }

        if (message.contains("timeout") || message.contains("timed out") || error is java.net.SocketTimeoutException) {
            return FailureDescriptor(
                domain = FailureDomain.TRANSPORT,
                failureClass = "NETWORK_TIMEOUT",
                scope = FailureScope.DEVICE_NETWORK,
                retryPolicy = RetryPolicy.AFTER_NETWORK_RESTORED,
                statusCode = statusCode,
                goalId = goalId,
                taskId = taskId,
                operationId = operationId,
                safeDiagnosticSummary = "Network request timed out.",
            )
        }

        if (error is IOException && (message.contains("network unreachable") || message.contains("socket closed") || message.contains("connection refused") || message.contains("connection_aborted"))) {
            return FailureDescriptor(
                domain = FailureDomain.TRANSPORT,
                failureClass = "NETWORK_CONNECTION_ABORT",
                scope = FailureScope.DEVICE_NETWORK,
                retryPolicy = RetryPolicy.AFTER_NETWORK_RESTORED,
                statusCode = statusCode,
                goalId = goalId,
                taskId = taskId,
                operationId = operationId,
                safeDiagnosticSummary = "Network connection was aborted or refused.",
            )
        }

        // 4. Rate limit with Retry-After header or HTTP 429
        if (statusCode == 429) {
            val scope = if (message.contains("account") || message.contains("quota")) FailureScope.ACCOUNT else FailureScope.MODEL
            return FailureDescriptor(
                domain = FailureDomain.PROVIDER,
                failureClass = "PROVIDER_RATE_LIMIT",
                scope = scope,
                retryPolicy = if (retryAfterMs != null && retryAfterMs > 0) RetryPolicy.AFTER_RETRY_AFTER else RetryPolicy.AFTER_PROVIDER_COOLDOWN,
                statusCode = statusCode,
                retryAfterMs = retryAfterMs,
                goalId = goalId,
                taskId = taskId,
                operationId = operationId,
                safeDiagnosticSummary = "Provider rate limit exceeded. Retry after cooldown.",
            )
        }

        // 5. Provider capacity (503 / overload)
        if (statusCode == 503 || message.contains("capacity") || message.contains("resource exhausted") || message.contains("overloaded")) {
            return FailureDescriptor(
                domain = FailureDomain.PROVIDER,
                failureClass = "PROVIDER_CAPACITY",
                scope = FailureScope.UPSTREAM_PROVIDER,
                retryPolicy = RetryPolicy.ON_DIFFERENT_COMPATIBLE_ROUTE,
                statusCode = statusCode,
                goalId = goalId,
                taskId = taskId,
                operationId = operationId,
                safeDiagnosticSummary = "Provider report capacity exhaustion.",
            )
        }

        // 6. Response schema / JSON envelope failure
        if (openRouterEx?.isResponseShapeFailure() == true || message.contains("jsonobject") || message.contains("malformed json") || message.contains("jsonschema")) {
            return FailureDescriptor(
                domain = FailureDomain.PROVIDER,
                failureClass = "RESPONSE_SCHEMA_FAILURE",
                scope = FailureScope.REQUEST,
                retryPolicy = RetryPolicy.ON_DIFFERENT_COMPATIBLE_ROUTE,
                statusCode = statusCode,
                goalId = goalId,
                taskId = taskId,
                operationId = operationId,
                safeDiagnosticSummary = "Provider returned an invalid JSON response schema.",
            )
        }

        // 7. Empty model output
        if (emptyModelOutput || message.contains("returned no usable text") || message.contains("empty model output")) {
            return FailureDescriptor(
                domain = FailureDomain.PROVIDER,
                failureClass = "EMPTY_MODEL_OUTPUT",
                scope = FailureScope.REQUEST,
                retryPolicy = RetryPolicy.ON_DIFFERENT_COMPATIBLE_ROUTE,
                statusCode = statusCode,
                goalId = goalId,
                taskId = taskId,
                operationId = operationId,
                safeDiagnosticSummary = "Provider returned an empty model response.",
            )
        }

        // 8. Tool argument failure
        if (toolArgumentFailure || message.contains("tool arguments must be")) {
            return FailureDescriptor(
                domain = FailureDomain.TOOL,
                failureClass = "TOOL_ARGUMENT_FAILURE",
                scope = FailureScope.TOOL,
                retryPolicy = RetryPolicy.IMMEDIATE_AFTER_LOCAL_REPAIR,
                statusCode = statusCode,
                goalId = goalId,
                taskId = taskId,
                operationId = operationId,
                safeDiagnosticSummary = "Local tool arguments were invalid.",
            )
        }

        // 9. Research progress stall
        if (researchStall || message.contains("made no measurable progress") || message.contains("no progress")) {
            return FailureDescriptor(
                domain = FailureDomain.RESEARCH,
                failureClass = "NO_PROGRESS",
                scope = FailureScope.REQUEST,
                retryPolicy = RetryPolicy.AFTER_MATERIAL_STRATEGY_CHANGE,
                statusCode = statusCode,
                goalId = goalId,
                taskId = taskId,
                operationId = operationId,
                safeDiagnosticSummary = "Research loop made no measurable progress.",
            )
        }

        // Generic application fallback
        return FailureDescriptor(
            domain = FailureDomain.APPLICATION,
            failureClass = "APPLICATION_INVARIANT_FAILURE",
            scope = FailureScope.REQUEST,
            retryPolicy = RetryPolicy.ON_DIFFERENT_COMPATIBLE_ROUTE,
            statusCode = statusCode,
            goalId = goalId,
            taskId = taskId,
            operationId = operationId,
            safeDiagnosticSummary = message.ifBlank { "Unclassified application error." },
        )
    }
}
