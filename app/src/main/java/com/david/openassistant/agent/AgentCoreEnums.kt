package com.david.openassistant.agent

enum class AgentGoalStatus {
    QUEUED,
    PLANNING,
    RESEARCHING,
    RETRIEVING,
    EXTRACTING,
    VALIDATING,
    SYNTHESIZING,
    RECOVERING,
    WAITING_FOR_NETWORK,
    WAITING_FOR_CREDENTIAL,
    WAITING_FOR_USER,
    BLOCKED_NEEDS_ACTION,
    COMPLETED,
    CANCELLING,
    CANCELLED,
    REJECTED,
    // Keep legacy for compatibility if referenced, or migrate
    RUNNING,
    VERIFYING,
    COMPLETED_WITH_STRONG_EVIDENCE,
    COMPLETED_WITH_QUALIFICATIONS,
    FAILED,
    PAUSED,
    BLOCKED,
    BLOCKED_WITH_PARTIAL_EVIDENCE,
    CONFLICTING_PRIMARY_SOURCES,
    INSUFFICIENT_CURRENT_DATA,
    REQUIRES_USER_CLARIFICATION,
    FINALIZING,
    RESEARCH_CYCLES_EXHAUSTED,
/** Parseable mission record whose execution-critical provenance is missing or invalid. */
    CORRUPT_OR_INCOMPLETE_MISSION,
}

/**
 * Thrown when an internal lifecycle transition or durable commit fails,
 * distinct from a provider or network failure.
 */
class InternalLifecycleException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

fun AgentGoalStatus.isFinalTerminalStatus(): Boolean = this in setOf(
    AgentGoalStatus.COMPLETED,
    AgentGoalStatus.COMPLETED_WITH_STRONG_EVIDENCE,
    AgentGoalStatus.COMPLETED_WITH_QUALIFICATIONS,
    AgentGoalStatus.CANCELLED,
    AgentGoalStatus.REJECTED,
    AgentGoalStatus.BLOCKED_WITH_PARTIAL_EVIDENCE,
    AgentGoalStatus.INSUFFICIENT_CURRENT_DATA,
    AgentGoalStatus.CONFLICTING_PRIMARY_SOURCES,
    AgentGoalStatus.RESEARCH_CYCLES_EXHAUSTED,
    AgentGoalStatus.CORRUPT_OR_INCOMPLETE_MISSION,
)

fun AgentGoalStatus.isInactive(): Boolean = isFinalTerminalStatus() || this in setOf(
    AgentGoalStatus.WAITING_FOR_CREDENTIAL,
    AgentGoalStatus.WAITING_FOR_NETWORK,
    AgentGoalStatus.WAITING_FOR_USER,
    AgentGoalStatus.REQUIRES_USER_CLARIFICATION,
    AgentGoalStatus.PAUSED,
    AgentGoalStatus.CANCELLING,
    AgentGoalStatus.FAILED,
    AgentGoalStatus.REJECTED,
    AgentGoalStatus.BLOCKED,
    AgentGoalStatus.BLOCKED_NEEDS_ACTION,
)

fun AgentGoalStatus.isActivePhase(): Boolean = this in setOf(
    AgentGoalStatus.PLANNING,
    AgentGoalStatus.QUEUED,
    AgentGoalStatus.RUNNING,
    AgentGoalStatus.RESEARCHING,
    AgentGoalStatus.RETRIEVING,
    AgentGoalStatus.EXTRACTING,
    AgentGoalStatus.VALIDATING,
    AgentGoalStatus.SYNTHESIZING,
    AgentGoalStatus.VERIFYING,
    AgentGoalStatus.RECOVERING,
    AgentGoalStatus.FINALIZING,
)

enum class AgentTaskStatus {
    PLANNED,
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    BLOCKED_WITH_PARTIAL_EVIDENCE,
}

fun AgentTaskStatus.isExecutable(): Boolean = this in setOf(
    AgentTaskStatus.PLANNED,
    AgentTaskStatus.QUEUED,
    AgentTaskStatus.RUNNING,
)

enum class AgentCapability(val wireName: String) {
    REASON("reason"),
    WEB_RESEARCH("web_research"),
    DEEP_RESEARCH("deep_research"),
    TOOL_USE("tool_use"),
    TOOL_CREATE("tool_create"),
    SYNTHESIZE("synthesize"),
    CORRECT("correct"),
    VERIFY("verify");

