package com.ettlinger.wearrecorder

import android.content.Context
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

/** Opt-in app/battery diagnostics only. No audio, transcript, grant codes, or tokens. */
object AppLog {
    private var app: Context? = null
    private var logs: DiagnosticLogStore? = null
    @Volatile var status = "Off"
        private set
    @Synchronized fun init(context: Context) { if (app == null) app = context.applicationContext }
    fun d(tag: String, msg: String) = write("D", tag, msg)
    fun i(tag: String, msg: String) = write("I", tag, msg)
    fun w(tag: String, msg: String) = write("W", tag, msg)
    fun e(tag: String, msg: String, throwable: Throwable? = null) =
        write("E", tag, msg + (throwable?.let { " [${it.javaClass.simpleName}]" } ?: ""))
    @Synchronized private fun write(level: String, tag: String, message: String) {
        val c = app ?: return
        if (!AppSettings.logging(c)) { status = "Off"; return }
        try {
            if (logs == null) logs = DiagnosticLogStore(RecordingFiles.logs(c))
            val now = System.currentTimeMillis()
            val at = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.ROOT).format(Date(now))
            val safe = DiagnosticLogStore.redact(message.replace('\n', ' ').take(1200))
            val line = "$at $level/$tag ${BatteryMonitor.label} | v=${BuildConfig.VERSION_NAME} $safe"
            logs!!.append(line, now, important = level == "E" || level == "W")
            status = "Enabled"
            Log.println(if (level == "E") Log.ERROR else Log.INFO, "RaveAI.$tag", safe)
        } catch (_: Exception) { status = "Paused: log storage limit or write error" }
    }
    @Synchronized fun flush() { try { logs?.seal() } catch (_: Exception) { status = "Log write error" } }
    @Synchronized fun checkpoint() {
        try { logs?.checkpoint(System.currentTimeMillis()) } catch (_: Exception) { status = "Log write error" }
    }
}
