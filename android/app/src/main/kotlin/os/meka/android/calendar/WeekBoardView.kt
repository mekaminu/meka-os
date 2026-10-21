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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextDecoration
import kotlinx.coroutines.delay
import os.meka.core.domain.BoardHandRules
import os.meka.core.domain.BoardFoldRules
import os.meka.core.domain.DayStripPill
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.semantics.selected
import os.meka.core.domain.BoardLookRules
import os.meka.core.domain.CardChip
import os.meka.core.domain.CardChipKind
import os.meka.core.domain.DayProgress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.calendarTone
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.BoardCapacity
import os.meka.core.domain.BoardCard
import os.meka.core.domain.BoardDay
import os.meka.core.domain.BoardGoal
import os.meka.core.domain.BoardGoalRules
import os.meka.core.domain.BoardTidyRules
import os.meka.core.domain.BoardTray
import os.meka.core.domain.TrayChip
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
import os.meka.core.facade.MekaCore

/**
 * Calendar's Board view on the Fold (Week board and roll-over, slice 2; core [WeekBoardRules]): a column per day from
 * this week's Monday for five weeks, each day's tasks as cards in his order with the day's events as faded chips above
 * them and an Office · Home · Away marker under the date. Unfolded, a week fills the width (seven columns); on the cover
 * screen one day fills it with the next peeking in, and ‹ › step a week.
 *
 * Long-press a card to pick it up (tick haptic, it lifts and follows the finger); the column under it glows gold where
 * it can land (today and later), a tick as it moves to another day; let go to drop it at that place in the day (light
 * haptic), with "Moved “Book MOT” to Thu 22 Oct · Undo". Near an edge the board scrolls along. Tap a card for Move up ·
 * Move down · Today · Tomorrow · the next days (the same moves without dragging, and for TalkBack). The ring ticks it
 * off (light haptic) and it settles faded at the foot of the day. "+ Add" at a column's foot adds a task to that day.
 *
 * Slice 3: a card's menu also asks "How long?" (15m · 30m · 1h · 2h, Navilife's guess marked "suggested", nothing set
 * until he taps), the card's line shows it ("09:00 · 30m"), and each column from today on shows "3h planned · 4h free"
 * over a slim bar that fills gold and turns amber (and breathes gently) when the day is over-planned.
 *
 * Slice 4: above the days, the week in view has its lane, "No day yet" (tasks parked for the week, off Today until
 * they get a day) and the folded Someday tray ("Someday · 12", tap to open it). Drag a card onto the lane to park it,
 * onto the tray to put it on Someday, or out of either onto a day; a card's menu has the same moves (No day this week ·
 * Someday). The lane and tray glow gold like a column while a card is over them.
 *
 * Slice 7: across the top, the week's goals (up to three, picked here with "+ Goal" or in the week ahead; this week
 * with none picked shows Today's This-week goals), each with "2 of 4 done" over a slim gold bar from the week's tasks
 * tagged to it. Drop a card on a goal to tag it, or use the card menu's FOR A GOAL; a tagged card says "◎ Run 5k". Tap
 * a goal for Off this week; "+ Goal" offers open goals or a new one typed. Every change has Undo.
 *
 * Slice 9: unfolded, each day from today shows its timeline of free times under its events ("10:00–12:00 · 2h free",
 * taller for longer stretches). Drop a card on one to block it there: it lands where you let go, on the quarter hour,
 * moved to that day if it was elsewhere, and its block goes to the calendar as busy time ("Blocked “Book MOT” · Sat
 * 10:00–10:30 in Google · Undo"); a stretch too short says so and nothing moves. While a card is held the free times
 * light up and the one under it glows gold with "Drop to block here".
 *
 * Item (6): "Plan your week" in the header opens [PlanWeekPane] over the Calendar.
 *
 * By hand, slice 2 (the look, [BoardLookRules]): each lane's header is a big day number over its weekday with a small
 * tag ("Today · Office"), today underlined in gold, "2 of 5 done" over a thin gold bar (a past day: "3 done"); cards are
 * raised tiles with small chips (time, estimate, carried in amber, repeat, goal in gold); events are slim one-line chips
 * ("09:30 · Dentist"); free time is one quiet line ("19:00–21:00 · 2h free · +1 more"), and the stretches open as drop
 * targets only while a card is held (slice 9's timeline, never as empty boxes).
 *
 * Reduced motion: no lift or scale, colours and moves cross-fade.
 */
