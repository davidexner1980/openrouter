package com.david.openassistant.agent

import java.io.IOException

class AgentFaultInjectorDebugImpl : FaultInjectorProvider {

    @Volatile
    private var activeScenario: FaultScenario = FaultScenario.NONE

    override fun isDebugBuild(): Boolean = true

    override fun setActiveScenario(scenario: FaultScenario) {
        activeScenario = scenario
    }

    override fun getActiveScenario(): FaultScenario = activeScenario

    override fun clearScenario() {
        activeScenario = FaultScenario.NONE
    }

    override fun maybeInjectFault(operation: String) {
        val current = activeScenario
        if (current == FaultScenario.NONE) return

        when (current) {
            FaultScenario.DNS_FAILURE -> {
                clearScenario()
                throw java.net.UnknownHostException("Simulated DNS failure for $operation")
            }
            FaultScenario.OFFLINE_TRANSPORT -> {
                clearScenario()
                throw IOException("Simulated network offline for $operation")
            }
            FaultScenario.MODEL_CAPACITY_503 -> {
                clearScenario()
                throw IOException("HTTP 503: Provider capacity exhausted for $operation")
            }
            else -> {
                // Handled at specific test points
            }
        }
    }
}
