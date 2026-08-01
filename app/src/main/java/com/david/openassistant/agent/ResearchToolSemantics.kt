package com.david.openassistant.agent

internal const val LOCAL_WEB_SEARCH_TOOL = "public_web_search"
internal const val LOCAL_WEB_FETCH_TOOL = "public_web_fetch"
internal const val PROVIDER_WEB_SEARCH_TOOL = "provider_web_search"
internal const val PROVIDER_WEB_FETCH_TOOL = "provider_web_fetch"
internal const val PROVIDER_WEB_EXTRACT_TOOL = "provider_web_extract"

internal val RESEARCH_SEARCH_TOOL_NAMES = setOf(
    LOCAL_WEB_SEARCH_TOOL,
    PROVIDER_WEB_SEARCH_TOOL,
)

internal val RESEARCH_SOURCE_READ_TOOL_NAMES = setOf(
    LOCAL_WEB_FETCH_TOOL,
    PROVIDER_WEB_FETCH_TOOL,
    PROVIDER_WEB_EXTRACT_TOOL,
)

internal val RESEARCH_AUDIT_TOOL_NAMES =
    RESEARCH_SEARCH_TOOL_NAMES + RESEARCH_SOURCE_READ_TOOL_NAMES

internal fun successfulResearchSearchCount(executions: Iterable<AgentToolExecution>): Int =
    executions.count { execution ->
        execution.succeeded && execution.toolName in RESEARCH_SEARCH_TOOL_NAMES
    }

internal data class ResearchReadAccounting(
    val localFullReads: Int,
    val providerFullReads: Int,
    val providerSubstantialExtracts: Int,
) {
    val fullReads: Int
        get() = localFullReads + providerFullReads

    /**
     * Provider search highlights are bounded, query-focused extracts rather
     * than whole pages. Two independent substantial extracts conservatively
     * equal one read unit; exact local/provider fetches each equal one unit.
     */
    val equivalentReadUnits: Int
        get() {
            val providerUnits = maxOf(
                providerFullReads.toDouble(),
                providerSubstantialExtracts.toDouble() / PROVIDER_EXTRACTS_PER_READ_UNIT,
            )
            return (localFullReads.toDouble() + providerUnits).toInt()
        }
}

internal fun successfulResearchReadAccounting(
    executions: Iterable<AgentToolExecution>,
): ResearchReadAccounting = ResearchReadAccounting(
    localFullReads = executions.count { execution ->
        execution.succeeded && execution.toolName == LOCAL_WEB_FETCH_TOOL
    },
    providerFullReads = executions.count { execution ->
        execution.succeeded && execution.toolName == PROVIDER_WEB_FETCH_TOOL
    },
    providerSubstantialExtracts = executions.count { execution ->
        execution.succeeded && execution.toolName == PROVIDER_WEB_EXTRACT_TOOL
    },
)

internal const val PROVIDER_EXTRACTS_PER_READ_UNIT = 2
