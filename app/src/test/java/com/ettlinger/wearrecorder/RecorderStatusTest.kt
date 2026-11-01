package com.ettlinger.wearrecorder

import org.junit.Assert.*
import org.junit.Test

class RecorderStatusTest {
    @Test fun staleOrPreviousBootHeartbeatCannotClaimRecording() {
        assertEquals("CHECK RECORDER", RecorderStatus.displayState("RECORDING", 60_001))
        assertEquals("CHECK RECORDER", RecorderStatus.displayState("LISTENING", -10))
        assertEquals("LISTENING", RecorderStatus.displayState("LISTENING", 25_000))
    }
    @Test fun stoppedAndErrorStatesDoNotBecomeActiveWhenOld() {
        assertEquals("STOPPED", RecorderStatus.displayState("STOPPED", Long.MAX_VALUE))
        assertEquals("NEEDS ATTENTION", RecorderStatus.displayState("NEEDS ATTENTION", Long.MAX_VALUE))
    }
}

class TileTimelineTest {
    @org.junit.Test fun cachedRecordingHasAnExpiryAndAConservativeFallback() {
        val layout = androidx.wear.protolayout.LayoutElementBuilders.Layout.Builder()
            .setRoot(androidx.wear.protolayout.LayoutElementBuilders.Text.Builder().setText("status").build()).build()
        val timeline = TileTimeline.build(layout, layout, true, 123_000L)
        org.junit.Assert.assertEquals(2, timeline.timelineEntries.size)
        org.junit.Assert.assertEquals(123_000L, timeline.timelineEntries[0].validity!!.endMillis)
        org.junit.Assert.assertEquals(123_000L, timeline.timelineEntries[1].validity!!.startMillis)
    }
}
