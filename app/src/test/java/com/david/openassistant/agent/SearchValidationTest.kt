package com.david.openassistant.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchValidationTest {

    @Test
    fun validatesSourceReadWithContentAndHttpCode() {
        val validResult = validateSourceRead(
            url = "https://www.usgs.gov/core-science-systems/ngp/board-on-geographic-names",
            httpCode = 200,
            content = "Denali summit official geodetic record elevation 20310 feet in Alaska and North America. Additional official topographic details provided.",
            targetEntities = listOf("Denali"),
        )

        assertTrue(validResult.isValid)
        assertTrue(validResult.authorityScore > 50)
    }

    @Test
    fun rejectsSemantic404Page() {
        val result = validateSourceRead(
            url = "https://ngs.noaa.gov/missing-page",
            httpCode = 200,
            content = "404 Page Not Found - The requested geodetic resource could not be found.",
        )

        assertFalse(result.isValid)
        assertEquals(SourceReadRejectionReason.SEMANTIC_404, result.rejectionReason)
    }

    @Test
    fun rejectsContentTooShortHtml() {
        val result = validateSourceRead(
            url = "https://example.com/short",
            httpCode = 200,
            content = "Short text.",
        )

        assertFalse(result.isValid)
        assertEquals(SourceReadRejectionReason.CONTENT_TOO_SHORT, result.rejectionReason)
    }

    @Test
    fun acceptsConciseJsonContent() {
        val result = validateSourceRead(
            url = "https://api.usgs.gov/elevation",
            httpCode = 200,
            content = """{"elevation": 20310, "unit": "feet", "name": "Denali"}""",
            contentType = "application/json",
            targetEntities = listOf("Denali"),
        )

        assertTrue(result.isValid)
    }

    @Test
    fun rejectsCloudflareChallenge() {
        val result = validateSourceRead(
            url = "https://example.com/blocked",
            httpCode = 403,
            content = "<html><body>Please verify you are human to access this site. Cloudflare challenge-platform.</body></html>",
        )

        assertFalse(result.isValid)
        assertEquals(SourceReadRejectionReason.ACCESS_CHALLENGE, result.rejectionReason)
    }

    @Test
    fun rejectsChallengeByHeader() {
        val result = validateSourceRead(
            url = "https://example.com/blocked",
            httpCode = 200,
            content = "Normal looking body",
            headers = mapOf("cf-mitigated" to "challenge")
        )

        assertFalse(result.isValid)
        assertEquals(SourceReadRejectionReason.ACCESS_CHALLENGE, result.rejectionReason)
    }
}
