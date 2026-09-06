package com.urlinspector.core.heuristics

import com.urlinspector.core.model.ScannedUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SuspiciousTldHeuristicTest {
    private fun urlWithHost(host: String) = ScannedUrl(raw = host, normalized = "http://$host", host = host)

    @Test
    fun `flags a high-abuse TLD`() {
        val finding = SuspiciousTldHeuristic.evaluate(urlWithHost("free-gift.tk"))
        assertEquals("suspicious_tld", finding?.id)
    }

    @Test
    fun `does not flag a common TLD`() {
        assertNull(SuspiciousTldHeuristic.evaluate(urlWithHost("example.com")))
    }

    @Test
    fun `does not flag a host with no TLD`() {
        assertNull(SuspiciousTldHeuristic.evaluate(urlWithHost("localhost")))
    }
}
