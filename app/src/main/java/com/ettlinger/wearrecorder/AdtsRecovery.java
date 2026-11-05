package com.ettlinger.wearrecorder;

import java.io.*;
import java.util.UUID;

/** Strict framing for this app's experimental AAC-LC, 16 kHz, mono ADTS spool.
 * This validates framing, not the decoded audio. Never scans past a damaged header.
 */
public final class AdtsRecovery {
    private AdtsRecovery() { }
    private static final class MalformedAdts extends IOException {
        MalformedAdts(String message) { super(message); }
    }
    public static final int HEADER_BYTES = 7;
    public static final int MAX_PAYLOAD = 8191 - HEADER_BYTES;

    public static byte[] header(int payloadBytes) throws IOException {
        if (payloadBytes <= 0 || payloadBytes > MAX_PAYLOAD) throw new IOException("Invalid AAC frame size");
        int length = payloadBytes + HEADER_BYTES;
        return new byte[]{(byte)0xff, (byte)0xf1, (byte)0x60,
            (byte)(0x40 | (length >> 11)), (byte)(length >> 3),
            (byte)(((length & 7) << 5) | 0x1f), (byte)0xfc};
    }

    private static int frameLength(byte[] h) throws IOException {
        // MPEG-4, layer 0, no CRC; AAC-LC profile 1, sample-rate index 8, mono.
        if ((h[0] & 255) != 255 || (h[1] & 255) != 0xf1
                || (h[2] & 0xfc) != 0x60 || (h[2] & 1) != 0
                || (h[3] & 0xfc) != 0x40 || (h[5] & 0x1f) != 0x1f
                || (h[6] & 255) != 0xfc) throw new MalformedAdts("Invalid AAC ADTS header");
        int length = ((h[3] & 3) << 11) | ((h[4] & 255) << 3) | ((h[5] & 0xe0) >> 5);
        if (length <= HEADER_BYTES) throw new MalformedAdts("Empty AAC ADTS frame");
        return length;
    }

    /** Complete framed prefix only. Invalid full headers are corruption, not a resync hint. */
    public static long completePrefix(File part) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(part, "r")) {
            long size = file.length(), at = 0;
            byte[] header = new byte[HEADER_BYTES];
            while (size - at >= HEADER_BYTES) {
                file.seek(at); file.readFully(header);
                int length = frameLength(header);
                if (size - at < length) break; // incomplete last payload
                at += length;
            }
            return at;
        }
    }

    /** Publishes a valid framed prefix, preserving invalid or empty parts in quarantine. */
    public static File recover(File part, RecordingStore.DirectorySync sync) throws IOException {
        if (!part.isFile() || !part.getName().endsWith(".aac.part")) throw new IOException("Invalid AAC partial path");
        long prefix;
        try { prefix = completePrefix(part); }
        catch (MalformedAdts malformed) { quarantine(part, sync); return null; }
        if (prefix == 0) { quarantine(part, sync); return null; }
        try (RandomAccessFile file = new RandomAccessFile(part, "rw")) {
            file.setLength(prefix); file.getFD().sync();
        }
        File target = new File(part.getParentFile(), part.getName().substring(0, part.getName().length() - 5));
        if (target.exists() || !part.renameTo(target)) throw new IOException("Cannot publish recovered AAC");
        sync.sync(part.getParentFile());
        return target;
    }

    public static int recoverDirectory(File directory, RecordingStore.DirectorySync sync) throws IOException {
        File[] parts = directory.listFiles((d, name) -> name.endsWith(".aac.part"));
        if (parts == null) throw new IOException("Cannot list AAC partials");
        int recovered = 0;
        for (File part : parts) if (recover(part, sync) != null) recovered++;
        return recovered;
    }

    private static void quarantine(File part, RecordingStore.DirectorySync sync) throws IOException {
        File target = new File(part.getPath() + ".damaged-" + UUID.randomUUID());
        if (!part.renameTo(target)) throw new IOException("Cannot preserve damaged AAC");
        sync.sync(part.getParentFile());
    }
}
