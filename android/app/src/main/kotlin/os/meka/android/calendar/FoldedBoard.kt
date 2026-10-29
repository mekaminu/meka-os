package os.meka.android.calendar

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.areaTone
import os.meka.android.designsystem.calendarTone
import os.meka.android.designsystem.mekaMenuBorder
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.BoardAreaRules
import os.meka.core.domain.BoardCard
import os.meka.core.domain.BoardDay
import os.meka.core.domain.BoardEventCard
import os.meka.core.domain.BoardFoldRules
import os.meka.core.domain.BoardFoldedRules
import os.meka.core.domain.BoardNudge
import os.meka.core.domain.DayStripPill
import os.meka.core.domain.BoardPlannerRules
import os.meka.core.domain.FoldedCard
import os.meka.core.domain.FoldedMenuItem
import os.meka.core.domain.PressureFix

/*
 * The Week board on the folded phone, as the approved mockup (Meka 2026-10-27 07:44; docs/design/week-board/folded.dc.html,
 * core BoardFoldedRules): one header row, the week's strip, the chosen day's line and plan bar, Obi's one line, and then
 * the day's cards high on the screen. One typeface (the app's sans): no serif, no monospace. The open Fold keeps its columns.
 */

/** "October  Week 43" then ‹ › ⋯ as 40 dp round buttons on the card surface; ⋯ opens [menu]. */
@Composable
internal fun FoldedHeader(
    month: String, week: String, spoken: String, canBack: Boolean, canForward: Boolean, onBack: () -> Unit, onForward: () -> Unit,
    menu: List<FoldedMenuItem>, onMenu: (FoldedMenuItem) -> Unit, modifier: Modifier,
) {
    val haptics = rememberMekaHaptics()
    var open by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().testTag(FOLDED_HEAD_TAG), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clearAndSetSemantics { contentDescription = spoken },
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(month, style = MekaType.nowTitle, color = Meka.colors.textPrimary, maxLines = 1, modifier = Modifier.testTag(MONTH_TAG))
            Spacer(Modifier.width(MekaSpace.xs))
            Text(week, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 1, modifier = Modifier.padding(bottom = MekaSpace.xxs))
        }
        RoundButton("‹", "Previous week", canBack) { haptics.tick(); onBack() }
        RoundButton("›", "Next week", canForward) { haptics.tick(); onForward() }
        Box {
            RoundButton("⋯", FOLDED_MENU_LABEL, true, FOLDED_MENU_TAG) { haptics.tick(); open = true }
            DropdownMenu(
                expanded = open, onDismissRequest = { open = false },
                containerColor = Meka.colors.surfaceRaised, border = mekaMenuBorder(Meka.colors),
            ) {
                menu.forEach { item ->
                    DropdownMenuItem(
                        text = {
                            Row(Modifier.width(FOLDED_MENU_WIDTH), verticalAlignment = Alignment.CenterVertically) {
                                Text(item.label, style = MekaType.itemMeta, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f), maxLines = 1)
                                if (item.detail.isNotEmpty()) Text(item.detail, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 1)
                            }
                        },
                        onClick = { open = false; haptics.tick(); onMenu(item) },
                    )
                }
            }
        }
    }
}

/** The mockup's round button: 40 dp on the card surface inside a 48 dp touch target; dimmed when it can't go. */
@Composable
private fun RoundButton(glyph: String, label: String, enabled: Boolean, tag: String = STEP_TAG, onClick: () -> Unit) {
    Box(
        Modifier.size(MekaSpace.touch).clip(CircleShape)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .graphicsLayer { alpha = if (enabled) 1f else 0.3f }
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(BoardFoldedRules.BUTTON.dp).clip(CircleShape).background(Meka.colors.surface),
            contentAlignment = Alignment.Center,
        ) { Text(glyph, style = MekaType.upNextCardTitle, color = Meka.colors.textSecondary) }
    }
}

/**
 * The week's seven days as the mockup's pills (60 dp): the weekday's letter, the number, and one short gold bar an open
 * task (up to three). The day in view is filled gold with dark ink; today's number is gold otherwise. Tap one to go to
 * it; while a card is held a pill under it swells (1.15, the approve spring) and letting go there moves the card.
 * Reduced motion: no swell, the colours cross-fade.
 */
