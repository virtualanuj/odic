package com.urlinspector.core.fakes

import com.urlinspector.core.ReputationProvider
import com.urlinspector.core.ScanRepository
import com.urlinspector.core.UrlExpander
import com.urlinspector.core.model.ReputationResult
import com.urlinspector.core.model.ScanHistoryEntry
import com.urlinspector.core.model.ScannedUrl

class FakeReputationProvider(
    override val id: String = "fake-reputation",
    private val result: (ScannedUrl) -> ReputationResult = {
        ReputationResult(matched = false, source = "fake-reputation", checked = true)
    },
) : ReputationProvider {
    override suspend fun check(url: ScannedUrl): ReputationResult = result(url)
}

class ThrowingReputationProvider(override val id: String = "fake-reputation") : ReputationProvider {
    override suspend fun check(url: ScannedUrl): ReputationResult {
        throw RuntimeException("simulated network failure")
    }
}

class FakeUrlExpander(
    private val expansions: Map<String, ScannedUrl> = emptyMap(),
) : UrlExpander {
    override suspend fun expand(url: ScannedUrl): ScannedUrl = expansions[url.normalized] ?: url
}

class FakeScanRepository : ScanRepository {
    val saved = mutableListOf<ScanHistoryEntry>()

    override suspend fun save(entry: ScanHistoryEntry) {
        saved.add(entry)
    }

    override suspend fun getAll(): List<ScanHistoryEntry> = saved.toList()

    override suspend fun deleteById(id: String) {
        saved.removeAll { it.id == id }
    }

    override suspend fun clearAll() {
        saved.clear()
    }
}
