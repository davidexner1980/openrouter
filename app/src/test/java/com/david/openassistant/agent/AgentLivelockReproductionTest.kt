package com.david.openassistant.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AgentLivelockReproductionTest {

    @Test
    fun reproduceCycleMismatchLivelockState() {
        val goalId = "livelock-goal"
        val cycleId = "cycle-baseline-$goalId"
        
        // 1. Construct the exact defective persisted state:
        // - Active baseline cycle exists.
        // - Six queued tasks exist.
        // - All six task cycle IDs are null.
        val tasks = (1..6).map { i ->
            AgentTask(
                id = "task-$i",
                cycleId = null, // DEFECT: cycleId is null
                order = i - 1,
                title = "Task $i",
                instructions = "Do $i",
                capability = AgentCapability.REASON,
                status = AgentTaskStatus.QUEUED
            )
        }
        
        val baselineCycle = ResearchCycle(
            id = cycleId,
            ordinal = 1,
            parentCycleId = null,
            status = ResearchCycleStatus.ACTIVE,
            objectiveRevisionId = "rev-baseline-$goalId",
            triggerDiagnosis = ExecutionStallDiagnosis.NONE,
            selectedAdvancementTactic = EscalationTactic.NONE,
            strategyFingerprint = "baseline",
            queryPortfolioFingerprint = "baseline",
            acceptedEvidenceFingerprint = "baseline",
            unresolvedGapFingerprint = "baseline",
            learningSummary = null,
            activatedAt = System.currentTimeMillis()
        )
        
        val goal = AgentGoal(
            id = goalId,
            conversationId = "conv-1",
            userRequest = "Reproduction request",
            title = "Reproduction Title",
            objective = "Reproduction Objective",
            finalOutputDescription = "Reproduction Description",
            status = AgentGoalStatus.QUEUED,
            plannerModelId = "model",
            executionModelId = "model",
            tasks = tasks,
            researchCycles = listOf(baselineCycle),
            objectiveRevisions = listOf(ObjectiveRevision(
                id = "rev-baseline-$goalId",
                ordinal = 1,
                parentRevisionId = null,
                immutableRootObjectiveFingerprint = "fp",
                operationalObjective = "O",
                unresolvedGaps = emptyList(),
                retainedConstraints = emptyList(),
                evidenceRequirements = emptyList(),
                revisionReason = "Initial",
                revisionFingerprint = "fp"
            )),
            activeResearchCycleId = cycleId
        )
        
        // 2. Verify that chooseNextTask returns null due to cycle mismatch
        val profile = AgentResearchAllocator.profileForGoal(goal, AutonomyPolicy.DEFAULT)
        val selection = AgentResearchAllocator.chooseNextTask(goal, profile)
        
        assertNull("Next task should be null due to cycle ID mismatch before repair", selection.taskId)
        assertEquals(6, goal.tasks.size)
        assertEquals(0, goal.tasks.count { it.cycleId == goal.activeResearchCycleId })

        // 3. Verify that the repair works
        val tempDir = java.nio.file.Files.createTempDirectory("livelock_repair").toFile()
        val store = AgentStore(tempDir)
        val validateMethod = AgentStore::class.java.getDeclaredMethod("validateAndRepairInvariants", AgentGoal::class.java)
        validateMethod.isAccessible = true
        
        val repairedGoal = validateMethod.invoke(store, goal) as AgentGoal
        
        // Assert repair effect
        assertEquals(6, repairedGoal.tasks.count { it.cycleId == cycleId })
        assertTrue(repairedGoal.idempotencyRecords.any { it.key == "v42-plan-cycle-binding:$goalId" })
        assertTrue(repairedGoal.events.any { it.message.contains("V42.4: Repaired initial task-cycle binding") })
        
        // 4. Verify that chooseNextTask now works
        val repairedSelection = AgentResearchAllocator.chooseNextTask(repairedGoal, profile)
        assertNotNull("Next task should be selected after repair", repairedSelection.taskId)
        assertEquals("task-1", repairedSelection.taskId)
    }
}