    companion object {
        fun fromWireName(value: String): AgentCapability =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unsupported agent capability: $value")

        val RESEARCH_CAPABILITIES = setOf(WEB_RESEARCH, DEEP_RESEARCH)
        val STRUCTURED_RESULT_CAPABILITIES = setOf(REASON, SYNTHESIZE, CORRECT, VERIFY)
        val SOURCE_DEPENDENT_FACTUAL_CAPABILITIES = setOf(WEB_RESEARCH, DEEP_RESEARCH, CORRECT, VERIFY, SYNTHESIZE)
    }
}

enum class AgentAttemptStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
}

enum class AgentEvidenceKind {
    PLAN,
    MODEL_OUTPUT,
    WEB_RESEARCH,
    DEEP_RESEARCH,
    RESEARCH_HIT,
    TOOL_RESULT,
    VERIFICATION,
    CHECKPOINT,
    SYSTEM_EVENT,
}

enum class AgentClaimType(val wireName: String) {
    FACT("fact"),
    INFERENCE("inference"),
    RECOMMENDATION("recommendation"),
    UNCERTAINTY("uncertainty"),
    ORIGINAL_HYPOTHESIS("original_hypothesis");

    companion object {
        fun fromWireName(value: String): AgentClaimType =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: INFERENCE
    }
}

enum class AgentClaimSupport(val wireName: String) {
    SUPPORTED("supported"),
    PARTIAL("partial"),
    UNSUPPORTED("unsupported"),
    CONTRADICTED("contradicted");

    companion object {
        fun fromWireName(value: String): AgentClaimSupport =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: UNSUPPORTED
    }
}

enum class AgentEvidenceRelation(val wireName: String) {
    SUPPORTS("supports"),
    CONTRADICTS("contradicts"),
    QUALIFIES("qualifies"),
    DEPENDS_ON("depends_on");
}

enum class AgentAcceptanceCheckStatus(val wireName: String) {
    PASS("pass"),
    PARTIAL("partial"),
    FAIL("fail"),
    NOT_EVALUATED("not_evaluated");

    companion object {
        fun fromWireName(value: String): AgentAcceptanceCheckStatus =
            entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: NOT_EVALUATED
    }
}

enum class AgentRoutingStage {
    AUTO_BETA,
    FREE,
    EXHAUSTED,
}

enum class RoutingPolicyProvenance {
    EXPLICIT_USER_SELECTION,
    ACCOUNT_SAFETY_RESTRICTION,
    LEGACY_EXPLICIT,
    LEGACY_AMBIGUOUS_SAFETY_LOCK,
    SYSTEM_RECOVERY,
}

enum class AgentConceptStatus {
    PROPOSED,
    SHADOW,
    ACCEPTED,
    REJECTED,
}

enum class ExchangeOutcome {
    ACTIVE,
    RESPONSE_SUCCESS,
    RESPONSE_ERROR,
    TRANSPORT_FAILURE,
    CANCELLED,

    /** Reserved outcome, not implemented in Slice 1. Slice 1 does not emit it. */
    CANCELLATION_TIMEOUT,
    INTERRUPTED_OUTCOME_UNKNOWN,

    // V34 Additions
    USABLE_STRUCTURED_RESULT,
    UNUSABLE_EMPTY_RESPONSE,
    UNUSABLE_WHITESPACE_RESPONSE,
    MALFORMED_STRUCTURED_RESPONSE,
    REFUSAL,
    RATE_LIMITED,
    AUTHENTICATION_FAILED,
    PROVIDER_UNAVAILABLE,
    RECOVERABLE_NETWORK_FAILURE,
    PERMANENT_FAILURE,
    UNCERTAIN_REMOTE_OUTCOME,
}

enum class ProviderAccountingOutcome {
    NOT_AVAILABLE,
    PENDING,
    COMMITTED,
    PARTIALLY_COMMITTED,
    UNKNOWN_AFTER_PROCESS_LOSS,
    FAILED_DURING_COMMIT,
    REJECTED_DUPLICATE,
}

enum class MissionDomainCommitOutcome {
    NOT_APPLICABLE,
    PENDING,
    COMMITTED,
    REJECTED_OBSOLETE_GENERATION,
    REJECTED_GOAL_TERMINAL,
    REJECTED_TASK_CANCELLED,
    REJECTED_DUPLICATE,
    FAILED_DURING_COMMIT,
}

