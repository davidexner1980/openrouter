package com.david.openassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ProviderRequestLedgerTest {

    @Test
    fun terminalizeIsExactlyOnce() {
        val exchangeId = "ex-" + UUID.randomUUID()
        ProviderRequestLedger.start(exchangeId)

        val firstTerminal = ProviderRequestLedger.terminalize(exchangeId, RequestState.COMPLETED)
        val secondTerminal = ProviderRequestLedger.terminalize(exchangeId, RequestState.FAILED)

        assertTrue("First terminalization must succeed", firstTerminal)
        assertFalse("Second terminalization must be rejected as duplicate", secondTerminal)
        assertEquals(RequestState.COMPLETED, ProviderRequestLedger.getState(exchangeId))
    }

    @Test
    fun structuredIdempotencyRecordLifecycle() {
        val record = IdempotencyRecord(
            key = "goal1:ex1:PROVIDER_ACCOUNTING",
            effectType = IdempotencyEffectType.PROVIDER_ACCOUNTING,
            state = IdempotencyState.CLAIMED,
            claimOwner = "worker-1",
            claimGeneration = 1,
        )

        val committed = record.copy(
            state = IdempotencyState.COMMITTED,
            committedAt = System.currentTimeMillis(),
            completedBy = "worker-1",
        )

        assertEquals(IdempotencyState.CLAIMED, record.state)
        assertEquals(IdempotencyState.COMMITTED, committed.state)
    }
}
