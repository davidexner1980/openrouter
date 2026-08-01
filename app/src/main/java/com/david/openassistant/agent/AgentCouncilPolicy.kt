package com.david.openassistant.agent

/**
 * Orchestrates the Collaborative Research Council.
 * Selects appropriate models for different semantic roles while maintaining
 * strict adherence to the goal's routing profile.
 */
internal object AgentCouncilPolicy {

    private const val AUTO_BETA_ROUTER = "openrouter/auto-beta"
    private const val FREE_ROUTER = "openrouter/free"
    private const val BODY_BUILDER = "openrouter/bodybuilder"

    fun selectModel(
        role: CouncilRole,
        profile: RoutingProfile,
        manualModelId: String? = null
    ): String {
        return when (profile) {
            RoutingProfile.MANUAL -> manualModelId ?: AUTO_BETA_ROUTER
            RoutingProfile.FREE_MODELS_ROUTER -> FREE_ROUTER
            RoutingProfile.AUTO_ROUTER_BETA -> {
                if (role == CouncilRole.INTERNAL_REQUEST_BUILDER) {
                    BODY_BUILDER
                } else {
                    AUTO_BETA_ROUTER
                }
            }
        }
    }

    fun roleForCapability(capability: AgentCapability, isFallback: Boolean = false): CouncilRole {
        return when (capability) {
            AgentCapability.REASON -> CouncilRole.COORDINATOR
            AgentCapability.WEB_RESEARCH,
            AgentCapability.DEEP_RESEARCH -> if (isFallback) CouncilRole.SOURCE_ANALYST else CouncilRole.EXPLORER
            AgentCapability.SYNTHESIZE -> CouncilRole.SYNTHESIZER
            AgentCapability.CORRECT -> CouncilRole.CORRECTOR
            AgentCapability.VERIFY -> CouncilRole.VERIFIER
            AgentCapability.TOOL_USE,
            AgentCapability.TOOL_CREATE -> CouncilRole.TOOL_SPECIALIST
        }
    }

    /**
     * Recommends independent models for Critic/Verifier roles if available.
     * Currently simplified for Slice 1.
     */
    fun selectIndependentModel(
        role: CouncilRole,
        primaryModelId: String,
        profile: RoutingProfile
    ): String {
        if (profile != RoutingProfile.AUTO_ROUTER_BETA) return primaryModelId
        
        // In Auto mode, we can try to select a known strong independent model
        // but for now, we'll stick to the Auto Router and let it handle selection.
        return AUTO_BETA_ROUTER
    }
}
