package com.urlinspector.app.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShareTextUrlExtractorTest {

    @Test
    fun `extracts a bare URL`() {
        assertEquals("https://example.com", extractFirstUrl("https://example.com"))
    }

    @Test
    fun `extracts a URL with leading surrounding text`() {
        assertEquals(
            "https://example.com/x",
            extractFirstUrl("Check this out: https://example.com/x"),
        )
    }

    @Test
    fun `extracts a URL with trailing surrounding text`() {
        assertEquals(
            "http://example.com/page",
            extractFirstUrl("See http://example.com/page for details"),
        )
    }

    @Test
    fun `trims trailing punctuation not part of the URL`() {
        assertEquals(
            "https://example.com/page",
            extractFirstUrl("Look at (https://example.com/page)."),
        )
    }

    @Test
    fun `preserves a query string`() {
        assertEquals(
            "https://example.com/page?q=1&r=2",
            extractFirstUrl("https://example.com/page?q=1&r=2 nice right?"),
        )
    }

    @Test
    fun `returns the first URL when multiple are present`() {
        assertEquals(
            "http://a.example.com",
            extractFirstUrl("http://a.example.com and also https://b.example.com"),
        )
    }

    @Test
    fun `returns null when no URL is present`() {
        assertNull(extractFirstUrl("no link in this text"))
    }

    @Test
    fun `returns null for blank text`() {
        assertNull(extractFirstUrl("   "))
    }
}