@Composable
internal fun FoldedDayStrip(
    pills: List<DayStripPill>, inView: Long?, hovered: Long?, onBounds: (Long, Rect) -> Unit, onGone: (Long) -> Unit,
    onTap: (Long) -> Unit, modifier: Modifier,
) {
    val reduced = Meka.reducedMotion
    Row(modifier.fillMaxWidth().testTag(FOLDED_STRIP_TAG), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        pills.forEach { p ->
            key(p.epochDay) {
                androidx.compose.runtime.DisposableEffect(p.epochDay) { onDispose { onGone(p.epochDay) } }
                val lit = p.epochDay == inView
                val over = hovered == p.epochDay && p.canDrop
                val swell by animateFloatAsState(if (over && !reduced) BoardFoldRules.PILL_SWELL else 1f, spring(dampingRatio = 0.6f, stiffness = 600f), label = "pill-swell")
                val fill by animateColorAsState(
                    when {
                        lit -> Meka.colors.accent
                        over -> Meka.colors.accent.copy(alpha = 0.22f)
                        else -> Meka.colors.surface
                    },
                    MekaMotion.appear(reduced), label = "pill-fill",
                )
                val ink by animateColorAsState(
                    when {
                        lit -> Meka.colors.onAccent
                        p.isToday -> Meka.colors.accent
                        p.isPast -> Meka.colors.textTertiary
                        else -> Meka.colors.textPrimary
                    },
                    MekaMotion.appear(reduced), label = "pill-ink",
                )
                val bar = when {
                    lit -> Meka.colors.onAccent.copy(alpha = 0.8f)
                    p.stacking -> Meka.colors.calendar3
                    else -> Meka.colors.accent.copy(alpha = 0.8f)
                }
                Column(
                    Modifier.weight(1f).height(BoardFoldedRules.PILL_HEIGHT.dp).zIndex(if (over) 1f else 0f)
                        .graphicsLayer { scaleX = swell; scaleY = swell }
                        .onGloballyPositioned { if (p.canDrop) onBounds(p.epochDay, it.boundsInRoot()) }
                        .clip(RoundedCornerShape(MekaRadius.m)).background(fill)
                        .then(if (over) Modifier.border(1.dp, Meka.colors.accent, RoundedCornerShape(MekaRadius.m)) else Modifier)
                        .clickable(role = Role.Tab) { onTap(p.epochDay) }
                        .semantics { contentDescription = p.accessibilityLabel; selected = lit },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(p.weekday.take(1), style = MekaType.micro, color = ink.copy(alpha = 0.8f), maxLines = 1)
                    Text(p.number, style = MekaType.upNextCardTitle, color = ink, maxLines = 1)
                    Box(
                        Modifier.padding(top = MekaSpace.xxs).width((p.dots * DOT_DP).dp.coerceAtLeast(1.dp)).height(4.dp)
                            .clip(RoundedCornerShape(MekaRadius.pill))
                            .background(if (p.dots > 0) bar else bar.copy(alpha = 0f)),
                    )
                }
            }
        }
    }
}

