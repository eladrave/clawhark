# Rave AI cloud setup

Cloud setup verified on 2026-11-01; APK validation updated 2026-11-02.

- Google Cloud project: `eladrave-rave-ai`
- Display name: **Rave AI**
- Project number: `89543816751`
- Google Drive API: enabled
- Administrative gcloud login: verified for the project owner
- OAuth branding: **Rave AI**, external audience, owner support/contact email
- OAuth client: **Rave AI Pixel Watch**, type **TVs and Limited Input devices**
- OAuth publishing status: **In production**
- Configured permission: only `https://www.googleapis.com/auth/drive.file`
- App homepage and privacy information: this repository and [PRIVACY.md](../PRIVACY.md)

The owner approved Google's required User Data Policy agreement. A live device-authorization request using the new client returned HTTP 200 with the required code-flow response fields. This checks the client configuration and endpoint; it does not establish a successful user consent, token refresh, or recording upload.

## Personal APK

The current configured APK is **Rave-AI-1.5.0-watch.apk**, compiled from code revision `c5a927b4c2ec6aeba2b0931a2f9cbc21b4a44ab8` with a private, ignored `app/src/main/assets/oauth_config.json`.

[Download the configured APK](https://chatgpt.com/library?file_id=libfile_1aa618f9a918819188e20c7bf444103d&account_id=personal)

- Android debug build for physical-device qualification, signed with the dedicated **Rave AI Personal Build** key.
- Android unit tests: 68 passed, 0 failures or errors. The 17 host recording-store recovery tests also passed, including buffered PCM checkpoint/close recovery. Coverage includes bounded diagnostic-first batches, cancellation propagation, cutoff-based continuations, explicit test-audio cleanup and busy-store refusal, preset labeling, guarded manual sync and stale grants, selected upload content, reduced diagnostic evidence, paused upload/token behavior, tile visibility/coalescing, disk reservation, diagnostic batching/quota, batched Drive IDs and account changes, plus the existing speech/login/upload protocol tests.
- Debug APK build and lint: passed with 0 errors and 75 warnings.
- APK signature and ZIP alignment: verified.
- Packaged app label: **Rave AI**.
- Packaged OAuth configuration: verified against the newly created client; no user access tokens, refresh tokens, or signing keys are embedded.
- APK SHA-256: `e9678ec773a941d491a63ba866018f2c02b0654106a226c2fe22d6929e0d637c`.

The user installed 1.0.0 and supplied screenshots showing Google web approval succeeded while the watch remained on the code/link screen. Source inspection found that Activity.onStop canceled polling without persisting or resuming the pending grant. Version 1.0.1 persists the pending grant in encrypted preferences and resumes polling on return or Activity/process recreation. It retains server pacing and retries transient failures. Token storage and clearing of the pending grant are synchronous and atomic. Linking does not start the microphone.

Install 1.5.0 **over the existing app**, preserving data and signing identity: `adb install -r Rave-AI-1.5.0-watch.apk`. Its version code is 7. Do not uninstall or clear storage. A working Drive link requires no new consent. The owner confirmed the earlier linking fix and real recording uploads.

The app creates a **Rave AI** folder in the root of that linked account's Drive on its first upload. Audio is retained until a matching cloud ID, size and checksum are verified, unless the owner explicitly confirms local test-audio deletion in Settings. The user reports successful Drive linking and real file uploads following the 1.0.1 fix. Uploaded 1.1 diagnostics now show speech-only capture and real log uploads. Version 1.2 diagnostics show capture and verified uploads, but do not establish a battery improvement. The 1.3 capture-only trial measured approximately 6.47 percentage points/hour of whole-watch drain over 111.35 minutes. Version 1.5 sync measurements, long screen-off continuity, interruptions and battery benefit still need physical-device qualification. See [recorder-qualification.md](recorder-qualification.md).

## Current feature setup and validation

Install 1.5.0 over the existing app. Existing working Drive authorization and recordings are preserved; there is no need to relink a working account. The earlier 1.0.0-to-1.0.1 fresh-code requirement is historical.

- Add **Rave AI status** in the watch's tile picker. Status, battery/charging, current mode, pending recordings and a timestamp are shown. The tile opens the recorder. Cached active status has an explicit two-minute timeline expiry to CHECK RECORDER; updates are best effort.
- Use **SETTINGS** for battery-test presets and BEGIN/END markers, Speech only (beta), sensitivity, opt-in logging and detail, live tile updates, upload timing/content, unmetered-only uploads and SYNC NOW. Recording mode/sensitivity apply on the next capture start. Defaults preserve continuous recording with immediate uploads, allow metered connections, and leave diagnostics off.
- Speech mode uses the pinned WebRTC GMM detector locally with up to one second of buffered context and two seconds of trailing audio. It still keeps the microphone active. Native detector failure falls back to storing audio and is surfaced in status. Soft/distant voices and TV/noise performance need watch qualification; no battery saving claim is made.
- Enabling logs writes dated action/battery batches and uploads them to **Rave AI/logs**. Every action includes battery state; battery changes and best-effort five-minute samples while active or marking an idle test are included. Logs have a 20 MiB local limit, retain unuploaded batches, and use the same stable ID/size/MD5 acknowledgment as recordings. Disabling logging pauses new diagnostics and log uploads.
- Upload timing offers Immediate, Every 15/30/60 minutes, While charging, and Manual only. Upload content can be Audio and logs, Logs only, or Audio only. Excluded files remain local. SYNC NOW bypasses the interval/manual-only choice but preserves content, logging, charging and unmetered constraints. Reconfiguring upload preferences or starting a test invalidates stale manual requests; current requests may finish. No battery reduction is established. See [battery-testing.md](battery-testing.md).
- New recording names include watch-local date/time, milliseconds, numeric UTC offset and a unique suffix. Existing pending names/IDs are preserved.

The user observed a battery drop from 76% to 69% during the prior continuous/immediate recording trial. Exact elapsed time and idle drain were not recorded. This observation does not isolate the app's share of whole-watch power use. Use timestamped diagnostic logs and comparable screen-off trials to compare upload policies, then speech-only mode; battery benefit is not yet measured.

APK validation includes matching the existing signing certificate, package version/label, tile/settings manifest entries, OAuth asset, native ABIs, and included third-party licenses. JNI exports and ARM64 16 KiB ELF alignment were checked. Native libraries are compressed for installer extraction to avoid older build-tool ZIP alignment limits. A host build of the same upstream WebRTC C source detected the supplied speech fixture and rejected 250 digital-silence frames; Android JNI loading and real-world accuracy are still watch checks. See [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) and [recorder-qualification.md](recorder-qualification.md).

## Version 1.2 comparison

This release adds the Rave AI pulse/memory logo, coalesced PCM writes, ten-second reserved storage checks, batched diagnostic syncs, visibility-aware tile refresh, lifecycle-scoped screen polling and finite uploader sessions with batched ID allocation. Every log line carries the app version; capture and upload counters aid comparison. Routine diagnostics can lose up to about 30 seconds since the last sync on hard power loss, while audio retains its approximately one-second sync cadence. WAV, microphone capture, speech sensitivity and existing upload preferences are retained. No measured battery improvement is claimed. See [battery-1.2.md](battery-1.2.md) for the comparison procedure.

## Version 1.3 controlled trials

Runtime presets isolate automatic upload cost without replacing the APK. BEGIN and END markers carry a unique test ID, battery, actual capture mode and selected settings. The Capture only preset stores audio and diagnostics locally until an explicit sync. Reduced logging detail and routine tile updates are independent controls, so subsequent trials can change one setting at a time. START enables logging and marks an existing capture session; END TEST & SYNC ends the marked interval and syncs selected content, while recording continues until STOP. Presets change timing/content only. Existing preferences are preserved on upgrade. See [battery-testing.md](battery-testing.md) for the one-hour comparison procedure.

## Version 1.4 sync recovery and test cleanup

The selected preset remains visible in Settings and the main screen, with confirmation after selection. Custom describes other timing/content combinations. Audio and diagnostics have separate progress text and pending counts, including the last successful diagnostic-sync timestamp. Current/last capture mode uses the persisted recorder status when the service is unbound.

Diagnostic batches are uploaded before recordings. Each worker attempts up to 20 objects and checks a 90-second soft budget between objects. A current network request may exceed that budget. Normal progress schedules a continuation without failure backoff; actual network errors retain retry/backoff. Continuations preserve a file-modification-time cutoff so ongoing recording and upload-generated logs do not grow the same sync indefinitely. Files finalized after that cutoff wait for a future sync. Coroutine cancellation is reported as a pause and rethrown to WorkManager; Android stop reasons are logged where available. Real-device scheduling and pause latency remain to be qualified.

**DELETE PENDING TEST RECORDINGS** requires explicit confirmation and both capture/upload locks. It removes local test audio and matching sidecars while retaining diagnostic logs, Drive account binding/login, settings and cloud copies. Stop recording first. An in-flight upload may need to finish before the cleanup lock is available. No deletion occurs on upgrade. Android Clear storage is unnecessary and would reset authorization/settings too.

Application startup and battery-test boundary events include firmware build/security patch. Install an offered system update between trials with capture stopped, on the charger and Wi-Fi with at least 50% battery, then open the app after restart. Record a fresh unplugged baseline once update activity settles. A firmware change prevents attributing an old/new battery difference solely to the app.

## Version 1.5 sync-session battery evidence

With logging enabled, one logical sync carries an ID through its bounded continuations and retries. It records battery/power/recording observations, verified audio/log counts and bytes, Drive API request counts, worker-running time and known non-worker gaps. Manual pre-start queue time is separate. Durations use monotonic time within one known boot; incomplete attempts and gaps are flagged. These are whole-watch endpoints, without an automatic rate or assignment of battery loss to recording, uploads or OS delays.

After selected files complete, a separate immutable `RaveAI_sync_<session-id>.log` uploads to **Rave AI/logs**, outside the original snapshot cutoff. Frozen bytes and Drive identity survive interrupted acknowledgment. Receipt delivery and retries are excluded from the measured session. The main app distinguishes pending from **Final sync summary verified in Drive**. Manual grants, selected content, charging, unmetered and logging preferences remain authoritative. Pending metadata is bounded and preserved; a full measurement outbox allows ordinary payload sync to continue.

START BATTERY TEST immediately shows green TEST ACTIVE and cannot accidentally replace an active marker. It can mark a stopped/idle trial without starting the microphone; idle sampling is best effort and adds no foreground service or wake lock. END TEST & SYNC links the upload session to the original test ID. Finish any current idle observation before upgrading, then follow [battery-testing.md](battery-testing.md) for matched unplugged idle, capture-only and stopped-upload trials.

Validation: all 68 Android unit tests and 17 host recovery tests passed. New coverage includes session timing, process-death/counter uncertainty, monotonic versus wall time, reboot handling, immutable receipts, duplicate attempts, bounded samples/metadata, orphan enqueue grace/reconciliation, receipt identity restoration after ACK, and validated metadata paths. Build/lint completed with 0 errors and 75 warnings. Signature and ZIP alignment verified; the signing certificate, private OAuth asset and all four native library payloads match 1.4. APK size: 14,725,784 bytes. Real-watch scheduling/recovery/UI qualification remains pending, and no battery saving is established by this release.

## Private configuration and future updates

The canonical private recovery archive is **Rave AI Build Credentials.zip** in the owner's private Codex operational-knowledge folder, Drive file ID `1Ta3gTrPco_w625VV86PH9WveXf5FyFCm`. It contains the native app client configuration and dedicated PKCS12 signing key with its password and recovery instructions. Owner-only access was verified. Do not commit or publish it. Administrative gcloud credentials and user OAuth tokens are not included.

Always reuse that signing key for updates. Preserve application ID `ai.etti.clawhark` and internal storage identifiers while pending recordings exist. Do not uninstall or clear app storage to resolve an update problem without first recovering unsynced audio.

The repository remains a fork of `ivar2000/clawhark` at `eladrave/clawhark` so the upstream relationship and existing review links remain available.
