package os.meka.android.calendar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import os.meka.android.today.DayListAcross
import os.meka.core.domain.DayDropZone
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.minTouch
import os.meka.android.today.DayListInBoard
import os.meka.android.today.DayListPane
import os.meka.core.domain.DayListBoardRules
import os.meka.core.domain.DayListPill
import os.meka.core.domain.DayListSpreadRules
import os.meka.core.domain.FoldedMenuItem
import os.meka.core.domain.FoldedMenuKind
import os.meka.core.facade.MekaCore

/**
 * The Week board on the folded phone as Day lists (approved 2026-11-08, `docs/design/day-list/BoardFolded.dc.html`;
 * Meka 2026-11-09 22:27: the old folded board "looks awful"): "November · Week 46 · 6h 15m planned" with ‹ › ⋯, the
 * week's seven days as 64 dp pills with each day's planned length, and under them the chosen day's Day list (its line
 * and total pill, the done bar, slim rows, its own add bar: Enter for the next). The same tasks as Today, the open Fold
 * and the Mac. ⋯ holds Agenda view, New event and Plan your week.
 *
 * Motion: the header and strip appear on the stagger; the chosen pill's fill and ink cross-fade (appear token); the
 * day's list cross-fades to the next day (the Day list's own row motion inside). Reduced motion: cross-fades only.
 */
@Composable
fun DayListBoard(
    core: MekaCore, modifier: Modifier = Modifier,
    onAgenda: (() -> Unit)? = null, onAddEvent: (() -> Unit)? = null, onPlanWeek: (() -> Unit)? = null,
) {
    val board by core.weekBoard.collectAsState()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    // The day in view, kept across a fold or unfold and a trip to another tab.
    var chosen by rememberSaveable { mutableStateOf<Long?>(null) }
    val day = DayListBoardRules.shownDay(board, chosen) ?: return
    val week = DayListBoardRules.weekOf(board, day) ?: return
    val head = remember(week) { DayListBoardRules.head(week) }
    val strip = remember(week) { DayListBoardRules.strip(week) }
    val menu = remember(onAgenda, onAddEvent, onPlanWeek) {
        buildList {
            if (onAgenda != null) add(FoldedMenuItem(FoldedMenuKind.AGENDA, "Agenda view", ""))
            if (onAddEvent != null) add(FoldedMenuItem(FoldedMenuKind.NEW_EVENT, "New event", ""))
            if (onPlanWeek != null) add(FoldedMenuItem(FoldedMenuKind.PLAN_WEEK, os.meka.core.domain.PlanWeekRules.OPEN, "Obi helps"))
        }
    }

    Column(modifier.fillMaxSize().background(Meka.colors.background).testTag(DAY_LIST_BOARD_TAG)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = MekaSpace.gutter).padding(top = MekaSpace.l),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.m),
        ) {
            FoldedHeader(
                month = head.month, week = head.weekLine, spoken = head.spoken,
                canBack = DayListBoardRules.canStep(board, day, -1), canForward = DayListBoardRules.canStep(board, day, 1),
                onBack = { DayListBoardRules.stepWeek(board, day, -1)?.let { chosen = it } },
                onForward = { DayListBoardRules.stepWeek(board, day, 1)?.let { chosen = it } },
                menu = menu,
                onMenu = { item ->
                    when (item.kind) {
                        FoldedMenuKind.AGENDA -> onAgenda?.invoke()
                        FoldedMenuKind.NEW_EVENT -> onAddEvent?.invoke()
                        FoldedMenuKind.PLAN_WEEK -> onPlanWeek?.invoke()
                        else -> Unit
                    }
                },
                modifier = Modifier.appear(rememberAppearance(0)),
            )
            DayPills(strip, setOf(day), Modifier.fillMaxWidth().appear(rememberAppearance(1))) { e -> haptics.tick(); chosen = e }
        }
        AnimatedContent(
            targetState = day,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "day-list-board-day",
        ) { d ->
            val bd = board.day(d)
            key(d) {
                DayListPane(
                    core, onClose = {}, closeLabel = null, forDay = d,
                    inBoard = bd?.let { DayListInBoard(DayListBoardRules.dayTitle(it), DayListBoardRules.placeholder(it), DayListBoardRules.HINT) },
                )
            }
        }
    }
}