@Composable
fun WeekBoardView(
    core: MekaCore, undo: EventUndo, onEvent: (CalendarEvent) -> Unit, modifier: Modifier = Modifier, onPlanWeek: (() -> Unit)? = null,
) {
    val board by core.weekBoard.collectAsState()
    if (board.weeks.isEmpty()) return
    // Item (8), the Friday review: from Friday "Review the week" opens Review on this week with its reflection open.
    val reviewApp = androidx.compose.ui.platform.LocalContext.current.applicationContext as? os.meka.android.MekaApplication
    val reviewScope = rememberCoroutineScope()
    val onReview: () -> Unit = {
        reviewScope.launch { runCatching { core.openReflection() } }
        reviewApp?.openDestination?.value = os.meka.android.shell.ShellDestination.REVIEW
    }
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Drag state, in root coordinates: the card picked up, where the finger is, and where on the card it was held.
    var drag by remember { mutableStateOf<BoardDrag?>(null) }
    // Where a card can land: a day's column (its epoch day), the week's lane (LANE) or the Someday tray (TRAY).
    val columns = remember { mutableStateMapOf<Long, Rect>() }
    val cards = remember { mutableStateMapOf<String, Rect>() }
    // Slice 9: the free times in each day's timeline (unfolded), keyed by day and start.
    val freeSlots = remember { mutableStateMapOf<Pair<Long, Long>, Rect>() }
    // By hand, slice 3 (folded): the 7-day strip's pills, drop targets for a held card; and the day in view, kept
    // across a fold or unfold.
    val pills = remember { mutableStateMapOf<Long, Rect>() }
    var focusDay by rememberSaveable { mutableStateOf<Long?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    // By hand, slice 1: the day whose "+ Add a task" field is open (one at a time) and where it is, so a tap anywhere
    // else closes it; each day's own vertical scroll (for the edge auto-scroll); the card that just landed (its spring
    // and spark); cards ticked a moment ago keep their place (id → day and place) until they settle.
    var addingDay by remember { mutableStateOf<Long?>(null) }
    var addRect by remember { mutableStateOf<Rect?>(null) }
    val dayScrolls = remember { HashMap<Long, ScrollState>() }
    var landed by remember { mutableStateOf<String?>(null) }
    val settling = remember { mutableStateMapOf<String, Pair<Long, Int>>() }
    // By hand, slice 4: the past days he opened (the rest fold narrow, unfolded), the tray open under its chip (folded,
    // as a sheet), and whether a held card came out of that sheet (it closes once the card is let go).
    var openedPast by remember { mutableStateOf(emptySet<Long>()) }
    var openTray by remember { mutableStateOf<BoardTray?>(null) }
    var fromSheet by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(landed) { if (landed != null) { delay(LAND_MS); landed = null } }
    // Slice 2: the free-time drop targets only show while a card is held, so their places are forgotten when it lands.
    LaunchedEffect(drag == null) { if (drag == null) freeSlots.clear() }
    val hoveredPill by remember {
        derivedStateOf { drag?.let { d -> pills.entries.firstOrNull { (_, r) -> r.contains(d.pointer) }?.key } }
    }
    val hovered by remember {
        derivedStateOf {
            drag?.let { d ->
                if (hoveredPill != null) return@let null
                columns.entries.firstOrNull { (k, r) -> k < 0 && r.contains(d.pointer) }?.key
                    ?: columns.entries.firstOrNull { (k, r) -> k >= 0 && d.pointer.x >= r.left && d.pointer.x < r.right && d.pointer.y >= r.top }?.key
            }
        }
    }
    val hoveredFree by remember {
        derivedStateOf { drag?.let { d -> freeSlots.entries.firstOrNull { (_, r) -> r.contains(d.pointer) }?.key } }
    }
    // Where a held card would land in the day under it (among that day's open cards, itself left out).
    val insertAt by remember {
        derivedStateOf {
            val d = drag ?: return@derivedStateOf null
            if (hoveredFree != null) return@derivedStateOf null
            val day = hovered?.takeIf { it >= 0 }?.let { board.day(it) }?.takeIf { it.canDrop } ?: return@derivedStateOf null
            val others = day.openCards.filter { it.id != d.card.id }
            day.epochDay to BoardHandRules.insertIndex(others.map { cards[it.id]?.center?.y ?: Float.MAX_VALUE }, d.pointer.y)
        }
    }
    LaunchedEffect(hovered) { if (drag != null && hovered != null) haptics.tick() }
    LaunchedEffect(hoveredPill) { if (drag != null && hoveredPill != null) haptics.tick() }
    LaunchedEffect(hoveredFree) { if (drag != null && hoveredFree != null) haptics.tick() }
    fun showLine(line: String?) { if (line != null) { haptics.light(); undo.show(line) { core.undoBoardMove() } } }
    val onPark: (BoardCard, Long) -> Unit = { card, week -> scope.launch { showLine(runCatching { core.parkOnBoard(card.id, week, Int.MAX_VALUE) }.getOrNull()) } }
    val onSomeday: (BoardCard) -> Unit = { card -> scope.launch { showLine(runCatching { core.somedayOnBoard(card.id) }.getOrNull()) } }
    val onTag: (BoardCard, String?) -> Unit = { card, goal ->
        scope.launch {
            val line = runCatching { core.tagOnBoard(card.id, goal) }.getOrNull()
            if (line != null) { haptics.light(); undo.show(line) { core.undoTagOnBoard() } }
        }
    }

    BoxWithConstraints(
        modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }
            // A tap outside the open add field closes it (watched only, never consumed, so the tap still does its job).
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val r = addRect
                    if (addingDay != null && (r == null || !r.contains(down.position + origin))) focusManager.clearFocus()
                }
            },
    ) {
        val wide = maxWidth >= 600.dp
        // By hand, slice 3: folded, one day fills the width (paged with a snap, never stopping between two days).
        val colWidth = if (wide) (maxWidth - MekaSpace.gutter * 2) / 7 else maxWidth - MekaSpace.xs * 2
        val colPx = with(density) { colWidth.toPx() }
        // By hand, slice 4: unfolded, each week's days share the width (folded past days narrow, no lane over 250 dp);
        // folded, every page is the full width. Scroll places come from these widths.
        val widths: List<androidx.compose.ui.unit.Dp> =
            if (wide) BoardTidyRules.laneWidths(board.days, (maxWidth - MekaSpace.gutter * 2).value, openedPast).map { it.dp }
            else List(board.days.size) { colWidth }
        val widthsPx = widths.map { with(density) { it.toPx() } }
        val startsPx = BoardTidyRules.starts(widthsPx)
        val widthsNow by rememberUpdatedState(widthsPx)
        val weekStartPx: (Int) -> Int = { w -> (startsPx.getOrNull(w * 7) ?: 0f).toInt() }
        val scroll = rememberScrollState()
        val dayCount = board.days.size
        val daySnap = remember(colPx, dayCount, reduced) {
            DaySnapFling(colPx, dayCount, { scroll.value.toFloat() }, with(density) { BoardFoldRules.FLING_DP_PER_S.dp.toPx() }, reduced) { haptics.tick() }
        }
        val turnTo: (Int) -> Unit = { index ->
            val page = index.coerceIn(0, (dayCount - 1).coerceAtLeast(0))
            daySnap.settled = page
            val target = (page * colPx).toInt()
            scope.launch { if (reduced) scroll.scrollTo(target) else scroll.animateScrollTo(target, spring(dampingRatio = 0.9f, stiffness = 500f)) }
        }
        // Start on today's column (unfolded: this week); across a fold or unfold, the same day stays in view. Then keep
        // note of the day in view (folded: the page; unfolded: today in the week shown, else its Monday), and a tick as
        // each folded page settles.
        val daysNow by rememberUpdatedState(board.days)
        LaunchedEffect(wide, colPx) {
            val index = BoardFoldRules.focusAfterFold(daysNow, focusDay)
            scroll.scrollTo(if (wide) weekStartPx(index / 7) else (index * colPx).toInt())
            daySnap.settled = index
            snapshotFlow {
                if (wide) BoardTidyRules.indexAt(scroll.value.toFloat(), widthsNow) else BoardFoldRules.pageOf(scroll.value.toFloat(), colPx, dayCount)
            }.collect { page ->
                focusDay = if (wide) BoardFoldRules.focusInWeek(daysNow, page / 7) else daysNow.getOrNull(page)?.epochDay
            }
        }
        // Near an edge while dragging, the board scrolls along.
        LaunchedEffect(drag != null) {
            var edgeDir = 0
            var edgeSince = 0L
            while (drag != null) {
                withFrameNanos { }
                val d = drag ?: break
                val edge = with(density) { BoardHandRules.EDGE.dp.toPx() }
                val most = with(density) { BoardHandRules.MAX_STEP.dp.toPx() }
                val width = with(density) { maxWidth.toPx() }
                // Across the days, and up and down the day under the finger, faster the nearer the edge. Folded, a
                // card held at the lane's edge turns a whole page instead, after a short wait.
                if (wide) {
                    val across = BoardHandRules.edgeStep(d.pointer.x - origin.x, 0f, width, edge, most)
                    if (across != 0f) scroll.scrollBy(across)
                } else {
                    val dir = if (hoveredPill != null) 0 else BoardFoldRules.edgeFlip(d.pointer.x - origin.x, width, edge)
                    val now = withFrameNanos { it } / 1_000_000L
                    if (dir == 0 || dir != edgeDir) { edgeDir = dir; edgeSince = now }
                    else if (now - edgeSince >= BoardFoldRules.FLIP_MS) {
                        edgeSince = now
                        haptics.tick()
                        turnTo(BoardFoldRules.pageOf(scroll.value.toFloat(), colPx, dayCount) + dir)
                    }
                }
                val day = hovered?.takeIf { it >= 0 }
                val col = day?.let { columns[it] }
                val down = col?.let { BoardHandRules.edgeStep(d.pointer.y, it.top, it.bottom, edge, most) } ?: 0f
                if (down != 0f) day?.let { dayScrolls[it] }?.scrollBy(down)
            }
        }

        val weekIndex by remember(board, colPx, wide) {
            derivedStateOf {
                val at = if (wide) BoardTidyRules.indexAt(scroll.value.toFloat(), widthsNow) else ((scroll.value + colPx / 2) / colPx).toInt()
                (at / 7).coerceIn(0, board.weeks.size - 1)
            }
        }
        val pick: (BoardCard, Offset) -> Unit = { card, at -> haptics.tick(); drag = BoardDrag(card, at, at - (cards[card.id]?.topLeft ?: at)) }
        val dragBy: (Offset) -> Unit = { delta -> drag = drag?.let { it.copy(pointer = it.pointer + delta) } }
        val dropCard: () -> Unit = drop@{
            // Read where it is over before letting go of it: these follow the held card and clear with it.
            val d = drag
            val at = hovered?.let { when (it) { LANE_OPEN -> LANE; TRAY_OPEN -> TRAY; else -> it } }
            val free = hoveredFree
            val pill = hoveredPill
            val place = insertAt
            val week = board.weeks.getOrNull(weekIndex)
            drag = null
            // Folded: dropped on a day's pill, it moves to the end of that day (the lane follows to show it land).
            if (d != null && pill != null) {
                board.day(pill)?.takeIf { it.canDrop }?.let { target ->
                    landed = d.card.id
                    turnTo(board.days.indexOfFirst { it.epochDay == target.epochDay })
                    scope.launch { showLine(runCatching { core.moveOnBoard(d.card.id, target.epochDay, Int.MAX_VALUE) }.getOrNull()) }
                }
                return@drop
            }
            if (d == null || at == null) return@drop
            landed = d.card.id
            when {
                free != null && wide -> {
                    val r = freeSlots[free]
                    val fraction = r?.let { ((d.pointer.y - it.top) / it.height.coerceAtLeast(1f)).coerceIn(0f, 1f) } ?: 0f
                    scope.launch {
                        val result = runCatching { core.dropOnFreeTime(d.card.id, free.first, free.second, fraction) }.getOrNull()
                        when {
                            result == null -> Unit
                            result.done -> { haptics.light(); undo.show(result.line) { core.undoBlock() } }
                            else -> undo.show(result.line, null)
                        }
                    }
                }
                at <= GOAL_KEY && week != null -> week.goals.getOrNull((GOAL_KEY - at).toInt())?.takeIf { !it.finished }?.let { onTag(d.card, it.goalId) }
                at == LANE && week != null -> {
                    val index = week.lane.filter { it.id != d.card.id }.count { c -> (cards[c.id]?.center?.x ?: 0f) < d.pointer.x }
                    scope.launch { showLine(runCatching { core.parkOnBoard(d.card.id, week.startEpochDay, index) }.getOrNull()) }
                }
                at == TRAY -> onSomeday(d.card)
                else -> board.day(at)?.takeIf { it.canDrop }?.let { target ->
                    val index = place?.takeIf { it.first == target.epochDay }?.second
                        ?: BoardHandRules.insertIndex(target.openCards.filter { it.id != d.card.id }.map { c -> cards[c.id]?.center?.y ?: 0f }, d.pointer.y)
                    scope.launch { showLine(runCatching { core.moveOnBoard(d.card.id, target.epochDay, index) }.getOrNull()) }
                }
            }
        }

        Column(Modifier.fillMaxSize()) {
            WeekHeader(board, weekIndex, scroll, weekStartPx, Modifier.padding(horizontal = MekaSpace.gutter).appear(rememberAppearance(2)), onPlanWeek, onReview)
            board.weeks.getOrNull(weekIndex)?.let { week ->
                // By hand, slice 4: the week's goals as small gold pills and the two trays as chips (unfolded on one
                // row, folded on two); an open tray's cards unfolded in a lane under them.
                val chips = BoardTidyRules.trayChips(week, board, openTray)
                LaunchedEffect(chips.map { it.count }) { openTray = BoardTidyRules.stillOpen(openTray, chips) }
                val onTrayTap: (TrayChip) -> Unit = { chip -> haptics.tick(); openTray = BoardTidyRules.toggle(openTray, chip) }
                val goalsLane: @Composable (Modifier) -> Unit = { mod ->
                    GoalsLane(
                        week = week, core = core, undo = undo, hovered = if (drag != null) hovered else null,
                        onBounds = { key, r -> columns[key] = r }, modifier = mod,
                    )
                }
                val trayChips: @Composable (Modifier) -> Unit = { mod ->
                    TrayChips(
                        chips, openTray, hovered = if (drag != null) hovered else null,
                        onBounds = { key, r -> columns[key] = r }, onGone = { key -> columns.remove(key) }, onTap = onTrayTap, modifier = mod,
                    )
                }
                if (wide) {
                    Row(Modifier.padding(horizontal = MekaSpace.gutter).padding(bottom = MekaSpace.xs).appear(rememberAppearance(3)), verticalAlignment = Alignment.CenterVertically) {
                        goalsLane(Modifier.weight(1f))
                        Spacer(Modifier.width(MekaSpace.xs))
                        trayChips(Modifier)
                    }
                } else {
                    Column(Modifier.padding(horizontal = MekaSpace.gutter).appear(rememberAppearance(3)), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                        if (week.goals.isNotEmpty() || week.canAddGoal) goalsLane(Modifier)
                        trayChips(Modifier)
                    }
                }
                val tray = openTray
                androidx.compose.animation.AnimatedVisibility(
                    visible = wide && tray != null,
                    enter = androidx.compose.animation.expandVertically(MekaMotion.appear(reduced)) + androidx.compose.animation.fadeIn(MekaMotion.appear(reduced)),
                    exit = androidx.compose.animation.shrinkVertically(MekaMotion.appear(reduced)) + androidx.compose.animation.fadeOut(MekaMotion.appear(reduced)),
                ) {
                    if (tray != null) {
                        val laneKey = if (tray == BoardTray.NO_DAY) LANE_OPEN else TRAY_OPEN
                        val lit = drag != null && hovered == laneKey
                        val glow by animateColorAsState(if (lit) Meka.colors.accent else Meka.colors.hairline, MekaMotion.appear(reduced), label = "open-lane-glow")
                        androidx.compose.runtime.DisposableEffect(laneKey) { onDispose { columns.remove(laneKey) } }
                        TrayCards(
                            tray, week, board, stacked = false, cardWidth = 200.dp, dragging = drag?.card?.id,
                            onCardBounds = { id, r -> cards[id] = r }, onPick = pick, onDrag = dragBy, onDrop = dropCard, onCancel = { drag = null },
                            onMoveTo = { card, to -> scope.launch { showLine(runCatching { core.moveOnBoard(card.id, to, Int.MAX_VALUE) }.getOrNull()) } },
                            onPark = onPark, onSomeday = onSomeday, onTag = onTag,
                            modifier = Modifier.padding(horizontal = MekaSpace.gutter).padding(bottom = MekaSpace.xs).fillMaxWidth()
                                .onGloballyPositioned { columns[laneKey] = it.boundsInRoot() }
                                .clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
                                .border(1.dp, glow, RoundedCornerShape(MekaRadius.m)).padding(MekaSpace.xs),
                        )
                    }
                }
                // By hand, slice 3: folded, the week's seven days as pills; tap one to go to it, drop a card on one.
                if (!wide) {
                    DayStrip(
                        pills = BoardFoldRules.strip(week), inView = focusDay, hovered = hoveredPill,
                        onBounds = { day, r -> pills[day] = r }, onGone = { day -> pills.remove(day) },
                        onTap = { day -> haptics.tick(); turnTo(board.days.indexOfFirst { it.epochDay == day }) },
                        modifier = Modifier.padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.xs).appear(rememberAppearance(3)),
                    )
                }
            }
            Row(
                Modifier.fillMaxSize().horizontalScroll(scroll, enabled = drag == null, flingBehavior = if (wide) null else daySnap)
                    .padding(horizontal = if (wide) MekaSpace.gutter else MekaSpace.xs)
                    .appear(rememberAppearance(4)),
            ) {
                board.days.forEachIndexed { index, day ->
                    val gap = insertAt?.takeIf { it.first == day.epochDay }?.let { (_, to) ->
                        drag?.let { d -> BoardHandRules.gapBefore(day.openCards.map { it.id }, d.card.id, to) }
                    }
                    DayColumn(
                        day = day,
                        width = widths.getOrElse(index) { colWidth },
                        collapsed = wide && BoardTidyRules.collapsed(day, openedPast),
                        onFold = if (wide && day.isPast) ({ haptics.tick(); openedPast = if (day.epochDay in openedPast) openedPast - day.epochDay else openedPast + day.epochDay }) else null,
                        gapAt = gap,
                        gapHeight = drag?.let { d -> cards[d.card.id]?.let { with(density) { it.height.toDp() } } } ?: MekaSpace.xxl,
                        held = settling.filterValues { it.first == day.epochDay }.mapValues { it.value.second },
                        landed = landed,
                        onScrollState = { dayScrolls[day.epochDay] = it },
                        adding = addingDay == day.epochDay,
                        onAddOpen = { haptics.tick(); addingDay = day.epochDay },
                        onAddClose = { if (addingDay == day.epochDay) { addingDay = null; addRect = null } },
                        onAddBounds = { addRect = it },
                        hovered = drag != null && hovered == day.epochDay && day.canDrop,
                        dragging = drag?.card?.id,
                        showFree = wide,
                        freeHovered = hoveredFree?.takeIf { drag != null && it.first == day.epochDay }?.second,
                        onFreeBounds = { start, r -> freeSlots[day.epochDay to start] = r },
                        onBounds = { columns[day.epochDay] = it },
                        onCardBounds = { id, r -> cards[id] = r },
                        onPick = pick,
                        onDrag = dragBy,
                        onDrop = dropCard,
                        onCancel = { drag = null },
                        onMove = { card, days, places ->
                            scope.launch { showLine(runCatching { core.nudgeOnBoard(card.id, days, places) }.getOrNull()) }
                        },
                        onMoveTo = { card, to ->
                            scope.launch { showLine(runCatching { core.moveOnBoard(card.id, to, Int.MAX_VALUE) }.getOrNull()) }
                        },
                        onPark = onPark,
                        onSomeday = onSomeday,
                        onDone = { card ->
                            haptics.light()
                            // It keeps its place a moment, then settles below the open ones (Sunsama-style).
                            settling[card.id] = day.epochDay to day.cards.indexOfFirst { it.id == card.id }.coerceAtLeast(0)
                            scope.launch {
                                runCatching { core.complete(card.id) }
                                undo.show("Done “${card.title}”") { core.reopen(card.id) }
                                delay(BoardHandRules.SETTLE_MS)
                                settling.remove(card.id)
                            }
                        },
                        onUndone = { card ->
                            haptics.tick()
                            scope.launch {
                                runCatching { core.reopen(card.id) }
                                undo.show("Not done “${card.title}”") { core.complete(card.id) }
                            }
                        },
                        onAdd = { text, minutes ->
                            haptics.light()
                            scope.launch { runCatching { core.addOnBoardSized(text, day.epochDay, minutes) } }
                        },
                        onEstimate = { card, minutes ->
                            scope.launch {
                                val line = runCatching { core.setEstimate(card.id, minutes) }.getOrNull()
                                if (line != null) { haptics.tick(); undo.show(line) { core.undoEstimate() } }
                            }
                        },
                        onBlockOffer = { card -> core.blockOffer(card.id) },
                        onBlock = { card, slot ->
                            scope.launch {
                                val line = runCatching { core.blockOnBoard(card.id, slot.startMs) }.getOrNull()
                                if (line != null) { haptics.light(); undo.show(line) { core.undoBlock() } }
                            }
                        },
                        board = board,
                        onEvent = onEvent,
                        onTag = onTag,
                        onEase = { fix ->
                            scope.launch { showLine(runCatching { core.easePressure(fix.taskId, fix.toDay) }.getOrNull()) }
                        },
                    )
                }
            }
        }

        // By hand, slice 4: folded, an open tray is a sheet over the day; it steps aside while one of its cards is held
        // and closes once that card is let go.
        val sheetTray = openTray
        if (!wide && sheetTray != null) {
            board.weeks.getOrNull(weekIndex)?.let { week ->
                LaunchedEffect(drag != null) {
                    if (drag != null) fromSheet = true
                    else if (fromSheet) { fromSheet = false; openTray = null }
                }
                TraySheet(
                    title = BoardTidyRules.trayChips(week, board).first { it.tray == sheetTray }.label,
                    hidden = drag != null, onClose = { haptics.tick(); openTray = null },
                ) {
                    TrayCards(
                        sheetTray, week, board, stacked = true, cardWidth = colWidth, dragging = drag?.card?.id,
                        onCardBounds = { id, r -> cards[id] = r }, onPick = pick, onDrag = dragBy, onDrop = dropCard, onCancel = { drag = null },
                        onMoveTo = { card, to -> scope.launch { showLine(runCatching { core.moveOnBoard(card.id, to, Int.MAX_VALUE) }.getOrNull()) } },
                        onPark = onPark, onSomeday = onSomeday, onTag = onTag, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // The card being dragged follows the finger, lifted (Reduced motion: no lift).
        drag?.let { d ->
            val at = d.pointer - d.grab - origin
            Box(
                Modifier.zIndex(3f).width(cards[d.card.id]?.let { with(density) { it.width.toDp() } } ?: (colWidth - MekaSpace.xs))
                    .graphicsLayer {
                        translationX = at.x; translationY = at.y
                        if (!reduced) { scaleX = LIFT; scaleY = LIFT; shadowElevation = 12.dp.toPx() }
                        shape = TILE; clip = true
                    },
            ) { CardFace(d.card, lifted = true) }
        }
    }
}

private const val LIFT = 1.04f
/** How long a dropped card's landing glow and spring last. */
private const val LAND_MS = 700L

/**
 * By hand, slice 3: folded, a fling always settles on a whole day ([BoardFoldRules.snapPage]): a quick swipe one day
 * the way it went, a slow drag the nearest; it settles on a firm spring (Reduced motion: it jumps), tick haptic when
 * the day changed.
 */
private class DaySnapFling(
    private val pagePx: Float, private val pages: Int, private val offset: () -> Float, private val threshold: Float,
    private val reduced: Boolean, private val onNewDay: () -> Unit,
) : FlingBehavior {
    /** The page last settled on (set by the board when it jumps there itself). */
    var settled = -1

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val target = BoardFoldRules.snapPage(offset(), pagePx, initialVelocity, threshold, pages)
        val distance = target * pagePx - offset()
        if (reduced) scrollBy(distance) else {
            var last = 0f
            androidx.compose.animation.core.animate(0f, distance, initialVelocity, spring(dampingRatio = 0.9f, stiffness = 500f)) { value, _ ->
                scrollBy(value - last)
                last = value
            }
        }
        if (settled >= 0 && target != settled) onNewDay()
        settled = target
        return 0f
    }
}

/**
 * By hand, slice 3: the folded Board's strip of the week's seven days ("Mon 19" … "Sun 25"): the day in view lit, today
 * in gold, a dot under a day with open tasks (amber when it is stacking up). Tap one to go to it; while a card is held
 * a pill under it swells (1.15, the approve spring) and glows gold, and letting go there moves the card to that day.
 * Reduced motion: no swell, the colours cross-fade.
 */
@Composable
private fun DayStrip(
    pills: List<DayStripPill>, inView: Long?, hovered: Long?, onBounds: (Long, Rect) -> Unit, onGone: (Long) -> Unit,
    onTap: (Long) -> Unit, modifier: Modifier,
) {
    val reduced = Meka.reducedMotion
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        pills.forEach { p ->
            key(p.epochDay) {
                androidx.compose.runtime.DisposableEffect(p.epochDay) { onDispose { onGone(p.epochDay) } }
                val lit = p.epochDay == inView
                val over = hovered == p.epochDay && p.canDrop
                val swell by animateFloatAsState(if (over && !reduced) BoardFoldRules.PILL_SWELL else 1f, spring(dampingRatio = 0.6f, stiffness = 600f), label = "pill-swell")
                val fill by animateColorAsState(
                    when {
                        over -> Meka.colors.accent.copy(alpha = 0.22f)
                        lit -> Meka.colors.surfaceRaised
                        else -> Meka.colors.surface
                    },
                    MekaMotion.appear(reduced), label = "pill-fill",
                )
                val edge by animateColorAsState(
                    if (over || (lit && p.isToday)) Meka.colors.accent else if (lit) Meka.colors.hairline else Meka.colors.hairline.copy(alpha = 0f),
                    MekaMotion.appear(reduced), label = "pill-edge",
                )
                val ink = when {
                    p.isToday -> Meka.colors.accent
                    p.isPast -> Meka.colors.textTertiary
                    else -> Meka.colors.textPrimary
                }
                Column(
                    Modifier.weight(1f).zIndex(if (over) 1f else 0f)
                        .graphicsLayer { scaleX = swell; scaleY = swell }
                        .onGloballyPositioned { if (p.canDrop) onBounds(p.epochDay, it.boundsInRoot()) }
                        .clip(RoundedCornerShape(MekaRadius.m)).background(fill)
                        .border(1.dp, edge, RoundedCornerShape(MekaRadius.m))
                        .clickable(role = Role.Tab) { onTap(p.epochDay) }
                        .semantics { contentDescription = p.accessibilityLabel; selected = lit }
                        .padding(vertical = MekaSpace.xs),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(p.weekday, style = MekaType.micro, color = if (p.isToday) Meka.colors.accent else Meka.colors.textTertiary, maxLines = 1)
                    Text(p.number, style = MekaType.metaStrong, color = ink, maxLines = 1)
                    Box(
                        Modifier.padding(top = MekaSpace.xxs).size(4.dp).clip(CircleShape).background(
                            when {
                                p.stacking -> Meka.colors.calendar3
                                p.hasTasks -> Meka.colors.textSecondary
                                else -> Meka.colors.textSecondary.copy(alpha = 0f)
                            },
                        ),
                    )
                }
            }
        }
    }
}

