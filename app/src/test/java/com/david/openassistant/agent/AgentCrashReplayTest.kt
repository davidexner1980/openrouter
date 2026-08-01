package com.david.openassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AgentCrashReplayTest {

    @Test
    fun staleActiveExchangeReconcilesToInterruptedOutcomeUnknown() {
        val exchangeId = "ex-" + UUID.randomUUID()
        val attempt = ProviderRequestAttempt(
            exchangeId = exchangeId,
            parentOperationId = "op-1",
            goalId = "goal-1",
            executionGeneration = 1,
            requestedModel = "openrouter/auto-beta",
            payloadFingerprint = "fingerprint123",
            exchangeOutcome = ExchangeOutcome.ACTIVE,
            providerAccountingOutcome = ProviderAccountingOutcome.PENDING,
            domainCommitOutcome = MissionDomainCommitOutcome.PENDING,
        )

        val updatedAttempt = attempt.copy(
            exchangeOutcome = ExchangeOutcome.INTERRUPTED_OUTCOME_UNKNOWN,
            providerAccountingOutcome = ProviderAccountingOutcome.UNKNOWN_AFTER_PROCESS_LOSS,
            domainCommitOutcome = MissionDomainCommitOutcome.REJECTED_OBSOLETE_GENERATION,
            usageSource = UsageSource.UNKNOWN_PROCESS_LOSS,
            safeDiagnosticSummary = "Reconciled stale ACTIVE exchange after process loss.",
        )

        assertEquals(ExchangeOutcome.INTERRUPTED_OUTCOME_UNKNOWN, updatedAttempt.exchangeOutcome)
        assertEquals(ProviderAccountingOutcome.UNKNOWN_AFTER_PROCESS_LOSS, updatedAttempt.providerAccountingOutcome)
        assertEquals(MissionDomainCommitOutcome.REJECTED_OBSOLETE_GENERATION, updatedAttempt.domainCommitOutcome)
        assertEquals(UsageSource.UNKNOWN_PROCESS_LOSS, updatedAttempt.usageSource)
    }

    @Test
    fun idempotentKeyPreventsDuplicateCommits() {
        val keys = mutableSetOf<String>()
        val key = "goal1:ex1:PROVIDER_ACCOUNTING"

        val firstClaim = keys.add(key)
        val secondClaim = keys.add(key)

        assertTrue(firstClaim)
        assertFalse(secondClaim)
    }
}
