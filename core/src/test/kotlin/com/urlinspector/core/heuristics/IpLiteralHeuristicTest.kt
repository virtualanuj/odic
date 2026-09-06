package com.urlinspector.core.heuristics

import com.urlinspector.core.model.ScannedUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IpLiteralHeuristicTest {
    private fun urlWithHost(host: String) = ScannedUrl(raw = host, normalized = "http://$host", host = host)

    @Test
    fun `flags an IPv4 literal host`() {
        val finding = IpLiteralHeuristic.evaluate(urlWithHost("192.168.1.1"))
        assertEquals("ip_literal", finding?.id)
    }

    @Test
    fun `flags an IPv6 literal host`() {
        val finding = IpLiteralHeuristic.evaluate(urlWithHost("[::1]"))
        assertEquals("ip_literal", finding?.id)
    }

    @Test
    fun `does not flag a normal domain`() {
        assertNull(IpLiteralHeuristic.evaluate(urlWithHost("example.com")))
    }
}
