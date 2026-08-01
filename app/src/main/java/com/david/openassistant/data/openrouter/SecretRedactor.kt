package com.david.openassistant.data.openrouter

/**
 * Universal secret redaction utility.
 *
 * It provides exact-string replacement for known secrets and supports
 * redaction across diagnostics, monitor reports, and UI state.
 */
object SecretRedactor {
    fun redact(text: String, secret: String?): String {
        if (secret.isNullOrBlank()) return text
        return text.replace(secret, "[REDACTED]")
    }

    /** Redacts multiple known secrets from a single string. */
    fun redactAll(text: String, secrets: Iterable<String?>): String {
        var current = text
        secrets.forEach { secret ->
            current = redact(current, secret)
        }
        return current
    }
}