private data class BoardDrag(val card: BoardCard, val pointer: Offset, val grab: Offset)

/** Drop-target keys besides a day's epoch day: the week's lane and the Someday tray. */
private const val LANE = -1L
private const val TRAY = -2L
/** By hand, slice 4: the open No day yet lane and the open Someday lane (unfolded), the same targets as their chips. */
private const val LANE_OPEN = -3L
private const val TRAY_OPEN = -4L
/** Slice 7: the week's goals are drop targets GOAL_KEY, GOAL_KEY - 1, GOAL_KEY - 2 (first, second, third). */
private const val GOAL_KEY = -10L

/** "This week · 19–25 Oct" with ‹ › stepping a week; follows the board as it scrolls. */
@Composable
private fun WeekHeader(
    board: WeekBoard, week: Int, scroll: ScrollState, weekStartPx: (Int) -> Int, modifier: Modifier, onPlanWeek: (() -> Unit)?, onReview: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val w = board.weeks[week]
    fun go(to: Int) {
        val target = weekStartPx(to)
        haptics.tick()
        scope.launch { if (reduced) scroll.scrollTo(target) else scroll.animateScrollTo(target, MekaMotion.replan<Float>(false)) }
    }
    Row(modifier.fillMaxWidth().padding(bottom = MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(w.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
        if (w.title != w.range) {
            Spacer(Modifier.width(MekaSpace.xs))
            Text(w.range, style = MekaType.caption, color = Meka.colors.textTertiary)
        }
        Spacer(Modifier.weight(1f))
        // Item (8): from Friday, on this week, Review the week (tick haptic; Review slides in as a tab change).
        if (board.offersReview && week == 0) {
            Text(
                os.meka.core.domain.BoardReviewRules.REVIEW, style = MekaType.itemMeta, color = Meka.colors.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                    .clickable(role = Role.Button) { haptics.tick(); onReview() }
                    .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xs),
            )
        }
        // Item (6): Plan your week opens the guided flow (tick haptic; the pane springs up).
        if (onPlanWeek != null) {
            Text(
                os.meka.core.domain.PlanWeekRules.OPEN, style = MekaType.itemMeta, color = Meka.colors.accent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill))
                    .clickable(role = Role.Button) { haptics.tick(); onPlanWeek() }
                    .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xs),
            )
        }
        StepButton("‹", "Previous week", week > 0) { go(week - 1) }
        StepButton("›", "Next week", week < board.weeks.size - 1) { go(week + 1) }
    }
}

