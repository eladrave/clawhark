package com.ettlinger.wearrecorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Experimental AAC-LC ADTS. Only emitted frames are checkpointed; the encoder can
 * retain an additional input tail. PCM remains the default recovery contract.
 * Must be created before opening the microphone, and used only by the capture owner.
 */
class AacSegment internal constructor(
    private val store: RecordingStore,
    startedAt: Long,
    private val clock: () -> Long,
    codecFactory: () -> Encoder
) : Closeable {
    constructor(store: RecordingStore, startedAt: Long) : this(
        store, startedAt, SystemClock::elapsedRealtime, { PlatformEncoder() })

    internal data class Packet(val bytes: ByteArray, val config: Boolean = false, val eos: Boolean = false)
    internal interface Encoder : Closeable {
        /** Nonblocking; returns the number of samples queued, zero when unavailable. */
        fun offer(samples: ShortArray, offset: Int, count: Int, presentationUs: Long): Int
        fun offerEnd(presentationUs: Long): Boolean
        fun poll(): Packet?
    }

    private var encoder: Encoder? = null
    private val partial: File
    private val target: File
    private val file: FileOutputStream
    private val output: BufferedOutputStream
    private var finished = false
    private var failed = false
    private var result: File? = null
    private var eosSeen = false
    private var frames = 0L
    var inputSamples = 0L
        private set
    var encodedBytes = 0L
        private set

    init {
        store.checkSpace(8192)
        // Prove codec configuration/start before creating a partial or opening the mic.
        val created = codecFactory()
        try {
            val date = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS_Z", Locale.ROOT).format(Date(startedAt))
            target = File(store.directory(), "RaveAI_${date}_${UUID.randomUUID()}.aac")
            partial = File(target.path + ".part")
            file = FileOutputStream(partial)
            output = BufferedOutputStream(file, 8192)
            try { file.fd.sync(); store.syncDirectory(store.directory()) }
            catch (e: Exception) { output.close(); throw e }
            encoder = created
        } catch (e: Exception) { created.close(); throw e }
    }

    fun append(samples: ShortArray, length: Int) {
        check(!finished) { "AAC segment is closed" }
        require(length in 0..samples.size)
        try {
            val active = checkNotNull(encoder)
            val deadline = clock() + CODEC_DEADLINE_MS
            var offset = 0
            while (offset < length) {
                checkDeadline(deadline)
                val n = active.offer(samples, offset, length - offset, inputSamples * 1_000_000L / SAMPLE_RATE)
                check(n in 0..(length - offset)) { "Invalid AAC input consumption" }
                offset += n; inputSamples += n
                val progressed = drain(active, deadline)
                if (n == 0 && !progressed) Thread.sleep(1)
            }
        } catch (e: Exception) { failed = true; throw e }
    }

    fun checkpoint() {
        check(!finished) { "AAC segment is closed" }
        try { output.flush(); file.fd.sync() }
        catch (e: Exception) { failed = true; throw e }
    }

    /** Idempotent. On codec/finalization failure the partial stays for prefix recovery. */
    fun seal(): File? {
        if (finished) return result
        if (failed) {
            close()
            throw IOException("AAC segment failed; partial retained for recovery")
        }
        finished = true
        val active = encoder ?: return null
        encoder = null
        try {
            if (inputSamples == 0L) return null
            val deadline = clock() + CODEC_DEADLINE_MS
            var endQueued = false
            while (!eosSeen) {
                checkDeadline(deadline)
                if (!endQueued) endQueued = active.offerEnd(inputSamples * 1_000_000L / SAMPLE_RATE)
                val progressed = drain(active, deadline)
                if (!progressed) Thread.sleep(1)
            }
            if (frames == 0L) throw IOException("AAC encoder emitted no audio")
            output.flush(); file.fd.sync(); output.close()
            val prefix = AdtsRecovery.completePrefix(partial)
            if (prefix <= 0 || prefix != partial.length()) throw IOException("AAC output framing incomplete")
            if (target.exists() || !partial.renameTo(target)) throw IOException("Cannot publish AAC segment")
            store.syncDirectory(store.directory())
            result = target
            return result
        } finally {
            try { output.close() } finally { active.close() }
        }
    }

    private fun drain(active: Encoder, deadline: Long): Boolean {
        var progressed = false
        while (true) {
            checkDeadline(deadline)
            val packet = active.poll() ?: return progressed
            progressed = true
            if (!packet.config && packet.bytes.isNotEmpty()) {
                val header = AdtsRecovery.header(packet.bytes.size)
                output.write(header); output.write(packet.bytes)
                encodedBytes += header.size + packet.bytes.size
                frames++
            }
            if (packet.eos) { eosSeen = true; return true }
        }
    }

    private fun checkDeadline(deadline: Long) {
        if (clock() >= deadline) throw IOException("AAC encoder timed out; partial retained")
    }

    /** Abandons encoder input but retains all already emitted/flushable frames. */
    override fun close() {
        if (finished) return
        finished = true
        val active = encoder; encoder = null
        try { output.flush(); file.fd.sync() }
        finally { try { output.close() } finally { active?.close() } }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val BIT_RATE = 24000
        private const val CODEC_DEADLINE_MS = 5_000L
    }

    private class PlatformEncoder : Encoder {
        private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        private val info = MediaCodec.BufferInfo()
        private var formatVerified = false
        init {
            try {
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1)
                format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                AppLog.i("Profile", "AAC codec started; codec=${codec.name}; requested_hz=$SAMPLE_RATE; channels=1; bitrate=$BIT_RATE")
            } catch (e: Exception) { codec.release(); throw e }
        }
        override fun offer(samples: ShortArray, offset: Int, count: Int, presentationUs: Long): Int {
            val index = codec.dequeueInputBuffer(0)
            if (index < 0) return 0
            val buffer = codec.getInputBuffer(index) ?: throw IOException("AAC input buffer missing")
            buffer.clear()
            val n = minOf(count, buffer.remaining() / 2)
            if (n == 0) throw IOException("AAC input buffer empty")
            repeat(n) { i ->
                val value = samples[offset + i].toInt()
                buffer.put(value.toByte()); buffer.put((value shr 8).toByte())
            }
            codec.queueInputBuffer(index, 0, n * 2, presentationUs, 0)
            return n
        }
        override fun offerEnd(presentationUs: Long): Boolean {
            val index = codec.dequeueInputBuffer(0)
            if (index < 0) return false
            codec.queueInputBuffer(index, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return true
        }
        override fun poll(): Packet? {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                verifyFormat(codec.outputFormat)
                formatVerified = true
                return Packet(byteArrayOf(), config = true)
            }
            if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) return Packet(byteArrayOf(), config = true)
            if (index < 0) return null
            return try {
                val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (!formatVerified && !config && info.size > 0) throw IOException("AAC output format unavailable")
                val bytes = if (info.size == 0) byteArrayOf() else {
                    val buffer = codec.getOutputBuffer(index) ?: throw IOException("AAC output buffer missing")
                    val copy = buffer.duplicate()
                    if (info.offset < 0 || info.size < 0 || info.offset.toLong() + info.size > copy.capacity())
                        throw IOException("Invalid AAC output buffer range")
                    copy.clear(); copy.position(info.offset); copy.limit(info.offset + info.size)
                    ByteArray(info.size).also { copy.get(it) }
                }
                Packet(bytes, config, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0)
            } finally { codec.releaseOutputBuffer(index, false) }
        }
        private fun verifyFormat(format: MediaFormat) {
            if (format.getInteger(MediaFormat.KEY_SAMPLE_RATE) != SAMPLE_RATE
                || format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != 1)
                throw IOException("AAC encoder changed sample rate/channels")
            var lc = false
            if (format.containsKey(MediaFormat.KEY_AAC_PROFILE)) {
                if (format.getInteger(MediaFormat.KEY_AAC_PROFILE) != MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    throw IOException("AAC encoder changed profile")
                lc = true
            }
            format.getByteBuffer("csd-0")?.duplicate()?.let { config ->
                if (config.remaining() < 2) throw IOException("AAC codec configuration missing")
                val first = config.get().toInt() and 255
                val second = config.get().toInt() and 255
                if ((first shr 3) != 2 || (((first and 7) shl 1) or (second shr 7)) != 8
                    || ((second shr 3) and 15) != 1) throw IOException("Unsupported AAC codec configuration")
                lc = true
            }
            if (!lc) throw IOException("AAC-LC output profile unproven")
            AppLog.i("Profile", "AAC output verified; codec=${codec.name}; sample_hz=$SAMPLE_RATE; channels=1; profile=LC")
        }
        override fun close() { try { codec.stop() } finally { codec.release() } }
    }
}
