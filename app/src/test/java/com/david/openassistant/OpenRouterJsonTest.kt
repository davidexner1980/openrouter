package com.david.openassistant

import com.david.openassistant.data.openrouter.parseOpenRouterKeyInfo
import com.david.openassistant.data.openrouter.requireOpenRouterObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterJsonTest {
    @Test
    fun singleObjectEnvelopeIsUnwrapped() {
        assertEquals("response", requireOpenRouterObject("[{\"id\":\"response\"}]", "test").getString("id"))
    }

    @Test
    fun emptyKeyDataArrayUsesConservativeMetadataFallback() {
        val info = parseOpenRouterKeyInfo("{\"data\":[]}")

        assertEquals("Connected key (metadata unavailable)", info.label)
        assertTrue(info.isFreeTier)
        assertNull(info.limitRemaining)
    }

    @Test
    fun oneObjectKeyDataArrayIsUnwrapped() {
        val info = parseOpenRouterKeyInfo("{\"data\":[{\"label\":\"array-key\",\"is_free_tier\":true}]}")

        assertEquals("array-key", info.label)
        assertTrue(info.isFreeTier)
    }
}
