# Rave AI: Pixel Watch recorder

Development fork of [ivar2000/clawhark](https://github.com/ivar2000/clawhark).
First milestone: continuous microphone capture, durable local recordings, and verified Google Drive upload.

## Current development behavior

- Start/Stop on the watch. Recording works without linking Drive or having a network connection.
- Mono PCM16 at 16 kHz, in approximately one-minute WAV files. Continuous mode retains silence. Optional Speech only mode uses local WebRTC VAD, a 1-second pre-buffer and 2-second trailing delay. Detector failure retains audio instead of silently dropping it.
- Audio is written to app-private storage first, with a file sync about every second. Restart repairs interrupted WAV headers and retains complete samples.
- A separate WorkManager queue uploads finalized files over an available network. Normal scheduling is best effort, not an exact timer.
- Each recording gets a persisted, pre-generated Drive file ID. A retry after a lost HTTP response checks that same object instead of creating another copy.
- Cleanup requires a remote metadata read that matches the ID, size, and MD5, followed by a locally synced receipt. An HTTP success response alone cannot delete audio.
- At the 4 GiB spool budget or 200 MiB free-space reserve, recording stops visibly and preserves existing audio. Unuploaded files are never evicted.
- After reboot, queued uploads resume and a notification asks the user to open the app to resume capture. Automatic microphone startup at boot is not promised.

**Development build, not yet qualified on physical Pixel Watch 5 hardware.** No all-day battery claim. Power loss may lose audio since the last successful filesystem sync; storage hardware guarantees still apply. Damaged legacy M4A partials are retained for manual recovery.

WAV costs about **115 MB/hour, 2.76 GB/day** of continuous recording, plus headers. It is intentionally simple for recovery qualification. Compression and encryption beyond device/app storage protection remain future work. Upload permits metered connections by default; Settings offers unmetered-only uploads.

## Rave AI 1.1 controls

- Add **Rave AI status** using the watch's tile picker. It shows recording/listening/stopped status, battery, mode, pending recordings, and a status timestamp. Tap the tile to open the recorder. Version 1.2 refreshes on tile entry, coalesces visible updates, and suppresses routine hidden-tile requests. Cached active status expires to CHECK RECORDER after two minutes.
- The app has a **SETTINGS** button. Continuous mode remains the default. To try **Speech only (beta)**, select it and restart capture. Sensitivity also applies on the next capture start. Quiet speech may be missed; TV speech can trigger recording. The microphone and CPU still listen, so battery savings are not promised.
- Battery appears in both the app and tile, including charging state.
- **Enable logging** is off by default. When on, app actions include battery levels; battery changes and 5-minute samples are recorded while the app/recorder is active. Immutable log batches upload to **Rave AI/logs** with ID/size/checksum verification before local cleanup. Logging has a 20 MiB budget plus the same free-space reserve as recordings, and stops writing at its limit instead of deleting unuploaded logs. Turning logging off pauses new diagnostics and log uploads, preserving queued logs.
- **Upload timing** offers Immediate (the existing default), Every 15 minutes, or While charging. Batching/charging can reduce radio wakes while local files remain protected. **Unmetered uploads only** applies to recordings and logs. **SYNC NOW** bypasses batching but still respects charging/unmetered constraints. None of these options suspends the microphone; actual battery benefit needs measurement.

### Version 1.2 battery comparison build

Version 1.2.0 adds the Rave AI pulse/memory logo and reduces file, diagnostic, tile and upload overhead. See [battery changes and comparison procedure](docs/battery-1.2.md). WAV format, microphone capture, speech detection, upload preferences and verified cleanup remain compatible. Every log line includes the app version; capture summaries report input/stored durations and process CPU time, and upload summaries report elapsed time, verified bytes and HTTP request counts. Actual battery improvement is not yet measured.

### Version 1.3 battery test controls

Use Settings for Baseline, Capture only (manual sync), or Logs only presets. Manual only disables automatic audio/log uploads; SYNC NOW and END TEST & SYNC explicitly upload selected content. Additional choices include 30/60-minute intervals, audio/log content selection, reduced diagnostic detail, and live tile updates. START BATTERY TEST enables logging and marks the current session; it does not start capture. Each log line has a test ID. Upgrade preserves existing settings. See [battery-testing.md](docs/battery-testing.md) for exact controls, retention and a repeatable comparison.
- New recording names include the watch's local date, time, milliseconds, numeric UTC offset, and unique suffix: `RaveAI_2026-11-01_11-42-18-123_-0500_<uuid>.wav`. Existing pending recordings keep their names and upload identities.

WebRTC native release provenance and included licenses are documented in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Omi uses Silero in software and dedicated activity detection on some hardware. No Omi implementation code is copied; a generic watch app cannot assume access to that dedicated hardware.

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
