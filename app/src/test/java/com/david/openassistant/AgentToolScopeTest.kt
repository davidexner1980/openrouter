package com.david.openassistant

import com.david.openassistant.agent.AgentAcceptanceCriterion
import com.david.openassistant.agent.AgentCapability
import com.david.openassistant.agent.AgentTask
import com.david.openassistant.agent.MAX_FOCUSED_TOOL_DEFINITIONS
import com.david.openassistant.agent.executionToolSelection
import com.david.openassistant.agent.focusedToolSelection
import com.david.openassistant.domain.tools.AdvancedToolCatalog
import com.david.openassistant.domain.tools.HostedSandboxToolCatalog
import com.david.openassistant.domain.tools.PublicWebToolCatalog
import com.david.openassistant.domain.tools.RuntimeDiagnosticToolCatalog
import com.david.openassistant.domain.tools.SafeToolCatalog
import com.david.openassistant.domain.tools.WorkspaceToolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolScopeTest {
    @Test
    fun reasoningMilestoneCannotSeeGlobalWorkspaceOrGeneralToolCatalog() {
        val selection = executionToolSelection(
            task = task(
                capability = AgentCapability.REASON,
                title = "Define the decision framework",
                instructions = "Identify criteria and evidence needs before research.",
            ),
            definitions = allDefinitions,
            focusedRecovery = false,
        )

        assertTrue(selection.definitions.isEmpty())
        assertEquals(null, selection.preferredToolName)
    }

    @Test
    fun freshToolMilestoneReceivesEveryBuiltInWithoutTopicSpecificNarrowing() {
        val selection = executionToolSelection(
            task = task(
                capability = AgentCapability.TOOL_USE,
                title = "Investigate an unfamiliar technical dataset",
                instructions = "Choose whatever deterministic, workspace, web, recipe, diagnostic, or hosted tool the evidence requires.",
            ),
            definitions = allDefinitions,
            focusedRecovery = false,
        )

        assertEquals(49, selection.definitions.size)
        assertEquals(allDefinitions.map { it.name }, selection.definitions.map { it.name })
        assertEquals(null, selection.preferredToolName)
    }

    @Test
    fun narrowingOccursOnlyDuringFocusedRecovery() {
        val selection = executionToolSelection(
            task = task(
                capability = AgentCapability.TOOL_USE,
                title = "Calculate a verified change",
                instructions = "Calculate percentage change from measured values.",
            ),
            definitions = allDefinitions,
            focusedRecovery = true,
        )

        assertTrue(selection.definitions.size <= MAX_FOCUSED_TOOL_DEFINITIONS)
        assertEquals("calculate", selection.preferredToolName)
    }

    @Test
    fun quantitativeEfficiencyMilestoneSelectsCalculatorWithoutSendingTheWholeRegistry() {
        val task = task(
            capability = AgentCapability.TOOL_USE,
            title = "Measured Throughput Efficiency",
            instructions = "Compare measured throughput and energy consumption, then calculate a transparent efficiency ratio and percentage change.",
        ).copy(
            acceptanceCriteria = listOf(
                AgentAcceptanceCriterion(
                    id = "efficiency_score",
                    description = "Each measured configuration has a throughput-to-energy efficiency score.",
                ),
            ),
        )

        val selection = focusedToolSelection(task, allDefinitions)
        val names = selection.definitions.map { it.name }

        assertEquals("calculate", selection.preferredToolName)
        assertTrue(names.size <= MAX_FOCUSED_TOOL_DEFINITIONS)
        assertTrue("calculate" in names)
        assertTrue("percentage_change" in names)
        assertTrue("statistics" in names)
        assertFalse("workspace_move_to_trash" in names)
        assertFalse("generate_uuid" in names)
    }

    @Test
    fun ambiguousToolMilestoneKeepsASmallSafeSetWithoutForcingAnIrrelevantCall() {
        val selection = focusedToolSelection(
            task(AgentCapability.TOOL_USE, "Complete milestone", "Complete the milestone carefully."),
            allDefinitions,
        )

        assertEquals(null, selection.preferredToolName)
        assertTrue(selection.definitions.isNotEmpty())
        assertTrue(selection.definitions.size <= MAX_FOCUSED_TOOL_DEFINITIONS)
    }

    @Test
    fun toolFoundryRecoveryCanForceRecipeCreationAndRetainsRelevantBuiltins() {
        val selection = focusedToolSelection(
            task(
                capability = AgentCapability.TOOL_CREATE,
                title = "Create efficiency score recipe",
                instructions = "Create and test a reusable throughput-to-energy ratio calculation recipe.",
            ),
            allDefinitions,
        )
        val names = selection.definitions.map { it.name }

        assertEquals("create_tool_recipe", selection.preferredToolName)
        assertTrue("create_tool_recipe" in names)
        assertTrue("list_tool_recipes" in names)
        assertTrue("calculate" in names)
        assertTrue(names.size <= MAX_FOCUSED_TOOL_DEFINITIONS)
    }

    private fun task(
        capability: AgentCapability,
        title: String,
        instructions: String,
    ) = AgentTask(
        id = "task",
        order = 0,
        title = title,
        instructions = instructions,
        capability = capability,
    )

    private val allDefinitions = listOf(
        SafeToolCatalog.definitions,
        AdvancedToolCatalog.definitions,
        WorkspaceToolCatalog.definitions,
        RuntimeDiagnosticToolCatalog.definitions,
        PublicWebToolCatalog.definitions,
        HostedSandboxToolCatalog.definitions,
    ).flatten()
}
