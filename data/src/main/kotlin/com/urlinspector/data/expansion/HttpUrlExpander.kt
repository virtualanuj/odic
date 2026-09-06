package com.urlinspector.data.expansion

import com.urlinspector.core.UrlExpander
import com.urlinspector.core.normalizeUrl
import com.urlinspector.core.model.ScannedUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders

private const val MAX_REDIRECTS = 5
private val REDIRECT_STATUS_RANGE = 300..399

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
            current = location
        }
        return normalizeUrl(current) ?: url
    }
}
