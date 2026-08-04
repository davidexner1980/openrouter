package com.david.openassistant.agent

import com.david.openassistant.domain.tools.AutonomousToolRuntime
import com.david.openassistant.domain.tools.SafeToolDefinition
import org.json.JSONArray
import org.json.JSONObject

/**
 * Authoritative single source of truth for tool availability in OpenAssistant.
 * 
 * UNIVERSAL TOOL AVAILABILITY LAW:
 * Every configured, operational, safe tool must remain available regardless of 
 * mission phase or role.
 */
object AgentToolRegistry {
    
    data class ToolAvailabilityAudit(
        val totalConfigured: Int,
        val operational: List<SafeToolDefinition>,
        val unavailable: Map<String, String> // name -> reason
    )

    data class PayloadWithAudit(
        val tools: JSONArray,
        val audit: ToolAvailabilityAudit
    )

    fun availableToolsForUserWork(
        runtime: AutonomousToolRuntime?,
        networkAvailable: Boolean,
        credentialsAvailable: Boolean,
        isFreeOnly: Boolean = false,
        includeAdvancedResearchTools: Boolean = false
    ): ToolAvailabilityAudit {
        val operational = mutableListOf<SafeToolDefinition>()
        val unavailable = mutableMapOf<String, String>()
        
        if (runtime == null) {
            return ToolAvailabilityAudit(0, emptyList(), mapOf("all" to "Tool runtime not initialized"))
        }

        val allDefinitions = runtime.definitions()
        
        allDefinitions.forEach { tool ->
            // Check operational status
            val requiresNetwork = tool.name in setOf("openrouter:web_search", "openrouter:web_fetch", "sandbox_workbench")
            val requiresCredentials = tool.name in setOf("openrouter:web_search", "openrouter:web_fetch", "sandbox_workbench")
            
            when {
                requiresNetwork && !networkAvailable -> unavailable[tool.name] = "Network unavailable"
                requiresCredentials && !credentialsAvailable -> unavailable[tool.name] = "Credentials missing"
                isFreeOnly && tool.name in setOf("openrouter:web_search", "openrouter:web_fetch") -> unavailable[tool.name] = "Search tools unavailable for free-only models"
                else -> operational.add(tool)
            }
        }

        if (!isFreeOnly && networkAvailable && credentialsAvailable) {
            // These would be included in attachedToolsPayload
        } else {
            if (isFreeOnly) unavailable["openrouter:research_tools"] = "Search tools unavailable for free-only models"
            if (!networkAvailable) unavailable["openrouter:research_tools"] = "Network unavailable"
            if (!credentialsAvailable) unavailable["openrouter:research_tools"] = "Credentials missing"
        }
        
        return ToolAvailabilityAudit(
            totalConfigured = allDefinitions.size,
            operational = operational,
            unavailable = unavailable
        )
    }
    
    /**
     * Returns the OpenRouter-compatible tool definitions for the current operational state.
     */
    fun attachedToolsPayloadWithAudit(
        runtime: AutonomousToolRuntime?,
        networkAvailable: Boolean,
        credentialsAvailable: Boolean,
        isFreeOnly: Boolean = false,
        includeAdvancedResearchTools: Boolean = false
    ): PayloadWithAudit {
        val audit = availableToolsForUserWork(runtime, networkAvailable, credentialsAvailable, isFreeOnly, includeAdvancedResearchTools)
        val array = JSONArray()
        
        // Add basic research-specific "openrouter:" tools if operational
        if (!isFreeOnly && networkAvailable && credentialsAvailable) {
            val searchTool = searchToolDefinition()
            val fetchTool = fetchToolDefinition()
            array.put(searchTool)
            array.put(fetchTool)
            array.put(dateTimeToolDefinition())
            
            array.put(subagentToolDefinition(searchTool, fetchTool))
            
            if (includeAdvancedResearchTools) {
                array.put(advisorToolDefinition(searchTool, fetchTool))
                array.put(fusionToolDefinition())
            }
        }
        
        // Add runtime definitions
        audit.operational.forEach { definition ->
            // Avoid duplicate definitions if they are already in the "openrouter:" set
            if (definition.name !in setOf("openrouter:web_search", "openrouter:web_fetch", "openrouter:datetime")) {
                array.put(definition.toOpenRouterFunctionTool())
            }
        }
        
        return PayloadWithAudit(array, audit)
    }

    /** Legacy compatibility method */
    fun attachedToolsPayload(
        runtime: AutonomousToolRuntime?,
        networkAvailable: Boolean,
        credentialsAvailable: Boolean,
        isFreeOnly: Boolean = false,
        includeAdvancedResearchTools: Boolean = false
    ): JSONArray = attachedToolsPayloadWithAudit(
        runtime, networkAvailable, credentialsAvailable, isFreeOnly, includeAdvancedResearchTools
    ).tools

    fun hasOperationalTools(
        runtime: AutonomousToolRuntime?,
        networkAvailable: Boolean,
        credentialsAvailable: Boolean,
        isFreeOnly: Boolean = false,
        includeAdvancedResearchTools: Boolean = false
    ): Boolean {
        val audit = availableToolsForUserWork(runtime, networkAvailable, credentialsAvailable, isFreeOnly, includeAdvancedResearchTools)
        if (audit.operational.isNotEmpty()) return true
        
        // Also consider the "openrouter:" tools which are added in attachedToolsPayloadWithAudit
        return !isFreeOnly && networkAvailable && credentialsAvailable
    }


    private fun searchToolDefinition() = JSONObject()
        .put("type", "openrouter:web_search")
        .put("parameters", JSONObject()
            .put("engine", "auto")
            .put("max_results", 8)
            .put("search_context_size", "high"))

    private fun fetchToolDefinition() = JSONObject()
        .put("type", "openrouter:web_fetch")
        .put("parameters", JSONObject().put("engine", "auto"))

    private fun dateTimeToolDefinition() = JSONObject()
        .put("type", "openrouter:datetime")
        .put("parameters", JSONObject().put("timezone", java.util.TimeZone.getDefault().id))

    private fun subagentToolDefinition(searchTool: JSONObject, fetchTool: JSONObject) = JSONObject()
        .put("type", "openrouter:subagent")
        .put("parameters", JSONObject()
            .put("instructions", "You are an independent research worker. Use primary sources where available, fetch the important pages, record exact URLs and dates, search for counterevidence, and return concise findings plus unresolved uncertainty. Do not simply agree with the parent model.")
            .put("tools", JSONArray().put(searchTool).put(fetchTool))
            .put("reasoning", JSONObject().put("effort", "medium"))
            .put("temperature", 0.1))

    private fun advisorToolDefinition(searchTool: JSONObject, fetchTool: JSONObject) = JSONObject()
        .put("type", "openrouter:advisor")
        .put("parameters", JSONObject()
            .put("name", "evidence_auditor")
            .put("instructions", "Act as an adversarial evidence auditor. Identify missing primary sources, stale claims, contradictions, selection bias, weak causal reasoning, and decisive tests that could falsify the current conclusion. Be specific and do not approve work merely because it is polished.")
            .put("tools", JSONArray().put(searchTool).put(fetchTool))
            .put("forward_transcript", true)
            .put("reasoning", JSONObject().put("effort", "high"))
            .put("temperature", 0.0))

    private fun fusionToolDefinition() = JSONObject()
        .put("type", "openrouter:fusion")
        .put("parameters", JSONObject()
            .put("reasoning", JSONObject().put("effort", "high"))
            .put("temperature", 0.1))
}
