package com.ettlinger.wearrecorder

import java.util.ArrayDeque

/** Speech-only storage, not microphone duty cycling. Unknown detector results fail open. */
class SpeechGate(
    private val classify: (ShortArray) -> Boolean,
    private val emit: (ShortArray, Int, Long) -> Unit,
    private val end: () -> Unit,
    private val state: (Boolean, Boolean) -> Unit,
    private val preFrames: Int = 50,
    private val tailFrames: Int = 100,
    private val onsetFrames: Int = 2,
    private val reuseBuffers: Boolean = false
) {
    private class Frame(val samples: ShortArray = ShortArray(320), var at: Long = 0L)
    private val before = ArrayDeque<Frame>()
    private val free = ArrayDeque<Frame>()
    private var frame = Frame()
    private var count = 0
    private var frameAt = 0L
    private var active = false
    private var positive = 0
    private var silence = 0
    var failedOpen = false
        private set

    init { require(preFrames >= onsetFrames && tailFrames > 0 && onsetFrames > 0) }
    fun accept(samples: ShortArray, length: Int, startedAt: Long) {
        require(length in 0..samples.size)
        for (i in 0 until length) {
            if (count == 0) frameAt = startedAt + i * 1000L / 16000
            frame.samples[count++] = samples[i]
            if (count == 320) {
                frame.at = frameAt
                process(frame)
                frame = if (reuseBuffers && free.isNotEmpty()) free.removeFirst() else Frame()
                count = 0
            }
        }
    }
    private fun process(current: Frame) {
        val speech = if (failedOpen) true else try { classify(current.samples) }
            catch (_: Exception) { failedOpen = true; true }
            catch (_: LinkageError) { failedOpen = true; true }
        if (!active) {
            positive = if (speech) positive + 1 else 0
            if (failedOpen || positive >= onsetFrames) {
                active = true; silence = 0
                state(true, failedOpen)
                while (before.isNotEmpty()) emitAndRelease(before.removeFirst())
                emitAndRelease(current)
            } else {
                before.addLast(current)
                while (before.size > preFrames) release(before.removeFirst())
            }
        } else {
            emitAndRelease(current)
            silence = if (speech) 0 else silence + 1
            if (silence >= tailFrames) {
                end(); active = false; positive = 0; silence = 0
                state(false, failedOpen)
            }
        }
    }
    // Both callbacks consume PCM synchronously. Neither may retain a pooled array.
    private fun emitAndRelease(current: Frame) {
        emit(current.samples, 320, current.at)
        release(current)
    }
    private fun release(current: Frame) {
        if (reuseBuffers) free.addLast(current)
    }
    fun finish() {
        if (active && count > 0) emit(frame.samples, count, frameAt)
        if (active) end()
        while (before.isNotEmpty()) release(before.removeFirst())
        count = 0; active = false
    }
}
