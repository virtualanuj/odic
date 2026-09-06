package com.urlinspector.core

import com.urlinspector.core.model.ScanHistoryEntry

interface ScanRepository {
    suspend fun save(entry: ScanHistoryEntry)
    suspend fun getAll(): List<ScanHistoryEntry>
    suspend fun deleteById(id: String)
    suspend fun clearAll()
}
