package com.urlinspector.data.reputation

import com.urlinspector.core.model.ReputationResult
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReputationCacheTest {
    @Test
    fun `a fresh entry is returned before it expires`() {
        val cache = ReputationCache(ttlMillis = 10_000)
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val result = ReputationResult(matched = true, source = "safe-browsing", checked = true)

        cache.put("http://example.com", result, now)

        assertEquals(result, cache.get("http://example.com", now.plusMillis(5_000)))
    }

    @Test
    fun `an expired entry is not returned`() {
        val cache = ReputationCache(ttlMillis = 10_000)
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val result = ReputationResult(matched = true, source = "safe-browsing", checked = true)

        cache.put("http://example.com", result, now)

        assertNull(cache.get("http://example.com", now.plusMillis(10_001)))
    }

    @Test
    fun `exceeding max size evicts the least recently used entry`() {
        val cache = ReputationCache(maxSize = 2, ttlMillis = 60_000)
        val now = Instant.parse("2026-01-01T00:00:00Z")
        val a = ReputationResult(matched = false, source = "safe-browsing", checked = true)
        val b = ReputationResult(matched = false, source = "safe-browsing", checked = true)
        val c = ReputationResult(matched = false, source = "safe-browsing", checked = true)

        cache.put("a", a, now)
        cache.put("b", b, now)
        cache.put("c", c, now)

        assertNull(cache.get("a", now))
        assertEquals(b, cache.get("b", now))
        assertEquals(c, cache.get("c", now))
    }
}
