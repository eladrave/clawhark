package com.ettlinger.wearrecorder

import android.content.Context
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

/** Android directory fsync uses libcore's supported POSIX API, not host-JVM file-channel assumptions. */
object RecordingFiles {
    fun open(context: Context): RecordingStore = RecordingStore(File(context.filesDir, "recordings")) { directory ->
        try {
            val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
            try { Os.fsync(fd) } finally { Os.close(fd) }
        } catch (e: Exception) { throw IOException("Cannot sync recording directory", e) }
    }
}