@Composable
private fun StepButton(glyph: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(MekaSpace.touch).clip(CircleShape)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.3f)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, style = MekaType.itemTitle, color = Meka.colors.textSecondary) }
}

@Composable
private fun DayColumn(
    day: BoardDay,
    width: androidx.compose.ui.unit.Dp,
    /** By hand, slice 4: a past day folded narrow (its number, weekday and "3 done"); tap to open it. */
    collapsed: Boolean,
    /** Opens a folded past day, or folds an opened one again (unfolded only); null elsewhere. */
    onFold: (() -> Unit)?,
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
) {
    val reduced = Meka.reducedMotion
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
            day.isToday -> Meka.colors.surface
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
    Column(
        Modifier.width(width).fillMaxHeight().padding(horizontal = MekaSpace.xxs)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .clip(RoundedCornerShape(MekaRadius.m)).background(fill)
            .border(1.dp, glow, RoundedCornerShape(MekaRadius.m))
            .alpha(if (day.isPast) 0.6f else 1f)
            .verticalScroll(vScroll, enabled = dragging == null)
            .padding(MekaSpace.xs),
    ) {
        // By hand, slice 2 (the look): a big day number over its weekday, a small tag, today underlined in gold, then
        // "2 of 5 done" over a thin gold bar (a past day only what was done) and the day's capacity.
        Column(
            Modifier.fillMaxWidth()
                .then(if (onFold != null) Modifier.clip(RoundedCornerShape(MekaRadius.s)).clickable(role = Role.Button) { onFold() } else Modifier)
                .clearAndSetSemantics {
                    contentDescription = day.accessibilityLabel + if (onFold != null) ". Tap to fold it" else ""
                    if (onFold != null) onClick { onFold(); true }
                },
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(day.number, style = MekaType.nowTitle, color = if (day.isToday) Meka.colors.accent else Meka.colors.textPrimary, maxLines = 1)
                Spacer(Modifier.width(MekaSpace.xs))
                Text(day.weekday, style = MekaType.metaStrong, color = if (day.isToday) Meka.colors.accent else Meka.colors.textSecondary, maxLines = 1)
                Spacer(Modifier.weight(1f))
                BoardLookRules.tag(day)?.let {
                    Text(it, style = MekaType.micro, color = Meka.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (day.isToday) {
                Box(Modifier.padding(top = MekaSpace.xxs).width(MekaSpace.l).height(2.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent))
            }
            if (day.isPast) {
                Text(BoardLookRules.pastLine(day), style = MekaType.micro, color = Meka.colors.textTertiary, maxLines = 1)
            } else {
                BoardLookRules.progress(day)?.let { DayProgressBar(it) }
            }
            day.capacity?.let { CapacityBar(it) }
        }
        day.pressure?.let { PressureNote(it, onEase) }
        // By hand, slice 1: "+ Add a task" at the top of every day from today, always there.
        if (day.canDrop) AddAtTop(day, adding, onAddOpen, onAddClose, onAddBounds, onAdd)
        // The day's events as slim one-line chips: "09:30 · Dentist" (slice 2), a dot in the calendar's colour.
        if (day.chips.isNotEmpty()) {
            Column(Modifier.padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                day.chips.forEach { chip ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surface)
                            .clickable(role = Role.Button) { onEvent(chip.event) }
                            .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(Meka.colors.calendarTone(chip.tone)))
                        Spacer(Modifier.width(MekaSpace.xxs))
                        Text(chip.time, style = MekaType.micro, color = Meka.colors.accent, maxLines = 1)
                        Spacer(Modifier.width(MekaSpace.xxs))
                        Text(chip.title, style = MekaType.micro, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                day.moreLabel?.let { Text(it, style = MekaType.micro, color = Meka.colors.textTertiary) }
            }
        }
        // Free time: one quiet line; only while a card is held (unfolded) do the stretches open as drop targets.
        BoardLookRules.freeLine(day.freeTimes)?.let { free ->
            if (!(showFree && dragging != null)) {
                Text(
                    free.label, style = MekaType.micro, color = Meka.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = MekaSpace.xs).clearAndSetSemantics { contentDescription = free.spoken },
                )
            }
        }
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
        Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
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
    }
}

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
    val tap by rememberUpdatedState { menu = true }
    val lift by rememberUpdatedState<(Offset) -> Unit> { at -> onPick(card, bounds.topLeft + at) }
    val move by rememberUpdatedState(onDrag)
    val drop by rememberUpdatedState(onDrop)
    val cancel by rememberUpdatedState(onCancel)
    Box(
        modifier.alpha(ghost)
            .onGloballyPositioned { bounds = it.boundsInRoot(); onCardBounds(card.id, bounds) }
            .graphicsLayer { scaleX = land.value; scaleY = land.value }
            .then(
                if (card.canMove) Modifier.boardCardHold(card.id, onTap = { tap() }, onLift = { lift(it) }, onMove = { move(it) }, onDrop = { drop() }, onCancel = { cancel() })
                else Modifier,
            )
            .clip(TILE)
            .border(1.5.dp, Meka.colors.accent.copy(alpha = glow.value), TILE)
            .semantics {
                contentDescription = card.accessibilityLabel
                if (card.canMove) { role = Role.Button; onClick { menu = true; true } }
            },
    ) {
        CardFace(
            card, lifted = false,
            onTick = when {
                card.done -> onUndone?.let { f -> { f(card) } }
                else -> onDone?.let { f -> { f(card) } }
            },
        )
        if (card.canMove) {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
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
    val chips = BoardLookRules.chips(card)
    Row(
        Modifier.fillMaxWidth()
            .background(if (card.done && !lifted) Meka.colors.surface else Meka.colors.surfaceRaised)
            .then(if (lifted) Modifier.border(1.5.dp, Meka.colors.accent, TILE) else Modifier)
            .padding(end = MekaSpace.xs),
        verticalAlignment = Alignment.Top,
    ) {
        TickTarget(card.done, card.title, onTick)
        Column(Modifier.weight(1f).padding(vertical = MekaSpace.xs)) {
            Text(
                card.title, style = MekaType.body, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (card.done) Meka.colors.textTertiary else Meka.colors.textPrimary,
                textDecoration = if (card.done) TextDecoration.LineThrough else null,
            )
            if (chips.isNotEmpty()) {
                FlowRow(
                    Modifier.padding(top = MekaSpace.xxs),
                    horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
                ) { chips.forEach { CardChipPill(it) } }
            }
        }
        // Slice 5: a small colour dot for where it came from (mail, Needs you, Discord, School); the screen reader hears
        // it in the card's label. Dimmed with the rest of a done card.
        card.source?.let { src ->
            Box(
                Modifier.padding(top = MekaSpace.m, start = MekaSpace.xxs).size(6.dp).clip(CircleShape)
                    .background(Meka.colors.calendarTone(src.tone).copy(alpha = if (card.done) 0.4f else 1f))
                    .testTag(SOURCE_DOT_TAG),
            )
        }
    }
}

/** The colour dot on a Board card that came from mail, Needs you, Discord or School (slice 5, UI tests). */
internal const val SOURCE_DOT_TAG = "board-source-dot"

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
 * "2 of 5 done" under a day's header with a thin bar that fills gold as cards are ticked (slice 2); the width glides
 * (Reduced motion: it steps).
 */
@Composable
private fun DayProgressBar(p: DayProgress) {
    val reduced = Meka.reducedMotion
    val fill by animateFloatAsState(p.fill, MekaMotion.appear(reduced), label = "day-progress")
    Text(
        p.line, style = MekaType.micro, color = if (p.complete) Meka.colors.accent else Meka.colors.textTertiary, maxLines = 1,
        modifier = Modifier.padding(top = MekaSpace.xxs),
    )
    Box(Modifier.padding(top = MekaSpace.xxs).fillMaxWidth().height(2.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.hairline)) {
        Box(Modifier.fillMaxWidth(fill).height(2.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent))
    }
}

/**
 * The tick (by hand, slice 1): a 20 dp circle in its own 48 dp target. A tap fills it gold with a tick and a spark ring
 * that grows and fades (600 ms); tapped again it empties. Reduced motion: the fill cross-fades, no spark.
 */
@Composable
private fun TickTarget(done: Boolean, title: String, onTick: (() -> Unit)?) {
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
        Modifier.size(BoardHandRules.CHECK_TARGET.dp)
            .then(if (onTick != null) Modifier.clip(CircleShape).clickable(role = Role.Checkbox) { onTick() } else Modifier)
            .semantics { contentDescription = BoardHandRules.tickLabel(title, done) },
        contentAlignment = Alignment.Center,
    ) {
        if (spark.value < 1f) {
            Box(
                Modifier.size(20.dp).graphicsLayer {
                    val p = spark.value
                    scaleX = 0.4f + 2f * p; scaleY = 0.4f + 2f * p; alpha = 1f - p
                }.border(2.dp, gold, CircleShape),
            )
        }
        Box(Modifier.size(20.dp).clip(CircleShape).background(fill).border(1.5.dp, edge, CircleShape), contentAlignment = Alignment.Center) {
            if (done) {
                Canvas(Modifier.size(12.dp)) {
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
) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
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
            Box(
                Modifier.fillMaxWidth().height(40.dp)
                    .drawBehind {
                        drawRoundRect(
                            hairline, cornerRadius = androidx.compose.ui.geometry.CornerRadius(MekaRadius.m.toPx()),
                            style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                        )
                    }
                    .clip(RoundedCornerShape(MekaRadius.m))
                    .clickable(role = Role.Button) { onOpen() }
                    .semantics { contentDescription = BoardHandRules.addLabel(day.title) }
                    .padding(horizontal = MekaSpace.m),
                contentAlignment = Alignment.CenterStart,
            ) { Text(BoardHandRules.ADD, style = MekaType.caption, color = Meka.colors.textTertiary) }
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
): Modifier = pointerInput(key) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val hold = awaitHold(down, slop)
        when (hold.kind) {
            HoldKind.TAP -> onTap()
            HoldKind.SCROLL -> Unit
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

private enum class HoldKind { TAP, SCROLL, LIFT }
private class Hold(val kind: HoldKind, val at: Offset)

/**
 * Waits for the press to resolve: let go within [BoardHandRules.HOLD_MS] (and not taken by a child, like the tick) is
 * a tap; moving past the touch slop first (or someone else taking it) is a scroll; held still that long, it lifts.
 */
private suspend fun AwaitPointerEventScope.awaitHold(down: PointerInputChange, slop: Float): Hold {
    var at = down.position
    val kind = withTimeoutOrNull(BoardHandRules.HOLD_MS) {
        var out = HoldKind.SCROLL
        while (true) {
            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
            if (c.changedToUpIgnoreConsumed()) { out = if (c.isConsumed) HoldKind.SCROLL else HoldKind.TAP; break }
            if (c.isConsumed || (c.position - down.position).getDistance() > slop) break
            at = c.position
        }
        out
    } ?: HoldKind.LIFT
    return Hold(kind, at)
}

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

@Composable
private fun CapacityBar(c: BoardCapacity) {
    val reduced = Meka.reducedMotion
    val amber = Meka.colors.calendar3
    val fill by animateFloatAsState(c.fill, MekaMotion.appear(reduced), label = "capacity-fill")
    val tone by animateColorAsState(if (c.over) amber else Meka.colors.accent, MekaMotion.appear(reduced), label = "capacity-tone")
    val breath = if (c.over && !reduced) {
        val t = rememberInfiniteTransition(label = "capacity-breath")
        t.animateFloat(0.55f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "capacity-alpha").value
    } else 1f
    Text(c.line, style = MekaType.micro, color = if (c.over) amber else Meka.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Box(Modifier.padding(top = MekaSpace.xxs).fillMaxWidth().height(3.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.hairline)) {
        Box(Modifier.fillMaxWidth(fill).height(3.dp).alpha(breath).clip(RoundedCornerShape(MekaRadius.pill)).background(tone))
    }
}

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
                        .clickable(role = Role.Button) { onEase(fix) }.padding(vertical = MekaSpace.xxs),
                )
            }
        }
    }
}

/** Agenda · Board above the Calendar (Week board, slice 2): which view the Calendar tab shows. */
@Composable
fun CalendarViewSwitch(board: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    Row(modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surface)) {
        listOf(false to WeekBoardRules.VIEW_AGENDA, true to WeekBoardRules.VIEW_BOARD).forEach { (isBoard, label) ->
            val lit = isBoard == board
            val fill by animateColorAsState(if (lit) Meka.colors.surfaceRaised else Meka.colors.surface, MekaMotion.appear(reduced), label = "view-$label")
            Text(
                label, style = MekaType.caption, color = if (lit) Meka.colors.textPrimary else Meka.colors.textTertiary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(fill)
                    .clickable(role = Role.Tab) { if (!lit) { haptics.tick(); onChange(isBoard) } }
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            )
        }
    }
}

