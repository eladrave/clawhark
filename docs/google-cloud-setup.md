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

The first configured APK is **Rave-AI-1.0.0-watch.apk**, compiled from code revision `0d5ae8418fba852b0c03985bb096b29345faba6e` with a private, ignored `app/src/main/assets/oauth_config.json`. Source code is unchanged from that revision; later documentation commits describe setup state.

[Download the configured APK](https://chatgpt.com/library?file_id=libfile_2619c87b732c81919516b750f511957d&account_id=personal)

- Android debug build for physical-device qualification, signed with the dedicated **Rave AI Personal Build** key.
- Android unit tests: 5 passed, 0 failures or errors.
- Debug APK build and lint: passed.
- APK signature and ZIP alignment: verified.
- Packaged app label: **Rave AI**.
- Packaged OAuth configuration: verified against the newly created client; no user access tokens, refresh tokens, or signing keys are embedded.
- APK SHA-256: `b96dafb695b15f48fd7df9678cd4cf8e636df82367bd70ebfcc41ea4d59f9696`.

The user must sideload this Wear OS APK onto the watch, open **Link Drive**, and approve the displayed device code using the intended Google account. No watch installation has been performed.

The app creates a **Rave AI** folder in the root of that linked account's Drive on its first upload. Audio remains local until a matching cloud ID, size, and checksum are verified. A real watch recording/upload roundtrip, screen-off capture, interruptions, and battery measurements are pending. See [recorder-qualification.md](recorder-qualification.md).

## Private configuration and future updates

The canonical private recovery archive is **Rave AI Build Credentials.zip** in the owner's private Codex operational-knowledge folder, Drive file ID `1Ta3gTrPco_w625VV86PH9WveXf5FyFCm`. It contains the native app client configuration and dedicated PKCS12 signing key with its password and recovery instructions. Owner-only access was verified. Do not commit or publish it. Administrative gcloud credentials and user OAuth tokens are not included.

Always reuse that signing key for updates. Preserve application ID `ai.etti.clawhark` and internal storage identifiers while pending recordings exist. Do not uninstall or clear app storage to resolve an update problem without first recovering unsynced audio.

The repository remains a fork of `ivar2000/clawhark` at `eladrave/clawhark` so the upstream relationship and existing review links remain available.
