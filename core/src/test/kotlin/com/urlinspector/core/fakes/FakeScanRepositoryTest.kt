package com.urlinspector.core.fakes

import com.urlinspector.core.model.ScanHistoryEntry
import com.urlinspector.core.model.Verdict
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FakeScanRepositoryTest {
    @Test
    fun `save then getAll returns the saved entry`() = runTest {
        val repository = FakeScanRepository()
        val entry = ScanHistoryEntry(
            id = "1",
            url = "http://example.com",
            verdict = Verdict.SAFE,
            scannedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )

        repository.save(entry)

        assertEquals(listOf(entry), repository.getAll())
    }

    @Test
    fun `deleteById removes only the matching entry`() = runTest {
        val repository = FakeScanRepository()
        val keep = ScanHistoryEntry("1", "http://a.com", Verdict.SAFE, Instant.parse("2026-01-01T00:00:00Z"))
        val remove = ScanHistoryEntry("2", "http://b.com", Verdict.SAFE, Instant.parse("2026-01-01T00:00:00Z"))
        repository.save(keep)
        repository.save(remove)

        repository.deleteById("2")

        assertEquals(listOf(keep), repository.getAll())
    }

    @Test
    fun `clearAll empties the history`() = runTest {
        val repository = FakeScanRepository()
        repository.save(ScanHistoryEntry("1", "http://a.com", Verdict.SAFE, Instant.parse("2026-01-01T00:00:00Z")))

        repository.clearAll()

        assertTrue(repository.getAll().isEmpty())
    }
}