/**
 * By hand, slice 4: "No day yet · 3" and "Someday · 12" as two small chips on one row (each a drop target that glows
 * gold and swells while a card is over it). Tap one with cards to open it, one at a time ([BoardTidyRules.toggle]);
 * an empty one never opens into an empty box. Removed targets are forgotten so a closed tray is never dropped on.
 */
@Composable
private fun TrayChips(
    chips: List<TrayChip>,
    open: BoardTray?,
    hovered: Long?,
    onBounds: (Long, Rect) -> Unit,
    onGone: (Long) -> Unit,
    onTap: (TrayChip) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = Meka.reducedMotion
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
        chips.forEach { chip ->
            val key = if (chip.tray == BoardTray.NO_DAY) LANE else TRAY
            val lit = hovered == key
            val isOpen = open == chip.tray
            val glow by animateColorAsState(
                when { lit || isOpen -> Meka.colors.accent; else -> Meka.colors.hairline }, MekaMotion.appear(reduced), label = "tray-glow-$key",
            )
            val swell by animateFloatAsState(if (lit && !reduced) BoardFoldRules.PILL_SWELL else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "tray-swell-$key")
            androidx.compose.runtime.DisposableEffect(key) { onDispose { onGone(key) } }
            Row(
                Modifier.heightIn(min = 32.dp)
                    .onGloballyPositioned { onBounds(key, it.boundsInRoot()) }
                    .graphicsLayer { scaleX = swell; scaleY = swell }
                    .clip(RoundedCornerShape(MekaRadius.pill))
                    .background(if (lit || isOpen) Meka.colors.surfaceRaised else Meka.colors.surface)
                    .border(1.dp, glow, RoundedCornerShape(MekaRadius.pill))
                    .clickable(enabled = chip.canOpen || isOpen, role = Role.Button) { onTap(chip) }
                    .clearAndSetSemantics { contentDescription = chip.accessibilityLabel }
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    chip.label, style = MekaType.caption, maxLines = 1,
                    color = when { isOpen -> Meka.colors.accent; chip.canOpen -> Meka.colors.textPrimary; else -> Meka.colors.textTertiary },
                )
                if (chip.canOpen) {
                    Spacer(Modifier.width(MekaSpace.xxs))
                    Text(if (isOpen) "▴" else "▾", style = MekaType.micro, color = Meka.colors.textTertiary)
                }
            }
        }
    }
}

