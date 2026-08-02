package com.david.openassistant.agent

import org.junit.Assert.*
import org.junit.Test

class EvidenceContextSelectorTest {

    @Test
    fun selectsEvidenceByActiveCycleAndCarriedForwardIds() {
        val cycle1Id = "cycle-1"
        val cycle2Id = "cycle-2"
        
        val ev1 = AgentEvidence(id = "ev1", cycleId = cycle1Id, kind = AgentEvidenceKind.WEB_RESEARCH, title = "Title 1", summary = "S1", content = "Content 1")
        val ev2 = AgentEvidence(id = "ev2", cycleId = cycle1Id, kind = AgentEvidenceKind.WEB_RESEARCH, title = "Title 2", summary = "S2", content = "Content 2")
        val ev3 = AgentEvidence(id = "ev3", cycleId = cycle2Id, kind = AgentEvidenceKind.WEB_RESEARCH, title = "Title 3", summary = "S3", content = "Content 3")
        
        val goal = AgentGoal(
            conversationId = "c1",
            userRequest = "Test",
            title = "Test",
            objective = "Test",
            finalOutputDescription = "Test",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "m1",
            executionModelId = "m2",
            tasks = listOf(
                AgentTask(id = "t1", order = 0, title = "T1", instructions = "I1", capability = AgentCapability.REASON, cycleId = cycle2Id)
            ),
            evidence = listOf(ev1, ev2, ev3),
            researchCycles = listOf(
                ResearchCycle(id = cycle1Id, ordinal = 0, status = ResearchCycleStatus.SUPERSEDED),
                ResearchCycle(
                    id = cycle2Id, 
                    ordinal = 1, 
                    status = ResearchCycleStatus.ACTIVE,
                    learningSummary = ResearchCycleLearningSummary(
                        carriedForwardEvidenceIds = listOf("ev1")
                    )
                )
            ),
            activeResearchCycleId = cycle2Id
        )
        
        val selected = EvidenceContextSelector.select(goal, goal.tasks[0])
        
        val selectedIds = selected.evidence.map { it.id }.toSet()
        
        assertTrue("ev1 should be carried forward", selectedIds.contains("ev1"))
        assertFalse("ev2 should be excluded (superseded cycle and not carried forward)", selectedIds.contains("ev2"))
        assertTrue("ev3 should be included (active cycle)", selectedIds.contains("ev3"))
    }
}
