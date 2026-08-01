package com.david.openassistant.agent

import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Locale

enum class ProviderRecoveryAction {
    RETRY_CURRENT_ROUTE,
    SWITCH_TO_STABLE,
    SWITCH_TO_FREE,
    ESCALATE_TO_PAID,
    ROUTE_EXHAUSTED,
    WAIT_FOR_CREDENTIAL,
    WAIT_FOR_NETWORK,
    LOCAL_REPAIR,
    AFTER_MATERIAL_STRATEGY_CHANGE,
}

data class ProviderRecoveryDecision(
    val action: ProviderRecoveryAction,
    val nextModelId: String,
    val explanation: String,
) {
    fun nextGoalStatus(current: AgentGoalStatus): AgentGoalStatus = when (action) {
        ProviderRecoveryAction.WAIT_FOR_CREDENTIAL -> AgentGoalStatus.WAITING_FOR_CREDENTIAL
        ProviderRecoveryAction.WAIT_FOR_NETWORK -> AgentGoalStatus.WAITING_FOR_NETWORK
        else -> if (current == AgentGoalStatus.PAUSED) AgentGoalStatus.PAUSED else AgentGoalStatus.QUEUED
    }
}

/**
 * Deterministic provider recovery policy.
 *
 * It never converts a provider/network/format failure into permanent mission
 * failure. The runtime either changes route, retries later, or waits durably
 * for a valid credential. OpenRouter remains the authority for account credit
 * and provider availability.
 */
object ProviderRecoveryPolicy {
    const val AUTO_BETA_ROUTER_MODEL_ID = "openrouter/auto-beta"
    const val FREE_ROUTER_MODEL_ID = "openrouter/free"

    /** Normalizes only the approved OpenRouter auto-router beta. */
    fun isAutoRouter(modelId: String): Boolean {
        val lower = modelId.lowercase(Locale.US)
        return lower == AUTO_BETA_ROUTER_MODEL_ID
    }

    fun decideWithDescriptor(
        descriptor: FailureDescriptor,
        currentModelId: String,
        routingStage: AgentRoutingStage = AgentRoutingStage.AUTO_BETA,
        isFreeOnly: Boolean = false,
        isIntelligenceEscalation: Boolean = false,
    ): ProviderRecoveryDecision {
        if (descriptor.safeDiagnosticSummary.contains("FREE_ROUTING_VIOLATION", ignoreCase = true)) {
            return ProviderRecoveryDecision(
                action = ProviderRecoveryAction.SWITCH_TO_FREE,
                nextModelId = FREE_ROUTER_MODEL_ID,
                explanation = "A routing violation was detected for this FREE mission. Switching to the verified Free Models Router.",
            )
        }

        if (descriptor.safeDiagnosticSummary.contains("Identical context fingerprint detected", ignoreCase = true)) {
            if (isFreeOnly || routingStage == AgentRoutingStage.EXHAUSTED) {
                return ProviderRecoveryDecision(
                    action = ProviderRecoveryAction.ROUTE_EXHAUSTED,
                    nextModelId = currentModelId,
                    explanation = "The mission is stuck in a repetitive state without measurable progress, and no further recovery route is available.",
                )
            }
            return ProviderRecoveryDecision(
                action = ProviderRecoveryAction.ESCALATE_TO_PAID,
                nextModelId = AUTO_BETA_ROUTER_MODEL_ID,
                explanation = "The mission is stuck in a repetitive state. Escalating to the Auto Router Beta to break through.",
            )
        }

        return decide(
            statusCode = descriptor.statusCode,
            currentModelId = currentModelId,
            routingStage = routingStage,
            responseShapeFailure = descriptor.failureClass == "RESPONSE_SCHEMA_FAILURE",
            networkResolutionFailure = descriptor.failureClass == "NETWORK_DNS_FAILURE" || descriptor.failureClass == "NETWORK_OFFLINE",
            networkTimeoutFailure = descriptor.failureClass == "NETWORK_TIMEOUT",
            sourceAccessChallenge = descriptor.failureClass == "SOURCE_ACCESS_CHALLENGE",
            localRequestSchemaFailure = descriptor.failureClass == "LOCAL_REQUEST_SCHEMA_FAILURE",
            emptyModelOutput = descriptor.failureClass == "EMPTY_MODEL_OUTPUT",
            providerCapacityFailure = descriptor.failureClass == "PROVIDER_CAPACITY" || descriptor.failureClass == "PROVIDER_RATE_LIMIT",
            progressStallFailure = descriptor.failureClass == "NO_PROGRESS",
            isFreeOnly = isFreeOnly,
            isIntelligenceEscalation = isIntelligenceEscalation,
        )
    }

