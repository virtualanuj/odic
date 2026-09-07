package com.urlinspector.app.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val PREFS_NAME = "url_inspector_settings"
private const val KEY_SMS_SCANNING_ENABLED = "sms_scanning_enabled"

interface ScanPreferences {
    val smsScanningEnabled: StateFlow<Boolean>
    fun setSmsScanningEnabled(enabled: Boolean)
}

class SharedPreferencesScanPreferences(context: Context) : ScanPreferences {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _smsScanningEnabled = MutableStateFlow(
        prefs.getBoolean(KEY_SMS_SCANNING_ENABLED, false),
    )
    override val smsScanningEnabled: StateFlow<Boolean> = _smsScanningEnabled.asStateFlow()

    override fun setSmsScanningEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SMS_SCANNING_ENABLED, enabled).apply()
        _smsScanningEnabled.value = enabled
    }
}
