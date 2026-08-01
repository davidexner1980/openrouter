package com.david.openassistant.agent

/**
 * Buffers streaming text deltas and suppresses lines that contain internal
 * safety metadata markers.
 */
class StreamingSafetyFilter {
    private val lineBuffer = StringBuilder()
    private var lastWasSuppressed = false

    /**
     * Processes a chunk of text. Returns the filtered text that is safe for
     * the UI, or an empty string if the current content is still being buffered
     * or was suppressed.
     */
    fun filter(delta: String): String {
        if (delta.isEmpty()) return ""
        
        val output = StringBuilder()
        var remaining = delta
        
        while (remaining.isNotEmpty()) {
            val newlineIndex = remaining.indexOf('\n')
            if (newlineIndex == -1) {
                lineBuffer.append(remaining)
                break
            } else {
                val segment = remaining.substring(0, newlineIndex + 1)
                lineBuffer.append(segment)
                remaining = remaining.substring(newlineIndex + 1)
                
                val fullLine = lineBuffer.toString()
                
                if (SafetyClassifier.isInternalMetadata(fullLine)) {
                    lineBuffer.setLength(0)
                    lastWasSuppressed = true
                } else {
                    lineBuffer.setLength(0)
                    output.append(fullLine)
                    lastWasSuppressed = false
                }
            }
        }
        
        return output.toString()
    }

    /**
     * Drains the final buffered content, applying filtering if it's a complete line.
     */
    fun finish(): String {
        val remaining = lineBuffer.toString()
        lineBuffer.setLength(0)
        return if (remaining.isNotEmpty() && !SafetyClassifier.isInternalMetadata(remaining)) {
            remaining
        } else {
            ""
        }
    }
}
