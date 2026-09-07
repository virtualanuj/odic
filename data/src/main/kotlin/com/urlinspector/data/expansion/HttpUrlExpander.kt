package com.urlinspector.data.expansion

import com.urlinspector.core.UrlExpander
import com.urlinspector.core.normalizeUrl
import com.urlinspector.core.model.ScannedUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import java.net.URI

private const val MAX_REDIRECTS = 5
private val REDIRECT_STATUS_RANGE = 300..399

/**
 * Follows a bounded chain of HTTP redirects (e.g. to resolve a shortened URL)
 * via HEAD requests. The [HttpClient] passed in here MUST be constructed with
 * `followRedirects = false` — otherwise the underlying engine transparently
 * follows 3xx responses itself, and this class never observes them (it will
 * always report "no redirect"). This is a different client configuration than
 * [com.urlinspector.data.reputation.SafeBrowsingClient] needs (which requires
 * ContentNegotiation + expectSuccess = true) — a caller wiring up DI (M3) must
 * construct two separately-configured HttpClient instances, not share one.
 */
class HttpUrlExpander(
    private val httpClient: HttpClient,
) : UrlExpander {

    override suspend fun expand(url: ScannedUrl): ScannedUrl {
        var current = url.normalized
        repeat(MAX_REDIRECTS) {
            val response: HttpResponse = httpClient.head(current)
            if (response.status.value !in REDIRECT_STATUS_RANGE) {
                return normalizeUrl(current) ?: url
            }
            val location = response.headers[HttpHeaders.Location] ?: return normalizeUrl(current) ?: url
            val next = URI(current).resolve(location).toString()
            if (current.startsWith("https://", ignoreCase = true) && !next.startsWith("https://", ignoreCase = true)) {
                // Refuse an https -> http protocol downgrade mid-redirect-chain.
                // Ktor's CIO engine has its own TLS stack and does not consult
                // Android's network security policy (androidApp's
                // network_security_config.xml, which declares cleartext
                // traffic disallowed) — this app-level guard is what actually
                // enforces that policy for the one code path in this app that
                // can vary scheme (a shortener redirect), since the platform
                // config alone does not bind CIO's raw-socket requests.
                return normalizeUrl(current) ?: url
            }
            current = next
        }
        return normalizeUrl(current) ?: url
    }
}
