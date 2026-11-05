# First milestone: capture, retain, upload

Implemented development contract:

- Continuous 16 kHz mono PCM16 microphone capture, independent of network and OAuth state.
- Approximately one-minute local WAV segments with periodic fsync, unique identity, and restart repair of interrupted PCM writes.
- App-private spool with a 4 GiB budget and 200 MiB free-space reserve. Storage errors stop capture and retain originals.
- Independent WorkManager upload queue with stable Drive IDs, retry backoff, mutually exclusive workers, and size/checksum verification before cleanup.
- Cloud account binding prevents pending audio adoption after account changes.
- Visible recording, recovering, stopped/error, pending count, storage and upload status.
- Post-reboot notification requires user-assisted microphone restart.

Acceptance and unverified device behavior: docs/recorder-qualification.md.
Not included: phone companion, compression, AI memory, transcription, external agent actions, custom audio encryption, unattended reboot microphone launch, or proven all-day battery life.
