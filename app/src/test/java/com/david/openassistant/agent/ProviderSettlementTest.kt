package com.david.openassistant.agent

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSettlementTest {

    @Test
    fun `test waitForSettlement waits for active requests`() = runBlocking {
        val exchangeId = "test-exchange-1"
        ProviderRequestLedger.start(exchangeId)
        
        val settlementJob = async {
            ProviderRequestLedger.waitForSettlement(timeoutMs = 1000)
        }
        
        assertFalse("Ledger should not be settled yet", ProviderRequestLedger.isSettled())
        
        delay(200)
        ProviderRequestLedger.terminalize(exchangeId, RequestState.COMPLETED)
        
        settlementJob.await()
        assertTrue("Ledger should be settled after terminalization", ProviderRequestLedger.isSettled())
        
        ProviderRequestLedger.clear(exchangeId)
    }

    @Test
    fun `test settlement with multiple requests`() = runBlocking {
        val id1 = "req-1"
        val id2 = "req-2"
        ProviderRequestLedger.start(id1)
        ProviderRequestLedger.start(id2)
        
        assertFalse(ProviderRequestLedger.isSettled())
        
        ProviderRequestLedger.terminalize(id1, RequestState.FAILED)
        assertFalse("Still one active request", ProviderRequestLedger.isSettled())
        
        ProviderRequestLedger.terminalize(id2, RequestState.CANCELLED)
        assertTrue("All terminal", ProviderRequestLedger.isSettled())
        
        ProviderRequestLedger.clear(id1)
        ProviderRequestLedger.clear(id2)
    }

    @Test
    fun `test timeout in waitForSettlement`() = runBlocking {
        val exchangeId = "hanging-req"
        ProviderRequestLedger.start(exchangeId)
        
        val start = System.currentTimeMillis()
        ProviderRequestLedger.waitForSettlement(timeoutMs = 500)
        val duration = System.currentTimeMillis() - start
        
        assertTrue("Should have waited at least 500ms", duration >= 500)
        assertFalse("Should still be active (timed out)", ProviderRequestLedger.isSettled())
        
        ProviderRequestLedger.terminalize(exchangeId, RequestState.TIMEOUT)
        ProviderRequestLedger.clear(exchangeId)
    }
}
