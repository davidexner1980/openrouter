package com.david.openassistant

import com.david.openassistant.agent.planTemporalScopeIsCurrent
import com.david.openassistant.agent.providerTemporalContext
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTemporalContextTest {
    private val today = LocalDate.of(2026, 7, 18)

    @Test
    fun providerReceivesTheExactDeviceDateAndFreshnessRule() {
        val context = providerTemporalContext(today)

        assertTrue(context.contains("2026-07-18"))
        assertTrue(context.contains("Do not cap current research at an older training year"))
    }

    @Test
    fun exactPhoneTraceStaleMarketBoundaryIsRejected() {
        val material = "The data set reflects the market state as of the latest available quarter (Q2-Q3 2024). Candidate bows are currently marketed (2022-2024)."

        assertEquals(
            false,
            planTemporalScopeIsCurrent(
                request = "what is the best recurve bow you can buy",
                material = material,
                today = today,
            ),
        )
    }

    @Test
    fun relativeFreshnessWithoutAnOutdatedUpperBoundaryIsAccepted() {
        val material = "Verify currently available recurve bow candidates from dated manufacturer pages and close all freshness gaps before ranking them."

        assertEquals(
            true,
            planTemporalScopeIsCurrent(
                request = "what is the best recurve bow you can buy",
                material = material,
                today = today,
            ),
        )
    }

    @Test
    fun userRequestedHistoricalYearIsNotOverridden() {
        assertEquals(
            true,
            planTemporalScopeIsCurrent(
                request = "what was the best recurve bow to buy in 2024",
                material = "Compare the market as of 2024.",
                today = today,
            ),
        )
    }

    @Test
    fun historicalComparisonDoesNotDisableCurrentScopeValidation() {
        assertEquals(
            false,
            planTemporalScopeIsCurrent(
                request = "compare the best recurve bow from 2024 with what is currently available",
                material = "Use the market as of 2024 for every candidate.",
                today = today,
            ),
        )
    }
}
