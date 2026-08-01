package com.david.openassistant

import com.david.openassistant.domain.tools.ToolValidationException
import com.david.openassistant.domain.tools.parseToolArguments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ToolJsonTest {
    @Test
    fun emptyArrayMeansNoToolArguments() {
        assertEquals(0, parseToolArguments("[]").length())
    }

    @Test
    fun singleObjectArrayIsUnwrapped() {
        assertEquals("value", parseToolArguments("[{\"key\":\"value\"}]").getString("key"))
    }

    @Test
    fun multiItemArrayIsRejectedWithoutObjectCastException() {
        val error = assertThrows(ToolValidationException::class.java) {
            parseToolArguments("[{\"first\":1},{\"second\":2}]")
        }
        assertEquals(
            "Local tool arguments must be one JSON object; a multi-item or non-object array cannot be executed.",
            error.message,
        )
    }
}
