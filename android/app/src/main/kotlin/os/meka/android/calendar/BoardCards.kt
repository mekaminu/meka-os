package os.meka.android.calendar

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import os.meka.core.domain.BoardHandRules
import os.meka.core.domain.BoardFoldRules
import os.meka.core.domain.BoardBesideRules
import os.meka.core.domain.BoardFoldedRules
import os.meka.core.domain.FoldedMenuKind
import os.meka.core.domain.DayStripPill
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.heading
import os.meka.core.domain.BoardLookRules
import os.meka.core.domain.BoardFitRules
import os.meka.core.domain.BoardPlannerRules
import os.meka.core.domain.BoardOpenFoldRules
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import os.meka.core.domain.CardChip
import os.meka.core.domain.CardChipKind
import os.meka.core.domain.DayProgress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.mekaMenuBorder
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.TouchPill
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.headFade
import os.meka.android.designsystem.areaTone
import os.meka.android.designsystem.calendarTone
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.designsystem.minTouch
import os.meka.core.domain.BoardNudge
import os.meka.core.domain.BoardNudgeRules
import os.meka.core.domain.BoardNudgePlace
import os.meka.core.domain.BoardCard
import os.meka.core.domain.BoardDay
import os.meka.core.domain.BoardGoal
import os.meka.core.domain.BoardGoalRules
import os.meka.core.domain.BoardTidyRules
import os.meka.core.domain.BoardTray
import os.meka.core.domain.GoalPickChoice
import os.meka.core.domain.BoardSource
import os.meka.core.domain.BoardWeek
import os.meka.core.domain.CalendarEvent
import os.meka.core.domain.DayPressure
import os.meka.core.domain.PressureFix
import os.meka.core.domain.TaskEstimateRules
import os.meka.core.domain.BlockOffer
import os.meka.core.domain.BlockSlot
import os.meka.core.domain.BoardBlockRules
import os.meka.core.domain.BoardFreeTime
import os.meka.core.domain.WeekBoard
import os.meka.core.domain.WeekBoardRules
import os.meka.core.domain.BoardArea
import os.meka.core.domain.BoardAreaRules
import os.meka.core.domain.BoardListColumn
import os.meka.core.domain.BoardWeekLayoutRules
import os.meka.core.domain.BoardTimelineRules
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.wrapContentWidth
import os.meka.core.facade.MekaCore
import os.meka.core.domain.BoardMatchRules
import os.meka.core.domain.BoardDenseRules
import os.meka.core.domain.BoardDenseHeadRules
import androidx.compose.ui.unit.sp

// The board's shared pieces (cards, ticks, holds, chips, free time, day columns) used by the Day lists, the folded
// board and the open spread. The old week board that first held them was removed 2026-11-15 (Meka's answer to
// Needs Meka #54: the Day-list board stays as it is).

/** Ada plans with you, part 2: Obi's ✦ thoughts by card, and what a tap or a swipe on one does. */
private class BoardHints(
    val byTask: Map<String, os.meka.core.domain.BoardHint>,
    val onApply: (os.meka.core.domain.BoardHint) -> Unit,
    val onDismiss: (os.meka.core.domain.BoardHint) -> Unit,
)

private val LocalBoardHints = androidx.compose.runtime.staticCompositionLocalOf<BoardHints?> { null }

/** The thought under its card, for the UI tests. */
internal const val BOARD_HINT_TAG = "board-hint"

/** A planner card's chips and area name beside (or under) its tick, for the UI tests. */
internal const val CARD_FOOT_TAG = "board-card-foot"

/**
 * One of Obi's ✦ thoughts under its card: the line in gold, then its one-tap pill and Not now. Swiped either way past
 * a third of its width it is put away; short of that it springs back (Reduced motion: no glide, Not now does it).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun BoardHintRow(hint: os.meka.core.domain.BoardHint, onApply: () -> Unit, onDismiss: () -> Unit) {
    val reduced = Meka.reducedMotion
    val dx = remember(hint.key) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var width by remember { mutableStateOf(1) }
    Column(
        Modifier.fillMaxWidth()
            .onGloballyPositioned { width = it.size.width.coerceAtLeast(1) }
            .graphicsLayer { translationX = dx.value; alpha = 1f - (kotlin.math.abs(dx.value) / width).coerceIn(0f, 0.8f) }
            .then(
                if (reduced) Modifier else Modifier.pointerInput(hint.key) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (kotlin.math.abs(dx.value) > width / 3f) onDismiss()
                            scope.launch { dx.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 600f)) }
                        },
                        onDragCancel = { scope.launch { dx.animateTo(0f) } },
                    ) { change, amount -> change.consume(); scope.launch { dx.snapTo(dx.value + amount) } }
                },
            )
            .testTag(BOARD_HINT_TAG)
            .semantics(mergeDescendants = false) {
                contentDescription = hint.spoken
                customActions = listOf(
                    androidx.compose.ui.semantics.CustomAccessibilityAction(hint.action) { onApply(); true },
                    androidx.compose.ui.semantics.CustomAccessibilityAction(os.meka.core.domain.BoardHintRules.NOT_NOW) { onDismiss(); true },
                )
            }
            .padding(top = MekaSpace.xxs),
    ) {
        // The walk-through 2026-11-02, A6: in the open Fold's narrow columns the line says only how far over (the
        // pill says where), so it no longer takes three lines.
        val dense = LocalBoardDense.current
        Text(if (dense) hint.shownBrief else hint.shown, style = MekaType.micro, color = Meka.colors.accent, maxLines = 3, overflow = TextOverflow.Ellipsis)
        // The walk-through 2026-11-08, A6: "Move to Sat" was cut to "Move to" in a narrow column; there it says "To Sat",
        // and Not now goes under it when the two don't fit side by side whole.
        val pills: @Composable () -> Unit = {
            TouchPill(if (dense) hint.actionBrief else hint.action, spoken = hint.action, pad = if (dense) MekaSpace.xs else MekaSpace.m, fill = Meka.colors.accent.copy(alpha = 0.16f), ink = Meka.colors.accent, onClick = onApply)
            TouchPill(os.meka.core.domain.BoardHintRules.NOT_NOW, fill = Meka.colors.surfaceRaised, ink = Meka.colors.textSecondary, onClick = onDismiss)
        }
        if (dense) FitLine(Modifier.fillMaxWidth(), pills)
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) { pills() }
    }
}

/** Ada plans with you, part 1: Obi's draft of the week while its faint cards show (null otherwise). */
private val LocalWeekDraft = androidx.compose.runtime.staticCompositionLocalOf<os.meka.core.domain.WeekDraft?> { null }

internal const val GHOST_CARD_TAG = "ghost-card"

