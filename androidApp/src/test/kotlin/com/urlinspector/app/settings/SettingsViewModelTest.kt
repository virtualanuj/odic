package com.urlinspector.app.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeScanPreferences(initiallyEnabled: Boolean = false) : ScanPreferences {
    private val _smsScanningEnabled = MutableStateFlow(initiallyEnabled)
    override val smsScanningEnabled: StateFlow<Boolean> = _smsScanningEnabled.asStateFlow()
    var setCallCount = 0
        private set

    override fun setSmsScanningEnabled(enabled: Boolean) {
        setCallCount++
        _smsScanningEnabled.value = enabled
    }
}

class SettingsViewModelTest {

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
    fun `reflects the preference's initial value`() = runTest {
        val viewModel = SettingsViewModel(FakeScanPreferences(initiallyEnabled = false))
        assertFalse(viewModel.smsScanningEnabled.value)
    }

    @Test
    fun `reflects an initially-enabled preference`() = runTest {
        val viewModel = SettingsViewModel(FakeScanPreferences(initiallyEnabled = true))
        assertTrue(viewModel.smsScanningEnabled.value)
    }

    @Test
    fun `setSmsScanningEnabled delegates to the preferences store`() = runTest {
        val preferences = FakeScanPreferences()
        val viewModel = SettingsViewModel(preferences)

        viewModel.setSmsScanningEnabled(true)

        assertEquals(1, preferences.setCallCount)
        assertTrue(viewModel.smsScanningEnabled.value)
    }
}
