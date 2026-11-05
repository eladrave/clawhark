package com.ettlinger.wearrecorder

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CaptureJobOwnershipTest {
    @Test fun cancelledCaptureOwnsResourcesUntilItsFinalizerCompletes() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val finalizing = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val job = launch {
            try { started.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { finalizing.complete(Unit); finish.await() } }
        }
        withTimeout(1_000) {
            started.await(); job.cancel(); finalizing.await()
            try {
                assertFalse(job.isActive)
                assertTrue(CaptureJobOwnership.inFlight(job))
                assertTrue(CaptureJobOwnership.finalizing(false, job))
            } finally { finish.complete(Unit) }
            job.join()
            assertFalse(CaptureJobOwnership.inFlight(job))
            assertFalse(CaptureJobOwnership.finalizing(false, job))
        }
    }
    @Test fun noOwnerOrCompletedOwnerNeverDisablesStart() {
        assertFalse(CaptureJobOwnership.inFlight(null))
        val job = Job(); job.complete()
        assertFalse(CaptureJobOwnership.inFlight(job))
    }
}
