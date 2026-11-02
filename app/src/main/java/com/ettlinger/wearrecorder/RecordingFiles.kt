package com.ettlinger.wearrecorder

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

/** Android directory fsync uses libcore's supported POSIX API, not host-JVM file-channel assumptions. */
object RecordingFiles {
    fun open(context: Context) = create(context, "recordings")
    fun measurements(context: Context) = create(context, "sync_measurements")
    fun logs(context: Context) = create(context, "diagnostics")
    private fun create(context: Context, name: String): RecordingStore = RecordingStore(File(context.filesDir, name)) { directory ->
        try {
            val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        } catch (e: Exception) { throw IOException("Cannot sync recording directory", e) }
    }
}
