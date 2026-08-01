package com.david.openassistant

import com.david.openassistant.agent.AgentClaim
import com.david.openassistant.agent.AgentClaimSupport
import com.david.openassistant.agent.AgentClaimType
import com.david.openassistant.agent.derivedMeasurementConsistencyIssues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DerivedMeasurementConsistencyTest {
    @Test
    fun impossibleArrowEnergyIsRejected() {
        val issues = derivedMeasurementConsistencyIssues(
            listOf(claim("Testing recorded 154 fps with a 567-grain arrow, delivering roughly 115 ft-lb.")),
        )

        assertEquals(1, issues.size)
        assertTrue(issues.single().message.contains("implies about 29.9 ft-lb"))
        assertTrue(issues.single().message.contains("not 115 ft-lb"))
    }

    @Test
    fun consistentArrowEnergyPasses() {
        val issues = derivedMeasurementConsistencyIssues(
            listOf(claim("Testing recorded 154 fps with a 567-grain arrow, producing 29.9 ft-lb.")),
        )

        assertTrue(issues.isEmpty())
    }

    @Test
    fun comparisonWithMultipleValueTriplesIsNotCrossPaired() {
        val issues = derivedMeasurementConsistencyIssues(
            listOf(
                claim(
                    "Bow A recorded 154 fps with 567 grains and 29.9 ft-lb, while Bow B recorded " +
                        "180 fps with 450 grains and 32.4 ft-lb.",
                ),
            ),
        )

        assertTrue(issues.isEmpty())
    }

    private fun claim(text: String) = AgentClaim(
        id = "measurement",
        taskId = "research",
        text = text,
        type = AgentClaimType.FACT,
        confidence = 0.9,
        support = AgentClaimSupport.SUPPORTED,
    )
}
