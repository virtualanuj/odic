package com.urlinspector.core

import com.urlinspector.core.model.ScannedUrl
import java.net.IDN
import java.net.URI

private val allowedSchemes = setOf("http", "https")

fun normalizeUrl(raw: String): ScannedUrl? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null

    val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"

    val uri = try {
        URI(withScheme)
    } catch (e: Exception) {
        return null
    }

    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme !in allowedSchemes) return null

    val rawHost = uri.host ?: uri.authority?.substringAfterLast('@')?.substringBefore(':')
    if (rawHost.isNullOrEmpty()) return null

    val host = try {
        IDN.toASCII(rawHost, IDN.ALLOW_UNASSIGNED).lowercase()
    } catch (e: Exception) {
        return null
    }
    if (host.isEmpty()) return null

    val port = if (uri.port != -1) ":${uri.port}" else ""
    val path = uri.rawPath?.ifEmpty { "/" } ?: "/"
    val query = uri.rawQuery?.let { "?$it" } ?: ""
    val normalized = "$scheme://$host$port$path$query"

    return ScannedUrl(raw = raw, normalized = normalized, host = host)
}
