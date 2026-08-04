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
    
    data class ToolOperationalRequirement(
        val toolName: String,
        val configured: Boolean,
        val operational: Boolean,
        val unavailabilityReason: String?,
        val requiresNetwork: Boolean,
        val requiresCredentials: Boolean,
        val requiresPublicWebEndpoint: Boolean,
        val isSafe: Boolean,
        val requiresExternalConfirmation: Boolean,
        val owner: String // "local" or "provider"
    )

    data class ToolAvailabilityAudit(
        val totalConfigured: Int,
        val operational: List<SafeToolDefinition>,
        val unavailable: Map<String, String>, // name -> reason
        val requirements: List<ToolOperationalRequirement>
    )

    data class PayloadWithAudit(
        val tools: JSONArray,
        val audit: ToolAvailabilityAudit
    )

    fun availableToolsForUserWork(
        runtime: AutonomousToolRuntime?,
        networkAvailable: Boolean,
        credentialsAvailable: Boolean,
        publicWebConfigured: Boolean = true,
        isFreeOnly: Boolean = false,
        includeAdvancedResearchTools: Boolean = false
    ): ToolAvailabilityAudit {
        val operational = mutableListOf<SafeToolDefinition>()
        val unavailable = mutableMapOf<String, String>()
        val requirements = mutableListOf<ToolOperationalRequirement>()
        
        if (runtime == null) {
            return ToolAvailabilityAudit(0, emptyList(), mapOf("all" to "Tool runtime not initialized"), emptyList())
        }

        val allDefinitions = runtime.definitions()
        
        allDefinitions.forEach { tool ->
            val req = determineRequirements(tool.name, publicWebConfigured)
            
            val (isOperational, reason) = when {
                req.requiresNetwork && !networkAvailable -> false to "Network unavailable"
                req.requiresCredentials && !credentialsAvailable -> false to "Credentials missing"
                req.requiresPublicWebEndpoint && !publicWebConfigured -> false to "Public web route unconfigured"
                isFreeOnly && tool.name in setOf("openrouter:web_search", "openrouter:web_fetch", "openrouter:subagent", "openrouter:advisor", "openrouter:fusion") -> false to "Provider-hosted tool unsupported on FREE route"
                else -> true to null
            }

            requirements.add(req.copy(operational = isOperational, unavailabilityReason = reason))
            
            if (isOperational) {
                operational.add(tool)
            } else {
                unavailable[tool.name] = reason ?: "Unknown"
            }
        }

        // Handle provider-hosted tools explicitly if not in runtime definitions
        val providerTools = listOf(
            "openrouter:web_search", "openrouter:web_fetch", "openrouter:datetime",
            "openrouter:subagent"
        ) + if (includeAdvancedResearchTools) listOf("openrouter:advisor", "openrouter:fusion") else emptyList()

        providerTools.forEach { name ->
            if (requirements.none { it.toolName == name }) {
                val req = determineRequirements(name, publicWebConfigured)
                val (isOperational, reason) = when {
                    req.requiresNetwork && !networkAvailable -> false to "Network unavailable"
                    req.requiresCredentials && !credentialsAvailable -> false to "Credentials missing"
                    isFreeOnly -> false to "Provider-hosted tool unsupported on FREE route"
                    else -> true to null
                }
                requirements.add(req.copy(operational = isOperational, unavailabilityReason = reason))
                if (!isOperational) {
                    unavailable[name] = reason ?: "Unknown"
                }
            }
        }
        
        return ToolAvailabilityAudit(
            totalConfigured = requirements.size,
            operational = operational,
            unavailable = unavailable,
            requirements = requirements
        )
    }

    private fun determineRequirements(name: String, publicWebConfigured: Boolean): ToolOperationalRequirement {
        val isProvider = name.startsWith("openrouter:")
        val isWeb = name in setOf("public_web_search", "public_web_fetch", "openrouter:web_search", "openrouter:web_fetch")
        val isSandbox = name == "sandbox_workbench"
        
        val requiresNetwork = isWeb || isSandbox || name == "openrouter:subagent" || name == "openrouter:advisor" || name == "openrouter:fusion"
        val requiresCredentials = isProvider || isSandbox
        val requiresPublicWeb = name in setOf("public_web_search", "public_web_fetch")
        
        return ToolOperationalRequirement(
            toolName = name,
            configured = if (requiresPublicWeb) publicWebConfigured else true,
            operational = true, // Default, will be adjusted
            unavailabilityReason = null,
            requiresNetwork = requiresNetwork,
            requiresCredentials = requiresCredentials,
            requiresPublicWebEndpoint = requiresPublicWeb,
            isSafe = !requiresExternalConfirmation(name),
            requiresExternalConfirmation = requiresExternalConfirmation(name),
            owner = if (isProvider) "provider" else "local"
        )
    }

    private fun requiresExternalConfirmation(name: String): Boolean {
        // Protect destructive or consequential operations
        return name in setOf(
            "workspace_delete_file",
            "device_send_message",
            "device_make_call",
            "account_modify",
            "repository_releasing"
        )
    }
    
    /**
     * Returns the OpenRouter-compatible tool definitions for the current operational state.
     */
    fun attachedToolsPayloadWithAudit(
        runtime: AutonomousToolRuntime?,
        networkAvailable: Boolean,
        credentialsAvailable: Boolean,
        publicWebConfigured: Boolean = true,
        isFreeOnly: Boolean = false,
        includeAdvancedResearchTools: Boolean = false
    ): PayloadWithAudit {
        val audit = availableToolsForUserWork(runtime, networkAvailable, credentialsAvailable, publicWebConfigured, isFreeOnly, includeAdvancedResearchTools)
        val array = JSONArray()
        
        // Add basic research-specific "openrouter:" tools if operational
        val providerTools = audit.requirements.filter { it.owner == "provider" && it.operational }
        
        providerTools.forEach { req ->
            val toolJson = when (req.toolName) {
                "openrouter:web_search" -> searchToolDefinition()
                "openrouter:web_fetch" -> fetchToolDefinition()
                "openrouter:datetime" -> dateTimeToolDefinition()
                "openrouter:subagent" -> subagentToolDefinition()
                "openrouter:advisor" -> advisorToolDefinition()
                "openrouter:fusion" -> fusionToolDefinition()
                else -> null
            }
            toolJson?.let { array.put(it) }
        }
        
        // Add local operational tools
        audit.operational.forEach { definition ->
            // Avoid duplicate definitions if they are already in the "openrouter:" set
            if (!definition.name.startsWith("openrouter:")) {
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
        runtime, networkAvailable, credentialsAvailable, true, isFreeOnly, includeAdvancedResearchTools
    ).tools

    fun hasOperationalTools(
        runtime: AutonomousToolRuntime?,
        networkAvailable: Boolean,
        credentialsAvailable: Boolean,
        publicWebConfigured: Boolean = true,
        isFreeOnly: Boolean = false,
        includeAdvancedResearchTools: Boolean = false
    ): Boolean {
        val audit = availableToolsForUserWork(runtime, networkAvailable, credentialsAvailable, publicWebConfigured, isFreeOnly, includeAdvancedResearchTools)
        return audit.operational.isNotEmpty() || audit.requirements.any { it.owner == "provider" && it.operational }
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

    private fun subagentToolDefinition(): JSONObject {
        val searchTool = searchToolDefinition()
        val fetchTool = fetchToolDefinition()
        return JSONObject()
            .put("type", "openrouter:subagent")
            .put("parameters", JSONObject()
                .put("instructions", "You are an independent research worker. Use primary sources where available, fetch the important pages, record exact URLs and dates, search for counterevidence, and return concise findings plus unresolved uncertainty. Do not simply agree with the parent model.")
                .put("tools", JSONArray().put(searchTool).put(fetchTool))
                .put("reasoning", JSONObject().put("effort", "medium"))
                .put("temperature", 0.1))
    }

    private fun advisorToolDefinition(): JSONObject {
        val searchTool = searchToolDefinition()
        val fetchTool = fetchToolDefinition()
        return JSONObject()
            .put("type", "openrouter:advisor")
            .put("parameters", JSONObject()
                .put("name", "evidence_auditor")
                .put("instructions", "Act as an adversarial evidence auditor. Identify missing primary sources, stale claims, contradictions, selection bias, weak causal reasoning, and decisive tests that could falsify the current conclusion. Be specific and do not approve work merely because it is polished.")
                .put("tools", JSONArray().put(searchTool).put(fetchTool))
                .put("forward_transcript", true)
                .put("reasoning", JSONObject().put("effort", "high"))
                .put("temperature", 0.0))
    }

    private fun fusionToolDefinition() = JSONObject()
        .put("type", "openrouter:fusion")
        .put("parameters", JSONObject()
            .put("reasoning", JSONObject().put("effort", "high"))
            .put("temperature", 0.1))
}