    fun decide(
        statusCode: Int?,
        currentModelId: String,
        routingStage: AgentRoutingStage = AgentRoutingStage.AUTO_BETA,
        responseShapeFailure: Boolean = false,
        requestStallFailure: Boolean = false,
        networkResolutionFailure: Boolean = false,
        networkTimeoutFailure: Boolean = false,
        sourceAccessChallenge: Boolean = false,
        localRequestSchemaFailure: Boolean = false,
        emptyModelOutput: Boolean = false,
        planStructureFailure: Boolean = false,
        providerCapacityFailure: Boolean = false,
        progressStallFailure: Boolean = false,
        intelligenceWallReached: Boolean = false,
        repetitiveSearchStall: Boolean = false,
        shallowResearchStall: Boolean = false,
        verificationCircularity: Boolean = false,
        isFreeOnly: Boolean = false,
        isIntelligenceEscalation: Boolean = false,
    ): ProviderRecoveryDecision {
        val current = currentModelId.ifBlank { AUTO_BETA_ROUTER_MODEL_ID }
        val isAuto = isAutoRouter(current)
        val isFreeRouter = current.equals(FREE_ROUTER_MODEL_ID, ignoreCase = true)
        val isFreeModel = current.endsWith(":free", ignoreCase = true)
        val isFree = isFreeRouter || isFreeModel || isFreeOnly

        if (localRequestSchemaFailure) {
            return ProviderRecoveryDecision(
                action = ProviderRecoveryAction.LOCAL_REPAIR,
                nextModelId = current,
                explanation = "The outbound request did not match the required provider schema. The runtime will attempt one deterministic local repair before retrying.",
            )
        }

        if (networkResolutionFailure || networkTimeoutFailure) {
            return ProviderRecoveryDecision(
                action = ProviderRecoveryAction.WAIT_FOR_NETWORK,
                nextModelId = current,
                explanation = if (networkResolutionFailure) "Network name resolution is temporarily unavailable." else "Network request timed out.",
            )
        }

        if (sourceAccessChallenge) {
            return ProviderRecoveryDecision(
                action = ProviderRecoveryAction.AFTER_MATERIAL_STRATEGY_CHANGE,
                nextModelId = current,
                explanation = "A research source is blocked by an access challenge (e.g. Cloudflare). The milestone will retry with a materially different discovery strategy.",
            )
        }
        
        val incompatibleRouteFailure = responseShapeFailure ||
            requestStallFailure ||
            planStructureFailure ||
            providerCapacityFailure ||
            progressStallFailure ||
            repetitiveSearchStall ||
            shallowResearchStall ||
            verificationCircularity ||
            emptyModelOutput
            
        val isRateLimit = statusCode == 429 || providerCapacityFailure
        
        val failureDescription = when {
            requestStallFailure -> "stalled beyond the provider request watchdog"
            providerCapacityFailure -> "reported provider capacity exhaustion"
            planStructureFailure -> "returned an unusable investigation structure"
            progressStallFailure -> "made no measurable progress"
            intelligenceWallReached -> "reached an intelligence wall"
            repetitiveSearchStall -> "stalled on repetitive search domains"
            shallowResearchStall -> "stalled with shallow search results"
            verificationCircularity -> "entered a verification circularity"
            emptyModelOutput -> "returned an empty model output"
            else -> "returned an incompatible JSON shape"
        }

        // Three-stage routing logic: AUTO_BETA -> FREE -> EXHAUSTED.
        // Free may return to Auto Beta only for a verified intelligence escalation.
        
        if (routingStage == AgentRoutingStage.AUTO_BETA && (isRateLimit || incompatibleRouteFailure)) {
            return ProviderRecoveryDecision(
                action = ProviderRecoveryAction.SWITCH_TO_FREE,
                nextModelId = FREE_ROUTER_MODEL_ID,
                explanation = "Current route $failureDescription. Switching to the Free Models Router.",
            )
        }
        
        if (routingStage == AgentRoutingStage.FREE && isFree) {
            if (isIntelligenceEscalation && !isFreeOnly && incompatibleRouteFailure) {
                return ProviderRecoveryDecision(
                    action = ProviderRecoveryAction.ESCALATE_TO_PAID,
                    nextModelId = AUTO_BETA_ROUTER_MODEL_ID,
                    explanation = "Free route $failureDescription. Escalating once to Auto Router Beta for intelligence recovery.",
                )
            }
            
            if (isRateLimit || incompatibleRouteFailure) {
                return ProviderRecoveryDecision(
                    action = ProviderRecoveryAction.ROUTE_EXHAUSTED,
                    nextModelId = current,
                    explanation = "The current free route $failureDescription and no further recovery route is available for this payload.",
                )
            }
        }

        if (routingStage == AgentRoutingStage.EXHAUSTED && (isRateLimit || incompatibleRouteFailure)) {
            return ProviderRecoveryDecision(
                action = ProviderRecoveryAction.ROUTE_EXHAUSTED,
                nextModelId = current,
                explanation = "All recovery routes are exhausted for this milestone. The mission is temporarily blocked.",
            )
        }

        return when {
            (intelligenceWallReached || progressStallFailure || repetitiveSearchStall || shallowResearchStall || verificationCircularity) && isFree && !isFreeOnly -> ProviderRecoveryDecision(
                action = ProviderRecoveryAction.ESCALATE_TO_PAID,
                nextModelId = AUTO_BETA_ROUTER_MODEL_ID,
                explanation = "The free model $failureDescription. Escalating to Auto Router Beta to break through the intelligence wall.",
            )
            statusCode in setOf(401, 403) -> ProviderRecoveryDecision(
                action = ProviderRecoveryAction.WAIT_FOR_CREDENTIAL,
                nextModelId = current,
                explanation = "OpenRouter rejected the stored credential. Work is preserved until a valid credential is available.",
            )
            statusCode == 402 && !isFree -> ProviderRecoveryDecision(
                action = ProviderRecoveryAction.SWITCH_TO_FREE,
                nextModelId = FREE_ROUTER_MODEL_ID,
                explanation = "The current paid route is unavailable for this account. Switched to OpenRouter's free-model router.",
            )
            incompatibleRouteFailure && isFreeRouter -> ProviderRecoveryDecision(
                action = ProviderRecoveryAction.RETRY_CURRENT_ROUTE,
                nextModelId = FREE_ROUTER_MODEL_ID,
                explanation = "OpenRouter's free-model route $failureDescription. Work is preserved; retrying with backoff.",
            )
            incompatibleRouteFailure && isAuto -> ProviderRecoveryDecision(
                action = ProviderRecoveryAction.RETRY_CURRENT_ROUTE,
                nextModelId = AUTO_BETA_ROUTER_MODEL_ID,
                explanation = "Auto Router Beta $failureDescription. Work and retrieved web evidence are preserved; Auto will retry through another compatible endpoint.",
            )
            statusCode in setOf(400, 404, 422) && isAuto -> ProviderRecoveryDecision(
                action = ProviderRecoveryAction.SWITCH_TO_FREE,
                nextModelId = FREE_ROUTER_MODEL_ID,
                explanation = "Auto Router Beta could not satisfy the request. Switched to the free-model router.",
            )
            else -> ProviderRecoveryDecision(
                action = ProviderRecoveryAction.RETRY_CURRENT_ROUTE,
                nextModelId = current,
                explanation = when (statusCode) {
                    402 -> "The free route is temporarily unavailable for this account. The durable mission will retry later."
                    429 -> "OpenRouter rate-limited the request. The durable mission will retry with WorkManager backoff."
                    in 500..599 -> "The provider reported a temporary server failure. The durable mission will retry later."
                    else -> "The operation failed before a verified result was committed. The durable mission will retry from its checkpoint."
                },
            )
        }
    }

