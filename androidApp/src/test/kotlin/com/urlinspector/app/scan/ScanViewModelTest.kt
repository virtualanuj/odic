package com.urlinspector.app.scan

import com.urlinspector.app.fakes.FakeReputationProvider
import com.urlinspector.app.fakes.FakeScanRepository
import com.urlinspector.app.fakes.FakeUrlExpander
import com.urlinspector.core.ScanUrlUseCase
import com.urlinspector.core.model.Verdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanViewModelTest {

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
    fun `starts in Idle state`() {
        val viewModel = ScanViewModel(
            ScanUrlUseCase(FakeReputationProvider(), FakeUrlExpander(), FakeScanRepository()),
        )

        assertTrue(viewModel.uiState.value is ScanUiState.Idle)
    }

    @Test
    fun `scanning a valid URL transitions through Loading to Success`() = runTest(dispatcher) {
        val viewModel = ScanViewModel(
            ScanUrlUseCase(FakeReputationProvider(), FakeUrlExpander(), FakeScanRepository()),
        )

        viewModel.scan("http://example.com")
        assertTrue(viewModel.uiState.value is ScanUiState.Loading)

        dispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is ScanUiState.Success)
        assertEquals(Verdict.SAFE, (state as ScanUiState.Success).result.verdict)
    }

    @Test
    fun `scanning an invalid URL transitions to Error`() = runTest(dispatcher) {
        val viewModel = ScanViewModel(
            ScanUrlUseCase(FakeReputationProvider(), FakeUrlExpander(), FakeScanRepository()),
        )

        viewModel.scan("   ")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value is ScanUiState.Error)
    }

    @Test
    fun `reset returns to Idle`() = runTest(dispatcher) {
        val viewModel = ScanViewModel(
            ScanUrlUseCase(FakeReputationProvider(), FakeUrlExpander(), FakeScanRepository()),
        )

        viewModel.scan("http://example.com")
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.reset()

        assertTrue(viewModel.uiState.value is ScanUiState.Idle)
    }
}