enum class UsageSource {
    PROVIDER_RESPONSE,
    LOCAL_CALCULATED,
    UNKNOWN_PROCESS_LOSS,
}

enum class ProposalDispatchStatus {
    CLAIMED,
    DISPATCHED,
    COMPLETED_ACCEPTED,
    COMPLETED_REJECTED,
    FAILED,
    CANCELLED,
    INTERRUPTED_OUTCOME_UNKNOWN,
}

enum class ResearchComplexity {
    LOW, MEDIUM, HIGH, EXTREME
}

enum class ResearchRisk {
    LOW, MEDIUM, HIGH
}

enum class FreshnessNeed {
    NONE, USEFUL, REQUIRED
}

enum class SourceStrictness {
    LOW, NORMAL, PRIMARY_REQUIRED
}

enum class ContradictionNeed {
    LOW, NORMAL, HIGH
}

enum class ModelStrength {
    NORMAL, STRONG
}

enum class AllocationRecoveryDecision {
    RETRY_WITH_STRATEGY_CHANGE,
    ESCALATE_MODEL,
    MARK_EXHAUSTED,
    LOCAL_REPAIR,
}

enum class AgentTaskRole {
    PRIMARY_REASONING,
    ECONOMICAL_RESEARCH,
    REQUEST_CONSTRUCTION,
}

enum class MissionUiAction {
    PAUSE,
    RESUME,
    RESTART,
    STOP,
    DELETE
}

enum class EscalationTactic {
    NONE,
    REFORMULATE_QUERY,
    DECOMPOSE_QUESTION,
    SEARCH_AUTHORITATIVE_DOMAINS,
    INSPECT_SITEMAPS_INDEXES,
    FOLLOW_RELEVANT_LINKS,
    ALTERNATIVE_DISCOVERY_ADAPTER,
    LOCAL_EVIDENCE_INDEX_SEARCH,
    ALTERNATE_MODEL_PROVIDER,
    RE_EVALUATE_ASSUMPTIONS,
    SMALLEST_MISSING_FACT,
    ASK_USER,
    CYCLE_ADVANCE,
    MARK_EXHAUSTED,

    // V42.2 Additions
    RESOLVE_ENTITIES,
    SHIFT_SOURCE_FAMILY,
    FOLLOW_CITATIONS,
    DECOMPOSE_UNRESOLVED_GAP,
    SEARCH_CONTRADICTING_EVIDENCE,
    CHANGE_TEMPORAL_SCOPE,
    CHANGE_GEOGRAPHIC_SCOPE,
    SEARCH_PRIMARY_RECORDS,
    REBUILD_QUERY_PORTFOLIO,
    REVISE_OPERATIONAL_OBJECTIVE
}

enum class SourceReadProvenance {
    VERIFIED_FETCH,
    PROVIDER_EXTRACT,
    LEGACY_ASSUMED,
    UNVERIFIED_CITATION
}

enum class StructureRepairReason {
    SCHEMA_FAILURE,
    INSUFFICIENT_CLAIMS,
    INSUFFICIENT_CONTENT,
    NO_SUPPORTED_FACTUAL_CLAIM,
    ACCEPTANCE_CRITERIA_INCOMPLETE,
    INVALID_PROVENANCE
}

enum class StructureRepairOutcome {
    NOT_ATTEMPTED,
    PASSED,
    FAILED_SCHEMA,
    FAILED_PROVENANCE,
    FAILED_QUALITY,
    FAILED_ACCEPTANCE_CRITERIA
}

/**
 * Represents the final decision of a worker execution loop.
 */
enum class WorkerOutcome {
    CONTINUE,
    DONE,
    RETRY,
    FAIL,
}

/**
 * Diagnostic categorization for why an execution loop has stopped making progress.
 */
enum class ExecutionStallDiagnosis {
    NONE,
    INTELLIGENCE_WALL,
    PROGRESS_STALL,
    REPETITIVE_SEARCH_STALL,
    SHALLOW_RESEARCH_STALL,
    VERIFICATION_CIRCULARITY,

