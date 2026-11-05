package com.ettlinger.wearrecorder

/** Independent experiments. Defaults preserve the 1.5.1 capture and upload path. */
data class PowerOptions(
    val efficientUploads: Boolean = false,
    val reuseSpeechBuffers: Boolean = false,
    val captureWakeLock: Boolean = true,
    val audioFormat: Int = WAV
) {
    init { require(audioFormat in WAV..AAC) }
    val formatLabel: String get() = formatLabels[audioFormat]
    val label: String get() = when (this) {
        BASELINE -> "Baseline (1.5.1 processing)"
        EFFICIENT -> "Efficient processing"
        else -> "Custom experiments"
    }
    val diagnostic: String get() = "efficient_uploads=$efficientUploads; pooled_vad=$reuseSpeechBuffers; capture_wake_lock=$captureWakeLock; audio_format=${if (audioFormat == AAC) "aac_lc_adts_24k" else "pcm16_wav"}"
    fun checkCapture(speechOnly: Boolean) {
        require(!speechOnly || audioFormat == WAV) { "AAC trial requires Continuous mode. Select WAV for Speech only." }
    }
    companion object {
        const val WAV = 0
        const val AAC = 1
        val formatLabels = listOf("WAV: original PCM", "AAC 24 kbps: experimental, Continuous only")
        val BASELINE = PowerOptions()
        val EFFICIENT = PowerOptions(efficientUploads = true, reuseSpeechBuffers = true)
    }
}
