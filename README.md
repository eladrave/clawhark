# Rave AI: Pixel Watch recorder

Development fork of [ivar2000/clawhark](https://github.com/ivar2000/clawhark).
First milestone: continuous microphone capture, durable local recordings, and verified Google Drive upload.

## Current development behavior

- Start/Stop on the watch. Recording works without linking Drive or having a network connection.
- Mono PCM16 at 16 kHz, in approximately one-minute WAV files. Silence is retained; no amplitude gate discards quiet speech.
- Audio is written to app-private storage first, with a file sync about every second. Restart repairs interrupted WAV headers and retains complete samples.
- A separate WorkManager queue uploads finalized files over an available network. Normal scheduling is best effort, not an exact timer.
- Each recording gets a persisted, pre-generated Drive file ID. A retry after a lost HTTP response checks that same object instead of creating another copy.
- Cleanup requires a remote metadata read that matches the ID, size, and MD5, followed by a locally synced receipt. An HTTP success response alone cannot delete audio.
- At the 4 GiB spool budget or 200 MiB free-space reserve, recording stops visibly and preserves existing audio. Unuploaded files are never evicted.
- After reboot, queued uploads resume and a notification asks the user to open the app to resume capture. Automatic microphone startup at boot is not promised.

**Development build, not yet qualified on physical Pixel Watch 5 hardware.** No all-day battery claim. Power loss may lose audio since the last successful filesystem sync; storage hardware guarantees still apply. Damaged legacy M4A partials are retained for manual recovery.

WAV costs about **115 MB/hour, 2.76 GB/day** of continuous recording, plus headers. It is intentionally simple for recovery qualification. Compression, Wi-Fi-only upload controls, and encryption beyond device/app storage protection are future work. Upload currently permits metered connections and can consume substantial mobile data.

## Build and tests

Requirements: JDK 17 (including `jdk.compiler`), Android SDK 34, Gradle 8.5.

```bash
git clone https://github.com/eladrave/clawhark.git
cd clawhark
git checkout codex/recorder-foundation
bash scripts/test-recording-store.sh
gradle --no-daemon testDebugUnitTest assembleDebug lintDebug
```

CI validates the wrapper JAR against Gradle’s published checksums before invoking it directly with Java to obtain Gradle 8.5. It does not execute the inherited wrapper shell script. The inherited `openclaw/Software-3.0.zip` and README download redirects have been removed from this development branch. No files from that archive are needed or executed.

The host tests exercise real filesystem writes, locks, an abruptly killed writer process, partial recovery, cloud receipts, storage pressure, and account isolation. Android unit tests simulate ambiguous HTTP responses, retries, conflicts, mismatched copies, and account mismatch.

## Google Drive setup

Recording works before cloud setup. For uploads:

1. Google Cloud project `eladrave-rave-ai` (display name **Rave AI**) has the Google Drive API enabled. For a separate installation, enable the API in your own project.
2. Configure an OAuth client for TVs and Limited Input devices with `drive.file` scope. Testing-mode consent must include your Google account.
3. Copy `app/src/main/assets/oauth_config.json.example` to `oauth_config.json` in that directory and enter your client configuration. Do not commit it. Rebuild for your own installation.
4. On watch, select Link Drive and authorize the displayed code at `google.com/device`.
5. Record a short session; verify its WAV in the app-created `Rave AI` Drive folder before any extended trial.

The personal Rave AI OAuth client and configured test APK are ready; see [docs/google-cloud-setup.md](docs/google-cloud-setup.md) for the download, verified setup, and private signing recovery location. A real watch recording/upload roundtrip has not yet been validated. OAuth tokens use the inherited encrypted preferences. The spool binds to the first linked Drive account's stable permission ID; a different account cannot adopt pending audio. A multi-account migration/export UI is not implemented.

## Device qualification

See [docs/recorder-qualification.md](docs/recorder-qualification.md) for staged tests, known limits, and recovery steps. Installation requires the owner's watch and wireless debugging, performed only as a separate device-testing step.

MIT license. Omi's [watch recovery design](https://github.com/BasedHardware/omi/blob/main/app/docs/apple-watch-recovery.md) informed the durability and acknowledgment requirements. No Omi implementation code is copied here.
