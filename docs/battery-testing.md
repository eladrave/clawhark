# Rave AI battery test controls (1.5)

Install 1.5.0 over the existing app. Existing authorization, recordings, logging preference, recording mode, speech sensitivity and upload timing retain their meanings. No new power setting is applied merely by upgrading.

## First comparison: capture without automatic uploads

1. Stop and sync the previous session, then install the update without uninstalling or clearing storage.
2. Open Settings and choose **Capture only: manual sync** under Battery test presets. This changes upload timing to Manual only and content to Audio and logs. Other controls stay as selected.
3. Start recording in the main app. Return to Settings and press **START BATTERY TEST**. The button enables diagnostics, assigns a unique test ID and writes a BEGIN marker with battery, current recorder state, actual mode and all selected test settings. It does not start the microphone.
4. Keep watch use, speech/TV conditions, charging and connectivity comparable for at least an hour. Do not manually sync during the measured interval. Audio and diagnostics remain local in this preset, so absence of new Drive logs is expected.
5. Press **END TEST & SYNC**. The END marker closes the measured interval before the selected files are uploaded. Capture continues until STOP is pressed in the main app. If you want to close the final recording segment too, press STOP before ending/syncing.
6. Use **Baseline: 15-minute uploads** for the next matched trial. Use the same mode, sensitivity, logging detail and tile setting. Sync any backlog before marking a new baseline trial, so an old backlog does not dominate the measurement.

Do not infer battery savings from shorter files or a single percentage reading. Compare timestamped, non-charging intervals. BEGIN/END and each log line identify the test. Mid-test setting changes are logged and should split or invalidate a comparison.

## Available controls

| Control | Choices | Effect |
|---|---|---|
| Test preset | Baseline, Capture only, Logs only | Sets upload timing/content without changing capture settings |
| Upload timing | Immediate, 15 minutes, charging, Manual only, 30 minutes, 60 minutes | Changes automatic scheduling; WorkManager intervals are best effort |
| Upload content | Audio and logs, Logs only, Audio only | Excluded files remain on watch; select Audio and logs to drain both queues |
| Logging detail | Normal, Battery trial | Reduced detail retains battery, settings, test markers, capture/batch summaries and warnings/errors; omits routine VAD, UI, auth and per-file progress |
| Live tile updates | On/off | Off suppresses routine push requests, retaining refresh on entry and safety events; cached active status still expires conservatively |
| Recording mode | Continuous, Speech only | Existing setting, applies next capture start |
| Speech sensitivity | High, Balanced, Noise rejection | Existing setting, applies next capture start |
| Unmetered uploads only | On/off | Still applies to explicit and automatic sync |

**SYNC NOW** bypasses Manual only and the batch interval, but respects selected content, logging being enabled, charging-only and unmetered requirements. Logs only does not upload recordings, even when manually syncing. Restore Audio and logs before syncing if both queues should drain.

The three presets leave logging detail and tile updates untouched. To isolate these costs, change one control per subsequent trial. Keep the microphone and audio checkpoint behavior constant.

## Pausing, races and retention

Manual only cancels the existing periodic/immediate automatic work. Old automatic jobs also check current policy before authentication and each network request. Manual requests use a separate work queue and carry an epoch invalidated by a new test or upload-preference reconfiguration. Those actions cancel stale manual work too. A request already in flight can finish; let an active sync settle before marking a test.

Explicit syncs append a finite pass behind an active manual pass. This ensures a newly sealed END marker is not silently omitted because the first pass took its immutable log snapshot earlier. Current settings/content and grant validity are rechecked throughout each pass. Paused or excluded files retain their original local bytes and stable Drive IDs. Remote ID/size/MD5 verification and synced acknowledgment still precede deletion.

The 4 GiB recording budget and 20 MiB log budget remain. Pending files are not evicted. If deferred recordings fill the recording budget, capture stops and preserves them. If the diagnostic budget fills, diagnostics pause rather than removing unsent logs. Trial controls do not promise reduced microphone power, TV rejection or an established battery saving.

## Validation

Unit tests exercise manual/automatic admission, content isolation, stale manual grants, interval/kick selection, reduced diagnostic evidence, zero network/token work when paused, and a policy change before a mutating upload. Existing upload identity/checksum, capture recovery, speech and tile tests remain part of release validation. Real-device scheduling, UI layout, pause latency and battery comparisons require a Pixel Watch trial.

## Version 1.4 recovery and firmware baseline

The selected timing/content preset stays visible; Custom indicates another combination. Main-screen audio/log progress and a last successful log-sync time distinguish a recording backlog from diagnostic progress. That timestamp covers sealed log files included in a sync, not diagnostics subsequently generated by the sync itself. New logs can remain pending until the next explicit sync or automatic interval.

Logs are offered before audio and share a 20-object, 90-second soft budget per worker. A network request can exceed that budget. Normal bounded progress queues a continuation; network failures use retry/backoff. Continuations retain the original modification-time cutoff, so files finalized after the request wait for a future sync. A clock change or coarse filesystem timestamp can affect that boundary; it is not an audio-content timestamp. Work cancellation is surfaced as a pause and rethrown to WorkManager, with its stop reason logged on Android 12+. This improves observability; it does not establish the cause of the previous cancellations or promise that Android will run the queue immediately.

To remove unwanted test audio, stop recording and use **DELETE PENDING TEST RECORDINGS**, then confirm. It cancels outstanding requests and tries both store locks; if a request is still finishing, retry once it has stopped. Diagnostic logs, account binding, OAuth credentials, settings and Drive copies remain. Do not use Android Clear storage unless intentionally resetting authorization and settings too.

