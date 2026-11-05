# Rave AI 1.6.1 recorder recovery

## Behavior

Temporary microphone silencing no longer clears the user's recording request. The foreground recorder shows **RECOVERING MIC**, displays the reason immediately beneath the main status, and keeps STOP available. Muted input is discarded, not saved or fed into speech detection. When unmuted audio becomes available, recording resumes without another START.

The previous segment is sealed at the interruption boundary. Resumed audio starts another segment so missing time is not silently joined into one apparently continuous recording. Diagnostic events distinguish interruption, pending recovery and resumed capture, with the detected gap duration. Audio unavailable during an OS interruption cannot be reconstructed. A trial crossing such a gap should be analyzed separately.

Read errors, device loss and open failures retry with delays of 1, 2, 4, 8, 16 and then at most 30 seconds. Muted input is checked about once per second. Recovery status/log heartbeats are throttled to 30 seconds. The watchdog tracks physical read responses independently of audible input, so a replacement microphone that stalls during recovery can also be reopened. It never runs network requests on the capture loop.

STOP clears recording intent, unblocks a microphone read and cancels pending retry delays. Finalization retains the current segment. A cancelled coroutine continues to own capture resources until its finalizer actually completes; START is disabled during that period and stale duplicate commands cannot create an overlapping owner.

Permission denial, invalid format/configuration and storage/encoder failures remain visible actionable errors. Watch reboot and force-stop recovery still follow Android's platform restrictions and can require opening the app. This patch does not override microphone privacy or audio-priority rules and does not guarantee capture while Android withholds audio.

## Upgrade and first check

Install the signed 1.6.1 APK over the current app without clearing storage. Drive authorization and current recording/upload settings are retained. WAV and capture wake lock on are the default capture path; 1.6 experimental processing controls remain available. Use the same baseline settings for the initial reliability check rather than changing compression or wake-lock policy at the same time.

1. Start capture and confirm RECORDING or LISTENING, with a STOP button.
2. Leave the screen off for a minute and reopen the app to confirm its state.
3. If Android temporarily mutes the input, expect RECOVERING MIC with the reason above the button. When the microphone is released, capture should resume automatically. Use STOP whenever you want recovery and recording to end.
4. If NEEDS ATTENTION appears, read/capture the prominent reason, note the time, then sync diagnostics. Do not clear app storage. End an active battery trial before restarting it.

## Validation and remaining watch checks

Deterministic acquisition tests cover mute/unmute without another start, muted-frame exclusion, device replacement, bounded retry delays, STOP during retry or microphone creation, permission failure, cancellation, storage-callback failure propagation, recovery heartbeat throttling and watchdog recovery of a stalled replacement. A real-file spool test checks separate files before and after a simulated interruption and excludes muted PCM. A coroutine finalization test checks that cancellation cannot release capture ownership prematurely. UI policy tests cover visible reasons, stale snapshots and STOP before service binding.

These checks validate software behavior. Physical-watch microphone contention, foreground-service lifecycle, screen-off continuity and the circular display still need device confirmation. Battery improvements from 1.6's optional controls remain unmeasured by this patch.
