package com.david.openassistant.data.network

import android.content.Context
import androidx.core.content.edit
import java.net.IDN
import java.net.URI

data class ResearchWebNetworkConfig(
    val searxngBaseUrl: String? = null,
)

/** Normalizes a user-owned SearXNG instance root without making a network call. */
internal fun normalizeSearxngBaseUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isBlank()) return null
    val uri = runCatching { URI(trimmed) }
        .getOrElse { throw IllegalArgumentException("The SearXNG URL is invalid.") }
    require(uri.scheme.equals("https", ignoreCase = true)) {
        "The SearXNG instance must use HTTPS."
    }
    require(uri.userInfo == null) { "Credentials are not allowed in the SearXNG URL." }
    require(uri.rawQuery == null && uri.rawFragment == null) {
        "Enter the SearXNG instance root without a query or fragment."
    }
    val host = uri.host?.trim()?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("The SearXNG URL has no valid host.")
    val asciiHost = runCatching { IDN.toASCII(host) }
        .getOrElse { throw IllegalArgumentException("The SearXNG host is invalid.") }
    require(!isLocalSearchHost(asciiHost)) {
        "The SearXNG instance must be a public host."
    }
    require(uri.port in -1..65_535 && uri.port != 0) { "The SearXNG port is invalid." }
    val path = uri.rawPath.orEmpty().ifBlank { "/" }.trimEnd('/').ifBlank { "/" }
    return URI("https", null, asciiHost, uri.port, path, null, null)
        .toASCIIString()
        .trimEnd('/')
}

private fun isLocalSearchHost(host: String): Boolean {
    val normalized = host.lowercase().trim('[', ']')
    if (
        normalized == "localhost" ||
        normalized.endsWith(".localhost") ||
        normalized.endsWith(".local") ||
        ':' in normalized
    ) {
        return true
    }
    val octets = normalized.split('.').mapNotNull(String::toIntOrNull)
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    return octets[0] == 10 ||
        octets[0] == 127 ||
        (octets[0] == 169 && octets[1] == 254) ||
        (octets[0] == 172 && octets[1] in 16..31) ||
        (octets[0] == 192 && octets[1] == 168) ||
        octets[0] == 0
}

class ResearchWebSettings(context: Context) {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    init {
        val legacyPreferences = applicationContext.getSharedPreferences(
            LEGACY_PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
        if (!preferences.contains(KEY_SEARXNG_BASE_URL) && legacyPreferences.contains(KEY_SEARXNG_BASE_URL)) {
            preferences.edit {
                putString(KEY_SEARXNG_BASE_URL, legacyPreferences.getString(KEY_SEARXNG_BASE_URL, null))
            }
        }
        applicationContext.deleteSharedPreferences(LEGACY_PREFERENCES_NAME)
    }

    fun load(): ResearchWebNetworkConfig {
        val endpoint = runCatching {
            normalizeSearxngBaseUrl(preferences.getString(KEY_SEARXNG_BASE_URL, null).orEmpty())
        }.getOrNull()
        return ResearchWebNetworkConfig(searxngBaseUrl = endpoint)
    }

    fun save(searxngBaseUrl: String): ResearchWebNetworkConfig {
        val normalized = ResearchWebNetworkConfig(
            searxngBaseUrl = normalizeSearxngBaseUrl(searxngBaseUrl),
        )
        preferences.edit {
            if (normalized.searxngBaseUrl == null) remove(KEY_SEARXNG_BASE_URL)
            else putString(KEY_SEARXNG_BASE_URL, normalized.searxngBaseUrl)
        }
        return normalized
    }

    private companion object {
        const val PREFERENCES_NAME = "research_search_settings"
        const val LEGACY_PREFERENCES_NAME = "research_web_settings"
        const val KEY_SEARXNG_BASE_URL = "searxng_base_url"
    }
}
