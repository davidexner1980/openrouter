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
    /** Parseable mission record whose execution-critical provenance is missing or invalid. */
    CORRUPT_OR_INCOMPLETE_MISSION,
}

enum class AgentTaskStatus {
    PLANNED,
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    BLOCKED_WITH_PARTIAL_EVIDENCE,
}

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
        val EVIDENCE_BOUNDED_CAPABILITIES = setOf(REASON, SYNTHESIZE, CORRECT, VERIFY)
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
    ASK_USER
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