    /**
     * Normalizes a fingerprint source to detect materially different strategies.
     * Preserves semantic meaning (numbers, currency, site restrictions) while
     * ignoring formatting noise, word reordering, and artificial retry counters.
     */
    fun normalizeFingerprintSource(text: String): String {
        // 1. Lowercase for case-insensitivity
        var normalized = text.lowercase(Locale.US)
        
        // 2. Remove artificial retry counters and synthetic suffixes like " - attempt 1", "_retry_2", etc.
        // We look for patterns like " - attempt \d", " (retry \d)", "_v\d" if they seem added by the app.
        // Common app-added suffixes:
        normalized = normalized.replace(Regex("[-_\\s]+(?:attempt|retry|pass|v|generation)[-_\\s]*\\d+"), " ")
        normalized = normalized.replace(Regex("\\((?:attempt|retry|pass|v|generation)\\s*\\d+\\)"), " ")
        
        // 3. Normalize punctuation: keep meaningful ones like site:, currency, comparison, quotes, meaningful dots/dashes
        // Preserve: " $ % < > = ! : / . -
        // Replace everything else with space
        normalized = normalized.replace(Regex("[^a-z0-9\"$%<>!=:/.-]+"), " ")
        
        // 4. Tokenize, filter stop words, and sort to ignore word reordering and minor grammatical noise
        val stopWords = setOf("a", "an", "the")
        val tokens = normalized.split(" ")
            .filter { it.isNotBlank() && it !in stopWords }
            .sorted()
            
        return tokens.joinToString(" ")
    }
}