/**
 * By hand, slice 4: an open tray's cards. Unfolded, a lane under the chips with its cards side by side; folded, the same
 * cards stacked in a sheet. They drag out onto a day (or a pill) and have the same menu as a day's.
 */
@Composable
private fun TrayCards(
    tray: BoardTray,
    week: BoardWeek,
    board: WeekBoard,
    stacked: Boolean,
    cardWidth: androidx.compose.ui.unit.Dp,
    dragging: String?,
    onCardBounds: (String, Rect) -> Unit,
    onPick: (BoardCard, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
    onMoveTo: (BoardCard, Long) -> Unit,
    onPark: (BoardCard, Long) -> Unit,
    onSomeday: (BoardCard) -> Unit,
    onTag: (BoardCard, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val (cards, source, spot) = when (tray) {
        BoardTray.NO_DAY -> Triple(week.lane, BoardSource.WEEK, week.startEpochDay)
        BoardTray.SOMEDAY -> Triple(board.someday, BoardSource.SOMEDAY, 0L)
    }
    @Composable
    fun row(card: BoardCard, mod: Modifier) = BoardCardRow(
        card, source, spot, emptyList(), board, dragging == card.id, onCardBounds, onPick, onDrag, onDrop,
        onCancel, { _, _, _ -> }, onMoveTo, onPark, onSomeday, null, { _, _ -> }, mod, onTag = onTag,
    )
    if (stacked) {
        Column(modifier.verticalScroll(rememberScrollState(), enabled = dragging == null), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            cards.forEach { card -> key(card.id) { row(card, Modifier.fillMaxWidth()) } }
        }
    } else {
        Row(modifier.horizontalScroll(rememberScrollState(), enabled = dragging == null), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            cards.forEach { card -> key(card.id) { row(card, Modifier.width(cardWidth)) } }
        }
    }
}

/**
 * By hand, slice 4: folded, an open tray is a sheet that rises from the bottom over the day (a scrim behind; tap it or
 * the chip to close). While a card from it is held the sheet steps aside (it fades out) so the day and the pills show,
 * and it closes once the card is let go. Reduced motion: it fades in and out.
 */
@Composable
private fun TraySheet(title: String, hidden: Boolean, onClose: () -> Unit, content: @Composable () -> Unit) {
    val reduced = Meka.reducedMotion
    val shown by animateFloatAsState(if (hidden) 0f else 1f, MekaMotion.appear(reduced), label = "tray-sheet")
    val rise = remember { Animatable(if (reduced) 0f else 1f) }
    LaunchedEffect(Unit) { rise.animateTo(0f, spring(dampingRatio = 0.85f, stiffness = 500f)) }
    val scrimTaps = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(Modifier.fillMaxSize().zIndex(1f).alpha(shown)) {
        Box(
            Modifier.fillMaxSize().background(Meka.colors.background.copy(alpha = 0.6f))
                .then(if (hidden) Modifier else Modifier.clickable(interactionSource = scrimTaps, indication = null) { onClose() }),
        )
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.6f)
                .graphicsLayer { translationY = rise.value * size.height }
                // A tap on the sheet itself stays on it (only the scrim closes it).
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false) } }
                .clip(RoundedCornerShape(topStart = MekaRadius.l, topEnd = MekaRadius.l))
                .background(Meka.colors.surfaceRaised)
                .border(1.dp, Meka.colors.hairline, RoundedCornerShape(topStart = MekaRadius.l, topEnd = MekaRadius.l))
                .padding(horizontal = MekaSpace.gutter, vertical = MekaSpace.m),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).width(MekaSpace.xl).height(4.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.hairline))
            Row(Modifier.fillMaxWidth().padding(vertical = MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MekaType.itemTitle, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                Text(
                    "Close", style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button) { onClose() }
                        .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xs),
                )
            }
            content()
        }
    }
}

