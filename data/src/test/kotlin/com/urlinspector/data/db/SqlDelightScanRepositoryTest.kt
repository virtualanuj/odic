package com.urlinspector.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.urlinspector.core.model.ScanHistoryEntry
import com.urlinspector.core.model.Verdict
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlDelightScanRepositoryTest {

    private fun newRepository(): SqlDelightScanRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        UrlInspectorDatabase.Schema.create(driver)
        return SqlDelightScanRepository(UrlInspectorDatabase(driver))
    }

    @Test
    fun `save then getAll returns entries newest first`() = runTest {
        val repository = newRepository()
        val older = ScanHistoryEntry("1", "http://a.com", Verdict.SAFE, Instant.parse("2026-01-01T00:00:00Z"))
        val newer = ScanHistoryEntry("2", "http://b.com", Verdict.MALICIOUS, Instant.parse("2026-01-02T00:00:00Z"))

        repository.save(older)
        repository.save(newer)

        assertEquals(listOf(newer, older), repository.getAll())
    }

    @Test
    fun `deleteById removes only the matching entry`() = runTest {
        val repository = newRepository()
        val keep = ScanHistoryEntry("1", "http://a.com", Verdict.SAFE, Instant.parse("2026-01-01T00:00:00Z"))
        val remove = ScanHistoryEntry("2", "http://b.com", Verdict.SAFE, Instant.parse("2026-01-01T00:00:00Z"))
        repository.save(keep)
        repository.save(remove)

        repository.deleteById("2")

        assertEquals(listOf(keep), repository.getAll())
    }

    @Test
    fun `clearAll empties the history`() = runTest {
        val repository = newRepository()
        repository.save(ScanHistoryEntry("1", "http://a.com", Verdict.SAFE, Instant.parse("2026-01-01T00:00:00Z")))

        repository.clearAll()

        assertTrue(repository.getAll().isEmpty())
    }
}
