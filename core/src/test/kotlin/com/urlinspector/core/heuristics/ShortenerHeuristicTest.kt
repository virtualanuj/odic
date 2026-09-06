package com.urlinspector.core.heuristics

import com.urlinspector.core.model.ScannedUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShortenerHeuristicTest {
    private fun urlWithHost(host: String) = ScannedUrl(raw = host, normalized = "http://$host", host = host)

    @Test
    fun `flags a known shortener domain`() {
        val finding = ShortenerHeuristic.evaluate(urlWithHost("bit.ly"))
        assertEquals("shortener", finding?.id)
    }

    @Test
    fun `does not flag a non-shortener domain`() {
        assertNull(ShortenerHeuristic.evaluate(urlWithHost("example.com")))
    }
}