/**
 * The week's goals across the top of the Board (slice 7): up to three, each its title, "2 of 4 done" and a slim bar
 * that fills gold as its tasks are done (all gold once the goal is done). Each is a drop target (it glows gold while a
 * card is over it; letting go tags the card). Tap a goal for Off this week; "+ Goal" offers open goals not picked yet
 * and New goal… to type one. Reduced motion: the bar and glow cross-fade.
 */
@Composable
private fun GoalsLane(
    week: BoardWeek,
    core: MekaCore,
    undo: EventUndo,
    hovered: Long?,
    onBounds: (Long, Rect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    fun show(line: String?) { if (line != null) { haptics.light(); undo.show(line) { core.undoBoardGoal() } } }
    Row(
        modifier.fillMaxWidth().padding(bottom = MekaSpace.xs).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        week.goals.forEachIndexed { i, g ->
            GoalChip(g, lit = hovered == GOAL_KEY - i, reduced = reduced, onBounds = { onBounds(GOAL_KEY - i, it) }) {
                scope.launch { show(runCatching { core.unpickBoardGoal(g.goalId, week.startEpochDay) }.getOrNull()) }
            }
        }
        if (week.canAddGoal) {
            AddGoalChip(
                week = week, empty = week.goals.isEmpty(),
                choices = { runCatching { core.boardGoalChoices(week.startEpochDay) }.getOrDefault(emptyList()) },
                onPick = { id -> scope.launch { show(runCatching { core.pickBoardGoal(id, week.startEpochDay) }.getOrNull()) } },
                onNew = { title -> scope.launch { show(runCatching { core.addBoardGoal(title, week.startEpochDay) }.getOrNull()) } },
            )
        }
    }
}

/**
 * By hand, slice 4: a goal as a small gold pill ("◎ Run 5k  2/4"), its background filling gold as its tasks are done
 * (all gold once the goal is done). A drop target: it glows gold while a card is over it. Tap it for Off this week.
 * Reduced motion: the fill and glow cross-fade.
 */
@Composable
private fun GoalChip(g: BoardGoal, lit: Boolean, reduced: Boolean, onBounds: (Rect) -> Unit, onOff: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val pill = BoardTidyRules.goalPill(g)
    val glow by animateColorAsState(if (lit) Meka.colors.accent else Meka.colors.accent.copy(alpha = 0.45f), MekaMotion.appear(reduced), label = "goal-glow")
    val fill by animateFloatAsState(pill.fill, MekaMotion.appear(reduced), label = "goal-fill")
    val tint = Meka.colors.accent.copy(alpha = 0.18f)
    val swell by animateFloatAsState(if (lit && !reduced) BoardFoldRules.PILL_SWELL else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "goal-swell")
    Box {
        Box(
            Modifier.widthIn(max = 220.dp).heightIn(min = 32.dp)
                .onGloballyPositioned { onBounds(it.boundsInRoot()) }
                .graphicsLayer { scaleX = swell; scaleY = swell }
                .clip(RoundedCornerShape(MekaRadius.pill))
                .background(if (lit) Meka.colors.surfaceRaised else Meka.colors.surface)
                .drawBehind { drawRect(tint, size = size.copy(width = size.width * fill)) }
                .border(1.dp, glow, RoundedCornerShape(MekaRadius.pill))
                .clickable(enabled = g.picked && !g.finished, role = Role.Button) { menu = true }
                .semantics(mergeDescendants = true) { contentDescription = pill.accessibilityLabel }
                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xxs),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pill.label, style = MekaType.caption, color = Meka.colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                pill.count?.let {
                    Spacer(Modifier.width(MekaSpace.xs))
                    Text(it, style = MekaType.micro, color = if (pill.finished) Meka.colors.accent else Meka.colors.textSecondary, maxLines = 1)
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(BoardGoalRules.OFF_WEEK, style = MekaType.itemMeta, color = Meka.colors.textPrimary) },
                onClick = { menu = false; onOff() },
            )
        }
    }
}

