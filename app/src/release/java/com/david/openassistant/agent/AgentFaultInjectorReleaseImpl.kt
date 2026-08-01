package com.david.openassistant.agent

/**
 * Hardcoded no-op release implementation.
 * Unconditionally disables fault injection in release builds.
 */
class AgentFaultInjectorReleaseImpl : FaultInjectorProvider {

    override fun isDebugBuild(): Boolean = false

    override fun setActiveScenario(scenario: FaultScenario) {
        // No-op in release builds
    }

    override fun getActiveScenario(): FaultScenario = FaultScenario.NONE

    override fun clearScenario() {
        // No-op in release builds
    }

    override fun maybeInjectFault(operation: String) {
        // Unconditional no-op in release builds
    }
}
