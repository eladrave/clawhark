# Rave AI 1.2.0 battery comparison build

This build isolates reductions in storage, UI and network overhead while preserving the existing continuously listening microphone, 16 kHz mono PCM, WebRTC speech detection and upload retention contract. No all-day battery or measured saving is claimed.

## Changes

- Coalesce 20 ms speech writes in an 8192-byte buffer (256 ms of PCM), flushing before each existing approximately one-second audio durability checkpoint, segment seal and orderly close. A sudden process kill can lose the unflushed memory tail; a hard power loss can lose data since the last successful sync. Recovery still salvages complete samples from interrupted files.
- Reserve ten seconds of worst-case PCM plus write/header headroom before appends. Reconcile directory/storage accounting every ten seconds or when that byte allowance is consumed, including bursty speech pre-roll. Segment creation still checks space. No pending recording is evicted.
- Keep the diagnostic file open and write each line to the OS immediately. Sync routine diagnostics on the next append after 30 seconds, and warnings/errors immediately. Seal/sync before exposing any log to uploads. Only diagnostic hard-power-loss exposure changes; audio checkpoint policy is retained. Cached quota accounting reconciles uploader deletions periodically and at the budget boundary.
- Run the main-screen poll every five seconds only while visible. Leaving that screen checkpoints logs without generating another tiny upload batch.
- Publish capture summaries once per minute. Keep fast speech/listening state in memory and persist status at most once per minute, plus safety/mode transitions. Request tile refresh on entry; coalesce routine visible updates to once per minute and suppress them while hidden. Stop/error/recovery transitions can refresh immediately. Active cached tiles conservatively expire after two minutes; slow background freshness is two hours.
- Verify the Drive account once per finite uploader instance and exact access token. Token changes invalidate the account proof, spare ID pool and folder cache. Generate up to 32 IDs together, but durably bind each file before its first upload. Preserve preflight lookup, remote ID/size/MD5 verification, crash-safe receipt and cleanup behavior.
- Upload finite snapshots with progress counters instead of rescanning the directory per file. Every diagnostic line identifies v=1.2.0. Capture summaries include elapsed_ms, input_ms, stored_ms, process_cpu_ms, pending and bytes. Upload summaries include verified_audio, bytes, elapsed_ms, HTTP requests, remaining and retry. Process CPU time includes all app threads, not just the detector; these are diagnostic counters, not an energy measurement.
- Replace launcher, tile-picker icon and repository icon with the Rave AI pulse/memory monogram. The transparent source is branding/rave-ai-logo.png. Adaptive-icon foreground padding preserves circular mask margins. Logo created with the built-in image generator; production brief: a bold rounded R combining an audio pulse and memory loop, violet/cyan, no wordmark, transparent background, legible at watch size.

## Explicitly retained

The extra capture wake lock stays in place for this comparison release. Android's audio service also holds capture wake locks, so deleting ours alone does not establish deep sleep or savings. Compression, microphone duty cycling, hardware SoundTrigger and a companion-phone architecture are deferred until separate device measurements justify them. No Omi implementation code is copied.

## Device comparison

Keep version 1.1.0 running with logging enabled until a sufficient baseline is collected. Before updating, use STOP and SYNC NOW when compatible with the existing upload constraints to seal the final segment and diagnostics. Record the transition time and battery. Install 1.2.0 over the existing app, keep logging, speech sensitivity and upload timing unchanged, then start capture. Do not uninstall or clear storage.

Compare at least 60 minutes per version under similar speech/TV conditions, radio connectivity and screen use. Exclude charging and setting changes; distinguish batched upload bursts from capture-only periods. For a cleaner follow-up, compare recorder-stopped, capture-with-uploads-deferred and capture-with-15-minute-uploads separately. Use logged event timestamps, not cloud arrival times. One-percent battery quantization limits short trials. Keep microphone continuity, speech onset/tail audibility, pending queue progress and successful remote verification as acceptance checks alongside battery drain.

## Validation

The local Android unit suite and real-filesystem crash/recovery suite cover the new buffering, budget, log sync/quota, tile coalescing and uploader identity behavior. Build, lint, personal upgrade signature, archive alignment and private OAuth asset packaging are checked before the configured APK is delivered. Actual Pixel Watch screen-off behavior and net battery savings require the owner's device trial.
