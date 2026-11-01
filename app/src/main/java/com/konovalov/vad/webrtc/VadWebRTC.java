package com.konovalov.vad.webrtc;

/** Small Java adapter for the pinned android-vad 2.0.10 native ABI. See THIRD_PARTY_NOTICES.md. */
public final class VadWebRTC implements AutoCloseable {
    static { System.loadLibrary("vad_jni"); }
    private long handle = -1;
    public VadWebRTC(int mode) {
        if (mode < 0 || mode > 3) throw new IllegalArgumentException("Invalid VAD mode");
        // Android may tag the top byte of native pointers; valid handles can be negative.
        handle = nativeInit();
        if ((handle == 0 || handle == -1)) throw new IllegalStateException("Cannot initialize speech detector");
        if (!nativeSetMode(handle, mode)) { close(); throw new IllegalStateException("Cannot set VAD mode"); }
    }
    public boolean isSpeech(short[] frame) {
        if ((handle == 0 || handle == -1) || frame.length != 320) throw new IllegalStateException("VAD needs 20 ms at 16 kHz");
        return nativeIsSpeech(handle, 16000, 320, frame);
    }
    @Override public void close() { if (handle != 0 && handle != -1) { nativeDestroy(handle); handle = -1; } }
    private native long nativeInit();
    private native boolean nativeSetMode(long handle, int mode);
    private native boolean nativeIsSpeech(long handle, int sampleRate, int frameSize, short[] frame);
    private native void nativeDestroy(long handle);
}