/**
 * Obi's draft bar: "✦ Obi's draft · 3 tasks over 3 days", what goes where in a line under it, then Keep all (gold),
 * Change and Start over. It grows in over the days (reduced motion: a fade) and goes the same way.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

/** One faint card of Obi's draft: ✦ and its duration on top, the title, a dashed gold edge; it fades in where it would go. */
@Composable
private fun GhostCardView(g: os.meka.core.domain.GhostCard) {
    val reduced = Meka.reducedMotion
    val shown = remember(g.taskId) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(g.taskId) { if (!reduced) shown.animateTo(1f, MekaMotion.appear(false)) }
    val edge = Meka.colors.accent
    Column(
        Modifier.fillMaxWidth().alpha(os.meka.core.domain.WeekDraftRules.GHOST_ALPHA * shown.value)
            .drawBehind {
                val r = MekaRadius.s.toPx()
                drawRoundRect(
                    edge, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(
                        width = 1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
                    ),
                )
            }
            .testTag(GHOST_CARD_TAG)
            .clearAndSetSemantics { contentDescription = g.spoken }
            .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xs),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(os.meka.core.domain.WeekDraftRules.MARK, style = MekaType.micro, color = Meka.colors.accent)
            Spacer(Modifier.weight(1f))
            Text(g.duration, style = MekaType.micro, color = Meka.colors.textTertiary)
        }
        Text(g.title, style = MekaType.body, color = Meka.colors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Option B slice 3: the card menu's AREA rows file a card under one of the five (null outside the Board). */
private val LocalBoardArea = androidx.compose.runtime.staticCompositionLocalOf<((BoardCard, BoardArea?) -> Unit)?> { null }

private const val LIFT = 1.04f
/** How long a dropped card's landing glow and spring last. */
private const val LAND_MS = 700L

/**
 * Option B match: the mockup's weights and sizes. The open Fold's alignment pass (Meka 2026-10-29 02:12 (e)): one
 * typeface, as the folded layout, no serif or mono; times and figures keep even widths with tabular numbers.
 */
internal object BoardType {
    /** "October 2026": the greeting token (34, semibold). */
    val month = MekaType.greeting
    /** "Oct 2026" on the cover screen, and each day's "27 Oct": the now-title token (22, semibold). */
    val monthFolded = MekaType.nowTitle
    val date = MekaType.nowTitle
    /** "W44" beside the month: the item-title token (the cover screen: item meta), tabular figures. */
    val week = MekaType.itemTitle.copy(fontFeatureSettings = TABULAR)
    val weekFolded = MekaType.itemMeta.copy(fontFeatureSettings = TABULAR)
    /** Times, lengths and the budget's figures: the micro token, tabular figures. */
    val mono = MekaType.micro.copy(fontFeatureSettings = TABULAR)
    val monoSmall = mono

    // The density pass (BoardDenseRules): the unfolded board and its lists in the mockup's proportions.
    val dateDense = date.copy(fontSize = BoardDenseRules.DATE_SP.sp, lineHeight = (BoardDenseRules.DATE_SP * 1.2f).sp) // type: ok (the density pass: the mockup's sizes, BoardDenseRules)
    val weekdayDense = MekaType.micro.copy(fontSize = BoardDenseRules.WEEKDAY_SP.sp, lineHeight = (BoardDenseRules.WEEKDAY_SP * 1.3f).sp) // type: ok (the density pass: the mockup's sizes, BoardDenseRules)
    val titleDense = MekaType.caption.copy(fontSize = BoardDenseRules.TITLE_SP.sp, lineHeight = (BoardDenseRules.TITLE_SP * 1.3f).sp) // type: ok (the density pass: the mockup's sizes, BoardDenseRules)
    val monoDense = mono.copy(fontSize = BoardDenseRules.CHIP_SP.sp, lineHeight = (BoardDenseRules.CHIP_SP * 1.3f).sp) // type: ok (the density pass: the mockup's sizes, BoardDenseRules)
    val footDense = MekaType.micro.copy(fontSize = BoardDenseRules.FOOT_SP.sp, lineHeight = (BoardDenseRules.FOOT_SP * 1.3f).sp) // type: ok (the density pass: the mockup's sizes, BoardDenseRules)

    // Density pass slice 2 (BoardDenseHeadRules): the header and "+ add" at the mockup's size.
    val monthDense = month.copy(fontSize = BoardDenseHeadRules.MONTH_SP.sp, lineHeight = (BoardDenseHeadRules.MONTH_SP * 1.15f).sp) // type: ok (density pass slice 2: the mockup's header, BoardDenseHeadRules)
    val weekDense = week.copy(fontSize = BoardDenseHeadRules.WEEK_SP.sp, lineHeight = (BoardDenseHeadRules.WEEK_SP * 1.3f).sp) // type: ok (density pass slice 2: the mockup's header, BoardDenseHeadRules)
    val labelDense = MekaType.itemMeta.copy(fontSize = BoardDenseHeadRules.LABEL_SP.sp, lineHeight = (BoardDenseHeadRules.LABEL_SP * 1.3f).sp) // type: ok (density pass slice 2: the mockup's header, BoardDenseHeadRules)
    val nudgeDense = MekaType.caption.copy(fontSize = BoardDenseHeadRules.NUDGE_SP.sp, lineHeight = (BoardDenseHeadRules.NUDGE_SP * 1.3f).sp) // type: ok (density pass slice 2: the mockup's header, BoardDenseHeadRules)
    val addDense = MekaType.caption.copy(fontSize = BoardDenseHeadRules.ADD_SP.sp, lineHeight = (BoardDenseHeadRules.ADD_SP * 1.3f).sp) // type: ok (density pass slice 2: the mockup's "+ add", BoardDenseHeadRules)

    /** The lists' headings under the weekend (Someday, Side hustle…): a size below a day's date (BoardBesideRules). */
    val listHead = date.copy(fontSize = BoardBesideRules.LIST_HEAD_SP.sp, lineHeight = (BoardBesideRules.LIST_HEAD_SP * 1.25f).sp) // type: ok (the shots beside the mockups: the mockup's list heading, BoardBesideRules)
}

/** Even-width figures for times and lengths (the alignment pass: one typeface, no mono). */
private const val TABULAR = "tnum"

/** The density pass: the lane being drawn is on the unfolded board or the Mac-sized layout (BoardDenseRules). */
internal val LocalBoardDense = staticCompositionLocalOf { false }

/** The month in the Board's header (Option B match, UI tests). */
internal const val MONTH_TAG = "board-month"
/** A day's serif date (Option B match, UI tests). */
internal const val DAY_DATE_TAG = "board-day-date"

/** A day head's empty room for its done bar (no cards), so the pills line up (the walk-through's A6). */
internal const val BAR_SLOT_TAG = "board-bar-slot"
/** An event's card in a day's column (Option B match, UI tests). */
internal const val EVENT_CARD_TAG = "board-event-card"
/** The "+ add" button at the foot of a column (Option B match, UI tests). */
internal const val ADD_TAG = "board-add"

/** Timeline / Hide timeline in the header (Option B slice 5, UI tests). */
internal const val TIMELINE_TOGGLE_TAG = "board-timeline-toggle"

/** Obi's nudge line in the header (Option B slice 4, UI tests). */
internal const val NUDGE_TAG = "board-nudge"

/**
 * ‹ or › in the header. Match slice 4: the mockup's round outlined button (`.btn`), a 40 dp circle on the card surface
 * edged in the hairline, inside the 48 dp touch target; dimmed when there's no week that way.
 */
@Composable
private fun StepButton(
    glyph: String, label: String, enabled: Boolean, size: Int = BoardMatchRules.BUTTON, tag: String = STEP_TAG, onClick: () -> Unit,
) {
    Box(
        Modifier.size(MekaSpace.touch).clip(CircleShape)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.3f)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size.dp).clip(CircleShape).background(Meka.colors.surface)
                .border(1.dp, Meka.colors.hairline, CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(glyph, style = MekaType.itemTitle, color = Meka.colors.textPrimary) }
    }
}

/** ‹ and › in the header (Option B match slice 4, UI tests). */
internal const val STEP_TAG = "board-step"
/** The words beside a card's dot (Option B match slice 4, UI tests). */
internal const val AREA_LABEL_TAG = "board-area-label"
/** "Nothing planned" under an empty past day (Option B match slice 4, UI tests). */
internal const val PAST_EMPTY_TAG = "board-past-empty"