/** "+ Goal": the open goals not picked for the week (read as it opens), then New goal… to type one. */
@Composable
private fun AddGoalChip(
    week: BoardWeek,
    empty: Boolean,
    choices: suspend () -> List<GoalPickChoice>,
    onPick: (String) -> Unit,
    onNew: (String) -> Unit,
) {
    val haptics = rememberMekaHaptics()
    var menu by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var offers by remember(week.startEpochDay) { mutableStateOf<List<GoalPickChoice>>(emptyList()) }
    LaunchedEffect(menu) { if (menu) offers = choices() }
    if (typing) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
            cursorBrush = SolidColor(Meka.colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                if (text.isNotBlank()) onNew(text)
                text = ""; typing = false
            }),
            modifier = Modifier.width(200.dp).padding(MekaSpace.xs).semantics { contentDescription = "New goal for the week" },
        )
        return
    }
    Box {
        // By hand, slice 4: "+ Goal" is a small pill beside the goals (with the hint beside it while the week has none).
        Row(
            Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(MekaRadius.pill))
                .border(1.dp, Meka.colors.hairline, RoundedCornerShape(MekaRadius.pill))
                .clickable(role = Role.Button) { haptics.tick(); menu = true }
                .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xxs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(BoardGoalRules.ADD, style = MekaType.caption, color = Meka.colors.accent)
            if (empty) {
                Spacer(Modifier.width(MekaSpace.xs))
                Text(BoardGoalRules.EMPTY, style = MekaType.micro, color = Meka.colors.textTertiary, maxLines = 1)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            offers.forEach { o ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(o.title, style = MekaType.itemMeta, color = Meka.colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(o.horizon, style = MekaType.micro, color = Meka.colors.textTertiary)
                        }
                    },
                    onClick = { menu = false; onPick(o.goalId) },
                )
            }
            DropdownMenuItem(
                text = { Text(BoardGoalRules.NEW_GOAL, style = MekaType.itemMeta, color = Meka.colors.accent) },
                onClick = { menu = false; typing = true },
            )
        }
    }
}
