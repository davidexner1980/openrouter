package com.david.openassistant.agent

import com.david.openassistant.BuildConfig

enum class FaultScenario {
    NONE,
    DNS_FAILURE,
    OFFLINE_TRANSPORT,
    RATE_LIMIT_WITH_RETRY_AFTER,
    MODEL_CAPACITY_503,
    CANCELLATION_RACE,
    OBSOLETE_GENERATION_CALLBACK,
    PROCESS_LOSS_STATE,
}

interface FaultInjectorProvider {
    fun isDebugBuild(): Boolean
    fun setActiveScenario(scenario: FaultScenario)
    fun getActiveScenario(): FaultScenario
    fun clearScenario()
    fun maybeInjectFault(operation: String)
}

object AgentFaultInjector {
    private var provider: FaultInjectorProvider? = null

    fun initialize(p: FaultInjectorProvider) {
        provider = p
    }

    fun isDebugBuild(): Boolean = BuildConfig.DEBUG && (provider?.isDebugBuild() == true)

    fun setActiveScenario(scenario: FaultScenario) {
        provider?.setActiveScenario(scenario)
    }

    fun getActiveScenario(): FaultScenario = provider?.getActiveScenario() ?: FaultScenario.NONE

    fun clearScenario() {
        provider?.clearScenario()
    }

    fun maybeInjectFault(operation: String) {
        provider?.maybeInjectFault(operation)
    }
}
