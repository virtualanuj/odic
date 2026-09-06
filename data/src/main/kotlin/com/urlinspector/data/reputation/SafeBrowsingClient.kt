package com.urlinspector.data.reputation

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json

val safeBrowsingJson = Json { ignoreUnknownKeys = true }

private const val DEFAULT_BASE_URL = "https://safebrowsing.googleapis.com/v4/threatMatches:find"
private val DEFAULT_THREAT_TYPES = listOf("MALWARE", "SOCIAL_ENGINEERING", "UNWANTED_SOFTWARE")

/**
 * A real Safe Browsing API response includes fields this client's DTOs don't
 * model (platformType, threatEntryType, threat.url, cacheDuration, etc).
 * The [HttpClient] passed in here MUST be configured with content negotiation
 * using [safeBrowsingJson] (or any Json with ignoreUnknownKeys = true) —
 * otherwise a real response throws SerializationException on the first
 * unrecognized field, which this class does not catch (by design — see
 * core.ScanUrlUseCase.runGuarded, which is where that's meant to be handled).
 */
class SafeBrowsingClient(
    private val httpClient: HttpClient,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    suspend fun findThreatMatches(url: String): List<SafeBrowsingThreatMatch> {
        val request = SafeBrowsingFindRequest(
            client = SafeBrowsingClientInfo(clientId = "url-inspector", clientVersion = "1.0.0"),
            threatInfo = SafeBrowsingThreatInfo(
                threatTypes = DEFAULT_THREAT_TYPES,
                platformTypes = listOf("ANY_PLATFORM"),
                threatEntryTypes = listOf("URL"),
                threatEntries = listOf(SafeBrowsingThreatEntry(url = url)),
            ),
        )

        val response: SafeBrowsingFindResponse = httpClient.post {
            url("$baseUrl?key=$apiKey")
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

        return response.matches.orEmpty()
    }
}
