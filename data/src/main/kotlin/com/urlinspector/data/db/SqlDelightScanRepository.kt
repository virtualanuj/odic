package com.urlinspector.data.db

import com.urlinspector.core.ScanRepository
import com.urlinspector.core.model.ScanHistoryEntry
import com.urlinspector.core.model.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

class SqlDelightScanRepository(
    private val database: UrlInspectorDatabase,
) : ScanRepository {

    override suspend fun save(entry: ScanHistoryEntry): Unit = withContext(Dispatchers.IO) {
        database.scanHistoryQueries.insertEntry(
            id = entry.id,
            url = entry.url,
            verdict = entry.verdict.name,
            scannedAt = entry.scannedAt.toEpochMilli(),
        )
    }

    override suspend fun getAll(): List<ScanHistoryEntry> = withContext(Dispatchers.IO) {
        database.scanHistoryQueries.selectAll().executeAsList().map { row ->
            ScanHistoryEntry(
                id = row.id,
                url = row.url,
                verdict = Verdict.valueOf(row.verdict),
                scannedAt = Instant.ofEpochMilli(row.scannedAt),
            )
        }
    }

    override suspend fun deleteById(id: String): Unit = withContext(Dispatchers.IO) {
        database.scanHistoryQueries.deleteById(id)
    }

    override suspend fun clearAll(): Unit = withContext(Dispatchers.IO) {
        database.scanHistoryQueries.deleteAll()
    }
}
