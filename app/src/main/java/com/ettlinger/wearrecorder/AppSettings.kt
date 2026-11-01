package com.ettlinger.wearrecorder

import android.content.Context

object AppSettings {
    const val MODE = "speech_only"
    const val SENSITIVITY = "vad_sensitivity"
    const val LOGGING = "diagnostic_logging"
    const val UNMETERED = "unmetered_uploads"
    private fun prefs(c: Context) = c.getSharedPreferences("rave_ai_settings", Context.MODE_PRIVATE)
    fun speechOnly(c: Context) = prefs(c).getBoolean(MODE, false)
    fun sensitivity(c: Context) = prefs(c).getInt(SENSITIVITY, 1).coerceIn(0, 2)
    fun logging(c: Context) = prefs(c).getBoolean(LOGGING, false)
    fun unmetered(c: Context) = prefs(c).getBoolean(UNMETERED, false)
    fun set(c: Context, key: String, value: Boolean) { check(prefs(c).edit().putBoolean(key, value).commit()) }
    fun setSensitivity(c: Context, value: Int) { check(prefs(c).edit().putInt(SENSITIVITY, value.coerceIn(0, 2)).commit()) }
}
