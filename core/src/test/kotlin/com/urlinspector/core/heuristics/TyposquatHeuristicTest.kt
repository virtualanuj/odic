package com.urlinspector.core.heuristics

import com.urlinspector.core.model.ScannedUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TyposquatHeuristicTest {
    private fun urlWithHost(host: String) = ScannedUrl(raw = host, normalized = "http://$host", host = host)

    @Test
    fun `flags a one-character lookalike of a protected brand`() {
        val finding = TyposquatHeuristic.evaluate(urlWithHost("paypa1.com"))
        assertEquals("typosquat", finding?.id)
    }

    @Test
    fun `does not flag the real brand domain`() {
        assertNull(TyposquatHeuristic.evaluate(urlWithHost("paypal.com")))
    }

    @Test
    fun `does not flag an unrelated domain`() {
        assertNull(TyposquatHeuristic.evaluate(urlWithHost("totally-unrelated-shop.net")))
    }
}
