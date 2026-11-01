# Rave AI cloud setup

Verified on 2026-11-01.

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

The current configured APK is **Rave-AI-1.2.0-watch.apk**, compiled from code revision `6fdf06fd48c49d56806ca0ec5c818d10eb915d76` with a private, ignored `app/src/main/assets/oauth_config.json`.

[Download the configured APK](https://chatgpt.com/library?file_id=libfile_40dc20867ee081919668f9ebe6bd9d2c&account_id=personal)

- Android debug build for physical-device qualification, signed with the dedicated **Rave AI Personal Build** key.
- Android unit tests: 39 passed, 0 failures or errors. The 13 host recording-store recovery tests also passed, including buffered PCM checkpoint/close recovery. Coverage includes tile visibility/coalescing, disk reservation, diagnostic batching/quota, batched Drive IDs and account changes, plus the existing speech/login/upload protocol tests.
- Debug APK build and lint: passed.
- APK signature and ZIP alignment: verified.
- Packaged app label: **Rave AI**.
- Packaged OAuth configuration: verified against the newly created client; no user access tokens, refresh tokens, or signing keys are embedded.
- APK SHA-256: `67fea40acef40e1301c67aa36bc39ea2eba543ff6cf22fd22a23a2dac1c7ba7d`.

The user installed 1.0.0 and supplied screenshots showing Google web approval succeeded while the watch remained on the code/link screen. Source inspection found that Activity.onStop canceled polling without persisting or resuming the pending grant. Version 1.0.1 persists the pending grant in encrypted preferences and resumes polling on return or Activity/process recreation. It retains server pacing and retries transient failures. Token storage and clearing of the pending grant are synchronous and atomic. Linking does not start the microphone.

Install 1.2.0 **over the existing app**, preserving data and signing identity: `adb install -r Rave-AI-1.2.0-watch.apk`. Its version code is 4. Do not uninstall or clear storage. A working Drive link requires no new consent. The owner confirmed the earlier linking fix and real recording uploads.

The app creates a **Rave AI** folder in the root of that linked account's Drive on its first upload. Audio remains local until a matching cloud ID, size, and checksum are verified. The user reports successful Drive linking and real file uploads following the 1.0.1 fix. Uploaded 1.1 diagnostics now show speech-only capture and real log uploads. The 1.2 changes, long screen-off continuity, interruptions and battery savings still need physical-device qualification. See [recorder-qualification.md](recorder-qualification.md).

## Current feature setup and validation

Install 1.2.0 over the existing app. Existing working Drive authorization and recordings are preserved; there is no need to relink a working account. The earlier 1.0.0-to-1.0.1 fresh-code requirement is historical.

- Add **Rave AI status** in the watch's tile picker. Status, battery/charging, current mode, pending recordings and a timestamp are shown. The tile opens the recorder. Cached active status has an explicit two-minute timeline expiry to CHECK RECORDER; updates are best effort.
- Use **SETTINGS** for Speech only (beta), sensitivity, opt-in logging, upload timing, unmetered-only uploads and SYNC NOW. Recording mode/sensitivity apply on the next capture start. Defaults preserve continuous recording with immediate uploads, allow metered connections, and leave diagnostics off.
- Speech mode uses the pinned WebRTC GMM detector locally with up to one second of buffered context and two seconds of trailing audio. It still keeps the microphone active. Native detector failure falls back to storing audio and is surfaced in status. Soft/distant voices and TV/noise performance need watch qualification; no battery saving claim is made.
- Enabling logs writes dated action/battery batches and uploads them to **Rave AI/logs**. Every action includes battery state; battery changes and five-minute active samples are included. Logs have a 20 MiB local limit, retain unuploaded batches, and use the same stable ID/size/MD5 acknowledgment as recordings. Disabling logging pauses new diagnostics and log uploads.
- Upload timing offers Immediate, Every 15 minutes, or While charging. Batched mode skips per-segment upload kicks and relies on the periodic queue; SYNC NOW bypasses batching but preserves charging/unmetered constraints. Charging mode keeps the network queue constrained until charging. Reconfiguring upload preferences cancels the old immediate pass, preserving stable file IDs and verified cleanup. These modes can reduce radio wakes but do not suspend microphone capture.
- New recording names include watch-local date/time, milliseconds, numeric UTC offset and a unique suffix. Existing pending names/IDs are preserved.

The user observed a battery drop from 76% to 69% during the prior continuous/immediate recording trial. Exact elapsed time and idle drain were not recorded. This observation does not isolate the app's share of whole-watch power use. Use timestamped diagnostic logs and comparable screen-off trials to compare upload policies, then speech-only mode; battery benefit is not yet measured.

APK validation includes matching the existing signing certificate, package version/label, tile/settings manifest entries, OAuth asset, native ABIs, and included third-party licenses. JNI exports and ARM64 16 KiB ELF alignment were checked. Native libraries are compressed for installer extraction to avoid older build-tool ZIP alignment limits. A host build of the same upstream WebRTC C source detected the supplied speech fixture and rejected 250 digital-silence frames; Android JNI loading and real-world accuracy are still watch checks. See [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) and [recorder-qualification.md](recorder-qualification.md).

## Version 1.2 comparison

This release adds the Rave AI pulse/memory logo, coalesced PCM writes, ten-second reserved storage checks, batched diagnostic syncs, visibility-aware tile refresh, lifecycle-scoped screen polling and finite uploader sessions with batched ID allocation. Every log line carries the app version; capture and upload counters aid comparison. Routine diagnostics can lose up to about 30 seconds since the last sync on hard power loss, while audio retains its approximately one-second sync cadence. WAV, microphone capture, speech sensitivity and existing upload preferences are retained. No measured battery improvement is claimed. See [battery-1.2.md](battery-1.2.md) for the comparison procedure.

## Private configuration and future updates

The canonical private recovery archive is **Rave AI Build Credentials.zip** in the owner's private Codex operational-knowledge folder, Drive file ID `1Ta3gTrPco_w625VV86PH9WveXf5FyFCm`. It contains the native app client configuration and dedicated PKCS12 signing key with its password and recovery instructions. Owner-only access was verified. Do not commit or publish it. Administrative gcloud credentials and user OAuth tokens are not included.

Always reuse that signing key for updates. Preserve application ID `ai.etti.clawhark` and internal storage identifiers while pending recordings exist. Do not uninstall or clear app storage to resolve an update problem without first recovering unsynced audio.

The repository remains a fork of `ivar2000/clawhark` at `eladrave/clawhark` so the upstream relationship and existing review links remain available.
