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
        test("single-pass receipt digests match both established algorithms", () -> {
            RecordingStore s=store(); File f=sealed(s);
            RecordingStore.ReceiptDigests combined=RecordingStore.receiptDigests(f);
            check(combined.md5.equals(RecordingStore.digest(f,"MD5")),"MD5 changed");
            check(combined.sha256.equals(RecordingStore.digest(f,"SHA-256")),"SHA256 changed");
        });
        test("efficient ACK still rejects mismatched identity size and hash", () -> {
            RecordingStore s=store(); File f=sealed(s); s.saveDriveId(f,"drive_id"); String md5=RecordingStore.digest(f,"MD5");
            for(int i=0;i<3;i++) {
                try { s.acknowledge(f,i==0?"wrong":"drive_id",f.length()+(i==1?1:0),i==2?"bad":md5,true);
                    throw new AssertionError("False receipt accepted"); } catch(IOException expected) { }
                check(f.exists(),"Original deleted");
            }
            s.acknowledge(f,"drive_id",f.length(),md5,true); check(!f.exists(),"Efficient verified ACK did not clean");
        });
        test("AAC prefix recovery joins the existing pending and verified cleanup path", () -> {
            RecordingStore s = store();
            byte[] header = AdtsRecovery.header(25), payload = new byte[25];
            File part = new File(s.directory(), "RaveAI_test.aac.part");
            try (OutputStream out = new FileOutputStream(part)) {
                out.write(header); out.write(payload); out.write(header); out.write(new byte[3]);
            }
            check(s.pending().isEmpty(), "Partial AAC was uploadable");
            check(s.recover() == 1, "AAC prefix not recovered through store");
            File f = s.pending().get(0);
            check(f.getName().endsWith(".aac") && f.length() == 32, "AAC prefix changed");
            s.saveDriveId(f, "aac_drive_id");
            s.acknowledge(f, "aac_drive_id", f.length(), RecordingStore.digest(f, "MD5"), true);
            check(!f.exists() && s.pending().isEmpty(), "Verified AAC not cleaned");
        });
        test("explicit cleanup includes AAC parts but preserves diagnostic logs", () -> {
            RecordingStore s = store();
            Files.write(new File(s.directory(), "trial.aac").toPath(), new byte[]{1});
            Files.write(new File(s.directory(), "interrupted.aac.part").toPath(), new byte[]{2});
            Files.write(new File(s.directory(), "failed.aac.part.damaged-test").toPath(), new byte[]{3});
            File log = new File(s.directory(), "diagnostic.log");
            Files.write(log.toPath(), new byte[]{4});
            check(s.deletePendingTestRecordings() == 3, "AAC cleanup count");
            check(log.exists(), "AAC cleanup removed diagnostics");
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
        test("explicit test cleanup deletes only audio and its metadata", () -> {
            RecordingStore s=store(); File f=sealed(s); s.saveDriveId(f,"test_id"); s.bindCloudOwner("owner_one");
            Files.write(new File(s.directory(),"test.log").toPath(),new byte[]{1,2,3});
            Files.write(new File(s.directory(),"interrupted.wav.part").toPath(),new byte[]{4,5,6});
            check(s.deletePendingTestRecordings()==2,"Audio cleanup count");
            check(!f.exists() && !new File(s.directory(),"."+f.getName()+".drive-id").exists(),"Audio metadata retained");
            check(new File(s.directory(),"test.log").exists(),"Diagnostic log deleted");
            s.bindCloudOwner("owner_one");
            try { s.bindCloudOwner("owner_two"); throw new AssertionError("Cloud ownership deleted"); } catch(IOException expected) { }
            check(s.deletePendingTestRecordings()==0,"Repeated cleanup not idempotent");
        });
        test("test cleanup refuses active capture or upload", () -> {
            RecordingStore s=store(); File f=sealed(s);
            for(String name:new String[]{"capture","upload"}) try(RecordingStore.Lock lock=s.tryLock(name)) {
                try { s.deletePendingTestRecordings(); throw new AssertionError("Deleted busy spool"); } catch(IOException expected) { }
                check(f.exists(),"Busy recording deleted");
            }
        });
        test("frozen sync receipt is immutable and can restore identity after ACK", () -> {
            RecordingStore s=store(); String name="RaveAI_sync_"+UUID.randomUUID()+".log";
            File f=s.publishSyncReceipt(name,"frozen summary\n");
            s.saveDriveId(f,"stable_summary_id");
            check(s.publishSyncReceipt(name,"frozen summary\n").equals(f),"Retry changed receipt");
            try { s.publishSyncReceipt(name,"changed"); throw new AssertionError("Receipt mutated"); } catch(IOException expected) { }
            long size=f.length(); String md5=RecordingStore.digest(f,"MD5");
            s.acknowledge(f,"stable_summary_id",size,md5);
            File recreated=s.publishSyncReceipt(name,"frozen summary\n"); s.saveDriveId(recreated,"stable_summary_id");
            check(recreated.length()==size && RecordingStore.digest(recreated,"MD5").equals(md5),"Receipt recovery changed bytes");
            check("stable_summary_id".equals(new RecordingStore(s.directory()).readDriveId(recreated)),"Receipt ID lost");
        });
        test("measurement state bounds and paths are validated with durable deletion", () -> {
            RecordingStore s=store(); String name="sync_"+UUID.randomUUID()+".json";
            s.writeMeasurementState(name,"{}"); s.deleteMeasurementState(name);
            check(!new File(s.directory(),name).exists(),"Delivered metadata retained");
            for(String bad:new String[]{"../escape.json","bad.json"}) {
                try { s.writeMeasurementState(bad,"{}"); throw new AssertionError("Invalid metadata path"); } catch(IOException expected) { }
                try { s.deleteMeasurementState(bad); throw new AssertionError("Invalid deletion path"); } catch(IOException expected) { }
                try { s.publishSyncReceipt(bad,"{}"); throw new AssertionError("Invalid receipt path"); } catch(IOException expected) { }
            }
            try { s.writeMeasurementState(name,"x".repeat(65537)); throw new AssertionError("Unbounded metadata"); } catch(IOException expected) { }
        });
        System.out.println(passed + " host recovery tests passed");
    }
}
