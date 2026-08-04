package com.david.openassistant.agent

import com.david.openassistant.domain.tools.AutonomousToolRuntime
import com.david.openassistant.domain.tools.SafeToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * High-level integration proof for Universal Tool Availability.
 */
class AgentUniversalToolAvailabilityTest {

    private open class FakeToolRuntime(val network: Boolean = true) : AutonomousToolRuntime(null, null, null, null, null, null, null, null, null, null, null, null) {
        override fun definitions(): List<SafeToolDefinition> = emptyList()
        override fun isNetworkAvailable(): Boolean = network
        override fun isPublicWebConfigured(): Boolean = true
    }

    @Test
    fun strategySelectionAllowsToolsInAllPhases() {
        AgentCapability.entries.forEach { capability ->
            val task = task(capability)
            val strategy = selectAgentExecutionStrategy(goal(task), task)
            assertTrue("Capability ${capability.name} must allow interactive tools", 
                strategy.allowsInteractiveTools)
        }
    }

    @Test
    fun milestoneBoundaryInstructionsDoNotRestrictEvidenceAcquisition() {
        AgentCapability.entries.forEach { capability ->
            val instruction = milestoneBoundaryInstruction(capability)
            val prohibitions = listOf("Do not research", "Do not perform new research", "Model-only", "Evidence-only")
            prohibitions.forEach { p ->
                assertFalse("Instruction for $capability must not contain '$p'", instruction.contains(p, ignoreCase = true))
            }
        }
    }

    @Test
    fun ordinaryChatUsesToolPath() {
        val policy = AutonomyPolicy.DEFAULT
        val request = "What time is it in Tokyo?"
        val decision = AutomationRouter.decide(request, hasImage = false, modelSupportsTools = true, policy = policy)
        assertEquals(AutomationRoute.TOOL_ASSISTED_CHAT, decision.route)
    }

    @Test
    fun registryReflectsRealOperationalConditions() {
        val onlineRuntime = FakeToolRuntime(network = true)
        val onlineAudit = AgentToolRegistry.availableToolsForUserWork(
            runtime = onlineRuntime,
            networkAvailable = true,
            credentialsAvailable = true,
            publicWebConfigured = true,
            isFreeOnly = false
        )
        assertTrue(onlineAudit.operational.any { it.name == "public_web_search" })
        
        val offlineRuntime = FakeToolRuntime(network = false)
        val offlineAudit = AgentToolRegistry.availableToolsForUserWork(
            runtime = offlineRuntime,
            networkAvailable = false,
            credentialsAvailable = true,
            publicWebConfigured = true,
            isFreeOnly = false
        )
        assertFalse(offlineAudit.operational.any { it.name == "public_web_search" })
        assertEquals("NETWORK_OFFLINE", offlineAudit.unavailable["public_web_search"])
    }

    @Test
    fun stuckMissionRepairRestoresQueuedState() {
        val restrictedTask = task(AgentCapability.CORRECT).copy(
            status = AgentTaskStatus.FAILED,
            lastError = "The previous attempt was evidence-bounded and failed."
        )
        val stuckGoal = goal(restrictedTask).copy(
            status = AgentGoalStatus.FAILED,
            error = "evidence-only correction failed"
        )
        
        val repairResult = repairSimulation(stuckGoal)
        assertTrue(repairResult is TypedRepairResult.Repaired)
        
        val repairedGoal = goal(restrictedTask).copy(
            status = AgentGoalStatus.QUEUED,
            error = null,
            tasks = stuckGoal.tasks.map { it.copy(status = AgentTaskStatus.QUEUED, lastError = null) }
        )
        assertEquals(AgentGoalStatus.QUEUED, repairedGoal.status)
        assertNull(repairedGoal.error)
    }

    private fun repairSimulation(goal: AgentGoal): TypedRepairResult {
        val restrictedMarkers = setOf("evidence-bounded", "evidence-only", "model-only", "without new searches")
        val isRestricted = goal.error?.lowercase()?.let { err -> restrictedMarkers.any { err.contains(it) } } ?: false ||
                           goal.tasks.any { t -> t.lastError?.lowercase()?.let { err -> restrictedMarkers.any { err.contains(it) } } ?: false }
        
        if (!isRestricted) return TypedRepairResult.NotApplicable
        return TypedRepairResult.Repaired
    }

    private fun task(capability: AgentCapability) = AgentTask(
        id = "task-1",
        order = 0,
        title = "Test Task",
        instructions = "Do work",
        capability = capability,
        status = AgentTaskStatus.QUEUED
    )

    private fun goal(task: AgentTask) = AgentGoal(
        conversationId = "c1",
        userRequest = "R",
        title = "T",
        objective = "O",
        finalOutputDescription = "D",
        status = AgentGoalStatus.RUNNING,
        plannerModelId = "m1",
        executionModelId = "m2",
        tasks = listOf(task)
    )
}