    // V42.2 Additions
    REPEATED_CONTEXT,
    DUPLICATE_QUERY_PORTFOLIO,
    SOURCE_HOMOGENEITY,
    NO_NEW_ACCEPTED_EVIDENCE,
    STALE_RESEARCH_STRATEGY,
    UNRESOLVED_GAP_STAGNATION,
    ENTITY_AMBIGUITY,
    TEMPORAL_SCOPE_MISMATCH
}

enum class CouncilRole {
    COORDINATOR,
    EXPLORER,
    SOURCE_ANALYST,
    SKEPTIC,
    GAP_ANALYST,
    CORRECTOR,
    VERIFIER,
    SYNTHESIZER,
    TOOL_SPECIALIST,
    PLANNER, // For compatibility
    INTERNAL_REQUEST_BUILDER // Body Builder role
}

enum class RoutingProfile {
    FREE_MODELS_ROUTER,
    AUTO_ROUTER_BETA,
    MANUAL
}

enum class ResumeReason {
    USER_RESUME,
    NETWORK_RESTORED,
    CREDENTIAL_RESTORED,
    PROCESS_RECOVERY,
    STALE_LEASE_RECOVERY,
    SCHEDULER_REPAIR,
    TACTIC_PIVOT,
    CYCLE_ADVANCE
}

enum class ToolUnavailabilityReason {
    NONE,
    NETWORK_UNAVAILABLE,
    CREDENTIALS_MISSING,
    PUBLIC_WEB_UNCONFIGURED,
    ROUTE_UNSUPPORTED,
    SAFE_MODE_RESTRICTION,
    USER_CONFIRMATION_REQUIRED,
    TOOL_RUNTIME_NOT_INITIALIZED,
    UNKNOWN
}

enum class MissionFailureClassification {
    NONE,
    SECURITY_VIOLATION,
    DATA_INTEGRITY_FAILURE,
    PROVIDER_ERROR,
    NETWORK_FAILURE,
    TOOL_FAILURE,
    RESEARCH_EXHAUSTION,
    OBJECTIVE_DRIFT,
    CITATION_FABRICATION,
    RECOVERY_STARVATION,
    TOOL_RESTRICTED_PHASE_STALL
}

data class EvidenceRequirement(
    val requiresExternalSources: Boolean = false,
    val minimumSubstantiveSources: Int = 0,
    val minimumPrimarySources: Int = 0,
    val requiresPublicWebSearch: Boolean = false,
    val requiresFullReads: Boolean = false,
    val mayUseUserProvidedSources: Boolean = true,
    val mayUseValidatedCachedSources: Boolean = true,
)

data class EvidenceCapability(
    val publicWebSearchAvailable: Boolean = false,
    val publicWebSearchUnavailableReason: ToolUnavailabilityReason = ToolUnavailabilityReason.NONE,
    val publicWebFetchAvailable: Boolean = false,
    val publicWebFetchUnavailableReason: ToolUnavailabilityReason = ToolUnavailabilityReason.NONE,
    val userProvidedEvidenceAvailable: Boolean = false,
    val validatedCachedEvidenceAvailable: Boolean = false,
)

/**
 * Derives evidence capability from a tool availability audit.
 */
fun deriveEvidenceCapability(audit: AgentToolRegistry.ToolAvailabilityAudit): EvidenceCapability {
    val searchReqs = audit.requirements.filter { it.toolName in setOf("public_web_search", "openrouter:web_search") }
    val fetchReqs = audit.requirements.filter { it.toolName in setOf("public_web_fetch", "openrouter:web_fetch") }

    val searchAvailable = searchReqs.any { it.operational }
    val fetchAvailable = fetchReqs.any { it.operational }

    return EvidenceCapability(
        publicWebSearchAvailable = searchAvailable,
        publicWebSearchUnavailableReason = if (searchAvailable) ToolUnavailabilityReason.NONE else searchReqs.firstOrNull()?.unavailabilityReason ?: ToolUnavailabilityReason.NONE,
        publicWebFetchAvailable = fetchAvailable,
        publicWebFetchUnavailableReason = if (fetchAvailable) ToolUnavailabilityReason.NONE else fetchReqs.firstOrNull()?.unavailabilityReason ?: ToolUnavailabilityReason.NONE,
        userProvidedEvidenceAvailable = true, // Policy: user provided is always "available" as a capability if it exists in the graph
        validatedCachedEvidenceAvailable = true
    )
}