/**
 * The strip: seven pills (letter, number, the day's planned length or "·"). The day in view is filled gold with dark ink,
 * today's number is gold otherwise, past days are quieter. Tap one to bring its list into view.
 */
@Composable
private fun DayPills(
    pills: List<DayListPill>, inView: Set<Long>, modifier: Modifier, pillWidth: androidx.compose.ui.unit.Dp? = null,
    /** Drag across (open Fold): the pill a held task would land on wears a gold ring; each pill reports its place. */
    target: Long? = null, onBounds: ((Long, Rect) -> Unit)? = null,
    onTap: (Long) -> Unit,
) {
    val reduced = Meka.reducedMotion
    Row(modifier.testTag(DAY_LIST_STRIP_TAG), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        pills.forEach { p ->
            key(p.epochDay) {
                val lit = p.epochDay in inView
                val fill by animateColorAsState(if (lit) Meka.colors.accent else Meka.colors.surface, MekaMotion.appear(reduced), label = "day-pill-fill")
                val ink by animateColorAsState(
                    when {
                        lit -> Meka.colors.onAccent
                        p.isToday -> Meka.colors.accent
                        p.isPast -> Meka.colors.textTertiary
                        else -> Meka.colors.textPrimary
                    },
                    MekaMotion.appear(reduced), label = "day-pill-ink",
                )
                val ring by animateFloatAsState(if (p.epochDay == target) 1f else 0f, MekaMotion.appear(reduced), label = "day-pill-ring")
                Column(
                    (if (pillWidth != null) Modifier.width(pillWidth) else Modifier.weight(1f)).height(DayListBoardRules.PILL_HEIGHT.dp)
                        .then(if (onBounds != null) Modifier.onGloballyPositioned { onBounds(p.epochDay, it.boundsInWindow()) } else Modifier)
                        .clip(RoundedCornerShape(MekaRadius.m)).background(fill)
                        .border(2.dp, Meka.colors.accent.copy(alpha = ring), RoundedCornerShape(MekaRadius.m))
                        .clickable(role = Role.Tab) { onTap(p.epochDay) }
                        .clearAndSetSemantics { contentDescription = p.spoken; selected = lit },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(p.letter, style = MekaType.micro, color = ink.copy(alpha = 0.75f), maxLines = 1)
                    Text(p.number, style = MekaType.upNextCardTitle, color = ink, maxLines = 1)
                    Text(p.length, style = MekaType.micro, color = ink.copy(alpha = 0.8f), maxLines = 1)
                }
            }
        }
    }
}

/**
 * The Week board on the open Fold as Day lists side by side (approved 2026-11-08, `docs/design/day-list/OpenSpread.dc.html`;
 * Meka 2026-11-09 22:27: "It looks awful"): "November · Week 46 · 6h 15m planned" with ‹ › ⋯; under it the 1 · 2 · 3 days
 * switch (3 by default, kept across a fold) and the week's seven pills with the days in view lit; then that many Day lists
 * across, each with its heading and total, the done bar, the slim rows and its own add bar (Enter for the next). Tap a pill
 * to start the spread there; it stays inside the week (Sunday with three across shows Fri · Sat · Sun).
 *
 * Motion: the header and the switch row appear on the stagger; the lit pills' fill and ink cross-fade; the columns
 * cross-fade to the next set of days (each Day list's own row motion inside). Reduced motion: cross-fades only.
 */
