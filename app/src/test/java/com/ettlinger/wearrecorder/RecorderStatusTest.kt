package com.ettlinger.wearrecorder

import org.junit.Assert.*
import org.junit.Test

class RecorderStatusTest {
    @Test fun staleOrPreviousBootHeartbeatCannotClaimRecording() {
        assertEquals("CHECK RECORDER", RecorderStatus.displayState("RECORDING", 120_001))
        assertEquals("CHECK RECORDER", RecorderStatus.displayState("LISTENING", -10))
        assertEquals("LISTENING", RecorderStatus.displayState("LISTENING", 25_000))
    }
    @Test fun stoppedAndErrorStatesDoNotBecomeActiveWhenOld() {
        assertEquals("STOPPED", RecorderStatus.displayState("STOPPED", Long.MAX_VALUE))
        assertEquals("NEEDS ATTENTION", RecorderStatus.displayState("NEEDS ATTENTION", Long.MAX_VALUE))
    }
}

class TileTimelineTest {
    private fun layout(label: String) = androidx.wear.protolayout.LayoutElementBuilders.Layout.Builder()
        .setRoot(androidx.wear.protolayout.LayoutElementBuilders.Text.Builder().setText(label).build()).build()
    private fun timeline(active: Boolean = true, expiry: Long = 120000, now: Long = 100000): androidx.wear.protolayout.proto.TimelineProto.Timeline {
        val built = TileTimeline.build(layout("RECORDING"), layout("CHECK RECORDER"), active, expiry, now)
        return androidx.wear.protolayout.proto.TimelineProto.Timeline.parseFrom(built.toProto().toByteArray())
    }
    // Documented Wear timeline contract: shortest valid finite entry, otherwise no-validity default.
    // Evaluate the serialized output rather than the builder, including proto default bounds.
    private fun selected(t: androidx.wear.protolayout.proto.TimelineProto.Timeline, at: Long): String {
        val finite = t.timelineEntriesList.filter { it.hasValidity() && it.validity.startMillis <= at && at < it.validity.endMillis }
            .minByOrNull { it.validity.endMillis - it.validity.startMillis }
        val entry = finite ?: t.timelineEntriesList.firstOrNull { !it.hasValidity() }
        org.junit.Assert.assertNotNull("No valid content at $at", entry)
        org.junit.Assert.assertTrue(entry!!.hasLayout() && entry.layout.hasRoot())
        return entry.layout.root.text.text.value
    }
    @org.junit.Test fun serializedActiveTileAlwaysHasContentAroundAndAfterExpiry() {
        val t = timeline()
        org.junit.Assert.assertEquals("CHECK RECORDER", selected(t, 99999))
        org.junit.Assert.assertEquals("RECORDING", selected(t, 100000))
        org.junit.Assert.assertEquals("RECORDING", selected(t, 119999))
        org.junit.Assert.assertEquals("CHECK RECORDER", selected(t, 120000))
        org.junit.Assert.assertEquals("CHECK RECORDER", selected(t, 9000000000000))
        org.junit.Assert.assertEquals("CHECK RECORDER", selected(t, Long.MAX_VALUE))
        org.junit.Assert.assertEquals(1, t.timelineEntriesList.count { !it.hasValidity() })
        org.junit.Assert.assertTrue(t.timelineEntriesList.filter { it.hasValidity() }.all {
            it.validity.startMillis < it.validity.endMillis
        })
    }
    @org.junit.Test fun alreadyExpiredOrZeroStatusCannotKeepClaimingRecording() {
        for (expiry in listOf(0L, 99999L, 100000L)) {
            val t = timeline(expiry = expiry)
            org.junit.Assert.assertEquals(1, t.timelineEntriesCount)
            org.junit.Assert.assertEquals("CHECK RECORDER", selected(t, 100000))
            org.junit.Assert.assertEquals("CHECK RECORDER", selected(t, Long.MAX_VALUE))
        }
    }
    @org.junit.Test fun inactiveTileRemainsVisibleWithoutAnExpiry() {
        val t = timeline(active = false)
        org.junit.Assert.assertEquals(1, t.timelineEntriesCount)
        org.junit.Assert.assertFalse(t.getTimelineEntries(0).hasValidity())
        org.junit.Assert.assertEquals("RECORDING", selected(t, Long.MAX_VALUE))
    }
}
