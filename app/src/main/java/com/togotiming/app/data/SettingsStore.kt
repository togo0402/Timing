package com.togotiming.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("timing_settings", Context.MODE_PRIVATE)

    private val _nfcEnabled = MutableStateFlow(prefs.getBoolean(KEY_NFC_ENABLED, true))
    val nfcEnabled: StateFlow<Boolean> = _nfcEnabled.asStateFlow()

    private val _fontPath = MutableStateFlow(prefs.getString(KEY_FONT_PATH, null))
    val fontPath: StateFlow<String?> = _fontPath.asStateFlow()

    private val _onboardingSeen = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDING_SEEN, false))
    val onboardingSeen: StateFlow<Boolean> = _onboardingSeen.asStateFlow()

    private val _widgetOpacity = MutableStateFlow(prefs.getInt(KEY_WIDGET_OPACITY, 100))
    val widgetOpacity: StateFlow<Int> = _widgetOpacity.asStateFlow()

    private val _pausedRecordId = MutableStateFlow(
        prefs.getLong(KEY_PAUSED_RECORD_ID, -1L).takeIf { it > 0L }
    )
    val pausedRecordId: StateFlow<Long?> = _pausedRecordId.asStateFlow()

    fun setPausedRecordId(id: Long?) {
        prefs.edit().putLong(KEY_PAUSED_RECORD_ID, id ?: -1L).apply()
        _pausedRecordId.value = id
    }

    fun setNfcEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NFC_ENABLED, enabled).apply()
        _nfcEnabled.value = enabled
    }

    fun setFontPath(path: String?) {
        prefs.edit().putString(KEY_FONT_PATH, path).apply()
        _fontPath.value = path
    }

    fun setOnboardingSeen() {
        prefs.edit().putBoolean(KEY_ONBOARDING_SEEN, true).apply()
        _onboardingSeen.value = true
    }

    fun setWidgetOpacity(percent: Int) {
        val v = percent.coerceIn(30, 100)
        prefs.edit().putInt(KEY_WIDGET_OPACITY, v).apply()
        _widgetOpacity.value = v
    }

    private companion object {
        const val KEY_NFC_ENABLED = "nfc_enabled"
        const val KEY_FONT_PATH = "font_path"
        const val KEY_ONBOARDING_SEEN = "onboarding_seen"
        const val KEY_WIDGET_OPACITY = "widget_opacity"
        const val KEY_PAUSED_RECORD_ID = "paused_record_id"
    }
}