@Composable
fun DayListSpread(
    core: MekaCore, modifier: Modifier = Modifier,
    onAgenda: (() -> Unit)? = null, onAddEvent: (() -> Unit)? = null, onPlanWeek: (() -> Unit)? = null,
) {
    val board by core.weekBoard.collectAsState()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    var chosen by rememberSaveable { mutableStateOf<Long?>(null) }
    var count by rememberSaveable { mutableStateOf(DayListSpreadRules.DEFAULT_COUNT) }
    val day = DayListBoardRules.shownDay(board, chosen) ?: return
    val week = DayListBoardRules.weekOf(board, day) ?: return
    val head = remember(week) { DayListBoardRules.head(week) }
    val strip = remember(week) { DayListBoardRules.strip(week) }
    val columns = remember(week, day, count) { DayListSpreadRules.columns(week, day, count) }
    // Drag across days (slice 4b): where each lit column and each pill is on the screen, and the held task's card.
    val scope = rememberCoroutineScope()
    val undo = rememberEventUndo()
    val places = remember { HashMap<String, DayDropZone>() }
    var ghost by remember { mutableStateOf<SpreadGhost?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    fun zones(): List<DayDropZone> =
        strip.mapNotNull { places["pill-${it.epochDay}"] } + columns.mapNotNull { places["col-${it.epochDay}"] }
    fun acrossFrom(from: Long) = DayListAcross(
        onMove = { _, title, at ->
            val zs = zones()
            val out = DayListSpreadRules.leftColumn(zs, at.x, at.y, from)
            val to = if (out) DayListSpreadRules.dropDay(zs, at.x, at.y, board, from) else null
            if (to != null && to != ghost?.target) haptics.tick()
            ghost = if (out) SpreadGhost(title, at, to) else null
            out
        },
        onEnd = { id, at ->
            ghost = null
            val to = at?.let { DayListSpreadRules.dropDay(zones(), it.x, it.y, board, from) }
            if (to == null) false else {
                haptics.light()
                scope.launch { runCatching { core.moveOnBoard(id, to, Int.MAX_VALUE) }.getOrNull()?.let { l -> undo.show(l) { core.undoBoardMove() } } }
                true
            }
        },
    )
    val menu = remember(onAgenda, onAddEvent, onPlanWeek) {
        buildList {
            if (onAgenda != null) add(FoldedMenuItem(FoldedMenuKind.AGENDA, "Agenda view", ""))
            if (onAddEvent != null) add(FoldedMenuItem(FoldedMenuKind.NEW_EVENT, "New event", ""))
            if (onPlanWeek != null) add(FoldedMenuItem(FoldedMenuKind.PLAN_WEEK, os.meka.core.domain.PlanWeekRules.OPEN, "Obi helps"))
        }
    }

    Box(modifier.fillMaxSize().background(Meka.colors.background).onGloballyPositioned { origin = it.positionInWindow() }) {
    Column(Modifier.fillMaxSize().testTag(DAY_LIST_SPREAD_TAG)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = MekaSpace.gutterWide).padding(top = MekaSpace.l),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.m),
        ) {
            FoldedHeader(
                month = head.month, week = head.weekLine, spoken = head.spoken,
                canBack = DayListBoardRules.canStep(board, day, -1), canForward = DayListBoardRules.canStep(board, day, 1),
                onBack = { DayListBoardRules.stepWeek(board, day, -1)?.let { chosen = it } },
                onForward = { DayListBoardRules.stepWeek(board, day, 1)?.let { chosen = it } },
                menu = menu,
                onMenu = { item ->
                    when (item.kind) {
                        FoldedMenuKind.AGENDA -> onAgenda?.invoke()
                        FoldedMenuKind.NEW_EVENT -> onAddEvent?.invoke()
                        FoldedMenuKind.PLAN_WEEK -> onPlanWeek?.invoke()
                        else -> Unit
                    }
                },
                modifier = Modifier.appear(rememberAppearance(0)),
            )
            Row(Modifier.fillMaxWidth().appear(rememberAppearance(1)), verticalAlignment = Alignment.CenterVertically) {
                CountSwitch(count, Modifier.weight(1f)) { k -> haptics.tick(); count = k }
                DayPills(
                    strip, columns.map { it.epochDay }.toSet(), Modifier, pillWidth = SPREAD_PILL_DP.dp, target = ghost?.target,
                    onBounds = { d, r -> places["pill-$d"] = r.zone(d, isColumn = false) },
                ) { e -> haptics.tick(); chosen = e }
            }
        }
        AnimatedContent(
            targetState = columns,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            contentKey = { cs -> cs.map { it.epochDay } },
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = MekaSpace.gutterWide - MekaSpace.xs),
            label = "day-list-spread-days",
        ) { cs ->
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                cs.forEach { c ->
                    key(c.epochDay) {
                        val ring by animateFloatAsState(if (ghost?.target == c.epochDay) 1f else 0f, MekaMotion.appear(reduced), label = "day-column-ring")
                        Box(
                            Modifier.weight(1f).fillMaxSize().testTag(DAY_LIST_COLUMN_TAG)
                                .onGloballyPositioned { places["col-${c.epochDay}"] = it.boundsInWindow().zone(c.epochDay, isColumn = true) }
                                .border(2.dp, Meka.colors.accent.copy(alpha = ring), RoundedCornerShape(MekaRadius.m)),
                        ) {
                            DayListPane(
                                core, onClose = {}, closeLabel = null, forDay = c.epochDay,
                                inBoard = DayListInBoard(c.title, c.placeholder, DayListBoardRules.HINT, narrow = true),
                                sidePadding = MekaSpace.xs,
                                across = acrossFrom(c.epochDay),
                            )
                        }
                    }
                }
            }
        }
    }
    // The held task as a card under the finger once it leaves its column (fades in and out on the appear token).
    var shown by remember { mutableStateOf<SpreadGhost?>(null) }
    ghost?.let { shown = it }
    AnimatedVisibility(
        visible = ghost != null,
        enter = fadeIn(MekaMotion.appear(reduced)), exit = fadeOut(MekaMotion.appear(reduced)),
    ) {
        shown?.let { g ->
            Text(
                g.title, style = MekaType.body, color = Meka.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .offset { IntOffset((g.at.x - origin.x).roundToInt() - 24.dp.roundToPx(), (g.at.y - origin.y).roundToInt() - 32.dp.roundToPx()) }
                    .widthIn(max = 220.dp).shadow(8.dp, RoundedCornerShape(MekaRadius.m)).clip(RoundedCornerShape(MekaRadius.m))
                    .background(Meka.colors.surfaceRaised)
                    .border(1.dp, if (g.target != null) Meka.colors.accent else Meka.colors.hairline, RoundedCornerShape(MekaRadius.m))
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs)
                    .testTag(DAY_LIST_GHOST_TAG),
            )
        }
    }
    EventUndoBar(undo, Modifier.align(Alignment.BottomCenter).padding(bottom = MekaSpace.xxl + MekaSpace.xxl + MekaSpace.l))
    }
}