@Composable
private fun DayColumn(
    day: BoardDay,
    width: androidx.compose.ui.unit.Dp,
    /** By hand, slice 4: a past day folded narrow (its number, weekday and "3 done"); tap to open it. */
    collapsed: Boolean,
    /** Opens a folded past day, or folds an opened one again (unfolded only); null elsewhere. */
    onFold: (() -> Unit)?,
    /** The column's own date, done bar and budget pill (folded, the day's line above the strip says them). */
    showHead: Boolean = true,
    /** By hand: where the gold gap opens for a held card (an index into the day's open cards), and its height. */
    gapAt: Int?,
    gapHeight: androidx.compose.ui.unit.Dp,
    /** Cards ticked a moment ago and the place they keep until they settle. */
    held: Map<String, Int>,
    landed: String?,
    onScrollState: (ScrollState) -> Unit,
    adding: Boolean,
    onAddOpen: () -> Unit,
    onAddClose: () -> Unit,
    onAddBounds: (Rect) -> Unit,
    onUndone: (BoardCard) -> Unit,
    hovered: Boolean,
    dragging: String?,
    showFree: Boolean,
    freeHovered: Long?,
    onFreeBounds: (Long, Rect) -> Unit,
    onBounds: (Rect) -> Unit,
    onCardBounds: (String, Rect) -> Unit,
    onPick: (BoardCard, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
    onMove: (BoardCard, Int, Int) -> Unit,
    onMoveTo: (BoardCard, Long) -> Unit,
    onPark: (BoardCard, Long) -> Unit,
    onSomeday: (BoardCard) -> Unit,
    onDone: (BoardCard) -> Unit,
    onAdd: (String, Int?) -> Unit,
    onEstimate: (BoardCard, Int?) -> Unit,
    onBlockOffer: suspend (BoardCard) -> BlockOffer?,
    onBlock: (BoardCard, BlockSlot) -> Unit,
    board: WeekBoard,
    onEvent: (CalendarEvent) -> Unit,
    onTag: (BoardCard, String?) -> Unit,
    onEase: (PressureFix) -> Unit,
    /** The open Fold's alignment pass: the column's whole content height (dp), so its row can fit its fullest day. */
    onContentHeight: ((Float) -> Unit)? = null,
) {
    val reduced = Meka.reducedMotion
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Item (10): a day stacking up wears an amber edge (the gold landing edge wins while a card is held over it).
    val glow by animateColorAsState(
        when {
            hovered -> Meka.colors.accent
            day.pressure != null -> Meka.colors.calendar3.copy(alpha = 0.7f)
            else -> Meka.colors.hairline.copy(alpha = 0f)
        },
        MekaMotion.appear(reduced), label = "board-glow",
    )
    val fill by animateColorAsState(
        when {
            hovered -> Meka.colors.surfaceRaised
            // Option B match: no box round a day; its cards stand on the board (today is the gold date and pill).
            else -> Meka.colors.background
        },
        MekaMotion.appear(reduced), label = "board-fill",
    )
    val vScroll = rememberScrollState()
    SideEffect { onScrollState(vScroll) }
    if (collapsed) {
        CollapsedPastDay(day, width, onBounds, onOpen = { onFold?.invoke() })
        return
    }
    // By hand, slice 8 (the quality gate's findings): a narrow lane fits its words (BoardFitRules).
    val compact = BoardFitRules.compact(width.value)
    CompositionLocalProvider(LocalBoardCompact provides compact, LocalBoardFolded provides !showHead) {
    Column(
        Modifier.width(width).fillMaxHeight().padding(horizontal = MekaSpace.xxs)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .clip(RoundedCornerShape(MekaRadius.m)).background(fill)
            .border(1.dp, glow, RoundedCornerShape(MekaRadius.m))
            .alpha(if (day.isPast) 0.6f else 1f)
            .verticalScroll(vScroll, enabled = dragging == null)
            .then(if (onContentHeight == null) Modifier else Modifier.onSizeChanged { onContentHeight(it.height / density.density) })
            .padding(horizontal = if (compact) MekaSpace.xxs else MekaSpace.xs, vertical = MekaSpace.xs),
    ) {
        // Option B match (Meka 2026-10-24 14:39): the mockup's header, "27 Oct" in serif with "Mon" on the right (today
        // in gold), the thin done bar under it without words, then the budget pill, always "planned · 4h 05 / 3h".
        val head = BoardMatchRules.dayHead(day)
        // The alignment pass (9): the head (date, done bar, pill, the amber note) is drawn as tall as the row's tallest
        // head, measured at its own height inside, so every column's first card starts at the same y.
        val rowHead = LocalRowHead.current
        Box(
            Modifier.fillMaxWidth()
                .then(if (showHead && rowHead != null) Modifier.heightIn(min = rowHead.least.dp) else Modifier),
        ) {
        Column(
            Modifier.fillMaxWidth()
                .then(if (showHead && rowHead != null) Modifier.onSizeChanged { rowHead.report(day.epochDay, it.height / density.density) } else Modifier),
        ) {
        if (showHead) Column(
            Modifier.fillMaxWidth().padding(bottom = MekaSpace.xxs)
                .clearAndSetSemantics { contentDescription = day.accessibilityLabel },
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                val dense = LocalBoardDense.current
                Text(
                    head.date, style = if (dense) BoardType.dateDense else BoardType.date,
                    color = if (day.isToday) Meka.colors.accent else Meka.colors.textPrimary, maxLines = 1,
                    modifier = Modifier.testTag(DAY_DATE_TAG),
                )
                Spacer(Modifier.weight(1f))
                Text(head.weekday, style = if (dense) BoardType.weekdayDense else MekaType.itemMeta, color = Meka.colors.textTertiary, maxLines = 1)
            }
            BoardLookRules.bar(day)?.let { DoneBar(it) }
            // The walk-through's A6: a day with no cards keeps the bar's room, so its pill lines up with its neighbours'.
            if (BoardOpenFoldRules.barSlot(day)) Spacer(Modifier.padding(top = MekaSpace.xxs).height(2.dp).testTag(BAR_SLOT_TAG))
        }
        // The alignment pass (c): one pill on every column, a past day's saying what was done.
        if (showHead) (day.capacity?.let(BoardPlannerRules::budget) ?: BoardOpenFoldRules.pastPill(day))?.let { BudgetPill(it, day.isToday) }
        // Folded, the day's line turns amber ("5h · 1h over") and Obi's line above carries the fix.
        if (showHead) day.pressure?.let { PressureNote(it, onEase) }
        }
        }
        // The day's events are cards in the column too, a blue time chip and their length (the mockup's stand-up).
        val events = BoardMatchRules.eventCards(day)
        if (events.isNotEmpty()) {
            Column(Modifier.padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                events.forEach { e ->
                    if (showHead) EventCard(e) { onEvent(e.event) } else FoldedEventCard(e) { onEvent(e.event) }
                }
                day.moreLabel?.let { Text(it, style = MekaType.micro, color = Meka.colors.textTertiary) }
            }
        }
        // Plan a lesson for a day: "Study · 2 lessons · about 1 h" under the day's events (lit when one rolled over);
        // a tap opens the course on Goals.
        day.study?.let { StudyNote(it) }
        // Free time shows only while a card is held (unfolded), as the drop targets that block time.
        androidx.compose.animation.AnimatedVisibility(
            visible = showFree && dragging != null && day.freeTimes.isNotEmpty(),
            enter = if (reduced) androidx.compose.animation.fadeIn(MekaMotion.appear(true)) else androidx.compose.animation.expandVertically(spring(stiffness = 600f)) + androidx.compose.animation.fadeIn(),
            exit = if (reduced) androidx.compose.animation.fadeOut(MekaMotion.appear(true)) else androidx.compose.animation.shrinkVertically(spring(stiffness = 600f)) + androidx.compose.animation.fadeOut(),
        ) {
            FreeTimeline(day.freeTimes, true, freeHovered, onFreeBounds)
        }
        Spacer(Modifier.height(MekaSpace.xs))
        // The cards in his order (ticked ones keep their place a moment); while a card is held over the day the others
        // slide apart where it would land, a gold line in the gap.
        val byId = day.cards.associateBy { it.id }
        val shown = BoardHandRules.settleOrder(day.cards.map { it.id }, held).mapNotNull(byId::get)
        val openIds = day.openCards.map { it.id }
        Column(verticalArrangement = Arrangement.spacedBy(if (LocalBoardDense.current) BoardDenseRules.CARD_GAP.dp else MekaSpace.xs)) {
            if (openIds.isEmpty()) GapSlot(gapAt != null, gapHeight)
            shown.forEach { card ->
                val openIndex = openIds.indexOf(card.id)
                if (openIndex >= 0) GapSlot(gapAt == openIndex, gapHeight)
                key(card.id) {
                    BoardCardRow(
                        card, BoardSource.DAY, day.epochDay, day.openCards, board, dragging == card.id, onCardBounds, onPick, onDrag, onDrop, onCancel,
                        onMove, onMoveTo, onPark, onSomeday, onDone, onEstimate,
                        onBlockOffer = onBlockOffer, onBlock = onBlock, onTag = onTag, onUndone = onUndone, landed = landed == card.id,
                    )
                }
                // The end of the open cards (done ones sit below).
                if (openIndex == openIds.size - 1) GapSlot(gapAt != null && gapAt >= openIds.size, gapHeight)
            }
        }
        // Ada plans with you, part 1: Obi's draft puts its faint cards after the day's own, until Keep all.
        val ghosts = LocalWeekDraft.current?.let { os.meka.core.domain.WeekDraftRules.ghostsOn(it, board, day.epochDay) }.orEmpty()
        if (ghosts.isNotEmpty()) Column(Modifier.padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            ghosts.forEach { g -> key("ghost-" + g.taskId) { GhostCardView(g) } }
        }
        // The folded moves: the mockup's hint under the day's cards until three tasks have been moved.
        LocalFoldedMoves.current?.takeIf { !showHead && BoardFoldedRules.showsHint(it.moves, day) }?.let {
            FoldedMoveHintLine(Modifier.padding(top = MekaSpace.xs))
        }
        // Option B match: "+ add" at the foot of every day from today, a quiet dashed button; the field opens there.
        if (day.canDrop) AddAtTop(day, adding, onAddOpen, onAddClose, onAddBounds, onAdd, if (showHead) BoardMatchRules.ADD else BoardFoldedRules.addLabel(day))
        // Match slice 4: a past day with nothing on it says so quietly under its date, never a bare column.
        BoardMatchRules.pastEmpty(day)?.let {
            Text(it, style = MekaType.caption, color = Meka.colors.textTertiary, maxLines = 1, modifier = Modifier.testTag(PAST_EMPTY_TAG))
        }
    }
    }
}

/**
 * Plan a lesson for a day: the day's study line, a quiet caption ("Study · 2 lessons · about 1
 * h"), in the accent when a lesson rolled over. A tap opens its course on Goals (light haptic). Cross-fades as lessons
 * are ticked (reduced motion: the same fade).
 */
@Composable
private fun StudyNote(line: os.meka.core.domain.StudyDayLine) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as? os.meka.android.MekaApplication
    val haptics = rememberMekaHaptics()
    androidx.compose.animation.Crossfade(line, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "board-study") { l ->
        Text(
            os.meka.core.domain.LoneWordGlue.kept(l.text), style = MekaType.caption, color = if (l.lit) Meka.colors.accent else Meka.colors.textSecondary,
            // Walk-through 2026-11-02 (A10): a narrow open-Fold column cut "Study · 1 lesson · about 1 h" before its length
            // at two lines; it takes the third it needs instead (the length is the point of the line).
            maxLines = os.meka.core.domain.StudyRules.BOARD_LINES, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = MekaSpace.xs).fillMaxWidth()
                .clip(RoundedCornerShape(MekaRadius.s))
                .clickable(enabled = l.courseId != null, onClickLabel = "Open the course") {
                    haptics.light()
                    app?.openStudy?.value = l.courseId
                    app?.openDestination?.value = os.meka.android.shell.ShellDestination.GOALS
                }
                .heightIn(min = 32.dp).padding(vertical = MekaSpace.xxs)
                .semantics { contentDescription = l.spoken }.testTag(STUDY_NOTE_TAG),
        )
    }
}

