package com.ettlinger.wearrecorder

/** Stable persisted indices: the first three timings retain their pre-1.3 meanings. */
object UploadPolicy {
    const val IMMEDIATE = 0
    const val EVERY_15 = 1
    const val CHARGING = 2
    const val MANUAL = 3
    const val EVERY_30 = 4
    const val EVERY_60 = 5
    val timingLabels = listOf("Immediate", "Every 15 minutes", "While charging", "Manual only", "Every 30 minutes", "Every 60 minutes")
    val contentLabels = listOf("Audio and logs", "Logs only", "Audio only")
    fun presetLabel(timing: Int, content: Int) = when {
        timing == EVERY_15 && content == 0 -> "Baseline: 15-minute uploads"
        timing == MANUAL && content == 0 -> "Capture only: manual sync"
        timing == EVERY_15 && content == 1 -> "Logs only: 15-minute uploads"
        else -> "Custom"
    }
    fun intervalMinutes(timing: Int) = when (timing) { EVERY_30 -> 30L; EVERY_60 -> 60L; else -> 15L }
    fun automaticKick(timing: Int) = timing == IMMEDIATE || timing == CHARGING
    fun mayRun(timing: Int, manual: Boolean, currentGrant: Boolean = true) = if (manual) currentGrant else timing != MANUAL
    fun mayUpload(timing: Int, manual: Boolean, content: Int, logFile: Boolean, currentGrant: Boolean = true) =
        mayRun(timing, manual, currentGrant) && if (logFile) content != 2 else content != 1
    fun keepDiagnostic(detail: Int, level: String, tag: String) =
        detail == 0 || level in listOf("W", "E") || tag in listOf("App", "Battery", "Settings", "Recorder", "Profile", "Service", "Test")
}