/** True when a failure was caused by an array/object response-shape mismatch. */
internal fun Throwable.isResponseShapeFailure(): Boolean {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = this
    while (current != null && seen.add(current)) {
        val normalized = current.message.orEmpty().lowercase()
        if (RESPONSE_SHAPE_ERROR_MARKERS.any(normalized::contains)) return true
        current = current.cause
    }
    return false
}

/** True when a provider call exceeded its bounded request window. */
internal fun Throwable.isProviderStallFailure(): Boolean {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = this
    while (current != null && seen.add(current)) {
        if (current is SocketTimeoutException) return true
        val normalized = current.message.orEmpty().lowercase()
        if (current is InterruptedIOException && REQUEST_STALL_ERROR_MARKERS.any(normalized::contains)) return true
        if (current::class.java.simpleName.contains("timeout", ignoreCase = true)) return true
        current = current.cause
    }
    return false
}

/** True when Android has connectivity but DNS cannot resolve the provider host. */
internal fun Throwable.isNetworkResolutionFailure(): Boolean {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = this
    while (current != null && seen.add(current)) {
        if (current is UnknownHostException) return true
        val normalized = current.message.orEmpty().lowercase()
        if (NETWORK_RESOLUTION_ERROR_MARKERS.any(normalized::contains)) return true
        current = current.cause
    }
    return false
}

/** True when planning or research-strategy content is structurally unusable or off-topic. */
internal fun Throwable.isPlanStructureFailure(): Boolean {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = this
    while (current != null && seen.add(current)) {
        val normalized = current.message.orEmpty().lowercase()
        if (PLAN_STRUCTURE_ERROR_MARKERS.any(normalized::contains)) return true
        current = current.cause
    }
    return false
}

/** True when a provider route reports that its local worker pool is exhausted. */
internal fun Throwable.isProviderCapacityFailure(): Boolean {
    val seen = mutableSetOf<Throwable>()
    var current: Throwable? = this
    while (current != null && seen.add(current)) {
        val normalized = current.message.orEmpty().lowercase()
        if (PROVIDER_CAPACITY_ERROR_MARKERS.any(normalized::contains)) return true
        current = current.cause
    }
    return false
}

internal val RESPONSE_SHAPE_ERROR_MARKERS = listOf(
    "cannot be converted to jsonobject",
    "top-level json array",
    "object envelope was required",
    "did not contain a valid json object",
    "returned malformed json",
    "unexpected trailing content",
    "local tool arguments must be a json object",
    "local tool arguments must be one json object",
    "returned no usable text",
    "no usable text after tool execution",
    "returned no response choice",
    "returned an invalid response message",
    "failed_generation",
    "jsonschema",
    "does not validate",
    "enum validation",
    "enum",
)

private val REQUEST_STALL_ERROR_MARKERS = listOf(
    "timeout",
    "timed out",
    "deadline exceeded",
    "deadline reached",
)

private val NETWORK_RESOLUTION_ERROR_MARKERS = listOf(
    "unable to resolve host",
    "no address associated with hostname",
    "name or service not known",
    "temporary failure in name resolution",
)

internal val PLAN_STRUCTURE_ERROR_MARKERS = listOf(
    "planner did not produce a valid request-specific investigation plan",
    "request-specific synthesis milestone is required",
    "generic or structurally incomplete investigation plan",
    "generic, structurally incomplete, or explicitly stale investigation plan",
    "deep research requires exactly",
    "did not produce a valid request-specific research strategy",
    "unrelated research strategy",
    "adaptive research strategy omitted",
    "adaptive research strategy needs at least",
    "adaptive research queries must investigate distinct unknowns",
    "adaptive research queries repeat one information need",
)

private val PROVIDER_CAPACITY_ERROR_MARKERS = listOf(
    "resourceexhausted",
    "resource exhausted",
    "worker local total request limit reached",
    "provider capacity exhausted",
    "provider capacity exhaustion",
    "rate limit",
    "temporarily rate-limited",
    "too many requests",
    "429",
)