internal const val STUDY_NOTE_TAG = "board-study"

/**
 * The alignment pass (9): a row of the open Fold's week: the height [least] (dp) its day heads are drawn at, and where
 * each day reports its head's own height.
 */
internal class RowHead(val least: Float, val report: (Long, Float) -> Unit)

/** The row a day column is drawn in, unfolded (null folded and in the lists). */
internal val LocalRowHead = androidx.compose.runtime.compositionLocalOf<RowHead?> { null }

/** By hand, slice 8: the lane being drawn is compact (BoardFitRules); trays and the folded page are roomy. */
internal val LocalBoardCompact = staticCompositionLocalOf { false }
/** Option B match, slice 2: a card in one of the lists at the board's foot (its top line only with a time or length). */
internal val LocalBoardInList = staticCompositionLocalOf { false }
/** The folded mockup, slice 2: the day's cards on the folded phone take the mockup's compact face (FoldedCardFace). */
internal val LocalBoardFolded = staticCompositionLocalOf { false }
/** The folded moves, slice 3: what a tap and a swipe on a folded day's card do (null unfolded). */
internal val LocalFoldedMoves = androidx.compose.runtime.compositionLocalOf<FoldedMoves?> { null }

/**
 * By hand, slice 4: a past day folded narrow, unfolded: its big number over the weekday and "3 done" (or "Nothing
 * done"), quiet on the background. Tap it to open the day at full width (the lanes ease to their new widths).
 */
@Composable
private fun CollapsedPastDay(day: BoardDay, width: androidx.compose.ui.unit.Dp, onBounds: (Rect) -> Unit, onOpen: () -> Unit) {
    val done = day.cards.count { it.done }
    Column(
        Modifier.width(width).fillMaxHeight().padding(horizontal = MekaSpace.xxs)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .clip(RoundedCornerShape(MekaRadius.m))
            .border(1.dp, Meka.colors.hairline, RoundedCornerShape(MekaRadius.m))
            .clickable(role = Role.Button, onClick = onOpen)
            .clearAndSetSemantics { contentDescription = BoardTidyRules.collapsedLabel(day); onClick { onOpen(); true } }
            .padding(vertical = MekaSpace.m),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(day.number, style = MekaType.nowTitle, color = Meka.colors.textSecondary, maxLines = 1)
        Text(day.weekday, style = MekaType.micro, color = Meka.colors.textTertiary, maxLines = 1)
        Spacer(Modifier.height(MekaSpace.xs))
        // "3 done" stacked to fit the narrow lane ("Nothing done" when none was), never a dash.
        if (done == 0) Text("Nothing", style = MekaType.micro, color = Meka.colors.textTertiary, maxLines = 1)
        else Text("$done", style = MekaType.metaStrong, color = Meka.colors.accent, maxLines = 1)
        Text("done", style = MekaType.micro, color = Meka.colors.textTertiary, maxLines = 1)
    }
}

@Composable
private fun BoardCardRow(
    card: BoardCard,
    /** Where it sits: a day ([key] its epoch day), a week's lane ([key] its Monday) or the Someday tray. */
    source: BoardSource,
    key: Long,
    /** The open cards it moves up and down among (a day's), or empty for none. */
    siblings: List<BoardCard>,
    board: WeekBoard,
    beingDragged: Boolean,
    onCardBounds: (String, Rect) -> Unit,
    onPick: (BoardCard, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
    onMove: (BoardCard, Int, Int) -> Unit,
    onMoveTo: (BoardCard, Long) -> Unit,
    onPark: (BoardCard, Long) -> Unit,
    onSomeday: (BoardCard) -> Unit,
    onDone: ((BoardCard) -> Unit)?,
    onEstimate: (BoardCard, Int?) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    /** Slice 6: a day's card offers Block time (read as the menu opens); null in the lane and the tray. */
    onBlockOffer: (suspend (BoardCard) -> BlockOffer?)? = null,
    onBlock: ((BoardCard, BlockSlot) -> Unit)? = null,
    /** Slice 7: FOR A GOAL — one of its week's goals, or none. */
    onTag: ((BoardCard, String?) -> Unit)? = null,
    /** By hand: a second tap on a done card's tick un-ticks it (a day's cards). */
    onUndone: ((BoardCard) -> Unit)? = null,
    /** By hand: it was just dropped here, so it settles with a little spring and a gold glow. */
    landed: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    val onArea = LocalBoardArea.current
    var offer by remember(card.id) { mutableStateOf<BlockOffer?>(null) }
    if (onBlockOffer != null) {
        LaunchedEffect(menu) { if (menu) offer = runCatching { onBlockOffer(card) }.getOrNull() }
    }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val reduced = Meka.reducedMotion
    val ghost by animateFloatAsState(if (beingDragged) 0.3f else 1f, MekaMotion.appear(reduced), label = "board-ghost")
    // By hand, slice 1: the landing spring (0.96 → 1) and a gold glow that fades (Reduced motion: the glow only).
    val land = remember { Animatable(1f) }
    val glow = remember { Animatable(0f) }
    LaunchedEffect(landed) {
        if (!landed) return@LaunchedEffect
        glow.snapTo(1f)
        if (!reduced) { land.snapTo(0.96f); launch { land.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 500f)) } }
        glow.animateTo(0f, tween(LAND_MS.toInt()))
    }
    // One gesture for the card: a tap opens its menu; held still for 250 ms it lifts (tick haptic) and follows the
    // finger until let go. Moving first is a scroll, which the board keeps. The latest callbacks, never stale ones.
    // The folded moves (slice 3): on the folded phone a day's card opens the Move to sheet on a tap (its Edit opens
    // this menu), and a swipe right sends it to Tomorrow.
    val folded = LocalFoldedMoves.current?.takeIf { LocalBoardFolded.current && source == BoardSource.DAY }
    if (folded != null) {
        LaunchedEffect(folded.editing) { if (folded.editing == card.id) { menu = true; folded.onEdited() } }
    }
    val canSwipe = folded != null && card.canMove && folded.canSwipe(card)
    val swipeX = remember { Animatable(0f) }
    val rowScope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val swipeAt = with(LocalDensity.current) { BoardFoldedRules.SWIPE_DP.dp.toPx() }
    val tap by rememberUpdatedState<() -> Unit> { if (folded != null) folded.open(card) else { menu = true } }
    val lift by rememberUpdatedState<(Offset) -> Unit> { at -> onPick(card, bounds.topLeft + at) }
    val move by rememberUpdatedState(onDrag)
    val drop by rememberUpdatedState(onDrop)
    val cancel by rememberUpdatedState(onCancel)
    val swiped by rememberUpdatedState<(Float) -> Unit> { dx ->
        val was = swipeX.value >= swipeAt
        val to = if (reduced) 0f else dx.coerceAtLeast(0f)
        rowScope.launch { swipeX.snapTo(to) }
        if (!reduced && !was && to >= swipeAt) haptics.tick()
    }
    val swipeEnd by rememberUpdatedState<(Float) -> Unit> { dx ->
        if (dx >= swipeAt) folded?.swipe(card)
        rowScope.launch { if (reduced) swipeX.snapTo(0f) else swipeX.animateTo(0f, spring(dampingRatio = 0.8f, stiffness = 600f)) }
    }
    val hints = LocalBoardHints.current
    val hint = hints?.byTask?.get(card.id)?.takeIf { !card.done && source != BoardSource.SOMEDAY }
    Column(modifier) {
    Box(
        Modifier.fillMaxWidth().alpha(ghost)
            .onGloballyPositioned { bounds = it.boundsInRoot(); onCardBounds(card.id, bounds) }
            .graphicsLayer { scaleX = land.value; scaleY = land.value }
            .then(
                if (card.canMove) Modifier.boardCardHold(
                    card.id, onTap = { tap() }, onLift = { lift(it) }, onMove = { move(it) }, onDrop = { drop() }, onCancel = { cancel() },
                    onSwipe = { dx: Float -> swiped(dx) }.takeIf { canSwipe },
                    onSwipeEnd = { dx: Float -> swipeEnd(dx) }.takeIf { canSwipe },
                )
                else Modifier,
            )
            .clip(TILE)
            .border(1.5.dp, Meka.colors.accent.copy(alpha = glow.value), TILE)
            .semantics {
                contentDescription = card.accessibilityLabel
                if (card.canMove) {
                    role = Role.Button
                    onClick { tap(); true }
                    if (canSwipe) customActions = listOf(androidx.compose.ui.semantics.CustomAccessibilityAction(BoardFoldedRules.TOMORROW) { folded?.swipe(card); true })
                }
            },
    ) {
        val tick: (() -> Unit)? = when {
            card.done -> onUndone?.let { f -> { f(card) } }
            else -> onDone?.let { f -> { f(card) } }
        }
        // Swiping right: "Tomorrow ›" shows behind the card, filling in gold as it nears the point where it goes.
        if (swipeX.value > 0f) FoldedSwipeReveal(BoardFoldedRules.swipeProgress(swipeX.value, swipeAt), Modifier.matchParentSize())
        // The folded mockup, slice 2: a day's card on the folded phone is the compact face (tick beside the title).
        Box(Modifier.graphicsLayer { translationX = swipeX.value }) {
            if (LocalBoardFolded.current && source == BoardSource.DAY) FoldedCardFace(card, tick) else CardFace(card, lifted = false, onTick = tick)
        }
        if (card.canMove) {
            DropdownMenu(
                expanded = menu, onDismissRequest = { menu = false },
                containerColor = Meka.colors.surfaceRaised, border = mekaMenuBorder(Meka.colors),
            ) {
                @Composable
                fun entry(label: String, onClick: () -> Unit) = DropdownMenuItem(
                    text = { Text(label, style = MekaType.itemMeta, color = Meka.colors.textPrimary) },
                    onClick = { menu = false; onClick() },
                )
                val index = siblings.indexOfFirst { it.id == card.id }
                if (index > 0) entry("Move up") { onMove(card, 0, -1) }
                if (index in 0 until siblings.size - 1) entry("Move down") { onMove(card, 0, 1) }
                // The day strip: today and the next six days, except the day it's on.
                (board.todayEpochDay until board.todayEpochDay + 7).filter { source != BoardSource.DAY || it != key }.forEach { d ->
                    val label = when (d) {
                        board.todayEpochDay -> "Today"
                        board.todayEpochDay + 1 -> "Tomorrow"
                        else -> board.day(d)?.let { "${it.weekday} ${it.number}" } ?: return@forEach
                    }
                    entry(label) { onMoveTo(card, d) }
                }
                // Slice 4: no day yet this week (a repeat belongs to its days), or Someday.
                if (!card.task.isRepeating) {
                    val thisWeek = board.weeks.firstOrNull()?.startEpochDay
                    if (thisWeek != null && !(source == BoardSource.WEEK && key == thisWeek)) entry(WeekBoardRules.TO_WEEK) { onPark(card, thisWeek) }
                    if (source != BoardSource.SOMEDAY) entry(WeekBoardRules.TO_SOMEDAY) { onSomeday(card) }
                }
                // Slice 3: how long it takes, Navilife's guess marked; nothing is set until he taps one.
                Box(Modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs).height(1.dp).background(Meka.colors.hairline))
                Text(
                    TaskEstimateRules.MENU_TITLE, style = MekaType.micro, color = Meka.colors.textTertiary,
                    modifier = Modifier.padding(horizontal = MekaSpace.m, vertical = MekaSpace.xxs),
                )
                card.estimateChoices.forEach { c -> entry(c.menuLabel) { if (!c.selected) onEstimate(card, c.minutes) } }
                if (card.estimate != null) entry(TaskEstimateRules.CLEAR) { onEstimate(card, null) }
                // Slice 7: FOR A GOAL — the week's goals (the one it has ticked), or No goal.
                val tags = if (onTag != null) BoardGoalRules.tagChoices(board, card.id) else emptyList()
                if (tags.isNotEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs).height(1.dp).background(Meka.colors.hairline))
                    Text(
                        BoardGoalRules.MENU_TITLE, style = MekaType.micro, color = Meka.colors.textTertiary,
                        modifier = Modifier.padding(horizontal = MekaSpace.m, vertical = MekaSpace.xxs),
                    )
                    tags.forEach { t -> entry(t.label) { if (!t.selected) onTag?.invoke(card, t.goalId) } }
                }
                // Option B slice 3: AREA — Family, Work, Side hustle, Money, House (the one it shows ticked), or No area.
                if (onArea != null) {
                    Box(Modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs).height(1.dp).background(Meka.colors.hairline))
                    Text(
                        BoardAreaRules.MENU_TITLE, style = MekaType.micro, color = Meka.colors.textTertiary,
                        modifier = Modifier.padding(horizontal = MekaSpace.m, vertical = MekaSpace.xxs),
                    )
                    BoardAreaRules.choices(card).forEach { c -> entry(c.label) { if (!c.selected) onArea(card, c.area) } }
                }
                // Slice 6: Block time — a free start in the day; his tap adds the block to his calendar (Undo on the bar).
                offer?.let { o ->
                    Box(Modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs).height(1.dp).background(Meka.colors.hairline))
                    Text(
                        o.heading, style = MekaType.micro, color = Meka.colors.textTertiary,
                        modifier = Modifier.padding(horizontal = MekaSpace.m, vertical = MekaSpace.xxs),
                    )
                    if (o.canBlock) o.slots.forEach { slot -> entry(slot.label) { onBlock?.invoke(card, slot) } }
                    Text(
                        o.line, style = MekaType.micro, color = Meka.colors.textTertiary,
                        modifier = Modifier.padding(horizontal = MekaSpace.m, vertical = MekaSpace.xxs).widthIn(max = 220.dp),
                    )
                }
            }
        }
    }
    if (hint != null && hints != null && !beingDragged) BoardHintRow(hint, onApply = { hints.onApply(hint) }, onDismiss = { hints.onDismiss(hint) })
    }
}

