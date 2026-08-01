package com.david.openassistant

import com.david.openassistant.data.security.maskCredentialLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CredentialMaskTest {
    @Test
    fun onlyLastFourSafeCharactersRemain() {
        val masked = maskCredentialLabel("sk-or-v1-112...7a7")
        assertEquals("••••••••27a7", masked)
        assertFalse(masked.contains("sk-or"))
    }

    @Test
    fun missingLabelUsesGenericText() {
        assertEquals("Stored encrypted credential", maskCredentialLabel(null))
    }
}
