package com.david.openassistant.data.security

internal fun maskCredentialLabel(label: String?): String {
    if (label.isNullOrBlank()) return "Stored encrypted credential"
    val suffix = label.filter(Char::isLetterOrDigit).takeLast(4)
    return if (suffix.isBlank()) "Stored encrypted credential" else "••••••••$suffix"
}
