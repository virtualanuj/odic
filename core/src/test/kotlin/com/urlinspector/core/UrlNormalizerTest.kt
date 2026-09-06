package com.urlinspector.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UrlNormalizerTest {
    @Test
    fun `parses a URL that already has a scheme`() {
        val result = normalizeUrl("http://Example.com/Path")
        assertEquals("example.com", result?.host)
    }

    @Test
    fun `adds a scheme when missing`() {
        val result = normalizeUrl("example.com/path")
        assertEquals("example.com", result?.host)
        assertEquals("http://example.com/path", result?.normalized)
    }

    @Test
    fun `returns null for blank input`() {
        assertNull(normalizeUrl("   "))
    }

    @Test
    fun `returns null when no host can be parsed`() {
        assertNull(normalizeUrl("http://"))
    }

    @Test
    fun `preserves the original raw string`() {
        val result = normalizeUrl("  http://example.com  ".trim())
        assertEquals("http://example.com", result?.raw)
    }

    @Test
    fun `IDN-encodes a non-ASCII host instead of rejecting it`() {
        // "а" is the Cyrillic small letter "a", a lookalike for ASCII "a".
        val result = normalizeUrl("http://\u0430pple.com")
        assertEquals(true, result?.host?.startsWith("xn--"))
    }

    @Test
    fun `rejects a javascript scheme`() {
        assertNull(normalizeUrl("javascript://example.com/%0aalert(1)"))
    }

    @Test
    fun `rejects an ftp scheme`() {
        assertNull(normalizeUrl("ftp://example.com/x"))
    }

    @Test
    fun `normalizes scheme and host case and drops fragments`() {
        val result = normalizeUrl("HTTP://EXAMPLE.com/path#fragment")
        assertEquals("http://example.com/path", result?.normalized)
    }
}
