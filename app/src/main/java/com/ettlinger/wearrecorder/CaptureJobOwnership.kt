package com.ettlinger.wearrecorder

import kotlinx.coroutines.Job

/** Cancellation still owns the microphone/spool until NonCancellable finalization completes. */
object CaptureJobOwnership {
    fun inFlight(job: Job?) = job != null && !job.isCompleted
    fun finalizing(requested: Boolean, job: Job?) = !requested && inFlight(job)
}
