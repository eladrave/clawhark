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

The current configured APK is **Rave-AI-1.0.1-watch.apk**, compiled from code revision `eef969db9755a908cafd408aca6ce8556180b26b` with a private, ignored `app/src/main/assets/oauth_config.json`.

[Download the configured APK](https://chatgpt.com/library?file_id=libfile_8e7e43bf10b4819198cf65a1bf025e06&account_id=personal)

- Android debug build for physical-device qualification, signed with the dedicated **Rave AI Personal Build** key.
- Android unit tests: 13 passed (8 login recovery tests and 5 upload protocol tests), 0 failures or errors.
- Debug APK build and lint: passed.
- APK signature and ZIP alignment: verified.
- Packaged app label: **Rave AI**.
- Packaged OAuth configuration: verified against the newly created client; no user access tokens, refresh tokens, or signing keys are embedded.
- APK SHA-256: `6c1078e9c97448397165ba28d15ac4d0fd599022c2a7c7ad0c95f996f1679687`.

The user installed 1.0.0 and supplied screenshots showing Google web approval succeeded while the watch remained on the code/link screen. Source inspection found that Activity.onStop canceled polling without persisting or resuming the pending grant. Version 1.0.1 persists the pending grant in encrypted preferences and resumes polling on return or Activity/process recreation. It retains server pacing and retries transient failures. Token storage and clearing of the pending grant are synchronous and atomic. Linking does not start the microphone.

Install 1.0.1 **over the existing app**, preserving its data and signing identity, for example `adb install -r Rave-AI-1.0.1-watch.apk`. The package version code is 2. Do not uninstall or clear storage. Because 1.0.0 did not persist its pending grant, this update needs a fresh code: open **Link Drive**, approve it at **google.com/device**, then reopen the app on the watch. Later screen-off/resume cycles reuse the saved code until it expires. Physical-device validation of the fix is pending.

The app creates a **Rave AI** folder in the root of that linked account's Drive on its first upload. Audio remains local until a matching cloud ID, size, and checksum are verified. A real watch recording/upload roundtrip, screen-off capture, interruptions, and battery measurements are pending. See [recorder-qualification.md](recorder-qualification.md).

## Private configuration and future updates

The canonical private recovery archive is **Rave AI Build Credentials.zip** in the owner's private Codex operational-knowledge folder, Drive file ID `1Ta3gTrPco_w625VV86PH9WveXf5FyFCm`. It contains the native app client configuration and dedicated PKCS12 signing key with its password and recovery instructions. Owner-only access was verified. Do not commit or publish it. Administrative gcloud credentials and user OAuth tokens are not included.

Always reuse that signing key for updates. Preserve application ID `ai.etti.clawhark` and internal storage identifiers while pending recordings exist. Do not uninstall or clear app storage to resolve an update problem without first recovering unsynced audio.

The repository remains a fork of `ivar2000/clawhark` at `eladrave/clawhark` so the upstream relationship and existing review links remain available.
