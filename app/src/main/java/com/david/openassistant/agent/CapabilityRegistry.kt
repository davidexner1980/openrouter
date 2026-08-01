package com.david.openassistant.agent

enum class CapabilityApprovalPolicy {
    AUTOMATIC,
    DISABLED,
}

data class AgentCapabilityDefinition(
    val capability: AgentCapability,
    val displayName: String,
    val description: String,
    val approvalPolicy: CapabilityApprovalPolicy,
    val usesNetwork: Boolean,
)

object AgentCapabilityRegistry {
    val definitions: Map<AgentCapability, AgentCapabilityDefinition> = listOf(
        AgentCapabilityDefinition(
            AgentCapability.REASON,
            "Reasoning",
            "Analyze the request and supplied mission evidence to define grounded intermediate conclusions without opening global workspace or research tools.",
            CapabilityApprovalPolicy.AUTOMATIC,
            usesNetwork = true,
        ),
        AgentCapabilityDefinition(
            AgentCapability.WEB_RESEARCH,
            "Legacy web research",
            "Compatibility mode for older saved goals. New factual work is planned as deep research.",
            CapabilityApprovalPolicy.AUTOMATIC,
            usesNetwork = true,
        ),
        AgentCapabilityDefinition(
            AgentCapability.DEEP_RESEARCH,
            "Deep research",
            "Run repeated discovery, full-source fetching, independent subagents, contradiction searches, multi-model synthesis, and source-backed claim extraction.",
            CapabilityApprovalPolicy.AUTOMATIC,
            usesNetwork = true,
        ),
        AgentCapabilityDefinition(
            AgentCapability.TOOL_USE,
            "Autonomous local tools",
            "Select and execute bounded deterministic local tools without approval dialogs.",
            CapabilityApprovalPolicy.AUTOMATIC,
            usesNetwork = true,
        ),
        AgentCapabilityDefinition(
            AgentCapability.TOOL_CREATE,
            "Tool Foundry",
            "Create versioned workflow-recipe tools from approved primitives, test them deterministically, and activate only passing recipes.",
            CapabilityApprovalPolicy.AUTOMATIC,
            usesNetwork = true,
        ),
        AgentCapabilityDefinition(
            AgentCapability.SYNTHESIZE,
            "Synthesis",
            "Combine completed evidence and tool results into the requested deliverable.",
            CapabilityApprovalPolicy.AUTOMATIC,
            usesNetwork = true,
        ),
        AgentCapabilityDefinition(
            AgentCapability.CORRECT,
            "Correction",
            "Repair a result after an independent verification failure.",
            CapabilityApprovalPolicy.AUTOMATIC,
            usesNetwork = true,
        ),
        AgentCapabilityDefinition(
            AgentCapability.VERIFY,
            "Verification",
            "Independently check evidence, source fit, contradictions, acceptance criteria, and the final result.",
            CapabilityApprovalPolicy.AUTOMATIC,
            usesNetwork = true,
        ),
    ).associateBy { it.capability }

    fun requireAllowed(capability: AgentCapability) {
        val definition = definitions[capability]
            ?: throw IllegalArgumentException("Capability is not registered: ${capability.wireName}")
        require(definition.approvalPolicy != CapabilityApprovalPolicy.DISABLED) {
            "Capability is disabled: ${definition.displayName}"
        }
    }
}
