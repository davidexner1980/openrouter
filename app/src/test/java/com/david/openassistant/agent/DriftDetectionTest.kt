package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Test

class DriftDetectionTest {

    @Test
    fun testStablePlanDetection() {
        val contract = ObjectiveContract(
            primarySubject = "SpaceX Mars Mission",
            strongAnchors = listOf("SpaceX", "Mars", "Starship"),
            temporalContext = "2024-2030",
            expectedDeliverableKind = "Detailed timeline"
        )
        
        val plan = AgentPlanDraft(
            title = "Starship Mars Timeline",
            objective = "Investigate the SpaceX Starship development for Mars missions.",
            finalOutputDescription = "A report on the Mars mission timeline.",
            acceptanceCriteria = emptyList(),
            tasks = listOf(
                AgentTaskDraft("t1", "Starship status", "Check SpaceX website for Starship updates regarding Mars.", AgentCapability.WEB_RESEARCH, emptyList(), 1.0, emptyList())
            )
        )
        
        val report = AgentDriftAuditor.evaluateDrift(contract, plan)
        assertFalse("Plan should not be drifted", report.isDrifted)
        assertEquals(0.0, report.driftSeverity, 0.01)
    }

    @Test
    fun testDriftedPlanDetection() {
        val contract = ObjectiveContract(
            primarySubject = "SpaceX Mars Mission",
            strongAnchors = listOf("SpaceX", "Mars", "Starship"),
            temporalContext = "2024-2030",
            expectedDeliverableKind = "Detailed timeline"
        )
        
        val plan = AgentPlanDraft(
            title = "Blue Origin Moon Mission",
            objective = "Investigate Blue Origin's plans for the Moon.",
            finalOutputDescription = "A report on the Moon mission.",
            acceptanceCriteria = emptyList(),
            tasks = listOf(
                AgentTaskDraft("t1", "New Glenn status", "Check Blue Origin website.", AgentCapability.WEB_RESEARCH, emptyList(), 1.0, emptyList())
            )
        )
        
        val report = AgentDriftAuditor.evaluateDrift(contract, plan)
        assertTrue("Plan should be drifted", report.isDrifted)
        assertEquals(1.0, report.driftSeverity, 0.01)
        assertTrue(report.missingAnchors.contains("SpaceX"))
        assertTrue(report.missingAnchors.contains("Mars"))
        assertTrue(report.missingAnchors.contains("Starship"))
    }

    @Test
    fun testPartialDrift() {
        val contract = ObjectiveContract(
            primarySubject = "SpaceX Mars Mission",
            strongAnchors = listOf("SpaceX", "Mars", "Starship", "Fuel"),
            temporalContext = "2024-2030",
            expectedDeliverableKind = "Detailed timeline"
        )
        
        val plan = AgentPlanDraft(
            title = "Starship Fueling",
            objective = "Investigate SpaceX fuel production.",
            finalOutputDescription = "Fuel report.",
            acceptanceCriteria = emptyList(),
            tasks = listOf(
                AgentTaskDraft("t1", "Methane", "Check SpaceX methane production for Starship.", AgentCapability.WEB_RESEARCH, emptyList(), 1.0, emptyList())
            )
        )
        
        val report = AgentDriftAuditor.evaluateDrift(contract, plan)
        // Missing "Mars" -> 1/4 = 0.25. Threshold is 0.3.
        assertFalse("Partial drift below threshold should be stable", report.isDrifted)
        assertEquals(0.25, report.driftSeverity, 0.01)
    }
    
    @Test
    fun testRecoveryDrift() {
        val contract = ObjectiveContract(
            primarySubject = "SpaceX Mars Mission",
            strongAnchors = listOf("SpaceX", "Mars", "Starship"),
            temporalContext = "2024-2030",
            expectedDeliverableKind = "Detailed timeline"
        )
        
        val proposal = RecoveryProposal(
            revisedInvestigationInterpretation = "Focusing on SpaceX booster recovery.",
            specificUnresolvedGap = "Boilerplate gap.",
            selectedSourceFamilyShift = null,
            evidenceTargets = listOf("Booster data"),
            falsifiers = emptyList(),
            newQueryPortfolio = listOf("SpaceX booster Starship"),
            followUpRule = null,
            rationale = "Test",
            expectedNoveltyDimensions = listOf("none")
        )
        
        val report = AgentDriftAuditor.evaluateRecoveryDrift(contract, proposal)
        // Contains SpaceX, Starship. Missing Mars. 1/3 = 0.33. Threshold for recovery is 0.5.
        assertFalse("Recovery drift below threshold should be stable", report.isDrifted)
    }
}