/** "Sun 25 October · today" and the plan on the right, the thin plan bar under them (amber when over). */
@Composable
internal fun FoldedDayLineView(day: BoardDay, modifier: Modifier) {
    val reduced = Meka.reducedMotion
    val line = remember(day) { BoardFoldedRules.dayLine(day) }
    val fill by animateFloatAsState(line.fill, MekaMotion.replan(reduced), label = "folded-plan")
    val tone by animateColorAsState(if (line.over) Meka.colors.calendar3 else Meka.colors.accent, MekaMotion.appear(reduced), label = "folded-plan-tone")
    Column(modifier.fillMaxWidth().testTag(FOLDED_DAY_TAG).clearAndSetSemantics { contentDescription = line.spoken }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.animation.Crossfade(line.title, Modifier.weight(1f), MekaMotion.appear(reduced), label = "folded-day") { t ->
                Text(t, style = MekaType.metaStrong, color = Meka.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (line.plan.isNotEmpty()) {
                Spacer(Modifier.width(MekaSpace.xs))
                Text(line.plan, style = MekaType.caption, color = if (line.over) Meka.colors.calendar3 else Meka.colors.textSecondary, maxLines = 1)
            }
        }
        Box(
            Modifier.padding(top = MekaSpace.xs).fillMaxWidth().height(BoardFoldedRules.BAR.dp)
                .clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surface),
        ) {
            Box(Modifier.fillMaxWidth(fill.coerceIn(0f, 1f)).height(BoardFoldedRules.BAR.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(tone))
        }
    }
}

/**
 * Obi's one line in a card (the mockup's orb and words): the Board's nudge, amber when a day is over; a line with a fix
 * moves that card on a tap (Undo on the bar). A new line cross-fades in.
 */
@Composable
internal fun FoldedObiLine(n: BoardNudge, modifier: Modifier, onFix: (PressureFix) -> Unit) {
    val reduced = Meka.reducedMotion
    val accent = Meka.colors.accent
    val tone by animateColorAsState(if (n.warns) Meka.colors.calendar3 else Meka.colors.textSecondary, MekaMotion.appear(reduced), label = "folded-obi-tone")
    val fix = n.fix
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .then(if (fix != null) Modifier.clickable(role = Role.Button) { onFix(fix) } else Modifier)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs)
            .testTag(NUDGE_TAG)
            .clearAndSetSemantics {
                contentDescription = n.spoken
                if (fix != null) role = Role.Button
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(BoardFoldedRules.ORB.dp).clip(CircleShape).background(
                Brush.radialGradient(0f to accent.copy(alpha = 0.45f), 0.4f to accent, 1f to accent.copy(alpha = 0.35f)),
            ),
        )
        Spacer(Modifier.width(MekaSpace.xs))
        androidx.compose.animation.Crossfade(n.line, animationSpec = MekaMotion.appear(reduced), label = "folded-obi") { l ->
            Text(l, style = MekaType.caption, color = tone, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * A task's card on the folded phone, as the mockup (slice 2; Meka 2026-10-29 02:12 (f)/(h)): 12 dp inside, the tick
 * (20 dp, its own 48 dp target) left of the title (15 sp), one quiet line under it with the area's or source's dot and
 * name, the time or length on the right, all in the app's sans. A done card fades (the tick's spark as before).
 */
@Composable
internal fun FoldedCardFace(card: BoardCard, onTick: (() -> Unit)?) {
    val reduced = Meka.reducedMotion
    val f = remember(card) { BoardFoldedRules.card(card) }
    val fade by animateFloatAsState(BoardPlannerRules.alpha(card), MekaMotion.appear(reduced), label = "folded-card-fade")
    val area = BoardAreaRules.of(card)
    val src = card.source
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .alpha(fade).testTag(PLANNER_CARD_TAG)
            .padding(horizontal = BoardFoldedRules.CARD_PAD.dp, vertical = MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TickTarget(card.done, card.title, onTick, column = BoardFoldedRules.TICK.dp + MekaSpace.xxs, height = BoardFoldedRules.TICK.dp + MekaSpace.xxs, circle = BoardFoldedRules.TICK.dp)
        Spacer(Modifier.width(MekaSpace.xs))
        FoldedCardWords(
            f, Modifier.weight(1f),
            dot = when {
                area != null -> Meka.colors.areaTone(area)
                src != null -> Meka.colors.calendarTone(src.tone)
                else -> null
            },
        )
    }
}

/**
 * An event's card on the folded phone: the calendar's colour bar where a task's tick sits, the title, the calendar's
 * name (never an address) and length under it, the start on the right. A tap opens the event.
 */
@Composable
internal fun FoldedEventCard(e: BoardEventCard, onOpen: () -> Unit) {
    val f = remember(e) { BoardFoldedRules.event(e) }
    val tone = Meka.colors.calendarTone(e.tone)
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button, onClick = onOpen)
            .testTag(FOLDED_EVENT_TAG)
            .clearAndSetSemantics { contentDescription = f.spoken; role = Role.Button }
            .padding(horizontal = BoardFoldedRules.CARD_PAD.dp, vertical = BoardFoldedRules.CARD_PAD.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(BoardFoldedRules.EVENT_BAR.dp).fillMaxHeight().clip(RoundedCornerShape(MekaRadius.pill)).background(tone))
        Spacer(Modifier.width(MekaSpace.xs))
        FoldedCardWords(f, Modifier.weight(1f), dot = tone)
    }
}

