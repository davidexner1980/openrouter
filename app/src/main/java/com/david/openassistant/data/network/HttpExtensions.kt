package com.david.openassistant.data.network

import okhttp3.Headers
import java.util.Locale

/**
 * Returns a copy of these headers with sensitive values (Authorization, Cookie, etc.) redacted.
 */
fun Headers.filterSensitive(): Headers {
    val builder = newBuilder()
    val sensitive = setOf("authorization", "cookie", "set-cookie", "proxy-authorization", "x-api-key")
    for (name in names()) {
        if (name.lowercase(Locale.US) in sensitive) {
            builder.set(name, "[REDACTED]")
        }
    }
    return builder.build()
}
