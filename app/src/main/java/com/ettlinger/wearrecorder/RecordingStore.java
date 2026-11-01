package com.ettlinger.wearrecorder;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.*;
import java.text.SimpleDateFormat;

/** Durable PCM spool. No Android dependencies; exercised by the host recovery tests. */
public final class RecordingStore {
    public static final int SAMPLE_RATE = 16000;
    public static final long MAX_BYTES = 4L * 1024 * 1024 * 1024;
    public static final long FREE_RESERVE = 200L * 1024 * 1024;
    private final File dir;
    private final DirectorySync directorySync;
    @FunctionalInterface public interface DirectorySync { void sync(File directory) throws IOException; }

    public RecordingStore(File dir) throws IOException { this(dir, RecordingStore::hostDirectorySync); }

    public RecordingStore(File dir, DirectorySync directorySync) throws IOException {
        this.dir = dir;
        this.directorySync = directorySync;
        if (!dir.isDirectory()) {
            if (!dir.mkdirs()) throw new IOException("Cannot create recording directory");
            if (dir.getParentFile() != null) directorySync.sync(dir.getParentFile());
        }
    }

    public File directory() { return dir; }

    public long bytesUsed() {
        long bytes = 0;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) {
            if (f.isFile() && !f.getName().startsWith(".")) bytes += f.length();
        }
        return bytes;
    }

    public void checkSpace(long additional) throws IOException {
        if (bytesUsed() + additional > MAX_BYTES || dir.getUsableSpace() < FREE_RESERVE + additional) {
            throw new IOException("Storage full: saved recordings retained. Upload files before resuming.");
        }
    }

    public List<File> pending() {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".wav") || n.endsWith(".m4a"));
        List<File> result = files == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(files));
        result.sort(Comparator.comparing(File::getName));
        return result;
    }

    public List<File> pendingLogs() {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".log"));
        List<File> result = files == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(files));
        result.sort(Comparator.comparing(File::getName));
        return result;
    }

    /** Explicit user discard only. Excludes diagnostics, cloud ownership, preferences and credentials. */
    public int deletePendingTestRecordings() throws IOException {
        try (Lock capture = tryLock("capture")) {
            if (capture == null) throw new IOException("Stop recording before deleting test recordings");
            try (Lock upload = tryLock("upload")) {
                if (upload == null) throw new IOException("Sync is still stopping; retry deletion shortly");
                File[] files = dir.listFiles((d, n) -> n.endsWith(".wav") || n.endsWith(".m4a")
                    || n.endsWith(".wav.part") || n.endsWith(".m4a.tmp") || n.endsWith(".m4a.uploading")
                    || n.contains(".wav.part.damaged-") || n.contains(".m4a.tmp.damaged-"));
                if (files == null) throw new IOException("Cannot list test recordings");
                int deleted = 0;
                for (File f : files) {
                    if (!f.isFile() || !f.delete()) throw new IOException("Cannot delete test recording");
                    deleted++;
                    for (String suffix : new String[]{".drive-id", ".drive-id.new", ".receipt", ".receipt.new"}) {
                        File metadata = new File(dir, "." + f.getName() + suffix);
                        if (metadata.exists() && !metadata.delete()) throw new IOException("Cannot delete test metadata");
                    }
                }
                syncDirectory(dir);
                return deleted;
            }
        }
    }

    public File sealLog(File part) throws IOException {
        if (!dir.equals(part.getParentFile()) || !part.getName().endsWith(".log.part")) throw new IOException("Invalid log batch");
        try (RandomAccessFile f = new RandomAccessFile(part, "rw")) { f.getFD().sync(); }
        File sealed = new File(dir, part.getName().substring(0, part.getName().length() - 5));
        if (sealed.exists() || !part.renameTo(sealed)) throw new IOException("Cannot seal diagnostic log");
        syncDirectory(dir);
        return sealed;
    }

    public void recoverLogs() throws IOException {
        File[] parts = dir.listFiles((d, n) -> n.endsWith(".log.part"));
        if (parts == null) throw new IOException("Cannot list logs");
        for (File part : parts) sealLog(part);
    }

    public Segment begin(long startedAtMillis) throws IOException {
        checkSpace(44);
        String date = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss-SSS_Z", Locale.ROOT).format(new Date(startedAtMillis));
        String name = "RaveAI_" + date + "_" + UUID.randomUUID() + ".wav";
        return new Segment(new File(dir, name));
    }

    /** Call only while holding the capture lock, before opening a new segment. */
    public int recover() throws IOException {
        int recovered = 0;
        File[] parts = dir.listFiles((d, n) -> n.endsWith(".wav.part"));
        if (parts == null) throw new IOException("Cannot list recordings");
        for (File part : parts) {
            try (RandomAccessFile file = new RandomAccessFile(part, "rw")) {
                if (!validHeader(file)) {
                    quarantine(part);
                    continue;
                }
                long payload = (file.length() - 44) & ~1L; // discard a torn half-sample only
                if (payload > 0xffffffffL - 36) { quarantine(part); continue; }
                file.setLength(44 + payload);
                header(file, payload);
                file.getFD().sync();
            }
            File sealed = new File(dir, part.getName().replaceFirst("\\.part$", ""));
            if (sealed.exists() || !part.renameTo(sealed)) throw new IOException("Cannot publish recovered segment");
            syncDirectory(dir);
            recovered++;
        }
        // A legacy M4A may lack its MP4 index. Preserve it for manual recovery, never upload as valid audio.
        File[] legacy = dir.listFiles((d, n) -> n.endsWith(".m4a.tmp"));
        if (legacy != null) for (File f : legacy) quarantine(f);
        return recovered;
    }

    private void quarantine(File file) throws IOException {
        File target = new File(dir, file.getName() + ".damaged-" + UUID.randomUUID());
        if (!file.renameTo(target)) throw new IOException("Cannot preserve damaged recording");
        syncDirectory(dir);
    }

    /** Worker lock makes recovery safe; no age-based ownership guessing. */
    public void recoverLegacyUploads() throws IOException {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".m4a.uploading"));
        if (files != null) for (File f : files) {
            File original = new File(dir, f.getName().replaceFirst("\\.uploading$", ""));
            if (original.exists() || !f.renameTo(original)) throw new IOException("Legacy upload needs recovery");
        }
    }

    public Lock tryLock(String name) throws IOException {
        if (!name.equals("capture") && !name.equals("upload")) throw new IllegalArgumentException("Unknown lock");
        RandomAccessFile file = new RandomAccessFile(new File(dir, "." + name + ".lock"), "rw");
        try {
            FileLock lock = file.getChannel().tryLock();
            if (lock != null) return new Lock(file, lock);
        } catch (OverlappingFileLockException ignored) {
        } catch (IOException e) { file.close(); throw e; }
        file.close();
        return null;
    }

    public static final class Lock implements Closeable {
        private final RandomAccessFile file;
        private final FileLock lock;
        private Lock(RandomAccessFile file, FileLock lock) { this.file = file; this.lock = lock; }
        public void close() throws IOException { try { lock.release(); } finally { file.close(); } }
    }

    public final class Segment implements Closeable {
        private final File target;
        private final File partial;
        private final RandomAccessFile file;
        private final byte[] writeBuffer = new byte[8192];
        private int buffered;
        private long bytes;
        private boolean closed;
        private Segment(File target) throws IOException {
            this.target = target;
            partial = new File(dir, target.getName() + ".part");
            file = new RandomAccessFile(partial, "rw");
            try { header(file, 0); file.getFD().sync(); syncDirectory(dir); }
            catch (IOException e) { file.close(); throw e; }
        }
        public long payloadBytes() { return bytes; }
        public void append(byte[] data, int length) throws IOException {
            if (closed) throw new IOException("Segment is closed");
            if (length < 0 || length > data.length || (length & 1) != 0) throw new IllegalArgumentException("PCM16 alignment");
            // Space is checked by the capture loop at each durability checkpoint.
            int offset = 0;
            while (offset < length) {
                int count = Math.min(length - offset, writeBuffer.length - buffered);
                System.arraycopy(data, offset, writeBuffer, buffered, count);
                buffered += count;
                offset += count;
                if (buffered == writeBuffer.length) flushBuffered();
            }
            bytes += length;
        }
        private void flushBuffered() throws IOException {
            if (buffered == 0) return;
            file.write(writeBuffer, 0, buffered);
            buffered = 0;
        }
        public void checkpoint() throws IOException {
            if (closed) throw new IOException("Segment is closed");
            flushBuffered();
            file.getFD().sync();
        }
        public File seal() throws IOException {
            if (closed) throw new IOException("Segment is closed");
            try { flushBuffered(); header(file, bytes); file.getFD().sync(); }
            finally { closed = true; file.close(); }
            if (target.exists() || !partial.renameTo(target)) throw new IOException("Cannot publish audio segment");
            syncDirectory(dir);
            return target;
        }
        /** Close without deleting. Startup repairs the header and publishes all complete samples. */
        public void close() throws IOException {
            if (!closed) {
                try { flushBuffered(); }
                finally { closed = true; file.close(); }
            }
        }
    }

    private static boolean validHeader(RandomAccessFile file) throws IOException {
        if (file.length() < 44) return false;
        byte[] actual = new byte[44]; file.seek(0); file.readFully(actual);
        byte[] signature = new byte[] {
            'R','I','F','F',0,0,0,0,'W','A','V','E','f','m','t',' ',
            16,0,0,0,1,0,1,0,(byte)0x80,0x3e,0,0,0,0x7d,0,0,2,0,16,0,'d','a','t','a',0,0,0,0
        };
        for (int i=0;i<44;i++) {
            if ((i>=4 && i<8) || i>=40) continue; // sizes may be stale after a crash
            if (actual[i] != signature[i]) return false;
        }
        return true;
    }

    private static void header(RandomAccessFile f, long bytes) throws IOException {
        f.seek(0); f.writeBytes("RIFF"); le32(f, bytes + 36); f.writeBytes("WAVEfmt ");
        le32(f, 16); le16(f, 1); le16(f, 1); le32(f, SAMPLE_RATE); le32(f, SAMPLE_RATE * 2);
        le16(f, 2); le16(f, 16); f.writeBytes("data"); le32(f, bytes);
    }
    private static void le16(RandomAccessFile f, int n) throws IOException { f.write(n); f.write(n >>> 8); }
    private static void le32(RandomAccessFile f, long n) throws IOException { for (int i = 0; i < 4; i++) f.write((int)(n >>> (8 * i))); }

    public static String digest(File file, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[32768]; int n;
                while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IOException(e); }
    }

    public String readDriveId(File audio) throws IOException {
        File sidecar = new File(dir, "." + audio.getName() + ".drive-id");
        if (!sidecar.exists()) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(sidecar))) {
            String id = reader.readLine();
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,200}")) throw new IOException("Invalid saved Drive ID");
            return id;
        }
    }

    public void saveDriveId(File audio, String id) throws IOException {
        if (!id.matches("[A-Za-z0-9_-]{1,200}")) throw new IOException("Invalid Drive ID");
        String previous = readDriveId(audio);
        if (previous != null && !previous.equals(id)) throw new IOException("Cannot replace recording identity");
        durableText(new File(dir, "." + audio.getName() + ".drive-id"), id);
    }

    /** Only a verified remote copy may authorize local cleanup. Receipt is synced before deletion. */
    public void acknowledge(File audio, String remoteId, long remoteSize, String remoteMd5) throws IOException {
        if (!audio.isFile() || !remoteId.equals(readDriveId(audio)) || audio.length() != remoteSize
                || !digest(audio, "MD5").equalsIgnoreCase(remoteMd5)) throw new IOException("Cloud verification mismatch");
        String sha256 = digest(audio, "SHA-256");
        durableText(new File(dir, "." + audio.getName() + ".receipt"),
            remoteId + "\n" + remoteSize + "\n" + remoteMd5 + "\n" + sha256 + "\n");
        if (!audio.delete()) throw new IOException("Cloud verified; local cleanup will retry");
        syncDirectory(dir);
        // No payload remains. Remove the per-file metadata to bound spool growth.
        new File(dir, "." + audio.getName() + ".drive-id").delete();
        new File(dir, "." + audio.getName() + ".receipt").delete();
    }

    private void durableText(File target, String text) throws IOException {
        File tmp = new File(target.getPath() + ".new");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
        }
        if (!tmp.renameTo(target)) throw new IOException("Cannot persist recording metadata");
        syncDirectory(target.getParentFile());
    }
    private void syncDirectory(File directory) throws IOException { directorySync.sync(directory); }

    private static void hostDirectorySync(File directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory.toPath(), StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    public void bindCloudOwner(String owner) throws IOException {
        if (!owner.matches("[A-Za-z0-9_-]{1,200}")) throw new IOException("Invalid cloud account identity");
        File identity = new File(dir, ".drive-owner");
        if (identity.exists()) {
            try (BufferedReader in = new BufferedReader(new FileReader(identity))) {
                if (!owner.equals(in.readLine())) throw new IOException("Different Drive account. Original audio retained.");
            }
        } else durableText(identity, owner);
    }

}