/**
 * A card as a raised tile (slice 2, the look): the tick on the left with its own 48 dp target (separate from the card's
 * tap for its menu), the title (body, up to two lines, struck through and dimmed once done), then small chips (time,
 * estimate, carried in amber, repeat, goal in gold). A done card sinks to the lane's colour, no chips. The lifted card
 * wears a gold edge.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CardFace(card: BoardCard, lifted: Boolean, onTick: (() -> Unit)? = null) {
    // Option B, slice 1 (the planner cards, BoardPlannerRules): a borderless tile on the lighter surface; the time chip
    // and the duration on top, the title across the whole card, the tick and the card's chips and source in the foot. A
    // done card fades (its words stay plain, never struck through); the fade glides (Reduced motion: it steps).
    val reduced = Meka.reducedMotion
    val compact = LocalBoardCompact.current
    val fade by animateFloatAsState(if (lifted) 1f else BoardPlannerRules.alpha(card), MekaMotion.appear(reduced), label = "card-fade")
    // Option B match: every card has its top line, the time chip (gold, or a quiet "Any time") and its length; in the
    // lists at the foot (slice 2) only when it has a time or a length, as the mockup's list cards are title and foot.
    // The density pass: no "Any time" chip; a card with no time shows its length alone, with neither no top line.
    val top = if (LocalBoardInList.current) BoardMatchRules.listTop(card) else BoardMatchRules.top(card)
    val foot = BoardPlannerRules.footChips(card)
    val dense = LocalBoardDense.current
    val pad = if (dense) BoardDenseRules.CARD_PAD.dp else MekaSpace.xs
    Column(
        Modifier.fillMaxWidth()
            .background(Meka.colors.surfaceRaised)
            .then(if (lifted) Modifier.border(1.5.dp, Meka.colors.accent, TILE) else Modifier)
            .alpha(fade)
            .testTag(PLANNER_CARD_TAG)
            .padding(start = pad, end = pad, top = pad, bottom = if (dense) MekaSpace.xxs + 2.dp else MekaSpace.xxs),
    ) {
        if (top != null) Row(Modifier.fillMaxWidth().padding(bottom = if (dense) 6.dp else MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
            top.time?.let { TimeChip(it, ChipTone.TASK) }
            Spacer(Modifier.weight(1f))
            top.duration?.let { Text(it, style = if (dense) BoardType.monoDense else BoardType.monoSmall, color = Meka.colors.textTertiary, maxLines = 1) }
        }
        Text(
            card.title, style = when { dense -> BoardType.titleDense; compact -> MekaType.caption; else -> MekaType.body }, maxLines = BoardFitRules.titleLines(compact),
            overflow = TextOverflow.Ellipsis, color = Meka.colors.textPrimary,
            textDecoration = if (card.done && BoardPlannerRules.STRIKE_DONE) TextDecoration.LineThrough else null,
        )
        // The walk-throughs 2026-11-02 and 2026-11-08, A6 ("Side h…", then Boiler service's "Ot…", "H…" in a lane): the
        // chips and the area's name sit beside the tick only when they fit there whole; otherwise they go under it, and
        // the name under the chips, so each is cut short only if even the card's full width is too narrow. (A FlowRow
        // measured each in the room left on the line, so the ellipsis took it before the line could break.)
        FitLine(Modifier.fillMaxWidth()) {
            // The tick keeps its own target (Compose widens a small one's touch area to 48 dp past its edges).
            TickTarget(
                card.done, card.title, onTick, column = if (dense) DENSE_TICK_COLUMN else FOOT_TICK, height = if (dense) DENSE_TICK_COLUMN else FOOT_TICK,
                circle = if (dense) BoardDenseRules.TICK.dp else 20.dp,
            )
            FitLine(Modifier.testTag(CARD_FOOT_TAG)) {
                if (foot.isNotEmpty()) {
                    // A Row, not a flow, so its natural width is exact (a card carries one or two short chips).
                    Row(
                        Modifier.padding(vertical = MekaSpace.xxs),
                        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { foot.forEach { CardChipPill(it) } }
                }
                // Option B slice 3: the area's colour dot and name ("● Family"); a card with no area keeps slice 5's dot
                // for where it came from ("● Mail"). Match slice 4: the name shows on the narrow lanes too, cut short
                // with "…" if it must be. The screen reader hears either in the card's label.
                val area = BoardAreaRules.of(card)
                val src = card.source
                val words = BoardMatchRules.footLabel(card)
                if (area != null || src != null) {
                    Row(Modifier.padding(start = MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
                        val dot = if (area != null) Meka.colors.areaTone(area) else Meka.colors.calendarTone(src!!.tone)
                        Box(Modifier.size(if (dense) BoardDenseRules.DOT.dp else 8.dp).clip(CircleShape).background(dot).testTag(if (area != null) AREA_DOT_TAG else SOURCE_DOT_TAG))
                        if (words != null && BoardMatchRules.footWords(compact)) {
                            Spacer(Modifier.width(if (dense) 5.dp else MekaSpace.xxs))
                            Text(
                                words, style = if (dense) BoardType.footDense else MekaType.micro, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                // Silent (the card's label says it); only the tag, for the UI tests.
                                modifier = Modifier.clearAndSetSemantics { testTag = AREA_LABEL_TAG },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A card's foot line: its children side by side when their natural widths fit (the first at the start, the rest
 * packed at the end, all centred on the line), else one under another, the first at the start and the rest at the end,
 * each given the full width (so a long one is cut short only when even that is too narrow).
 */
