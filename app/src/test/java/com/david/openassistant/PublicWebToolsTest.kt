package com.david.openassistant

import com.david.openassistant.domain.tools.readBoundedPublicResponse
import com.david.openassistant.domain.tools.isPublicPdfResponse
import com.david.openassistant.domain.tools.isPublicSearchThrottleResponse
import com.david.openassistant.domain.tools.isSubstantialPublicFetchText
import com.david.openassistant.domain.tools.extractSiteConstraints
import com.david.openassistant.domain.tools.rebuildValidatedPublicHttpsUrl
import com.david.openassistant.domain.tools.searchResultMatchesSiteConstraints
import com.david.openassistant.domain.tools.parseBingRssCandidates
import com.david.openassistant.data.network.normalizeSearxngBaseUrl
import java.net.URI
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicWebToolsTest {
    @Test
    fun boundedReaderReturnsACompleteSmallResponse() {
        val result = readBoundedPublicResponse(StringReader("complete"), maximumChars = 20)

        assertEquals("complete", result.text)
        assertFalse(result.truncated)
    }

    @Test
    fun boundedReaderStopsAtTheLimitAndMarksRemainingContent() {
        val result = readBoundedPublicResponse(StringReader("abcdefgh"), maximumChars = 5)

        assertEquals("abcde", result.text)
        assertTrue(result.truncated)
    }

    @Test
    fun boundedReaderDoesNotMarkAnExactLengthResponseAsTruncated() {
        val result = readBoundedPublicResponse(StringReader("abcde"), maximumChars = 5)

        assertEquals("abcde", result.text)
        assertFalse(result.truncated)
    }

    @Test
    fun searchThrottleRecognizesHttp202AndSpecificChallengeMarkup() {
        assertTrue(isPublicSearchThrottleResponse(202, ""))
        assertTrue(isPublicSearchThrottleResponse(200, "<form id='challenge-form'>verify you are human</form>"))
        assertFalse(isPublicSearchThrottleResponse(200, "A normal page about automated manufacturing requests."))
    }

    @Test
    fun fullSourceReadRejectsTinyAndChallengePages() {
        assertFalse(isSubstantialPublicFetchText("Access denied"))
        assertFalse(
            isSubstantialPublicFetchText(
                "Just a moment. Enable JavaScript and cookies to continue. ".repeat(20),
            ),
        )
        assertFalse(isSubstantialPublicFetchText("A short but otherwise readable page.".repeat(5)))
    }

    @Test
    fun fullSourceReadAcceptsSubstantivePageEvenIfItContainsChallengeMarkers() {
        val substantiveContent = (
            "The three-piece takedown recurve bow is a staple of modern hunting. " +
            "It allows for easy transport and limb replacement. " +
            "Many hunters prefer the stability of a 62-inch bow. " +
            "This particular model features a riser made of cocobolo and osage orange. " +
            "The limbs are carbon foam core for speed and consistency. "
        ).repeat(10)
        
        // This is a common pattern on Cloudflare sites - a legitimate page with a footer mentioning performance/security.
        val pageWithCloudflareFooter = substantiveContent + " Performance & security by Cloudflare"
        
        assertTrue("Substantive page with Cloudflare footer should be accepted", 
            isSubstantialPublicFetchText(pageWithCloudflareFooter))
    }

    @Test
    fun fullSourceReadAcceptsSubstantiveTextAndStructuredData() {
        val article = (
            "The source documents its method, dates, measurements, limitations, provenance, and results. " +
                "It distinguishes verified observations from interpretation and records enough detail for review. "
            ).repeat(5)
        val record = "{\"name\":\"verified item\",\"value\":42},"
        val json = "{\"records\":[${record.repeat(20)}{}]}"

        assertTrue(isSubstantialPublicFetchText(article))
        assertTrue(isSubstantialPublicFetchText(json))
    }

    @Test
    fun pdfDetectionUsesContentTypeOrUrlPath() {
        assertTrue(isPublicPdfResponse("application/pdf", "https://example.com/report"))
        assertTrue(isPublicPdfResponse("application/octet-stream", "https://example.com/report.PDF?download=1"))
        assertFalse(isPublicPdfResponse("text/html", "https://example.com/report"))
    }

    @Test
    fun siteQualifiedQueriesRejectUnrelatedSearchResults() {
        val constraints = extractSiteConstraints(
            "packed dimensions site:beararchery.com OR site:3riversarchery.com",
        )

        assertEquals(setOf("beararchery.com", "3riversarchery.com"), constraints)
        assertTrue(
            searchResultMatchesSiteConstraints(
                "https://www.beararchery.com/products/takedown",
                constraints,
            ),
        )
        assertTrue(
            searchResultMatchesSiteConstraints(
                "https://shop.3riversarchery.com/bow",
                constraints,
            ),
        )
        assertFalse(
            searchResultMatchesSiteConstraints(
                "https://example.com/unrelated-result",
                constraints,
            ),
        )
        assertFalse(
            searchResultMatchesSiteConstraints(
                "https://beararchery.com.evil.example/fake",
                constraints,
            ),
        )
    }

    @Test
    fun validatedSearchUrlPreservesExistingPercentEncoding() {
        val rebuilt = rebuildValidatedPublicHttpsUrl(
            URI("https://html.duckduckgo.com/html/?q=site%3Abeararchery.com+takedown"),
            "html.duckduckgo.com",
        )

        assertTrue(rebuilt.contains("site%3Abeararchery.com"))
        assertFalse(rebuilt.contains("%253A"))
    }

    @Test
    fun searxngEndpointRequiresAPublicHttpsInstanceRoot() {
        assertEquals(
            "https://search.example.org/searx",
            normalizeSearxngBaseUrl("https://search.example.org/searx/"),
        )
        assertEquals(null, normalizeSearxngBaseUrl("   "))
        assertTrue(runCatching { normalizeSearxngBaseUrl("http://search.example.org") }.isFailure)
        assertTrue(runCatching { normalizeSearxngBaseUrl("https://localhost:8080") }.isFailure)
        assertTrue(runCatching { normalizeSearxngBaseUrl("https://[::1]:8080") }.isFailure)
        assertTrue(runCatching { normalizeSearxngBaseUrl("https://192.168.1.2") }.isFailure)
        assertTrue(runCatching { normalizeSearxngBaseUrl("https://user:pass@example.org") }.isFailure)
    }

    @Test
    fun bingRssDiscoveryParsesIndependentCandidates() {
        val candidates = parseBingRssCandidates(
            """
            <rss><channel>
              <item><title>First &amp; best</title><link>https://one.example/page</link><description><![CDATA[Detailed <b>first</b> result.]]></description></item>
              <item><title>Second</title><link>https://two.example/report</link><description>Independent result.</description></item>
            </channel></rss>
            """.trimIndent(),
        )

        assertEquals(2, candidates.size)
        assertEquals("First & best", candidates.first().title)
        assertEquals("https://one.example/page", candidates.first().url)
        assertEquals("Detailed first result.", candidates.first().excerpt)
    }

    @Test
    fun googleGbvParserFindsEncodedUrlsAndCleanTitles() {
        val html = """
            <a href="/url?q=https://example.com/page&amp;sa=U&amp;ved=123"><h3>Example <b>Title</b></h3></a>
            <a href="/url?q=https://another.com/report?id=42&amp;source=web"><h3>Another Result</h3></a>
        """.trimIndent()
        
        val sources = com.david.openassistant.domain.tools.parseGoogleGbvSources(html, emptySet())
        
        assertEquals(2, sources.length())
        assertEquals("Example Title", sources.getJSONObject(0).getString("title"))
        assertEquals("https://example.com/page", sources.getJSONObject(0).getString("url"))
        assertEquals("https://another.com/report?id=42", sources.getJSONObject(1).getString("url"))
    }

    @Test
    fun googleUrlDecoderHandlesDirectAndProxiedLinks() {
        assertEquals(
            "https://example.com/direct",
            com.david.openassistant.domain.tools.decodeGoogleUrl("https://example.com/direct")
        )
        assertEquals(
            "https://example.com/proxied",
            com.david.openassistant.domain.tools.decodeGoogleUrl("/url?q=https://example.com/proxied&sa=U")
        )
    }

    @Test
    fun searchUrlAppliesPaginationCorrect() {
        val config = com.david.openassistant.data.network.ResearchWebNetworkConfig()
        
        val googleUrlP1 = com.david.openassistant.domain.tools.searchUrl(
            com.david.openassistant.domain.tools.PublicSearchProvider.GOOGLE_GBV,
            "test",
            config,
            page = 1
        )
        assertTrue(googleUrlP1!!.contains("start=0"))
        
        val googleUrlP2 = com.david.openassistant.domain.tools.searchUrl(
            com.david.openassistant.domain.tools.PublicSearchProvider.GOOGLE_GBV,
            "test",
            config,
            page = 2
        )
        assertTrue(googleUrlP2!!.contains("start=10"))
    }
}
