package com.david.openassistant

import com.david.openassistant.agent.AgentAcceptanceCheckStatus
import com.david.openassistant.agent.AgentAcceptanceCriterion
import com.david.openassistant.agent.recoverExplicitStepAssessment
import com.david.openassistant.agent.stepExecutionShapeContract
import com.david.openassistant.agent.stepRepairShapeContract
import com.david.openassistant.agent.hasCanonicalStepWireShape
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StepStructureRecoveryTest {
    private val criterion = AgentAcceptanceCriterion(
        id = "R1-AC",
        description = "A complete list of unknowns and required evidence is recorded.",
    )

    @Test
    fun repairContractNamesEveryRequiredWireField() {
        val contract = stepRepairShapeContract(listOf(criterion))

        listOf(
            "work_product",
            "completion_score",
            "acceptance_checks",
            "criterion_id",
            "claims",
            "unresolved_questions",
            "R1-AC",
        ).forEach { required -> assertTrue(contract.contains(required)) }
    }

    @Test
    fun executionContractTellsCompatibilityModelsTheExactTopLevelShape() {
        val contract = stepExecutionShapeContract(listOf(criterion))

        assertTrue(contract.contains("only the assigned milestone"))
        assertTrue(contract.contains("\"work_product\""))
        assertTrue(contract.contains("R1-AC"))
        assertTrue(contract.contains("Writing a grounded claim in this response is synthesis, not invention"))
        assertFalse(contract.contains("Use an empty claims array rather than inventing factual claims"))
    }

    @Test
    fun repairContractPreservesEmptyClaimsAsTheSafeStructuralFallback() {
        val contract = stepRepairShapeContract(listOf(criterion))

        assertTrue(contract.contains("Use an empty claims array rather than inventing claims or citations"))
    }

    @Test
    fun canonicalShapeRequiresExactFieldsAndScalarTypes() {
        val canonical = JSONObject(
            """{"work_product":"mapped","completion_score":1.0,"acceptance_checks":[],"claims":[],"unresolved_questions":[]}""",
        )
        val alternate = JSONObject(
            """{"work_product":{"text":"mapped"},"completion_score":"1.0","acceptance_checks":[],"claims":[],"unresolved_questions":[]}""",
        )

        assertTrue(hasCanonicalStepWireShape(canonical))
        assertEquals(false, hasCanonicalStepWireShape(alternate))
    }

    @Test
    fun nestedExplicitMetGradeRecoversWithoutInventingClaims() {
        val recovered = recoverExplicitStepAssessment(
            repairContent = """
                {
                  "milestone": "Define unknowns",
                  "acceptance_criteria": {
                    "R1-AC": {
                      "description": "A complete list is recorded.",
                      "grade": "Met"
                    }
                  },
                  "evidence_gaps": ["Primary records remain unresolved"]
                }
            """.trimIndent(),
            criteria = listOf(criterion),
        )

        assertEquals(AgentAcceptanceCheckStatus.PASS, recovered?.checks?.single()?.status)
        assertEquals(1.0, recovered?.completionScore ?: -1.0, 0.0)
        assertEquals(listOf("Primary records remain unresolved"), recovered?.unresolvedQuestions)
    }

    @Test
    fun criteriaGradesShapeFromDeviceTraceRecoversPass() {
        val recovered = recoverExplicitStepAssessment(
            repairContent = """
                {
                  "acceptance_criteria": {
                    "R1-AC": "A complete list of unknowns and required evidence is recorded."
                  },
                  "criteria_grades": {"R1-AC": "Pass"}
                }
            """.trimIndent(),
            criteria = listOf(criterion),
        )

        assertEquals(AgentAcceptanceCheckStatus.PASS, recovered?.checks?.single()?.status)
        assertEquals(1.0, recovered?.completionScore ?: -1.0, 0.0)
    }

    @Test
    fun pascalCaseGradingShapeFromPhoneReportRecoversPass() {
        val recovered = recoverExplicitStepAssessment(
            repairContent = """
                {
                  "Milestone": "Reason: Define the decision problem",
                  "AcceptanceCriteria": {
                    "R1-AC": {
                      "Requirement": "A complete list is recorded.",
                      "Status": "COMPLETED"
                    }
                  },
                  "Grading": {"R1-AC": "COMPLETED"},
                  "EvidenceGaps": ["Current prices remain unverified"]
                }
            """.trimIndent(),
            criteria = listOf(criterion),
        )

        assertEquals(AgentAcceptanceCheckStatus.PASS, recovered?.checks?.single()?.status)
        assertEquals(1.0, recovered?.completionScore ?: -1.0, 0.0)
        assertEquals(listOf("Current prices remain unverified"), recovered?.unresolvedQuestions)
    }

    @Test
    fun criterionDescriptionIsNeverMistakenForACompletionGrade() {
        val recovered = recoverExplicitStepAssessment(
            repairContent = """
                {
                  "acceptance_criteria": {
                    "R1-AC": "A complete list of unknowns and required evidence is recorded."
                  }
                }
            """.trimIndent(),
            criteria = listOf(criterion),
        )

        assertNull(recovered)
    }

    @Test
    fun explicitFailureRemainsFailure() {
        val recovered = recoverExplicitStepAssessment(
            repairContent = """{"criteria_grades":{"R1-AC":"Not met"}}""",
            criteria = listOf(criterion),
        )

        assertEquals(AgentAcceptanceCheckStatus.FAIL, recovered?.checks?.single()?.status)
        assertEquals(0.0, recovered?.completionScore ?: -1.0, 0.0)
    }
}
