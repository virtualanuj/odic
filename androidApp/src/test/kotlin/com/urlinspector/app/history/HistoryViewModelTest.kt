package com.urlinspector.app.history

import com.urlinspector.app.fakes.FakeScanRepository
import com.urlinspector.core.model.ScanHistoryEntry
import com.urlinspector.core.model.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoryViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads existing entries on init`() = runTest(dispatcher) {
        val repository = FakeScanRepository()
        repository.save(ScanHistoryEntry("1", "http://a.com", Verdict.SAFE, Instant.parse("2026-01-01T00:00:00Z")))

        val viewModel = HistoryViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModel.entries.value.size)
    }

    @Test
    fun `delete removes an entry and refreshes`() = runTest(dispatcher) {
        val repository = FakeScanRepository()
        repository.save(ScanHistoryEntry("1", "http://a.com", Verdict.SAFE, Instant.parse("2026-01-01T00:00:00Z")))
        val viewModel = HistoryViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.delete("1")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.entries.value.isEmpty())
    }

    @Test
    fun `clearAll empties the list`() = runTest(dispatcher) {
        val repository = FakeScanRepository()
        repository.save(ScanHistoryEntry("1", "http://a.com", Verdict.SAFE, Instant.parse("2026-01-01T00:00:00Z")))
        repository.save(ScanHistoryEntry("2", "http://b.com", Verdict.SAFE, Instant.parse("2026-01-01T00:00:00Z")))
        val viewModel = HistoryViewModel(repository)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.clearAll()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.entries.value.isEmpty())
    }
}
