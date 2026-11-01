package com.ettlinger.wearrecorder;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Host tests use real files, locks, fsync, and a process killed without cleanup. */
public final class RecordingStoreTest {
    static int passed;
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static RecordingStore store() throws IOException { return new RecordingStore(Files.createTempDirectory("clawhark-test-").toFile()); }
    static byte[] audio() { byte[] b = new byte[32000]; new Random(42).nextBytes(b); return b; }
    static File sealed(RecordingStore s) throws IOException {
        try (RecordingStore.Segment segment = s.begin(1234567890000L)) {
            segment.append(audio(), audio().length); return segment.seal();
        }
    }
    static void test(String name, Checked action) throws Exception { action.run(); passed++; System.out.println("PASS " + name); }
    interface Checked { void run() throws Exception; }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("crash-writer")) {
            RecordingStore s = new RecordingStore(new File(args[1]));
            s.tryLock("capture");
            RecordingStore.Segment segment = s.begin(1234567890000L);
            segment.append(audio(), audio().length); segment.checkpoint();
            Runtime.getRuntime().halt(17); // No finally, no seal, no shutdown hooks.
        }
        test("sealed WAV contains exact PCM", () -> {
            RecordingStore s = store(); File f = sealed(s);
            byte[] bytes = Files.readAllBytes(f.toPath());
            check(bytes.length == 32044, "WAV size");
            check(Arrays.equals(Arrays.copyOfRange(bytes,44,bytes.length),audio()), "PCM changed");
            check(s.pending().size() == 1, "Not pending");
        });
        test("20ms speech frames coalesce and checkpoint flushes exact bytes", () -> {
            RecordingStore s = store(); byte[] frame = Arrays.copyOf(audio(), 640);
            try (RecordingStore.Segment segment = s.begin(1)) {
                for (int i=0;i<12;i++) segment.append(frame, frame.length);
                File part = s.directory().listFiles((d,n)->n.endsWith(".part"))[0];
                check(part.length()==44,"Tiny frames were not buffered");
                check(segment.payloadBytes()==7680,"Buffered bytes not accounted");
                segment.checkpoint(); check(part.length()==7724,"Checkpoint did not flush");
                segment.append(frame,frame.length);
            }
            s.recover(); check(s.pending().get(0).length()==8364,"Close/recovery lost final buffered frame");
        });
        test("abrupt process death recovers exact audio and releases lock", () -> {
            RecordingStore s = store();
            Process child = new ProcessBuilder(System.getProperty("java.home") + "/bin/java", "-cp", System.getProperty("java.class.path"),
                RecordingStoreTest.class.getName(), "crash-writer", s.directory().getPath()).inheritIO().start();
            check(child.waitFor() == 17, "Crash was not simulated");
            try (RecordingStore.Lock lock = s.tryLock("capture")) {
                check(lock != null, "Dead owner retained lock"); check(s.recover() == 1, "Partial not recovered");
            }
            byte[] bytes = Files.readAllBytes(s.pending().get(0).toPath());
            check(Arrays.equals(Arrays.copyOfRange(bytes,44,bytes.length),audio()), "Recovered PCM changed");
            check(s.recover() == 0, "Recovery not idempotent");
        });
        test("torn final half-sample is trimmed, complete PCM survives", () -> {
            RecordingStore s = store();
            try (RecordingStore.Segment part = s.begin(1)) { part.append(audio(),audio().length); part.checkpoint(); }
            File f = s.directory().listFiles((d,n)->n.endsWith(".part"))[0];
            try (FileOutputStream out = new FileOutputStream(f,true)) { out.write(99); }
            s.recover(); check(s.pending().get(0).length()==32044,"Torn sample not repaired");
        });
        test("corrupt and legacy partials are retained, never queued as valid audio", () -> {
            RecordingStore s = store();
            Files.write(new File(s.directory(),"broken.wav.part").toPath(),new byte[]{1,2,3});
            Files.write(new File(s.directory(),"old.m4a.tmp").toPath(),new byte[]{4,5,6});
            s.recover(); check(s.pending().isEmpty(),"Invalid audio queued"); check(s.bytesUsed()==6,"Original bytes lost");
        });
        test("capture and upload locks exclude duplicate owners independently", () -> {
            RecordingStore s = store();
            try (RecordingStore.Lock a=s.tryLock("upload"); RecordingStore.Lock b=s.tryLock("capture")) {
                check(a!=null && b!=null,"Independent owners blocked"); check(s.tryLock("upload")==null,"Duplicate upload allowed");
            }
        });
        test("legacy uploading files return to pending without deletion", () -> {
            RecordingStore s=store(); Files.write(new File(s.directory(),"old.m4a.uploading").toPath(),audio());
            s.recoverLegacyUploads(); check(s.pending().size()==1,"Legacy audio stranded");
        });
        test("cloud identity survives a fresh store instance", () -> {
            RecordingStore s=store(); File f=sealed(s); s.saveDriveId(f,"generated_drive_id");
            RecordingStore fresh=new RecordingStore(s.directory()); check("generated_drive_id".equals(fresh.readDriveId(f)),"Identity not durable");
            try { fresh.saveDriveId(f,"different_id"); throw new AssertionError("Identity replaced"); } catch(IOException expected) { }
        });
        test("wrong cloud checksum, length, or ID cannot delete local audio", () -> {
            RecordingStore s=store(); File f=sealed(s); s.saveDriveId(f,"drive_id"); String md5=RecordingStore.digest(f,"MD5");
            for (int i=0;i<3;i++) {
                try { s.acknowledge(f,i==0?"wrong":"drive_id",f.length()+(i==1?1:0),i==2?"bad":md5);
                    throw new AssertionError("False receipt accepted"); } catch(IOException expected) { }
                check(f.exists(),"Original deleted");
            }
        });
        test("matching cloud receipt authorizes cleanup", () -> {
            RecordingStore s=store(); File f=sealed(s); s.saveDriveId(f,"drive_id");
            s.acknowledge(f,"drive_id",f.length(),RecordingStore.digest(f,"MD5")); check(!f.exists(),"Verified copy not cleaned");
        });
        test("storage limit retains unuploaded originals", () -> {
            RecordingStore s=store(); File f=new File(s.directory(),"huge.wav");
            try(RandomAccessFile file=new RandomAccessFile(f,"rw")) { file.setLength(RecordingStore.MAX_BYTES+1); }
            try { s.begin(1); throw new AssertionError("Exceeded storage budget"); } catch(IOException expected) { }
            check(f.exists() && f.length()>RecordingStore.MAX_BYTES,"Unuploaded original evicted");
        });
        test("account changes cannot adopt another account's recordings", () -> {
            RecordingStore s=store(); s.bindCloudOwner("owner_one"); s.bindCloudOwner("owner_one");
            try { new RecordingStore(s.directory()).bindCloudOwner("owner_two"); throw new AssertionError("Account adopted audio"); } catch(IOException expected) { }
        });
        test("clock collisions cannot overwrite a recording", () -> {
            RecordingStore s=store(); File a=sealed(s), b=sealed(s); check(!a.equals(b) && s.pending().size()==2,"Recording overwritten");
        });
        System.out.println(passed + " host recovery tests passed");
    }
}
