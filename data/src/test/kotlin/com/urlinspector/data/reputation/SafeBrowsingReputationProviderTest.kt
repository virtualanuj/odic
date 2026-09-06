package com.urlinspector.data.reputation

import com.urlinspector.core.model.ScannedUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SafeBrowsingReputationProviderTest {

    @Test
    fun `a threat match is reported and cached`() = runTest {
        var requestCount = 0
        val engine = MockEngine { _ ->
            requestCount++
            respond(
                content = ByteReadChannel("""{"matches":[{"threatType":"MALWARE"}]}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(safeBrowsingJson) }
        }
        val client = SafeBrowsingClient(httpClient, apiKey = "test-key")
        val cache = ReputationCache()
        val fixedNow = Instant.parse("2026-01-01T00:00:00Z")
        val provider = SafeBrowsingReputationProvider(client, cache, now = { fixedNow })
        val url = ScannedUrl(raw = "http://malicious.example.com", normalized = "http://malicious.example.com/", host = "malicious.example.com")

        val first = provider.check(url)
        val second = provider.check(url)

        assertTrue(first.matched)
        assertEquals(first, second)
        assertEquals(1, requestCount)
    }

    @Test
    fun `no threat match is reported as unmatched`() = runTest {
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel("""{}"""),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(safeBrowsingJson) }
        }
        val client = SafeBrowsingClient(httpClient, apiKey = "test-key")
        val provider = SafeBrowsingReputationProvider(client, ReputationCache())
        val url = ScannedUrl(raw = "http://example.com", normalized = "http://example.com/", host = "example.com")

        val result = provider.check(url)

        assertEquals(false, result.matched)
        assertTrue(result.checked)
    }
}
