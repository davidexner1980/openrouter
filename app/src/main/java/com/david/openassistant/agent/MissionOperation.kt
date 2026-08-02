package com.david.openassistant.agent

/**
 * Typed runtime operation classification for autonomous mission provider requests.
 * Serialization of this operation remains outside Version 7.
 */
enum class MissionOperation(
    val operationName: String,
    val taskBound: Boolean,
) {
    CREATE_PLAN("create_plan", false),
    PLAN_REFINEMENT("plan_refinement", false),
    EXECUTE_TASK("execute_task", true),
    STEP_STRUCTURE_REPAIR("step_structure_repair", true),
    VERIFY_GOAL("verify_goal", false),
    VERIFICATION_REPAIR("verification_repair", false),
    ADAPTIVE_RESEARCH_STRATEGY("adaptive_research_strategy", true),
    FAILED_QUERY_REFINEMENT("failed_query_refinement", true),
    INFORMATION_NEED_RECONSTRUCTION("information_need_reconstruction", true),
    EVIDENCE_FOLLOW_UP("evidence_follow_up", true),
    RESEARCH_STRATEGY_REFINEMENT("research_strategy_refinement", true),
    BODY_BUILDER_REQUEST("body_builder_request", true),
    BODY_BUILDER_GENERATED_EXECUTION("body_builder_generated_execution", true),
    LENGTH_CONTINUATION("length_continuation", true);

    companion object {
        fun fromName(name: String): MissionOperation? = values().firstOrNull { it.operationName == name }
    }
}
