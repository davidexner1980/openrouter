package com.david.openassistant.handoff

import org.json.JSONArray
import org.json.JSONObject

object DeterministicJson {
    fun stringify(json: JSONObject): String {
        val keys = json.keys().asSequence().toList().sorted()
        val sb = StringBuilder("{")
        keys.forEachIndexed { index, key ->
            if (index > 0) sb.append(",")
            sb.append("\"").append(key).append("\":")
            val value = json.get(key)
            sb.append(stringifyValue(value))
        }
        sb.append("}")
        return sb.toString()
    }

    private fun stringifyArray(array: JSONArray): String {
        val sb = StringBuilder("[")
        for (i in 0 until array.length()) {
            if (i > 0) sb.append(",")
            sb.append(stringifyValue(array.get(i)))
        }
        sb.append("]")
        return sb.toString()
    }

    private fun stringifyValue(value: Any?): String = when (value) {
        is JSONObject -> stringify(value)
        is JSONArray -> stringifyArray(value)
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> JSONObject.valueToString(value)
    }
}
