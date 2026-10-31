# Development recorder privacy

The watch captures microphone audio only after the user starts recording. It saves mono PCM/WAV files in app-private local storage, including silence. Recording can continue with the screen off. Stop ends capture; already saved files remain eligible for upload.

When the user links Google Drive, the app requests `drive.file` access and uploads finalized recordings to its `ClawHark` folder through HTTPS. It also reads the linked account's Drive permission ID to prevent account changes from adopting another account's pending recordings. OAuth tokens use Android encrypted preferences. Local audio relies on Android device/app storage protection; additional application-level audio encryption is not implemented.

A verified remote ID, size, and checksum are required before local cleanup. Offline recordings stay on the watch until verified; at storage limits capture stops instead of evicting unuploaded files. The cloud copies are not automatically deleted by this recorder. The user controls them in Google Drive.

Uploads may use metered connections. There is no transcription, AI processing, analytics SDK, or third-party memory backend added by this branch. Local diagnostic logs include recording state, byte counts, pending counts, timestamps and errors, not audio or OAuth codes/tokens. Logs remain on the watch unless the owner exports them.

Use recording/pause controls and obtain any required consent for conversations you record. Do not uninstall or clear app storage while recordings need recovery.
