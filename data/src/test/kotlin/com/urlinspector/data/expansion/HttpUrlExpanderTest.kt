package com.urlinspector.data.expansion

import com.urlinspector.core.model.ScannedUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpUrlExpanderTest {

    @Test
    fun `follows a redirect chain to the final destination`() = runTest {
        val engine = MockEngine { request ->
            when (request.url.toString()) {
                "http://bit.ly/abc123" -> respond(
                    content = ByteReadChannel.Empty,
                    status = HttpStatusCode.MovedPermanently,
                    headers = headersOf(HttpHeaders.Location, "http://real-destination.example.com/"),
                )
                else -> respond(
                    content = ByteReadChannel.Empty,
                    status = HttpStatusCode.OK,
                )
            }
        }
        val httpClient = HttpClient(engine) { followRedirects = false }
        val expander = HttpUrlExpander(httpClient)
        val shortUrl = ScannedUrl(raw = "http://bit.ly/abc123", normalized = "http://bit.ly/abc123", host = "bit.ly")

        val result = expander.expand(shortUrl)

        assertEquals("real-destination.example.com", result.host)
    }

    @Test
    fun `returns the same URL when there is no redirect`() = runTest {
        val engine = MockEngine { _ ->
            respond(content = ByteReadChannel.Empty, status = HttpStatusCode.OK)
        }
        val httpClient = HttpClient(engine) { followRedirects = false }
        val expander = HttpUrlExpander(httpClient)
        val url = ScannedUrl(raw = "http://example.com", normalized = "http://example.com/", host = "example.com")

        val result = expander.expand(url)

        assertEquals("example.com", result.host)
    }
}