/** The held task's card on the spread: its title, the finger's place on the screen, and the day it would land on. */
private data class SpreadGhost(val title: String, val at: Offset, val target: Long?)

private fun Rect.zone(day: Long, isColumn: Boolean) = DayDropZone(day, left, top, right, bottom, isColumn)

/** 1 day · 2 days · 3 days: a pill of three choices, the one in use raised. */
@Composable
private fun CountSwitch(count: Int, modifier: Modifier, onPick: (Int) -> Unit) {
    val reduced = Meka.reducedMotion
    Box(modifier) {
        Row(
            Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surface).padding(MekaSpace.xxs)
                .testTag(DAY_LIST_COUNT_TAG),
            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
        ) {
            DayListSpreadRules.COUNTS.forEach { k ->
                val on = k == count
                val fill by animateColorAsState(if (on) Meka.colors.surfaceRaised else Meka.colors.surface, MekaMotion.appear(reduced), label = "count-fill")
                Text(
                    DayListSpreadRules.countLabel(k),
                    style = if (on) MekaType.metaStrong else MekaType.itemMeta,
                    color = if (on) Meka.colors.textPrimary else Meka.colors.textSecondary,
                    maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(fill)
                        .clickable(role = Role.Tab) { onPick(k) }
                        .semantics { selected = on }
                        .minTouch().padding(horizontal = MekaSpace.m),
                )
            }
        }
    }
}

/** A pill in the open Fold's strip, dp wide (the mockup's 54). */
private const val SPREAD_PILL_DP = 54

/** The open Fold's spread, its columns and its 1 · 2 · 3 days switch (screen tests). */
const val DAY_LIST_SPREAD_TAG = "day-list-spread"
const val DAY_LIST_COLUMN_TAG = "day-list-column"
const val DAY_LIST_COUNT_TAG = "day-list-count"
const val DAY_LIST_GHOST_TAG = "day-list-ghost"

/** The folded Week board as Day lists, and its strip (screen tests). */
const val DAY_LIST_BOARD_TAG = "day-list-board"
const val DAY_LIST_STRIP_TAG = "day-list-strip"
