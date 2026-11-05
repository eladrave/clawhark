package com.ettlinger.wearrecorder

import androidx.wear.tiles.*
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders.*
import androidx.wear.protolayout.ColorBuilders.argb
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import java.text.SimpleDateFormat
import java.util.*

/** Local snapshots only. A timeline expires cached active status even if the process cannot refresh. */
class StatusTileService : TileService() {
    override fun onTileEnterEvent(eventParams: EventBuilders.TileEnterEvent) { RecorderStatus.tileVisible(this, true) }
    override fun onTileLeaveEvent(eventParams: EventBuilders.TileLeaveEvent) { RecorderStatus.tileVisible(this, false) }
    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val state = RecorderStatus.state(this)
        val pending = try { RecordingFiles.open(this).pending().size } catch (_: Exception) { -1 }
        val at = RecorderStatus.updated(this)
        fun layout(label: String): LayoutElementBuilders.Layout {
            val column = LayoutElementBuilders.Column.Builder()
            fun line(value: String, size: Float, color: Int = 0xffeeeeee.toInt()) {
                column.addContent(LayoutElementBuilders.Text.Builder().setText(value).setMaxLines(2)
                    .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color)).build()).build())
                column.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(3f)).build())
            }
            line("Rave AI · OPEN", 13f)
            line(label, 16f, RecorderAttentionUi.statusColor(label))
            line(BatteryMonitor.label, 12f)
            line(RecorderStatus.mode(this), 11f)
            line(if (pending >= 0) "$pending recordings pending" else "Storage needs attention", 10f)
            line(if (at > 0) "Status ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(at))}" else "Open app to start", 9f)
            val open = ActionBuilders.LaunchAction.Builder().setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder().setPackageName(packageName).setClassName(MainActivity::class.java.name).build()).build()
            val root = LayoutElementBuilders.Box.Builder().setWidth(expand()).setHeight(expand())
                .setModifiers(ModifiersBuilders.Modifiers.Builder()
                    .setPadding(ModifiersBuilders.Padding.Builder().setAll(dp(20f)).build())
                    .setBackground(ModifiersBuilders.Background.Builder().setColor(argb(0xff111111.toInt())).build())
                    .setClickable(ModifiersBuilders.Clickable.Builder().setId("open_rave_ai").setOnClick(open).build()).build())
                .addContent(column.build()).build()
            return LayoutElementBuilders.Layout.Builder().setRoot(root).build()
        }
        val active = state in listOf("RECORDING", "LISTENING", "RECOVERING MIC", "STARTING")
        val now = System.currentTimeMillis()
        val timeline = TileTimeline.build(layout(state), layout("CHECK RECORDER"), active,
            minOf(at + 120_000L, now + 120_000L), now)
        return Futures.immediateFuture(TileBuilders.Tile.Builder().setResourcesVersion("2")
            .setFreshnessIntervalMillis(2 * 60 * 60_000L).setTileTimeline(timeline).build())
    }
    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(ResourceBuilders.Resources.Builder().setVersion("2").build())
}

object TileTimeline {
    fun build(current: LayoutElementBuilders.Layout, stale: LayoutElementBuilders.Layout, active: Boolean,
        expires: Long, now: Long = System.currentTimeMillis()): TimelineBuilders.Timeline {
        if (!active) return TimelineBuilders.Timeline.Builder()
            .addTimelineEntry(TimelineBuilders.TimelineEntry.Builder().setLayout(current).build()).build()
        // No validity means a permanent default. A start-only interval has end=0, not infinity.
        val timeline = TimelineBuilders.Timeline.Builder().addTimelineEntry(
            TimelineBuilders.TimelineEntry.Builder().setLayout(stale).build())
        if (expires > now) timeline.addTimelineEntry(TimelineBuilders.TimelineEntry.Builder().setLayout(current)
            .setValidity(TimelineBuilders.TimeInterval.Builder().setStartMillis(now).setEndMillis(expires).build()).build())
        return timeline.build()
    }
}
