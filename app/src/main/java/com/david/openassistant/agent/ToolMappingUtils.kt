package com.david.openassistant.agent

import com.david.openassistant.domain.tools.SafeToolDefinition
import org.json.JSONArray
import org.json.JSONObject

fun SafeToolDefinition.toOpenRouterFunctionTool(): JSONObject {
    val properties = JSONObject()
    val required = JSONArray()
    parameters.forEach { parameter ->
        properties.put(
            parameter.name,
            JSONObject()
                .put("type", parameter.type)
                .put("description", parameter.description),
        )
        if (parameter.required) required.put(parameter.name)
    }
    return JSONObject()
        .put("type", "function")
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put(
                    "parameters",
                    JSONObject()
                        .put("type", "object")
                        .put("properties", properties)
                        .put("required", required)
                        .put("additionalProperties", false),
                ),
        )
}
