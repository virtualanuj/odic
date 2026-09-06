package com.urlinspector.data.reputation

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class SafeBrowsingClientTest {

    private fun clientWith(responseBody: String, status: HttpStatusCode = HttpStatusCode.OK): SafeBrowsingClient {
        val engine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(responseBody),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val httpClient = HttpClient(engine) {
            expectSuccess = true
            install(ContentNegotiation) { json(safeBrowsingJson) }
        }
        return SafeBrowsingClient(httpClient, apiKey = "test-key")
    }

    @Test
    fun `returns matches when the API reports a threat`() = runTest {
        val client = clientWith("""{"matches":[{"threatType":"MALWARE"}]}""")

        val matches = client.findThreatMatches("http://malicious.example.com")

        assertEquals(1, matches.size)
        assertEquals("MALWARE", matches.first().threatType)
    }

    @Test
    fun `returns an empty list when the API reports no threats`() = runTest {
        val client = clientWith("""{}""")

        val matches = client.findThreatMatches("http://example.com")

        assertTrue(matches.isEmpty())
    }

    @Test
    fun `tolerates extra fields present in a real Safe Browsing response`() = runTest {
        val client = clientWith(
            """
            {
              "matches": [
                {
                  "threatType": "MALWARE",
                  "platformType": "ANY_PLATFORM",
                  "threatEntryType": "URL",
                  "threat": {"url": "http://malicious.example.com/"},
                  "cacheDuration": "300.000s"
                }
              ]
            }
            """.trimIndent(),
        )

        val matches = client.findThreatMatches("http://malicious.example.com")

        assertEquals(1, matches.size)
        assertEquals("MALWARE", matches.first().threatType)
    }

    @Test
    fun `throws when the API returns an error status`() = runTest {
        val client = clientWith("""{"error":"bad request"}""", status = HttpStatusCode.BadRequest)

        assertFailsWith<Exception> {
            client.findThreatMatches("http://example.com")
        }
    }
}
