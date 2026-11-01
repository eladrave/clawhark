# Recorder qualification: Pixel Watch 5

Target device reported by owner: Google Pixel Watch 5. Record exact watch size, Wear OS/Android build, free storage, Wi-Fi/LTE state and battery baseline before testing. Published product details do not establish the installed OS build.

## Acceptance sequence

1. **Offline capture:** Leave Drive unlinked. Record ten minutes with screen off. Stop, export the app-private recordings using owner-authorized ADB, and verify WAV playback, sample counts, chronology, and no missing spoken markers across boundaries.
2. **Crash recovery:** During a new segment, use a debug process-kill (not app-data deletion). Open the app again. Verify prior complete files and all fsynced complete PCM samples from the partial remain readable. Check the UI does not claim recording until the microphone supplies data.
3. **Cloud roundtrip:** Configure own Google OAuth project, authorize Drive and record markers. Compare downloaded WAV MD5 and size with the local original before cleanup. Verify a successful response without matching metadata retains the original.
4. **Network interruption:** Record offline, restore connectivity, interrupt an upload, and restore it again. Check the stable Drive ID and exactly one matching cloud object per recording. Pending audio must remain locally until verified.
5. **Microphone interruption:** Exercise calls, assistant microphone use, privacy mute, and permission revocation. Check state changes, recovery or a visible error, and retained files. Interrupted periods must not be mistaken for captured audio.
6. **Reboot:** Reboot with pending audio. Check a resume notification, persisted uploads, and successful repair after opening the app. Capture does not run while powered off or before user-assisted restart.
7. **Storage pressure:** In a controlled test build with a reduced budget, fill the spool. Check explicit stop/error and byte-for-byte preservation of unuploaded files. Never test by filling the owner's entire watch.
8. **Long trial:** Progress from 30 minutes to 2 hours to a full waking day. Measure battery percentage/hour, device temperature, sample continuity, upload backlog, and storage. Test both quiet and conversational conditions. Record the actual results, not estimates from advertised watch battery life.

## Current limits

- Physical watch, real microphone, screen-off behavior, OS process scheduling, Google login, Drive transfer, power-loss durability and all-day battery have not been qualified by host tests.
- PCM/WAV is about 115 MB/hour. A 4 GiB spool holds roughly 37 hours if space permits; the free-space reserve can stop capture earlier. This budget is fixed for the initial build.
- Filesystem sync occurs about once per second of active reads. Sudden power loss may lose recent unsynced bytes; corrupt or unrecoverable partials are kept, not silently deleted.
- Worker scheduling is OS-controlled. Background uploads may be deferred. Metered connections are allowed. Recording is independent of upload progress.
- The app uses private device storage and HTTPS. It does not yet add per-recording application encryption, configurable retention, audio export UI, live transcription, or a phone companion.
- Different Google accounts cannot inherit another account's pending spool. Account migration needs an explicit future design.
- Legacy full `.m4a` files can upload; `.m4a.tmp` files are preserved as `.damaged-*`. This branch does not repair MP4 indexes.
- This change replaces the old recording service. Do not install over an installation with irreplaceable audio without exporting that audio first.

## Safe recovery

Do not clear app data, uninstall, factory reset, or delete partials while audio is pending recovery. Stop recording, export originals using authorized ADB, and preserve sidecars. If free space is low, restore cloud upload rather than deleting unuploaded recordings. If Drive account mismatch is shown, relink the original account.

## Design references

- Google microphone foreground-service restrictions: https://developer.android.com/develop/background-work/services/fgs/service-types
- Drive pre-generated IDs and retry semantics: https://developers.google.com/workspace/drive/api/guides/create-file
- Omi custody/recovery requirements: https://github.com/BasedHardware/omi/blob/main/app/docs/apple-watch-recovery.md


## Version 1.1 device checks

1. Install as an update, retain Drive authorization and pending recordings. Add the Rave AI status tile.
2. Confirm app/tile battery and charging status match the watch. Start/stop capture and verify the tile. Kill/stop the app and verify the cached active status expires conservatively; opening the app is needed after a force-stop.
3. Keep Continuous as the comparison baseline. In Settings choose Speech only, restart capture, and test soft speech, normal speech, distant speakers, noisy rooms, TV/music, short pauses, and speech immediately after Start. Verify audible starts and the trailing two seconds. LISTENING should create no files during sustained non-speech. Compare actual battery drain over equal periods; do not infer all-day battery from fewer files.
4. Speech files should contain pre-speech context (up to one second) and new names with local date/time/UTC offset. Long speech is split, and long silence ends a segment. Detector failure must surface VAD fallback and preserve audio.
5. Enable logging, use Start/Stop/Settings/Sync and wait for battery samples. Inspect Drive/Rave AI/logs for dated plain-text batches with action and battery fields. Verify there are no tokens or audio contents. Test offline queuing, restart recovery, off/on logging, and unmetered-only waiting. Simulated quota/checksum tests cover retention; real-device low-space behavior still needs testing.
6. Disable logging and confirm future log uploads pause. Existing cloud logs are not deleted automatically.

7. Compare equal-length screen-off trials with Immediate uploads, Every 15 minutes, and While charging (not charging during the observation). Keep other settings and network conditions comparable. Gauge percentage measures whole-watch drain, not app attribution. The user observed 76% to 69% during the prior continuous/immediate trial; exact interval and baseline idle drain were not recorded. Use diagnostic timestamps for subsequent comparisons.
