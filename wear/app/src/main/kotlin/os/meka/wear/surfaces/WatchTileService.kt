package os.meka.wear.surfaces

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.DimensionBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import os.meka.core.domain.WatchTileRules
import os.meka.core.domain.WatchTileView
import os.meka.wear.WatchActivity
import os.meka.wear.WatchApplication
import os.meka.wear.designsystem.MekaDarkColors
import os.meka.wear.designsystem.MekaRadius
import os.meka.wear.designsystem.MekaSpace
import os.meka.wear.designsystem.MekaType

/**
 * MEKA's tile (Galaxy Watch, slice 3): swipe from the watch face to see Up next with its one primary button (Done, or
 * Went on a booked session) and a running fast's clock. A tap on the button comes back here as the tile's last
 * clickable id and does what the watch's own button does, only while the watch still offers it; a tap anywhere else
 * opens MEKA. Drawn from the watch's own replica (no Data Layer, ADR-005 amendment 2026-10-10); it looks again each
 * minute while something counts and every quarter hour otherwise, and after every sync.
 */
class WatchTileService : TileService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        CallbackToFutureAdapter.getFuture { completer ->
            scope.launch {
                try {
                    val core = (application as WatchApplication).core.value
                    if (core != null) {
                        // The button's tap (the complication follows through the app's watch on the day).
                        runCatching { core.watchTilePress(requestParams.currentState.lastClickableId) }
                        runCatching { core.tick() }
                    }
                    val view = core?.watchTile() ?: WatchTileRules.tile(null)
                    val after = core?.watchRefreshAfterMs() ?: WatchTileRules.CALM_REFRESH_MS
                    completer.set(tile(view, after))
                } catch (e: Exception) {
                    completer.setException(e)
                }
            }
            "meka-tile"
        }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(ResourceBuilders.Resources.Builder().setVersion(RESOURCES).build())
            "meka-tile-resources"
        }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun tile(v: WatchTileView, refreshAfterMs: Long): TileBuilders.Tile =
        TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES)
            .setFreshnessIntervalMillis(refreshAfterMs.coerceAtLeast(MIN_REFRESH_MS))
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(v)))
            .build()

    private fun layout(v: WatchTileView): LayoutElementBuilders.LayoutElement {
        val column = LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(text(v.label, MekaType.captionStrong, if (v.lit) C.accent else C.textSecondary, 1))
            .addContent(gap(MekaSpace.xxs.value))
            .addContent(text(v.title, MekaType.metaStrong, C.textPrimary, 2))
        v.line?.let { column.addContent(gap(MekaSpace.xxs.value)).addContent(text(it, MekaType.caption, C.textSecondary, 2)) }
        if (v.button != null && v.buttonId != null) {
            column.addContent(gap(MekaSpace.xs.value)).addContent(button(v.button!!.label, v.buttonId!!))
        }
        v.fastLine?.let {
            column.addContent(gap(MekaSpace.xs.value))
                .addContent(text(it, MekaType.caption, if (v.fastReached) C.success else C.accent, 1))
        }
        // The whole tile opens MEKA; the button sits on top with its own click.
        return LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand())
            .setHeight(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId(WatchTileRules.OPEN_ID)
                            .setOnClick(openMeka())
                            .build(),
                    )
                    .setSemantics(ModifiersBuilders.Semantics.Builder().setContentDescription(v.spoken).build())
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setStart(DimensionBuilders.dp(MekaSpace.gutterWide.value))
                            .setEnd(DimensionBuilders.dp(MekaSpace.gutterWide.value))
                            .build(),
                    )
                    .build(),
            )
            .addContent(column.build())
            .build()
    }

    private fun openMeka(): ActionBuilders.Action =
        ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(WatchActivity::class.java.name)
                    .build(),
            )
            .build()

    /** The primary button, filled with the accent like the watch's own; its tap reloads the tile with [id]. */
    private fun button(label: String, id: String): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Box.Builder()
            .setHeight(DimensionBuilders.dp(MekaSpace.touch.value))
            .setWidth(DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId(id)
                            .setOnClick(ActionBuilders.LoadAction.Builder().build())
                            .build(),
                    )
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(C.accent))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(DimensionBuilders.dp(MekaRadius.l.value)).build())
                            .build(),
                    )
                    .build(),
            )
            .addContent(text(label, MekaType.metaStrong, C.onAccent, 1))
            .build()

    private fun text(s: String, style: TextStyle, color: Color, maxLines: Int): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Text.Builder()
            .setText(s)
            .setMaxLines(maxLines)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(DimensionBuilders.sp(style.fontSize.value))
                    .setWeight(
                        // The token's weight as the tile's two stable weights: medium from 500 (bold is experimental).
                        if (strong(style)) LayoutElementBuilders.FONT_WEIGHT_MEDIUM else LayoutElementBuilders.FONT_WEIGHT_NORMAL,
                    )
                    .setColor(argb(color))
                    .build(),
            )
            .build()

    private fun gap(dp: Float): LayoutElementBuilders.LayoutElement =
        LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(dp)).build()

    private companion object {
        /** A watch is always dark. */
        val C = MekaDarkColors
        const val RESOURCES = "1"
        /** Never asked to redraw sooner than this (Wear OS rate-limits tiles anyway). */
        const val MIN_REFRESH_MS = 30_000L

        fun argb(c: Color): ColorBuilders.ColorProp = ColorBuilders.argb(c.toArgb())

        /** The token is set at 500 or heavier. */
        fun strong(style: TextStyle): Boolean = (style.fontWeight?.weight ?: 400) >= 500
    }
}
