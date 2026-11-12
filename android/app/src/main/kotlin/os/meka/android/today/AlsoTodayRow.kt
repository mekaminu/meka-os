package os.meka.android.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.wordsTap
import os.meka.core.domain.AlsoKind
import os.meka.core.domain.AlsoTile
import os.meka.core.domain.AlsoTodayRules
import os.meka.core.domain.DayPressure
import os.meka.core.domain.MeetingPrep
import os.meka.core.domain.PressureFix
import os.meka.core.domain.TrainDetail
import os.meka.core.domain.TrainDetailRow

internal const val ALSO_TODAY_TAG = "today-also"

/** How wide each Also today tile is (the mockup's 150 px), so the next one peeks in from the edge. */
private val TILE_WIDTH = 150.dp

/**
 * The Today rebuild, slice 2b-2: ALSO TODAY, one side-swiping row of small tiles below the schedule (core
 * [AlsoTodayRules]): a coloured dot and small caps key, the value, the quieter line under it. A tile with somewhere to
 * go (study, the week ahead, a renewal, For the train) presses in with a light haptic and opens it; a value that changes cross-fades.
 * The dot is gold only for something to do today, so gold still means "act here".
 */
@Composable
internal fun AlsoTodayRow(
    tiles: List<AlsoTile>, onOpen: (AlsoTile) -> Unit, opens: (AlsoTile) -> Boolean,
    unfolded: AlsoKind? = null, detail: @Composable (AlsoKind) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (tiles.isEmpty()) return
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    Column(modifier.fillMaxWidth().testTag(ALSO_TODAY_TAG), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(AlsoTodayRules.LABEL, style = MekaType.cardLabel, color = Meka.colors.textSecondary, maxLines = 1,
            modifier = Modifier.semantics { heading() })
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            tiles.forEach { tile ->
                val tappable = opens(tile)
                val open = unfolded == tile.kind
                val shape = RoundedCornerShape(MekaRadius.m)
                Column(
                    Modifier.width(TILE_WIDTH)
                        .testTag("also-tile-${tile.kind.name.lowercase()}")
                        .clip(shape)
                        .background(Meka.colors.surface)
                        .then(if (open) Modifier.border(1.dp, Meka.colors.hairline, shape) else Modifier)
                        .then(if (tappable) Modifier.clickable(role = Role.Button) { haptics.light(); onOpen(tile) } else Modifier)
                        .padding(MekaSpace.xs)
                        .clearAndSetSemantics {
                            contentDescription = tile.spoken; if (tappable) role = Role.Button
                            if (AlsoTodayRules.unfolds(tile.kind)) stateDescription = if (open) "Open" else "Closed"
                        },
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(dotColour(tile)))
                        Text(tile.key, style = MekaType.cardLabel, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Crossfade(tile.value, animationSpec = MekaMotion.appear(reduced), label = "also-${tile.kind}") { v ->
                        Text(v, style = MekaType.metaStrong, color = Meka.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    if (tile.line.isNotEmpty()) {
                        Text(tile.line, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        // Slice 2b-2b-2: a prep or stacking-up tile's tap unfolds its detail under the row (expand, motion catalogue).
        val shownKind = unfolded?.takeIf { k -> tiles.any { it.kind == k } }
        var lastKind by remember { mutableStateOf(shownKind) }
        if (shownKind != null) lastKind = shownKind
        AnimatedVisibility(
            visible = shownKind != null,
            enter = if (reduced) fadeIn(MekaMotion.appear(true)) else fadeIn(MekaMotion.appear(false)) + expandVertically(MekaMotion.expand(false)),
            exit = if (reduced) fadeOut(MekaMotion.appear(true)) else fadeOut(MekaMotion.appear(false)) + shrinkVertically(MekaMotion.expand(false)),
        ) {
            lastKind?.let { k ->
                Column(
                    Modifier.fillMaxWidth().testTag(ALSO_DETAIL_TAG).clip(RoundedCornerShape(MekaRadius.m))
                        .background(Meka.colors.surface).padding(MekaSpace.xs),
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
                ) { detail(k) }
            }
        }
    }
}

internal const val ALSO_DETAIL_TAG = "today-also-detail"

/** The prep tile unfolded: the headline, who's coming, then each guest's last email and what's open (at most four lines). */
@Composable
internal fun PrepDetail(p: MeetingPrep) {
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = p.spoken }, verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Text(p.headline, style = MekaType.metaStrong, color = Meka.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(p.whoLine, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        p.lines.forEach { line ->
            Text(line, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The stacking-up tile unfolded: the day's line, then its one-tap fixes in gold (a Board move, Undo on the bar). */
@Composable
internal fun PressureDetail(p: DayPressure, onFix: (PressureFix) -> Unit) {
    Text(p.line, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.semantics { contentDescription = p.spoken })
    p.fixes.forEach { fix ->
        Text(fix.label, style = MekaType.caption, color = Meka.colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("also-fix-${fix.taskId}").wordsTap { onFix(fix) }.padding(vertical = MekaSpace.xxs))
    }
}

internal const val TRAINS_DETAIL_TAG = "today-trains-detail"

/**
 * The Trains tile unfolded (Meka 2026-11-11 07:30; core RouteRules.detail): the line and its stations with TfL's word,
 * when to leave, every departure it knows (time, on time / late / cancelled in amber when bad, platform and
 * arrival), the change, the fallback with its own trains, and when it was checked. One screen-reader line for the whole.
 */
@Composable
internal fun TrainsDetail(d: TrainDetail) {
    Column(
        Modifier.fillMaxWidth().testTag(TRAINS_DETAIL_TAG).semantics(mergeDescendants = true) { contentDescription = d.spoken },
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Text(d.title, style = MekaType.metaStrong, color = Meka.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        d.status?.let { Text(it, style = MekaType.caption, color = if (d.lit) os.meka.core.domain.TodayInk.ATTENTION.color() else Meka.colors.textSecondary, maxLines = 1) }
        d.leaveBy?.let { Text(it, style = MekaType.caption, color = Meka.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        d.rows.forEach { TrainRowLine(it) }
        d.noTrains?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        d.change?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = MekaSpace.xxs)) }
        d.fallback?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        d.fallbackRows.forEach { TrainRowLine(it) }
        d.checked?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 1,
            modifier = Modifier.padding(top = MekaSpace.xxs)) }
    }
}

internal const val PARCELS_DETAIL_TAG = "today-parcels-detail"

/**
 * The PARCELS tile unfolded (Meka 2026-11-11: no Also today tile is a dead end; core ParcelRules.detail): "Parcels
 * today", then each parcel (who it's from, what the emails say, amber when yesterday's hasn't come, the window and
 * carrier quieter) and where it was read from. One screen-reader line for the whole.
 */
@Composable
internal fun ParcelsDetail(d: os.meka.core.domain.ParcelDetail) {
    Column(
        Modifier.fillMaxWidth().testTag(PARCELS_DETAIL_TAG).semantics(mergeDescendants = true) { contentDescription = d.spoken },
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
    ) {
        Text(d.title, style = MekaType.metaStrong, color = Meka.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        d.rows.forEach { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, style = MekaType.metaStrong, color = Meka.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                Text(r.status, style = MekaType.caption, color = if (r.bad) os.meka.core.domain.TodayInk.ATTENTION.color() else Meka.colors.textSecondary, maxLines = 1)
                if (r.meta.isNotEmpty()) {
                    Text(r.meta, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(d.note, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 1, modifier = Modifier.padding(top = MekaSpace.xxs))
    }
}

/** "07:12  on time  platform 3 · arrives 07:41": the time, the status (amber when late or cancelled), the rest quieter. */
@Composable
private fun TrainRowLine(r: TrainDetailRow) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(r.time, style = MekaType.metaStrong, color = Meka.colors.textPrimary, maxLines = 1)
        Text(r.status, style = MekaType.caption, color = if (r.bad) os.meka.core.domain.TodayInk.ATTENTION.color() else Meka.colors.textSecondary, maxLines = 1)
        if (r.meta.isNotEmpty()) {
            Text(r.meta, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun dotColour(tile: AlsoTile): Color = if (tile.lit) Meka.colors.accent else when (tile.kind) {
    AlsoKind.STUDY, AlsoKind.STUDY_WEEK, AlsoKind.REFLECTION, AlsoKind.REVIEW, AlsoKind.GOALS, AlsoKind.PREP -> Meka.colors.calendar1
    AlsoKind.RENEWAL, AlsoKind.PARCELS, AlsoKind.CLASH, AlsoKind.PRESSURE -> Meka.colors.calendar3
    AlsoKind.WEEK_AHEAD, AlsoKind.TRIP, AlsoKind.LIFE_ADMIN, AlsoKind.TRAINS, AlsoKind.TRAIN_QUEUE -> Meka.colors.calendar2
    AlsoKind.BINS, AlsoKind.DINNER, AlsoKind.SCHOOL -> Meka.colors.calendar4
    AlsoKind.GIFTS, AlsoKind.DATE_NIGHT, AlsoKind.REPLIES, AlsoKind.TRADING, AlsoKind.DONE_FOR_YOU -> Meka.colors.calendar5
}
