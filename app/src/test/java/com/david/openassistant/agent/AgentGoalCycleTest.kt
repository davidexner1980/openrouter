package com.david.openassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentGoalCycleTest {

    @Test
    fun completedTaskCountFiltersByActiveCycle() {
        val cycle1 = "cycle-1"
        val cycle2 = "cycle-2"
        val tasks = listOf(
            AgentTask(id = "t1", cycleId = cycle1, status = AgentTaskStatus.COMPLETED, order = 0, title = "T1", instructions = "I1", capability = AgentCapability.REASON),
            AgentTask(id = "t2", cycleId = cycle1, status = AgentTaskStatus.COMPLETED, order = 1, title = "T2", instructions = "I2", capability = AgentCapability.REASON),
            AgentTask(id = "t3", cycleId = cycle2, status = AgentTaskStatus.COMPLETED, order = 0, title = "T3", instructions = "I3", capability = AgentCapability.REASON),
            AgentTask(id = "t4", cycleId = cycle2, status = AgentTaskStatus.QUEUED, order = 1, title = "T4", instructions = "I4", capability = AgentCapability.REASON)
        )
        
        val goal = AgentGoal(
            conversationId = "c1",
            userRequest = "R1",
            title = "T",
            objective = "O",
            finalOutputDescription = "D",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "m",
            executionModelId = "m",
            tasks = tasks,
            activeResearchCycleId = cycle2
        )
        
        assertEquals(1, goal.completedTaskCount)
    }

    @Test
    fun denseProgressScoreFiltersByActiveCycle() {
        val cycle1 = "cycle-1"
        val cycle2 = "cycle-2"
        val tasks = listOf(
            AgentTask(id = "t1", cycleId = cycle1, progressScore = 1.0, status = AgentTaskStatus.COMPLETED, order = 0, title = "T1", instructions = "I1", capability = AgentCapability.REASON),
            AgentTask(id = "t2", cycleId = cycle2, progressScore = 0.5, status = AgentTaskStatus.RUNNING, order = 0, title = "T2", instructions = "I2", capability = AgentCapability.REASON)
        )
        
        val goal = AgentGoal(
            conversationId = "c1",
            userRequest = "R1",
            title = "T",
            objective = "O",
            finalOutputDescription = "D",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "m",
            executionModelId = "m",
            tasks = tasks,
            activeResearchCycleId = cycle2
        )
        
        assertEquals(0.5, goal.denseProgressScore, 1e-6)
    }

    @Test
    fun nextRunnableTaskFiltersByActiveCycle() {
        val cycle1 = "cycle-1"
        val cycle2 = "cycle-2"
        val tasks = listOf(
            AgentTask(id = "t1", cycleId = cycle1, status = AgentTaskStatus.QUEUED, order = 0, title = "T1", instructions = "I1", capability = AgentCapability.REASON),
            AgentTask(id = "t2", cycleId = cycle2, status = AgentTaskStatus.QUEUED, order = 0, title = "T2", instructions = "I2", capability = AgentCapability.REASON)
        )
        
        val goal = AgentGoal(
            conversationId = "c1",
            userRequest = "R1",
            title = "T",
            objective = "O",
            finalOutputDescription = "D",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "m",
            executionModelId = "m",
            tasks = tasks,
            activeResearchCycleId = cycle2
        )
        
        assertEquals("t2", goal.nextRunnableTask()?.id)
    }

    @Test
    fun isReadyForVerificationFiltersByActiveCycle() {
        val cycle1 = "cycle-1"
        val cycle2 = "cycle-2"
        val tasks = listOf(
            AgentTask(id = "t1", cycleId = cycle1, status = AgentTaskStatus.FAILED, order = 0, title = "T1", instructions = "I1", capability = AgentCapability.REASON),
            AgentTask(id = "t2", cycleId = cycle2, status = AgentTaskStatus.COMPLETED, order = 0, title = "T2", instructions = "I2", capability = AgentCapability.REASON)
        )
        
        val goal = AgentGoal(
            conversationId = "c1",
            userRequest = "R1",
            title = "T",
            objective = "O",
            finalOutputDescription = "D",
            status = AgentGoalStatus.RUNNING,
            plannerModelId = "m",
            executionModelId = "m",
            tasks = tasks,
            activeResearchCycleId = cycle2
        )
        
        assertTrue(goal.isReadyForVerification)
    }
}
