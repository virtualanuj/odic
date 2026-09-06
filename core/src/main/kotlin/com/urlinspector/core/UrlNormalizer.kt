package com.urlinspector.core

import com.urlinspector.core.model.ScannedUrl
import java.net.URI

fun normalizeUrl(raw: String): ScannedUrl? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"

    val uri = try {
        URI(withScheme)
    } catch (e: Exception) {
        return null
    }

    val host = uri.host?.lowercase() ?: return null
    if (host.isEmpty()) return null

    return ScannedUrl(raw = raw, normalized = uri.toString(), host = host)
}