Apply an offered system firmware update between trials with capture stopped. After restart, open the app and check settings. Run a fresh unplugged baseline once update activity has settled. Application-start and test-boundary events include firmware build/security patch. Do not attribute a before/after change solely to this app when firmware changed too.

## Verified pre-firmware capture-only baseline

On November 1, 2026, version 1.3.0 ran a stable speech-only interval from 14:10:40 to 16:02:01 America/New_York (UTC-05:00). Battery went from 49% to 37% across 111.35 minutes, approximately 6.47 percentage points/hour. Upload timing was Manual only, sensitivity Noise rejection, normal diagnostic detail and live tile updates enabled. No automatic uploads, setting changes, charging, microphone errors or unrequested capture restarts were logged inside this interval. The earlier short stop/restart after BEGIN is excluded. Charging began at 16:06:09, after capture stopped and END at 16:02:10.

This is whole-watch drain, not an isolated app energy measurement. It exceeds the initial diagnostic alert threshold of 5 percentage points/hour. Do not extrapolate runtime or attribute the difference from older 1.1/1.2 intervals to a single cause. Firmware updates require a fresh baseline. The failed final sync logged three worker cancellations, not a demonstrated charging or authentication fault. Logs-only sync then succeeded with 190 audio files still pending.

Evidence: owner diagnostic log batches beginning at 14:10:40, 15:59:57 and 16:18:37, retained privately in Rave AI/logs. No audio was inspected.

## Version 1.5: separate recording, idle and upload trials

The update preserves preferences and audio behavior. It adds opt-in sync measurements and fixes the battery-test button so it immediately turns green and says TEST ACTIVE. Tapping an active test does not silently replace its ID. START marks a test and enables logging; it does not start the microphone. Five-minute battery samples are attempted while an idle test marker remains active, without adding a foreground service or wake lock. Android may suspend the process, so idle sampling remains best effort.

Finish the current stopped/idle comparison before upgrading. Do not clear storage. Keep firmware, always-on display, connectivity, logging detail and other watch use unchanged between matched trials. With Manual only, Audio and logs, and logging enabled:

1. **Idle:** keep recording stopped and press START BATTERY TEST. Remain unplugged, screen off, and avoid syncing for at least 60 minutes. Press END TEST & SYNC at the end.
2. **Capture:** after uploads settle, start recording, mark a new test, and use the same settings and comparable conditions for at least 60 minutes. Stop recording, then END TEST & SYNC. Capture interval endpoints precede sync.
3. **Upload:** keep recording stopped and remain unplugged with stable connectivity while the backlog syncs. Wait for audio/log progress to settle and **Final sync summary verified in Drive**. Do not infer an upload-only hourly rate unless at least 30 minutes of comparable non-charging evidence exists. A short sync may move no displayed battery percentage.

A marked END & SYNC retains the original test ID in the new sync session even though the test itself has ended. Sync measurements require logging enabled. Manual/content, charging and unmetered settings remain authoritative. If logging or log uploads are disabled, final summaries remain locally pending until an eligible explicit sync or recovery. App startup and explicit SYNC NOW reconcile existing sessions and retry eligible pending summaries. Queue execution and delivery are asynchronous. A missing WorkManager record is given a ten-minute create/enqueue grace before orphan reconciliation; aborted summaries retain the last observed endpoint.

Each logical sync has a unique session ID across bounded worker continuations and retries. It stores battery/power/recording samples at execution boundaries, verified audio/log file counts and payload bytes, Drive HTTP requests, elapsed time, worker-running time and known non-worker gaps. The initial manual queue wait is separate; automatic pre-start queue time is unknown. Monotonic time and a boot identifier prevent wall-clock changes from becoming false durations. Reboot/unknown boot or an unmatched process-death attempt makes durations/counts incomplete instead of assigning unknown work to waiting.

After completion or reconciled abort, an immutable `RaveAI_sync_<session-id>.log` is queued under **Rave AI/logs**, independent of the original snapshot cutoff. Its own delivery and retry costs are excluded, avoiding recursive summaries. The report and Drive identity survive an interrupted acknowledgment so the same object is retried. The main app shows pending versus remotely verified final-summary status. Ordinary log-success timestamps may cover only earlier batches; look for the explicit final-summary confirmation.

These are whole-watch battery endpoints, not per-app energy readings. Worker-running time includes network/CPU waits; non-worker time includes OS scheduling and retry backoff. Charging or recording observed during a pass is flagged. Gaps between passes cannot prove charging/recording absent, even with matching endpoints. Recovered aborts end at the last observed sample, not the later reconciliation time. HTTP counts exclude OAuth refresh; byte counts exclude radio/protocol overhead. No automatic hourly rate, runtime extrapolation or energy split is calculated. Compare measured intervals, and repeat matched trials before attributing a difference.

Measurement metadata is limited to 32 active/undelivered sessions, at most 64 KiB each with 64 retained boundary/state-change samples. Delivered metadata is removed; full metadata storage skips new measurement visibly while ordinary payload sync continues. Final receipt files share the existing diagnostic quota. Existing audio/checksum retention rules remain in force. Tests cover session timing, frozen receipts, delivery identity restoration, duplicate attempts, wall-clock/reboot handling, interrupted attempts, sample/capacity bounds and file-path validation. Real-device UI, WorkManager scheduling, process recovery and battery comparisons still require watch qualification. This version establishes measurement, not a demonstrated battery reduction.
