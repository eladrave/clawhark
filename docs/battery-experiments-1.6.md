# Rave AI 1.6 battery experiments

This release provides selectable processing experiments, not a measured battery-saving claim. Install only after ending and syncing the current 1.5.1 15-minute-upload trial. Updating during a trial invalidates its comparison. No cloud configuration, Drive folder or existing recording conversion is required.

## Controls and defaults

Settings > Battery experiments offers a processing preset and independent controls. New controls can only be changed with recording stopped and no marked battery test active. Changes apply to the next capture or upload batch. The selected preset and audio format are visible in Settings and the main screen; switches show each independent choice. Existing upload timing/content presets remain independent.

| Control | Default after upgrade | Experiment |
| --- | --- | --- |
| Processing preset | Baseline (1.5.1 processing) | Efficient processing enables efficient uploads/checksums and speech-buffer reuse, preserving WAV and the capture wake lock |
| Efficient uploads and checksums | Off | Omit the first remote lookup only for a newly generated ID; combine ACK MD5 and SHA-256 reads |
| Reuse speech-analysis buffers | Off | Reuse bounded 320-sample frames with unchanged speech gating; has no effect in Continuous mode |
| Audio format | WAV: original PCM | AAC-LC ADTS, mono 16 kHz, target 24 kbps; Continuous mode only |
| Capture wake lock | On | Off omits only the app's manual capture lock; Android may still hold an audio lock |

Presets do not change upload timing, recording mode, sensitivity, logging or tile preferences. Existing settings and Drive authorization survive an in-place upgrade with the same signing certificate.

## Next useful comparison

1. Finish the current 1.5.1 Continuous + Every 15 minutes trial, stop capture, END TEST & SYNC, and let its queue drain.
2. Install 1.6. Select Efficient processing, Continuous, Every 15 minutes, Audio and logs, logging enabled, Battery trial: fewer events, unmetered-only off, always-on display off. Keep other watch conditions similar.
3. Start recording and START BATTERY TEST. Stay unplugged for about two hours, with no manual sync or setting changes during the interval.
4. Stop capture, note battery, END TEST & SYNC. Analyze actual event timestamps and upload batch starts, not nominal schedule or Drive modification times.

This compares the new upload/checksum implementation while keeping recording mode and network cadence matched. Speech-buffer reuse is inactive in this Continuous trial. Isolate it later with WAV Speech only and the same upload policy on both sides. Baseline and optimized output equivalence is also checked in unit tests.

Only after that comparison, change one additional control: AAC with Continuous while retaining the same processing/upload settings, or wake lock off with WAV. Before a long AAC or wake-lock-off trial, use a known audible source for a short screen-off continuity and playback check. Diagnostic heartbeats cannot prove that every spoken word survived.

## Upload correctness

Efficient uploads skip a GET only when the ID was freshly allocated in the current call and has never been used for a mutating request. The ID is still persisted and synced before POST. IDs loaded from disk always get inspected first, including retries after an ambiguous response or process death. Owner binding, remote ID/size/MD5 verification, durable receipt and local deletion ordering are retained. AAC uses `audio/aac`; WAV and legacy M4A retain their MIME types.

Efficient ACK computes both existing receipt hashes in one payload read. It does not weaken verification or change the receipt format. Logging and sync receipts use the same selected optimization. Each upload pass snapshots its flag and records `effective_efficient_uploads`, including at the batch result.

Every 15 minutes remains a best-effort WorkManager schedule. A newly enqueued job may run promptly, and a finite snapshot may require several continuations/retries. It is not a strict 15-minute quiet period. Successful verification is still required before cleanup. The logs folder remains the existing Rave AI/logs folder.

## Recording and recovery

WAV remains mono PCM16 at 16 kHz, with the existing approximately one-second checkpoint, pre-roll/tail, quotas and fail-open VAD. Frame reuse is bounded and callbacks consume their PCM synchronously. The encoder and microphone never perform network requests.

AAC is lossy and experimental. Its encoder is configured before the first microphone open. Unsupported or failed codecs stop visibly, preserving emitted partial audio rather than claiming a successful fallback. AAC with Speech only is rejected before capture; repeated short-utterance encoder start/EOS cycles are excluded from this release. Segments rotate by retained input-sample duration, not encoded byte count.

The writer skips codec configuration buffers, verifies AAC-LC/16 kHz/mono output, respects output-buffer offsets, adds ADTS headers, drains data-bearing EOS output and uses bounded input/EOS waits. Backpressure sleeps rather than spinning. Normal sealing is idempotent; append/checkpoint errors prevent normal publication.

Only complete emitted ADTS frames are checkpointed each second. Input still buffered inside the encoder is an additional crash-loss tail, so AAC does not inherit the PCM durability claim. Restart recovery validates from byte zero, trims an incomplete final frame, syncs the repaired file, renames, then syncs its directory. Malformed/empty partials are quarantined without scanning past corruption or uploading them. This verifies container framing, not decodability of arbitrary payloads. Independent clips can include encoder priming/padding silence. Existing WAV and legacy M4A recordings remain unchanged and uploadable.

AAC encoder finalization/setup is synchronous. It can delay capture; logs flag finalization above 500 ms and delivery delays above 2 seconds. Watch tests must check minute rotation, screen-off recording, stop/EOS, playback, and process-interruption recovery. No physical Pixel Watch codec or wake-lock-off qualification is claimed by host tests.

The capture wake-lock switch is a separate experiment. Removing an app lock may save nothing while Android's audio lock remains active, and it can affect continuity on some devices. Keep it on for the first optimized WAV comparison. Capture summaries include requested/effective flags, input/retained duration, process CPU, delivery delay counts and pending bytes. For AAC, `stored_ms` measures encoder-accepted input duration, not a guarantee that all of that input has been emitted or synced.

## Deliberately deferred

Omi's optional microphone/PDM hardware sleep depends on its dedicated hardware and is not exposed here as a watch setting. Speech-file packing is also deferred because omitting gaps requires timeline metadata to preserve chronology. One-second WAV durability is not relaxed. No TV/speaker identification or automatic microphone duty cycling is claimed.

## Validation scope

Release checks cover default/independent policy behavior, baseline-versus-pooled speech output, upload ambiguity and checksum retention, single-pass hashes, AAC fake-codec input/EOS/failure behavior, every torn final-frame position, corrupt-header quarantine, size limits, store pending/cleanup integration, the existing host crash-recovery tests, Android assembly and lint. These are software checks; battery savings require the matched watch trials above.

Compression architecture was reviewed with Astra because changing the recoverable storage format affects data integrity. The review selected ADTS rather than an MP4 index-dependent file and required explicit disclosure of encoder-buffered crash loss. Root integrated the result and retained PCM defaults.