/** The title (15 sp), the quiet line with its dot, and the time on the right: the folded cards' shared words. */
@Composable
private fun FoldedCardWords(f: FoldedCard, modifier: Modifier, dot: androidx.compose.ui.graphics.Color?) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            Text(f.title, style = MekaType.itemMeta, color = Meka.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (f.meta.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                if (dot != null) {
                    Box(Modifier.size(FOLDED_DOT).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(MekaSpace.xxs + 2.dp))
                }
                Text(f.meta, style = MekaType.micro, color = Meka.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (f.right.isNotEmpty()) {
            Spacer(Modifier.width(MekaSpace.xs))
            Text(f.right, style = MekaType.micro, color = Meka.colors.textSecondary, maxLines = 1, modifier = Modifier.testTag(FOLDED_TIME_TAG))
        }
    }
}

/**
 * The folded moves (slice 3; the approved mockup): what a tap ([open]: the Move to sheet) and a swipe right ([swipe]:
 * Tomorrow) do to a day's card on the folded phone. [editing] is the card whose own menu the sheet's Edit asked for
 * (that card opens it, then calls [onEdited]).
 */
@androidx.compose.runtime.Stable
internal class FoldedMoves(
    val open: (BoardCard) -> Unit,
    val swipe: (BoardCard) -> Unit,
    val canSwipe: (BoardCard) -> Boolean,
    val editing: String?,
    val onEdited: () -> Unit,
    /** Tasks moved on the folded Board so far ([FoldedMoveHint]); the hint line shows below three. */
    val moves: Int,
)

/** How many tasks he has moved on the folded Board on this phone, for the hint line (it hides after three). */
internal object FoldedMoveHint {
    private const val PREFS = "meka.hints"
    private const val KEY = "boardFoldedMoves"

    fun moves(context: android.content.Context): Int =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getInt(KEY, 0)

    /** One more move; returns the new count (stops counting once the hint is gone). */
    fun moved(context: android.content.Context): Int {
        val prefs = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        val n = prefs.getInt(KEY, 0)
        if (n >= BoardFoldedRules.HINT_MOVES) return n
        prefs.edit().putInt(KEY, n + 1).apply()
        return n + 1
    }
}

/** The mockup's quiet hint under the day's cards, until three tasks have been moved. */
@Composable
internal fun FoldedMoveHintLine(modifier: Modifier = Modifier) {
    Text(
        BoardFoldedRules.HINT, style = MekaType.micro, color = Meka.colors.textTertiary,
        modifier = modifier.fillMaxWidth().padding(horizontal = MekaSpace.xxs).testTag(FOLDED_HINT_TAG),
    )
}

/** Behind a card being swiped right: "Tomorrow ›" in the accent, filling in as [progress] nears 1. */
@Composable
internal fun FoldedSwipeReveal(progress: Float, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.accent.copy(alpha = 0.08f + 0.17f * progress))
            .padding(start = BoardFoldedRules.CARD_PAD.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            BoardFoldedRules.TOMORROW + " ›", style = MekaType.metaStrong,
            color = Meka.colors.accent.copy(alpha = 0.4f + 0.6f * progress), maxLines = 1,
        )
    }
}

