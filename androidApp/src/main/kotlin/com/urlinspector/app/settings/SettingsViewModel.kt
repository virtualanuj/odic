package com.urlinspector.app.settings

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(
    private val preferences: ScanPreferences,
) : ViewModel() {
    val smsScanningEnabled: StateFlow<Boolean> = preferences.smsScanningEnabled

    fun setSmsScanningEnabled(enabled: Boolean) {
        preferences.setSmsScanningEnabled(enabled)
    }
}
