package com.urlinspector.core.heuristics

import com.urlinspector.core.model.ScannedUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomographHeuristicTest {
    private fun urlWithHost(host: String) = ScannedUrl(raw = host, normalized = "http://$host", host = host)

    @Test
    fun `flags a punycode-encoded label`() {
        val finding = HomographHeuristic.evaluate(urlWithHost("xn--pple-43d.com"))
        assertEquals("homograph", finding?.id)
    }

    @Test
    fun `flags a host containing non-ASCII characters`() {
        val finding = HomographHeuristic.evaluate(urlWithHost("аpple.com"))
        assertEquals("homograph", finding?.id)
    }

    @Test
    fun `does not flag a plain ASCII domain`() {
        assertNull(HomographHeuristic.evaluate(urlWithHost("apple.com")))
    }
}
