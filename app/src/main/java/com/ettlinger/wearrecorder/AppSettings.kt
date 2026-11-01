package com.ettlinger.wearrecorder

import android.content.Context

object AppSettings {
    const val MODE = "speech_only"
    const val SENSITIVITY = "vad_sensitivity"
    const val LOGGING = "diagnostic_logging"
    const val UNMETERED = "unmetered_uploads"
    const val UPLOAD_TIMING = "upload_timing"
    const val UPLOAD_CONTENT = "upload_content"
    const val LOG_DETAIL = "log_detail"
    const val TILE_UPDATES = "tile_updates"
    private fun prefs(c: Context) = c.getSharedPreferences("rave_ai_settings", Context.MODE_PRIVATE)
    fun speechOnly(c: Context) = prefs(c).getBoolean(MODE, false)
    fun sensitivity(c: Context) = prefs(c).getInt(SENSITIVITY, 1).coerceIn(0, 2)
    fun uploadTiming(c: Context) = prefs(c).getInt(UPLOAD_TIMING, 0).coerceIn(0, 5)
    fun setUploadTiming(c: Context, value: Int) { setInt(c, UPLOAD_TIMING, value.coerceIn(0, 5)) }
    fun uploadContent(c: Context) = prefs(c).getInt(UPLOAD_CONTENT, 0).coerceIn(0, 2)
    fun logDetail(c: Context) = prefs(c).getInt(LOG_DETAIL, 0).coerceIn(0, 1)
    fun tileUpdates(c: Context) = prefs(c).getBoolean(TILE_UPDATES, true)
    fun setInt(c: Context, key: String, value: Int) { check(prefs(c).edit().putInt(key, value).commit()) }
    fun uploadLabel(c: Context) = "${UploadPolicy.timingLabels[uploadTiming(c)]} / ${UploadPolicy.contentLabels[uploadContent(c)]}"
    fun profile(c: Context) = "speech_only=${speechOnly(c)}; sensitivity=${sensitivity(c)}; upload_timing=${uploadTiming(c)}; upload_content=${uploadContent(c)}; unmetered=${unmetered(c)}; logging=${logging(c)}; log_detail=${logDetail(c)}; tile_updates=${tileUpdates(c)}"
    fun testId(c: Context) = prefs(c).getString("test_id", "none")!!
    fun syncEpoch(c: Context) = prefs(c).getLong("sync_epoch", 0)
    @Synchronized fun invalidateManualSync(c: Context) { check(prefs(c).edit().putLong("sync_epoch", syncEpoch(c) + 1).commit()) }
    fun startTest(c: Context): String {
        val id = java.util.UUID.randomUUID().toString()
        check(prefs(c).edit().putString("test_id", id).putLong("test_started", System.currentTimeMillis())
            .putLong("sync_epoch", syncEpoch(c) + 1).commit())
        return id
    }
    fun finishTest(c: Context) { check(prefs(c).edit().remove("test_id").remove("test_started").commit()) }
    fun applyPreset(c: Context, preset: Int) {
        require(preset in 1..3)
        check(prefs(c).edit().putInt(UPLOAD_TIMING, if (preset == 2) UploadPolicy.MANUAL else UploadPolicy.EVERY_15)
            .putInt(UPLOAD_CONTENT, if (preset == 3) 1 else 0).commit())
    }
    fun logging(c: Context) = prefs(c).getBoolean(LOGGING, false)
    fun unmetered(c: Context) = prefs(c).getBoolean(UNMETERED, false)
    fun set(c: Context, key: String, value: Boolean) { check(prefs(c).edit().putBoolean(key, value).commit()) }
    fun setSensitivity(c: Context, value: Int) { check(prefs(c).edit().putInt(SENSITIVITY, value.coerceIn(0, 2)).commit()) }
}
