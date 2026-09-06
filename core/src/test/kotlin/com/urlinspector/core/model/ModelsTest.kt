package com.urlinspector.core.model

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelsTest {
    @Test
    fun `ScanResult holds all scan outputs together`() {
        val url = ScannedUrl(raw = "http://example.com", normalized = "http://example.com/", host = "example.com")
        val finding = HeuristicFinding(id = "ip_literal", description = "Host is a raw IP address")
        val reputation = ReputationResult(matched = false, source = "safe-browsing", checked = true)
        val scannedAt = Instant.parse("2026-01-01T00:00:00Z")

        val result = ScanResult(
            url = url,
            verdict = Verdict.SUSPICIOUS,
            heuristicFindings = listOf(finding),
            reputationResult = reputation,
            scannedAt = scannedAt,
        )

        assertEquals(Verdict.SUSPICIOUS, result.verdict)
        assertEquals(1, result.heuristicFindings.size)
        assertTrue(result.reputationResult.checked)
    }

    @Test
    fun `ScanHistoryEntry stores the essentials for the history list`() {
        val entry = ScanHistoryEntry(
            id = "abc-123",
            url = "http://example.com",
            verdict = Verdict.SAFE,
            scannedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

        assertEquals("abc-123", entry.id)
        assertEquals(Verdict.SAFE, entry.verdict)
    }
}