@Composable
private fun FitLine(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.ui.layout.Layout(content, modifier) { items, c ->
        val loose = c.copy(minWidth = 0, minHeight = 0)
        val natural = items.map { it.maxIntrinsicWidth(loose.maxHeight) }
        val fits = natural.sum() <= c.maxWidth
        if (fits) {
            val placed = items.mapIndexed { i, m -> m.measure(loose.copy(maxWidth = natural[i].coerceAtMost(c.maxWidth))) }
            val width = if (c.hasBoundedWidth && c.minWidth == c.maxWidth) c.maxWidth else placed.sumOf { it.width }.coerceIn(c.minWidth, c.maxWidth)
            val height = (placed.maxOfOrNull { it.height } ?: 0).coerceIn(c.minHeight, c.maxHeight)
            layout(width, height) {
                placed.firstOrNull()?.placeRelative(0, (height - placed[0].height) / 2)
                var x = width
                placed.drop(1).reversed().forEach { p -> x -= p.width; p.placeRelative(x, (height - p.height) / 2) }
            }
        } else {
            val placed = items.map { it.measure(loose) }
            val width = if (c.hasBoundedWidth && c.minWidth == c.maxWidth) c.maxWidth else (placed.maxOfOrNull { it.width } ?: 0).coerceIn(c.minWidth, c.maxWidth)
            val height = placed.sumOf { it.height }.coerceIn(c.minHeight, c.maxHeight)
            layout(width, height) {
                var y = 0
                placed.forEachIndexed { i, p -> p.placeRelative(if (i == 0) 0 else width - p.width, y); y += p.height }
            }
        }
    }
}

/** A time chip's colours: gold for a task's planned time, blue for an event, quiet for "Any time". */
private enum class ChipTone { TASK, EVENT, QUIET }

/** The time chip on a card's top line (the mockup's `.tm`): mono figures on a faint tint, gold, blue or quiet. */
@Composable
private fun TimeChip(time: String, tone: ChipTone = ChipTone.TASK) {
    val dense = LocalBoardDense.current
    val ink = when (tone) {
        ChipTone.TASK -> Meka.colors.accent
        ChipTone.EVENT -> Meka.colors.calendar4
        ChipTone.QUIET -> Meka.colors.textTertiary
    }
    Text(
        time, style = if (dense) BoardType.monoDense else BoardType.monoSmall, color = ink, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(if (dense) 4.dp else 6.dp)).background(if (tone == ChipTone.QUIET) Meka.colors.surface else ink.copy(alpha = 0.16f))
            .padding(horizontal = if (dense) 6.dp else MekaSpace.xs, vertical = if (dense) 2.dp else MekaSpace.xxs),
    )
}

/**
 * Option B match: one of the day's events as a card in its column (the mockup's stand-up): the blue time chip and its
 * length on top, the title, then the calendar's dot and name in the foot. A tap opens the event.
 */
