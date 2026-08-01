package com.david.openassistant.agent

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentCallCancellationRegistryTest {
    @Test
    fun cancelInvokesEveryGoalCallbackOnceAndContainsFailures() {
        val goalId = UUID.randomUUID().toString()
        val otherGoalId = UUID.randomUUID().toString()
        val successfulCallbacks = AtomicInteger()
        val failingCallbacks = AtomicInteger()
        val otherGoalCallbacks = AtomicInteger()

        AgentCallCancellationRegistry.register(goalId) {
            failingCallbacks.incrementAndGet()
            error("A failing cancellation callback must not block the remaining callbacks.")
        }
        AgentCallCancellationRegistry.register(goalId) { successfulCallbacks.incrementAndGet() }
        AgentCallCancellationRegistry.register(otherGoalId) { otherGoalCallbacks.incrementAndGet() }

        AgentCallCancellationRegistry.cancel(goalId)
        AgentCallCancellationRegistry.cancel(goalId)

        assertEquals(1, failingCallbacks.get())
        assertEquals(1, successfulCallbacks.get())
        assertEquals(0, otherGoalCallbacks.get())

        AgentCallCancellationRegistry.cancel(otherGoalId)
        assertEquals(1, otherGoalCallbacks.get())
    }

    @Test
    fun closingRegistrationRemovesItsCallback() {
        val goalId = UUID.randomUUID().toString()
        val callbacks = AtomicInteger()
        val registration = AgentCallCancellationRegistry.register(goalId) {
            callbacks.incrementAndGet()
        }

        registration.close()
        AgentCallCancellationRegistry.cancel(goalId)

        assertEquals(0, callbacks.get())
    }
}
