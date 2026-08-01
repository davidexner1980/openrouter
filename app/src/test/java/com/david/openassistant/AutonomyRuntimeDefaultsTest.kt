package com.david.openassistant

import com.david.openassistant.agent.AgentCapability
import com.david.openassistant.agent.AgentCapabilityRegistry
import com.david.openassistant.agent.AutonomyPolicy
import com.david.openassistant.agent.CapabilityApprovalPolicy
import com.david.openassistant.domain.tools.AdvancedToolCatalog
import com.david.openassistant.domain.tools.HostedSandboxToolCatalog
import com.david.openassistant.domain.tools.RuntimeDiagnosticToolCatalog
import com.david.openassistant.domain.tools.PublicWebToolCatalog
import com.david.openassistant.domain.tools.SafeToolCatalog
import com.david.openassistant.domain.tools.WorkspaceToolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomyRuntimeDefaultsTest {
    @Test
    fun autopilotResearchAndToolFoundryAreDefaultOn() {
        val policy = AutonomyPolicy.DEFAULT

        assertTrue(policy.autopilotEnabled)
        assertTrue(policy.deepResearchByDefault)
        assertTrue(policy.autoExecuteLocalTools)
        assertTrue(policy.toolFoundryEnabled)
        assertTrue(policy.independentResearchAgents >= 3)
        assertTrue(policy.minimumResearchPasses >= 4)
        assertTrue(policy.minimumResearchSources >= 8)
        assertTrue(policy.minimumNovelSourcesPerResearchPass >= 1)
    }

    @Test
    fun everyRegisteredRuntimeCapabilityIsAutomatic() {
        assertEquals(AgentCapability.entries.toSet(), AgentCapabilityRegistry.definitions.keys)
        assertTrue(
            AgentCapabilityRegistry.definitions.values.all {
                it.approvalPolicy == CapabilityApprovalPolicy.AUTOMATIC
            },
        )
    }

    @Test
    fun autonomyPolicyContainsQualityRequirementsButNoExecutionBudget() {
        val fields = AutonomyPolicy::class.java.declaredFields.map { it.name.lowercase() }

        assertTrue(fields.none { name ->
            name.contains("budget") ||
                name.contains("maxtoken") ||
                name.contains("maxcost") ||
                name.contains("maxround") ||
                name.contains("maxretr") ||
                name.contains("maxstep") ||
                name.contains("duration")
        })
    }

    @Test
    fun builtInToolCatalogHasFortyNineUniqueTools() {
        val definitions = SafeToolCatalog.definitions +
            AdvancedToolCatalog.definitions +
            WorkspaceToolCatalog.definitions +
            RuntimeDiagnosticToolCatalog.definitions +
            PublicWebToolCatalog.definitions +
            HostedSandboxToolCatalog.definitions

        assertEquals(49, definitions.size)
        assertEquals(definitions.size, definitions.map { it.name }.distinct().size)
    }
}