/**
 * The folded Move to sheet (the approved mockup's tap on a task): it rises over the day on a scrim with the task's
 * title, MOVE TO and its chips (Tomorrow, the week's days with today on, its own day lit, Someday), then Done · Edit ·
 * Close. A chip moves it and closes the sheet ("Moved “…” to Thu 22 Oct · Undo" on the bar); the scrim or Close shuts
 * it. Reduced motion: it fades in rather than rising.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun FoldedMoveSheetView(
    sheet: os.meka.core.domain.FoldedMoveSheet, onMove: (os.meka.core.domain.FoldedMoveChip) -> Unit,
    onDone: () -> Unit, onEdit: () -> Unit, onClose: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val rise = remember { androidx.compose.animation.core.Animatable(if (reduced) 0f else 1f) }
    val shown = remember { androidx.compose.animation.core.Animatable(if (reduced) 0f else 1f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (reduced) shown.animateTo(1f, MekaMotion.appear(true))
        else rise.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 500f))
    }
    val scrimTaps = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(Modifier.fillMaxSize().zIndex(2f).alpha(if (reduced) shown.value else 1f).testTag(FOLDED_SHEET_TAG)) {
        Box(
            Modifier.fillMaxSize().background(Meka.colors.background.copy(alpha = 0.6f))
                .clickable(interactionSource = scrimTaps, indication = null) { onClose() }
                .semantics { contentDescription = BoardFoldedRules.CLOSE },
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .graphicsLayer { translationY = rise.value * size.height }
                // A tap on the sheet itself stays on it (only the scrim closes it).
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }
                .clip(RoundedCornerShape(topStart = MekaRadius.l, topEnd = MekaRadius.l))
                .background(Meka.colors.surfaceRaised)
                .border(1.dp, Meka.colors.hairline, RoundedCornerShape(topStart = MekaRadius.l, topEnd = MekaRadius.l))
                .padding(start = MekaSpace.m, end = MekaSpace.m, top = MekaSpace.m, bottom = MekaSpace.l),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.s),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(MekaSpace.xl).height(4.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.hairline))
            Text(sheet.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(BoardFoldedRules.MOVE_TO.uppercase(), style = MekaType.micro, color = Meka.colors.textSecondary, modifier = Modifier.semantics { heading() })
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
            ) {
                sheet.chips.forEach { c ->
                    Box(
                        Modifier.heightIn(min = MekaSpace.touch).clip(RoundedCornerShape(MekaRadius.pill))
                            .clickable(role = Role.Button) { haptics.light(); onMove(c) }
                            .semantics { contentDescription = c.spoken; selected = c.lit },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            c.label, style = MekaType.itemMeta, color = if (c.lit) Meka.colors.onAccent else Meka.colors.textPrimary, maxLines = 1,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                                .background(if (c.lit) Meka.colors.accent else Meka.colors.surface)
                                .padding(horizontal = MekaSpace.s, vertical = MekaSpace.xs).testTag(FOLDED_CHIP_TAG),
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xxs), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                SheetButton(BoardFoldedRules.DONE, Meka.colors.accent, Meka.colors.onAccent, Modifier.weight(1f)) { haptics.light(); onDone() }
                SheetButton(BoardFoldedRules.EDIT, Meka.colors.surface, Meka.colors.textPrimary, Modifier.weight(1f)) { haptics.tick(); onEdit() }
                SheetButton(BoardFoldedRules.CLOSE, androidx.compose.ui.graphics.Color.Transparent, Meka.colors.textSecondary, Modifier.weight(1f)) { haptics.tick(); onClose() }
            }
        }
    }
}

@Composable
private fun SheetButton(label: String, fill: androidx.compose.ui.graphics.Color, ink: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(SHEET_BUTTON).clip(RoundedCornerShape(MekaRadius.m)).background(fill)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = MekaType.metaStrong, color = ink, maxLines = 1) }
}

private val SHEET_BUTTON = 48.dp
private val FOLDED_DOT = 7.dp
private const val DOT_DP = 5
private val FOLDED_MENU_WIDTH = 200.dp
internal const val FOLDED_MENU_LABEL = "More: agenda view, timeline, plan or review the week, goals, lists"

/** The folded Board's parts (UI tests). */
internal const val FOLDED_HEAD_TAG = "board-folded-head"
internal const val FOLDED_MENU_TAG = "board-folded-menu"
internal const val FOLDED_STRIP_TAG = "board-folded-strip"
internal const val FOLDED_DAY_TAG = "board-folded-day"
internal const val FOLDED_EVENT_TAG = "board-folded-event"
internal const val FOLDED_TIME_TAG = "board-folded-time"
internal const val FOLDED_SHEET_TAG = "board-folded-sheet"
internal const val FOLDED_CHIP_TAG = "board-folded-chip"
internal const val FOLDED_HINT_TAG = "board-folded-hint"
