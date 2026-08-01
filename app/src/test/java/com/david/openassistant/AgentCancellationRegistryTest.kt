package com.david.openassistant

import com.david.openassistant.agent.AgentCancellationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCancellationRegistryTest {
    @Test
    fun cancelSignalsOnlyTheCurrentRegistration() {
        var cancelled = false
        val token = AgentCancellationRegistry.register("goal") { cancelled = true }

        assertTrue(AgentCancellationRegistry.cancel("goal"))
        assertTrue(cancelled)

        AgentCancellationRegistry.unregister("goal", token)
        assertFalse(AgentCancellationRegistry.cancel("goal"))
    }
}
