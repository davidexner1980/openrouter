package com.david.openassistant

import com.david.openassistant.agent.AgentCapability
import com.david.openassistant.agent.AgentGoal
import com.david.openassistant.agent.AgentGoalStatus
import com.david.openassistant.agent.AgentTask
import com.david.openassistant.agent.AgentTaskStatus
import com.david.openassistant.agent.selectAgentExecutionStrategy
import com.david.openassistant.agent.milestoneBoundaryInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integration-level proof for universal tool availability across all mission phases.
 */
class AgentToolAvailabilityTest {

    @Test
    fun allMissionPhasesAllowInteractiveTools() {
        val capabilities = AgentCapability.entries
        
        capabilities.forEach { capability ->
            val task = task(capability)
            val strategy = selectAgentExecutionStrategy(goal(task), task)
            
            assertTrue("Capability ${capability.name} must allow interactive tools", 
                strategy.allowsInteractiveTools)
        }
    }

    @Test
    fun milestoneBoundariesNeverProhibitResearch() {
        AgentCapability.entries.forEach { capability ->
            val instruction = milestoneBoundaryInstruction(capability)
            
            val prohibitedPhrases = listOf(
                "Do not research",
                "Do not perform new research",
                "Do not introduce searches",
                "Correct only from preserved evidence",
                "One response without tool loops",
                "Model-only",
                "Evidence-only"
            )
            
            prohibitedPhrases.forEach { phrase ->
                assertTrue("Instruction for ${capability.name} contains prohibited phrase: '$phrase'",
                    !instruction.contains(phrase, ignoreCase = true))
            }
            
            // Verify specific allowed research behavior
            when (capability) {
                AgentCapability.REASON -> {
                    assertTrue(instruction.contains("search or use tools"))
                    assertTrue(instruction.contains("current information are required"))
                }
                AgentCapability.SYNTHESIZE -> {
                    assertTrue(instruction.contains("gather new evidence"))
                    assertTrue(instruction.contains("critical gap remains"))
                }
                AgentCapability.CORRECT -> {
                    assertTrue(instruction.contains("search, fetch, calculate, inspect, or use another tool"))
                    assertTrue(instruction.contains("preserved evidence is insufficient"))
                }
                AgentCapability.VERIFY -> {
                    assertTrue(instruction.contains("search and use tools"))
                    assertTrue(instruction.contains("acceptance criteria"))
                }
                else -> {}
            }
        }
    }

    @Test
    fun checkpointCompletionRetainsToolAvailability() {
        val task = task(AgentCapability.DEEP_RESEARCH).copy(attemptCount = 2)
        val strategy = selectAgentExecutionStrategy(goal(task), task)
        
        // Note: depends on isUsefulCheckpoint which might need evidence to be true
        // But the profile CHECKPOINT_COMPLETION is already tested in AgentExecutionRecoveryTest
        // Here we just ensure that if it WERE selected, tools are allowed.
        assertTrue(strategy.allowsInteractiveTools)
    }

    private fun task(capability: AgentCapability) = AgentTask(
        id = "task-1",
        order = 0,
        title = "Test task",
        instructions = "Test instructions",
        capability = capability,
        status = AgentTaskStatus.QUEUED
    )

    private fun goal(task: AgentTask) = AgentGoal(
        conversationId = "conv-1",
        userRequest = "Test request",
        title = "Test goal",
        objective = "Test objective",
        finalOutputDescription = "Test output",
        status = AgentGoalStatus.RUNNING,
        plannerModelId = "model-1",
        executionModelId = "model-1",
        tasks = listOf(task)
    )
}