@Composable
private fun EventCard(e: os.meka.core.domain.BoardEventCard, onOpen: () -> Unit) {
    val compact = LocalBoardCompact.current
    val dense = LocalBoardDense.current
    Column(
        Modifier.fillMaxWidth().clip(TILE).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button, onClick = onOpen)
            .testTag(EVENT_CARD_TAG)
            .clearAndSetSemantics { contentDescription = e.spoken; role = Role.Button; onClick { onOpen(); true } }
            .padding(if (dense) BoardDenseRules.CARD_PAD.dp else MekaSpace.xs),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TimeChip(e.time, ChipTone.EVENT)
            Spacer(Modifier.weight(1f))
            e.duration?.let { Text(it, style = if (dense) BoardType.monoDense else BoardType.monoSmall, color = Meka.colors.textTertiary, maxLines = 1) }
        }
        Text(
            e.title, style = when { dense -> BoardType.titleDense; compact -> MekaType.caption; else -> MekaType.body }, color = Meka.colors.textPrimary,
            maxLines = BoardFitRules.titleLines(compact), overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = if (dense) 6.dp else MekaSpace.xs),
        )
        Row(Modifier.fillMaxWidth().padding(top = if (dense) 6.dp else MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(if (dense) BoardDenseRules.DOT.dp else 8.dp).clip(CircleShape).background(Meka.colors.calendarTone(e.tone)))
            // Match slice 4: the calendar's name on the narrow lanes too, cut short if it must be.
            if (BoardMatchRules.footWords(compact)) {
                Spacer(Modifier.width(MekaSpace.xxs))
                Text(
                    e.calendar, style = if (dense) BoardType.footDense else MekaType.micro, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

/** The tick's drawn square in a planner card's foot (dp); Compose widens its touch area to 48 dp past its edges. */
private val FOOT_TICK = 24.dp
/** The density pass: the tick's column on the unfolded board (its touch area still widened to 48 dp). */
private val DENSE_TICK_COLUMN = 20.dp

/** The colour dot on a Board card that came from mail, Needs you, Discord or School (slice 5, UI tests). */
internal const val SOURCE_DOT_TAG = "board-source-dot"
/** Option B slice 3: the area's colour dot in a card's foot. */
internal const val AREA_DOT_TAG = "board-area-dot"

/** A Board card's face (Option B slice 1, UI tests read its fade). */
internal const val PLANNER_CARD_TAG = "board-planner-card"

/** The tile's shape (slice 2): rounded like the design's cards. */
private val TILE = RoundedCornerShape(MekaRadius.m)

/** One small chip under a card's title: gold for its estimate and goal, amber when carried, quiet otherwise. */
@Composable
private fun CardChipPill(chip: CardChip) {
    val amber = Meka.colors.calendar3
    val (ink, fill) = when (chip.kind) {
        CardChipKind.CARRIED -> amber to amber.copy(alpha = 0.14f)
        CardChipKind.ESTIMATE, CardChipKind.GOAL -> Meka.colors.accent to Meka.colors.accent.copy(alpha = 0.12f)
        else -> Meka.colors.textSecondary to Meka.colors.surface
    }
    Text(
        chip.label, style = MekaType.micro, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(fill).padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs),
    )
}

/**
 * The thin done bar under a day's header (Option B match: no "2 of 5 done" words, the screen reader still hears them),
 * filling gold as cards are ticked; the width glides (Reduced motion: it steps).
 */
@Composable
private fun DoneBar(p: DayProgress) {
    val reduced = Meka.reducedMotion
    val fill by animateFloatAsState(p.fill, MekaMotion.appear(reduced), label = "day-progress")
    Box(Modifier.padding(top = MekaSpace.xxs).fillMaxWidth().height(2.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.hairline)) {
        Box(Modifier.fillMaxWidth(fill).height(2.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent))
    }
}

/**
 * The tick (by hand, slice 1): a 20 dp circle in its own 48 dp target. A tap fills it gold with a tick and a spark ring
 * that grows and fades (600 ms); tapped again it empties. Reduced motion: the fill cross-fades, no spark.
 */
@Composable
internal fun TickTarget(
    done: Boolean, title: String, onTick: (() -> Unit)?, column: androidx.compose.ui.unit.Dp = BoardHandRules.CHECK_TARGET.dp,
    height: androidx.compose.ui.unit.Dp = BoardHandRules.CHECK_TARGET.dp,
    /** The drawn circle (the density pass draws 18 dp on the unfolded board). */
    circle: androidx.compose.ui.unit.Dp = 20.dp,
) {
    val reduced = Meka.reducedMotion
    val gold = Meka.colors.accent
    val ink = Meka.colors.background
    val fill by animateColorAsState(if (done) gold else gold.copy(alpha = 0f), MekaMotion.appear(reduced), label = "tick-fill")
    val edge by animateColorAsState(if (done) gold else Meka.colors.textTertiary, MekaMotion.appear(reduced), label = "tick-edge")
    val spark = remember { Animatable(1f) }
    var was by remember { mutableStateOf(done) }
    LaunchedEffect(done) {
        if (done && !was && !reduced) { spark.snapTo(0f); spark.animateTo(1f, tween(600)) }
        was = done
    }
    Box(
        // Slice 8: on a compact lane the column is 28 dp wide (48 tall); Compose widens a small clickable's touch area to
        // the 48 dp minimum past its edges, so the tick stays as easy to hit while the title gets the width.
        Modifier.width(column).height(height)
            .then(if (onTick != null) Modifier.clip(CircleShape).clickable(role = Role.Checkbox) { onTick() } else Modifier)
            .semantics { contentDescription = BoardHandRules.tickLabel(title, done) },
        contentAlignment = Alignment.Center,
    ) {
        if (spark.value < 1f) {
            Box(
                Modifier.size(circle).graphicsLayer {
                    val p = spark.value
                    scaleX = 0.4f + 2f * p; scaleY = 0.4f + 2f * p; alpha = 1f - p
                }.border(2.dp, gold, CircleShape),
            )
        }
        Box(Modifier.size(circle).clip(CircleShape).background(fill).border(1.5.dp, edge, CircleShape), contentAlignment = Alignment.Center) {
            if (done) {
                Canvas(Modifier.size(circle * 0.6f)) {
                    val w = size.width
                    val path = Path().apply { moveTo(w * 0.21f, w * 0.52f); lineTo(w * 0.40f, w * 0.71f); lineTo(w * 0.79f, w * 0.29f) }
                    drawPath(path, ink, style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}

/**
 * Where a held card would land (by hand, slice 1): the gap the other cards slide apart to make, the card's height, with
 * a gold line through it. It opens and closes on the expand spring (Reduced motion: it fades).
 */
@Composable
private fun GapSlot(open: Boolean, height: androidx.compose.ui.unit.Dp) {
    val reduced = Meka.reducedMotion
    androidx.compose.animation.AnimatedVisibility(
        visible = open,
        enter = if (reduced) androidx.compose.animation.fadeIn(MekaMotion.appear(true)) else androidx.compose.animation.expandVertically(spring(stiffness = 600f)) + androidx.compose.animation.fadeIn(),
        exit = if (reduced) androidx.compose.animation.fadeOut(MekaMotion.appear(true)) else androidx.compose.animation.shrinkVertically(spring(stiffness = 600f)) + androidx.compose.animation.fadeOut(),
    ) {
        Box(
            Modifier.fillMaxWidth().height(height).clip(TILE).background(Meka.colors.accent.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = MekaSpace.xs).height(2.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent))
        }
    }
}

/**
 * "+ Add a task" at the top of a day (by hand, slice 1), always there as a dashed row. A tap opens a field with the
 * keyboard up at once; Return adds the task last in the day and keeps the field open for the next; the chips under it
 * (15m · 30m · 1h · 2h) give the next one an estimate; a tap anywhere else (or Return on an empty field) closes it,
 * adding what was typed. The field opens on the expand spring (Reduced motion: it fades).
 */
@Composable
private fun AddAtTop(
    day: BoardDay,
    open: Boolean,
    onOpen: () -> Unit,
    onClose: () -> Unit,
    onBounds: (Rect) -> Unit,
    onAdd: (String, Int?) -> Unit,
    /** "+ add", or the folded mockup's "+ Add to Sun". */
    label: String = BoardMatchRules.ADD,
) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val dense = LocalBoardDense.current
    var text by remember(day.epochDay) { mutableStateOf("") }
    var picked by remember(day.epochDay) { mutableStateOf<Int?>(null) }
    val hairline = Meka.colors.hairline
    fun add() {
        if (text.isNotBlank()) onAdd(text.trim(), picked)
        text = ""; picked = null
    }
    androidx.compose.animation.AnimatedContent(
        targetState = open,
        transitionSpec = {
            if (reduced) androidx.compose.animation.fadeIn(MekaMotion.appear(true)) togetherWith androidx.compose.animation.fadeOut(MekaMotion.appear(true))
            else (androidx.compose.animation.fadeIn() + androidx.compose.animation.expandVertically(spring(stiffness = 600f))) togetherWith androidx.compose.animation.fadeOut()
        },
        label = "board-add",
        modifier = Modifier.fillMaxWidth().padding(top = MekaSpace.xs),
    ) { isOpen ->
        if (!isOpen) {
            // Density pass slice 2: unfolded, a slim dashed row (28 high, 11 type) as the mockup's.
            Box(
                Modifier.fillMaxWidth().height(if (dense) BoardDenseHeadRules.ADD_HEIGHT.dp else 40.dp).testTag(ADD_TAG)
                    .drawBehind {
                        // The alignment pass (4): the dashed line inside the edge, so the column's clip keeps its corners.
                        val inset = BoardOpenFoldRules.ADD_STROKE_INSET.dp.toPx()
                        drawRoundRect(
                            hairline, topLeft = Offset(inset, inset),
                            size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(MekaRadius.m.toPx() - inset),
                            style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                        )
                    }
                    .clip(RoundedCornerShape(MekaRadius.m))
                    .clickable(role = Role.Button) { onOpen() }
                    .semantics { contentDescription = BoardHandRules.addLabel(day.title) }
                    .padding(horizontal = MekaSpace.m),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, style = if (dense) BoardType.addDense else MekaType.caption, color = Meka.colors.textTertiary,
                    modifier = if (label == BoardMatchRules.ADD) Modifier else Modifier.fillMaxWidth(),
                )
            }
        } else {
            val focus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            var focused by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { focus.requestFocus(); keyboard?.show() }
            Column(
                Modifier.fillMaxWidth().onGloballyPositioned { onBounds(it.boundsInRoot()) }
                    .clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
                    .border(1.dp, Meka.colors.accent, RoundedCornerShape(MekaRadius.m))
                    .padding(MekaSpace.xs),
            ) {
                Box(Modifier.fillMaxWidth().padding(horizontal = MekaSpace.xxs, vertical = MekaSpace.xs)) {
                    if (text.isEmpty()) Text(BoardHandRules.ADD_HINT, style = MekaType.body, color = Meka.colors.textTertiary)
                    BasicTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
                        cursorBrush = SolidColor(Meka.colors.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        // Return adds it and keeps the field (and the keyboard) for the next one; empty, it closes.
                        keyboardActions = KeyboardActions(onDone = { if (text.isBlank()) onClose() else add() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus)
                            .onFocusChanged { f ->
                                if (focused && !f.isFocused) { add(); onClose() }
                                focused = f.isFocused
                            }
                            .semantics { contentDescription = BoardHandRules.addLabel(day.title) },
                    )
                }
                Row(Modifier.padding(top = MekaSpace.xxs), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    BoardHandRules.estimateChips(picked).forEach { chip ->
                        val chipFill by animateColorAsState(
                            if (chip.picked) Meka.colors.accent else Meka.colors.surface, MekaMotion.appear(reduced), label = "add-chip",
                        )
                        Text(
                            chip.label, style = MekaType.micro,
                            color = if (chip.picked) Meka.colors.background else Meka.colors.textSecondary,
                            modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(chipFill)
                                .clickable(role = Role.RadioButton) { haptics.tick(); picked = BoardHandRules.toggleEstimate(picked, chip.minutes) }
                                .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A Board card's one gesture (By hand, slice 1; tested in BoardCardHoldTest): a tap is [onTap]; held still for
 * [BoardHandRules.HOLD_MS] it lifts ([onLift] with where the finger is), then every move is [onMove] until let go
 * ([onDrop]) or taken away ([onCancel]); moving past the touch slop first is left to the scroll around it. Once lifted
 * the card consumes the finger's moves, so the board's scroll never takes a held card.
 */
internal fun Modifier.boardCardHold(
    key: Any,
    onTap: () -> Unit,
    onLift: (Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
    /**
     * The folded moves (slice 3): when set, a press that moves right first (more across than down) is a swipe, not the
     * board's scroll: every move is [onSwipe] with how far right it has gone (px), and letting go is [onSwipeEnd] with
     * the distance (0 when taken away).
     */
    onSwipe: ((Float) -> Unit)? = null,
    onSwipeEnd: ((Float) -> Unit)? = null,
): Modifier = pointerInput(key, onSwipe != null) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val hold = awaitHold(down, slop, swipe = onSwipe != null)
        when (hold.kind) {
            HoldKind.TAP -> onTap()
            HoldKind.SCROLL -> Unit
            HoldKind.SWIPE -> {
                onSwipe?.invoke(hold.at.x - down.position.x)
                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    if (c == null) { onSwipeEnd?.invoke(0f); break }
                    val dx = c.position.x - down.position.x
                    c.consume()
                    if (c.changedToUpIgnoreConsumed()) { onSwipeEnd?.invoke(dx); break }
                    onSwipe?.invoke(dx)
                }
            }
            HoldKind.LIFT -> {
                onLift(hold.at)
                while (true) {
                    val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    if (c == null) { onCancel(); break }
                    if (c.changedToUpIgnoreConsumed()) { c.consume(); onDrop(); break }
                    val delta = c.positionChangeIgnoreConsumed()
                    c.consume()
                    if (delta != Offset.Zero) onMove(delta)
                }
            }
        }
    }
}

private enum class HoldKind { TAP, SCROLL, SWIPE, LIFT }
private class Hold(val kind: HoldKind, val at: Offset)

/**
 * Waits for the press to resolve: let go within [BoardHandRules.HOLD_MS] (and not taken by a child, like the tick) is
 * a tap; moving past the touch slop first (or someone else taking it) is a scroll, except, with [swipe], a move to the
 * right that is more across than down, which it takes (consumed, so the board's scroll never starts); held still that
 * long, it lifts.
 */
private suspend fun AwaitPointerEventScope.awaitHold(down: PointerInputChange, slop: Float, swipe: Boolean = false): Hold {
    var at = down.position
    val kind = withTimeoutOrNull(BoardHandRules.HOLD_MS) {
        var out = HoldKind.SCROLL
        while (true) {
            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (c.changedToUpIgnoreConsumed()) { out = if (c.isConsumed) HoldKind.SCROLL else HoldKind.TAP; break }
            if (c.isConsumed) break
            val d = c.position - down.position
            if (d.getDistance() > slop) {
                if (swipe && d.x > 0f && kotlin.math.abs(d.x) > kotlin.math.abs(d.y) * SWIPE_ACROSS) {
                    c.consume()
                    at = c.position
                    out = HoldKind.SWIPE
                }
                break
            }
            at = c.position
        }
        out
    } ?: HoldKind.LIFT
    return Hold(kind, at)
}

/** A swipe is a move at least this much more across than down (else it's the day's own scroll). */
private const val SWIPE_ACROSS = 1.5f

/**
 * "3h planned · 4h free" under a column's date with a slim bar (slice 3): gold as it fills, amber when the day is
 * over-planned, breathing gently then (Reduced motion: steady). The bar's width glides as estimates change.
 */
/**
 * Slice 9: a day's free times, unfolded, each a slim slot taller for a longer stretch ("10:00–12:00 · 2h free"). While a
 * card is held they light up; the one under it glows gold and says "Drop to block here". Reduced motion: colours only.
 */
@Composable
private fun FreeTimeline(free: List<BoardFreeTime>, holding: Boolean, hovered: Long?, onBounds: (Long, Rect) -> Unit) {
    val reduced = Meka.reducedMotion
    Column(Modifier.fillMaxWidth().padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Text(BoardBlockRules.FREE_TITLE.uppercase(), style = MekaType.micro, color = Meka.colors.textTertiary)
        free.forEach { f ->
            val lit = hovered == f.startMs
            val edge by animateColorAsState(
                when {
                    lit -> Meka.colors.accent
                    holding -> Meka.colors.accent.copy(alpha = 0.4f)
                    else -> Meka.colors.hairline
                },
                MekaMotion.appear(reduced), label = "free-edge",
            )
            val tint by animateColorAsState(
                if (lit) Meka.colors.accent.copy(alpha = 0.14f) else Meka.colors.background.copy(alpha = 0f),
                MekaMotion.appear(reduced), label = "free-fill",
            )
            Box(
                Modifier.fillMaxWidth().height(FREE_MIN_H + (FREE_MAX_H - FREE_MIN_H) * f.weight)
                    .onGloballyPositioned { onBounds(f.startMs, it.boundsInRoot()) }
                    .clip(RoundedCornerShape(MekaRadius.s)).background(tint)
                    .border(1.dp, edge, RoundedCornerShape(MekaRadius.s))
                    .clearAndSetSemantics { contentDescription = f.spoken }
                    .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs),
            ) {
                Text(
                    if (lit) "Drop to block here" else "${f.label} · ${f.lengthLabel}",
                    style = MekaType.micro, color = if (lit) Meka.colors.accent else Meka.colors.textTertiary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private val FREE_MIN_H = 24.dp
private val FREE_MAX_H = 64.dp

/**
 * Option B, slice 1: the day's budget pill under its header, "planned · 4h 05 / 3h" (a compact lane: "4h 05 / 3h"),
 * edged gold on today, its thin bar filling gold; amber when more is planned than the day has free, the bar breathing
 * gently then (Reduced motion: steady). Colours cross-fade and the bar's width glides as estimates change.
 */
@Composable
private fun BudgetPill(b: os.meka.core.domain.PlannerBudget, today: Boolean) {
    val reduced = Meka.reducedMotion
    val amber = Meka.colors.calendar3
    val compact = LocalBoardCompact.current
    val fill by animateFloatAsState(b.fill, MekaMotion.appear(reduced), label = "budget-fill")
    val tone by animateColorAsState(if (b.over) amber else Meka.colors.accent, MekaMotion.appear(reduced), label = "budget-tone")
    val edge by animateColorAsState(
        when { b.over -> amber.copy(alpha = 0.7f); today -> Meka.colors.accent; else -> Meka.colors.hairline },
        MekaMotion.appear(reduced), label = "budget-edge",
    )
    val breath = if (b.over && !reduced) {
        val t = rememberInfiniteTransition(label = "budget-breath")
        t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "budget-alpha").value
    } else 1f
    // Option B match: the mockup's mono figures in a 30 dp pill.
    val dense = LocalBoardDense.current
    val figures = if (dense) BoardType.monoDense else BoardType.mono
    Column(
        Modifier.padding(top = MekaSpace.xs).heightIn(min = 30.dp).fillMaxWidth().clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.surface)
            .border(1.dp, edge, RoundedCornerShape(MekaRadius.s))
            .testTag(BUDGET_TAG)
            .clearAndSetSemantics { contentDescription = b.spoken },
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs), verticalAlignment = Alignment.CenterVertically) {
            if (BoardOpenFoldRules.pillStacked(dense, compact)) {
                // Match slice 4: a narrow lane stacks "planned" over the figures, so the word stays and "nothing yet /
                // 6h 15" keeps its whole line (it may still wrap rather than be cut short).
                Column {
                    Text(BoardMatchRules.pillLabel(b), style = figures, color = Meka.colors.textTertiary, maxLines = 1)
                    // Density pass slice 2: unfolded the figures keep one line ("0m / 4h"), so the pill is two lines at most.
                    Text(
                        if (dense) BoardDenseHeadRules.pillValue(b, narrow = true) else BoardPlannerRules.compactLine(b),
                        style = figures, color = if (b.over) amber else Meka.colors.textPrimary,
                        maxLines = if (dense) 1 else 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag(BUDGET_VALUE_TAG),
                    )
                }
            } else {
                Text(b.label, style = figures, color = Meka.colors.textTertiary, maxLines = 1)
                Spacer(Modifier.weight(1f))
                if (b.value.isNotEmpty()) {
                    Text(b.value, style = figures, color = if (b.over) amber else Meka.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(Meka.colors.hairline)) {
            Box(Modifier.fillMaxWidth(fill).height(2.dp).alpha(breath).background(tone))
        }
    }
}

/** The day's budget pill (Option B slice 1, UI tests). */
internal const val BUDGET_TAG = "board-budget"
/** The budget pill's figures on a narrow lane, under "planned" (Option B match slice 4, UI tests). */
internal const val BUDGET_VALUE_TAG = "board-budget-value"

/**
 * Item (10): "Stacking up: Trip form, Council tax and Gym" in amber under a day's capacity, with its one-tap fixes in
 * the accent ("Move “Gym” to Sat" · "Do “Trip form” today"); a tap moves the task with a light haptic and Undo on the
 * bar. Cross-fades in and out as the day changes (Reduced motion: the same, a plain fade).
 */
@Composable
private fun PressureNote(p: DayPressure, onEase: (PressureFix) -> Unit) {
    androidx.compose.animation.Crossfade(p, animationSpec = MekaMotion.appear(Meka.reducedMotion), label = "board-pressure") { now ->
        Column(Modifier.fillMaxWidth().padding(top = MekaSpace.xs)) {
            Text(
                now.short, style = MekaType.micro, color = Meka.colors.calendar3, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { contentDescription = now.spoken },
            )
            now.fixes.forEach { fix ->
                Text(
                    fix.label, style = MekaType.micro, color = Meka.colors.accent, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = MekaSpace.xxs).clip(RoundedCornerShape(MekaRadius.s))
                        .clickable(role = Role.Button) { onEase(fix) }.padding(vertical = MekaSpace.xxs).minTouch(),
                )
            }
        }
    }
}

