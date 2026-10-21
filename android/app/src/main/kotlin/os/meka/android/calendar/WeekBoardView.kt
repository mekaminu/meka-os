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
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import os.meka.core.domain.BoardSource
import os.meka.core.domain.BoardWeek
import os.meka.core.domain.CalendarEvent
import os.meka.core.domain.TaskEstimateRules
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
 * Reduced motion: no lift or scale, colours and moves cross-fade.
 */
@Composable
fun WeekBoardView(core: MekaCore, undo: EventUndo, onEvent: (CalendarEvent) -> Unit, modifier: Modifier = Modifier) {
    val board by core.weekBoard.collectAsState()
    if (board.weeks.isEmpty()) return
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // Drag state, in root coordinates: the card picked up, where the finger is, and where on the card it was held.
    var drag by remember { mutableStateOf<BoardDrag?>(null) }
    // Where a card can land: a day's column (its epoch day), the week's lane (LANE) or the Someday tray (TRAY).
    val columns = remember { mutableStateMapOf<Long, Rect>() }
    val cards = remember { mutableStateMapOf<String, Rect>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val hovered by remember {
        derivedStateOf {
            drag?.let { d ->
                columns.entries.firstOrNull { (k, r) -> k < 0 && r.contains(d.pointer) }?.key
                    ?: columns.entries.firstOrNull { (k, r) -> k >= 0 && d.pointer.x >= r.left && d.pointer.x < r.right && d.pointer.y >= r.top }?.key
            }
        }
    }
    LaunchedEffect(hovered) { if (drag != null && hovered != null) haptics.tick() }
    fun showLine(line: String?) { if (line != null) { haptics.light(); undo.show(line) { core.undoBoardMove() } } }
    val onPark: (BoardCard, Long) -> Unit = { card, week -> scope.launch { showLine(runCatching { core.parkOnBoard(card.id, week, Int.MAX_VALUE) }.getOrNull()) } }
    val onSomeday: (BoardCard) -> Unit = { card -> scope.launch { showLine(runCatching { core.somedayOnBoard(card.id) }.getOrNull()) } }

    BoxWithConstraints(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val wide = maxWidth >= 600.dp
        val colWidth = if (wide) (maxWidth - MekaSpace.gutter * 2) / 7 else maxWidth * 0.8f
        val colPx = with(density) { colWidth.toPx() }
        val scroll = rememberScrollState()
        // Start on today's column (unfolded: this week).
        LaunchedEffect(Unit) {
            val index = board.days.indexOfFirst { it.isToday }.coerceAtLeast(0)
            scroll.scrollTo(((if (wide) index - index % 7 else index) * colPx).toInt())
        }
        // Near an edge while dragging, the board scrolls along.
        LaunchedEffect(drag != null) {
            while (drag != null) {
                withFrameNanos { }
                val d = drag ?: break
                val x = d.pointer.x - origin.x
                val edge = with(density) { MekaSpace.xxl.toPx() }
                val width = with(density) { maxWidth.toPx() }
                val step = when {
                    x < edge -> -with(density) { MekaSpace.xs.toPx() }
                    x > width - edge -> with(density) { MekaSpace.xs.toPx() }
                    else -> 0f
                }
                if (step != 0f) scroll.scrollBy(step)
            }
        }

        val weekIndex by remember(board, colPx) { derivedStateOf { ((scroll.value + colPx / 2) / colPx / 7).toInt().coerceIn(0, board.weeks.size - 1) } }
        val pick: (BoardCard, Offset) -> Unit = { card, at -> haptics.tick(); drag = BoardDrag(card, at, at - (cards[card.id]?.topLeft ?: at)) }
        val dragBy: (Offset) -> Unit = { delta -> drag = drag?.let { it.copy(pointer = it.pointer + delta) } }
        val dropCard: () -> Unit = drop@{
            val d = drag
            drag = null
            val at = hovered
            val week = board.weeks.getOrNull(weekIndex)
            if (d == null || at == null) return@drop
            when {
                at == LANE && week != null -> {
                    val index = week.lane.filter { it.id != d.card.id }.count { c -> (cards[c.id]?.center?.x ?: 0f) < d.pointer.x }
                    scope.launch { showLine(runCatching { core.parkOnBoard(d.card.id, week.startEpochDay, index) }.getOrNull()) }
                }
                at == TRAY -> onSomeday(d.card)
                else -> board.day(at)?.takeIf { it.canDrop }?.let { target ->
                    val index = target.openCards.filter { it.id != d.card.id }.count { c -> (cards[c.id]?.center?.y ?: 0f) < d.pointer.y }
                    scope.launch { showLine(runCatching { core.moveOnBoard(d.card.id, target.epochDay, index) }.getOrNull()) }
                }
            }
        }

        Column(Modifier.fillMaxSize()) {
            WeekHeader(board, weekIndex, scroll, colPx, Modifier.padding(horizontal = MekaSpace.gutter).appear(rememberAppearance(2)))
            board.weeks.getOrNull(weekIndex)?.let { week ->
                LaneAndTray(
                    week = week, board = board, laneWidth = colWidth, hovered = if (drag != null) hovered else null, dragging = drag?.card?.id,
                    onBounds = { key, r -> columns[key] = r }, onCardBounds = { id, r -> cards[id] = r },
                    onPick = pick, onDrag = dragBy, onDrop = dropCard, onCancel = { drag = null },
                    onMoveTo = { card, to -> scope.launch { showLine(runCatching { core.moveOnBoard(card.id, to, Int.MAX_VALUE) }.getOrNull()) } },
                    onPark = onPark, onSomeday = onSomeday,
                    modifier = Modifier.padding(horizontal = MekaSpace.gutter).appear(rememberAppearance(3)),
                )
            }
            Row(
                Modifier.fillMaxSize().horizontalScroll(scroll, enabled = drag == null)
                    .padding(horizontal = if (wide) MekaSpace.gutter else MekaSpace.xs)
                    .appear(rememberAppearance(4)),
            ) {
                board.days.forEach { day ->
                    DayColumn(
                        day = day,
                        width = colWidth,
                        hovered = drag != null && hovered == day.epochDay && day.canDrop,
                        dragging = drag?.card?.id,
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
                            scope.launch {
                                runCatching { core.complete(card.id) }
                                undo.show("Done “${card.title}”") { core.reopen(card.id) }
                            }
                        },
                        onAdd = { text -> scope.launch { runCatching { core.addOnBoard(text, day.epochDay) } } },
                        onEstimate = { card, minutes ->
                            scope.launch {
                                val line = runCatching { core.setEstimate(card.id, minutes) }.getOrNull()
                                if (line != null) { haptics.tick(); undo.show(line) { core.undoEstimate() } }
                            }
                        },
                        board = board,
                        onEvent = onEvent,
                    )
                }
            }
        }

        // The card being dragged follows the finger, lifted (Reduced motion: no lift).
        drag?.let { d ->
            val at = d.pointer - d.grab - origin
            Box(
                Modifier.zIndex(2f).width(colWidth - MekaSpace.xs)
                    .graphicsLayer {
                        translationX = at.x; translationY = at.y
                        if (!reduced) { scaleX = LIFT; scaleY = LIFT; shadowElevation = 12.dp.toPx() }
                        shape = RoundedCornerShape(MekaRadius.s); clip = true
                    },
            ) { CardFace(d.card, lifted = true) }
        }
    }
}

private const val LIFT = 1.04f

private data class BoardDrag(val card: BoardCard, val pointer: Offset, val grab: Offset)

/** Drop-target keys besides a day's epoch day: the week's lane and the Someday tray. */
private const val LANE = -1L
private const val TRAY = -2L

/** "This week · 19–25 Oct" with ‹ › stepping a week; follows the board as it scrolls. */
@Composable
private fun WeekHeader(board: WeekBoard, week: Int, scroll: ScrollState, colPx: Float, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val w = board.weeks[week]
    fun go(to: Int) {
        val target = (to * 7 * colPx).toInt()
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
    hovered: Boolean,
    dragging: String?,
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
    onAdd: (String) -> Unit,
    onEstimate: (BoardCard, Int?) -> Unit,
    board: WeekBoard,
    onEvent: (CalendarEvent) -> Unit,
) {
    val reduced = Meka.reducedMotion
    val glow by animateColorAsState(if (hovered) Meka.colors.accent else Meka.colors.hairline.copy(alpha = 0f), MekaMotion.appear(reduced), label = "board-glow")
    val fill by animateColorAsState(
        when {
            hovered -> Meka.colors.surfaceRaised
            day.isToday -> Meka.colors.surface
            else -> Meka.colors.background
        },
        MekaMotion.appear(reduced), label = "board-fill",
    )
    Column(
        Modifier.width(width).fillMaxHeight().padding(horizontal = MekaSpace.xxs)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .clip(RoundedCornerShape(MekaRadius.m)).background(fill)
            .border(1.dp, glow, RoundedCornerShape(MekaRadius.m))
            .alpha(if (day.isPast) 0.6f else 1f)
            .verticalScroll(rememberScrollState(), enabled = dragging == null)
            .padding(MekaSpace.xs),
    ) {
        // Date, marker, count.
        Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = day.accessibilityLabel }) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(day.title, style = MekaType.metaStrong, color = if (day.isToday) Meka.colors.accent else Meka.colors.textPrimary, maxLines = 1)
                day.placeLabel?.let {
                    Spacer(Modifier.width(MekaSpace.xs))
                    Text(it, style = MekaType.micro, color = Meka.colors.textTertiary, maxLines = 1)
                }
            }
            Text(day.countLine, style = MekaType.micro, color = Meka.colors.textTertiary, maxLines = 1)
            day.capacity?.let { CapacityBar(it) }
        }
        // The day's events, faded.
        if (day.chips.isNotEmpty()) {
            Column(Modifier.padding(top = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                day.chips.forEach { chip ->
                    Row(
                        Modifier.fillMaxWidth().alpha(0.7f).clip(RoundedCornerShape(MekaRadius.s))
                            .clickable(role = Role.Button) { onEvent(chip.event) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(Meka.colors.calendarTone(chip.tone)))
                        Spacer(Modifier.width(MekaSpace.xxs))
                        Text("${chip.time} ${chip.title}", style = MekaType.micro, color = Meka.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                day.moreLabel?.let { Text(it, style = MekaType.micro, color = Meka.colors.textTertiary) }
            }
        }
        Spacer(Modifier.height(MekaSpace.xs))
        Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            day.cards.forEach { card ->
                BoardCardRow(
                    card, BoardSource.DAY, day.epochDay, day.openCards, board, dragging == card.id, onCardBounds, onPick, onDrag, onDrop, onCancel,
                    onMove, onMoveTo, onPark, onSomeday, onDone, onEstimate,
                )
            }
        }
        if (hovered && day.openCards.isEmpty()) {
            Text(WeekBoardRules.DROP_HERE, style = MekaType.caption, color = Meka.colors.accent, modifier = Modifier.padding(top = MekaSpace.xs))
        }
        if (day.canDrop) QuickAdd(onAdd)
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
) {
    var menu by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val ghost by animateFloatAsState(if (beingDragged) 0.3f else 1f, MekaMotion.appear(Meka.reducedMotion), label = "board-ghost")
    Box(
        modifier.alpha(ghost)
            .onGloballyPositioned { bounds = it.boundsInRoot(); onCardBounds(card.id, bounds) }
            .then(
                if (card.canMove) Modifier.pointerInput(card.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { at -> onPick(card, bounds.topLeft + at) },
                        onDrag = { change, amount -> change.consume(); onDrag(amount) },
                        onDragEnd = onDrop,
                        onDragCancel = onCancel,
                    )
                } else Modifier,
            )
            .clip(RoundedCornerShape(MekaRadius.s))
            .clickable(enabled = card.canMove, role = Role.Button) { menu = true }
            .semantics { contentDescription = card.accessibilityLabel },
    ) {
        CardFace(card, lifted = false, onDone = onDone?.let { f -> { f(card) } })
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
            }
        }
    }
}

/** A card: the ring, the title (body), a quiet line under it; carried ones say so in the accent; done ones faded. */
@Composable
private fun CardFace(card: BoardCard, lifted: Boolean, onDone: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().background(if (lifted) Meka.colors.surfaceRaised else Meka.colors.surfaceRaised.copy(alpha = 0.6f))
            .padding(MekaSpace.xs),
        verticalAlignment = Alignment.Top,
    ) {
        val ring = if (card.done) Meka.colors.accent else Meka.colors.textTertiary
        Box(
            Modifier.padding(top = MekaSpace.xxs).size(16.dp).clip(CircleShape)
                .background(if (card.done) Meka.colors.accent else Meka.colors.surfaceRaised.copy(alpha = 0f))
                .border(1.5.dp, ring, CircleShape)
                .then(if (onDone != null && !card.done) Modifier.clickable(role = Role.Checkbox) { onDone() } else Modifier)
                .semantics { contentDescription = if (card.done) "Done" else "Tick off ${card.title}" },
        )
        Spacer(Modifier.width(MekaSpace.xs))
        Column(Modifier.weight(1f)) {
            Text(
                card.title, style = MekaType.body, maxLines = 3, overflow = TextOverflow.Ellipsis,
                color = if (card.done) Meka.colors.textTertiary else Meka.colors.textPrimary,
            )
            val meta = card.meta
            if (meta.isNotEmpty()) {
                Text(meta, style = MekaType.micro, color = if (card.carried) Meka.colors.accent else Meka.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * "3h planned · 4h free" under a column's date with a slim bar (slice 3): gold as it fills, amber when the day is
 * over-planned, breathing gently then (Reduced motion: steady). The bar's width glides as estimates change.
 */
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

/** "+ Add" at a column's foot: a line to type a task for that day; Done adds it last in the day. */
@Composable
private fun QuickAdd(onAdd: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    if (!open) {
        Text(
            "+ Add", style = MekaType.caption, color = Meka.colors.textTertiary,
            modifier = Modifier.padding(top = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.pill))
                .clickable(role = Role.Button) { open = true }.padding(vertical = MekaSpace.xs),
        )
    } else {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
            cursorBrush = SolidColor(Meka.colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                if (text.isNotBlank()) onAdd(text)
                text = ""; open = false
            }),
            modifier = Modifier.fillMaxWidth().padding(top = MekaSpace.xs).semantics { contentDescription = "New task for this day" },
        )
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
 * Above the days (slice 4): the week in view's lane, "No day yet" with its cards side by side (parked for the week, off
 * Today until they get a day), and the folded Someday tray; tap the tray to open its cards. Both glow gold while a card
 * is over them. Cards here drag and have the same menu as a day's.
 */
@Composable
private fun LaneAndTray(
    week: BoardWeek,
    board: WeekBoard,
    laneWidth: androidx.compose.ui.unit.Dp,
    hovered: Long?,
    dragging: String?,
    onBounds: (Long, Rect) -> Unit,
    onCardBounds: (String, Rect) -> Unit,
    onPick: (BoardCard, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
    onMoveTo: (BoardCard, Long) -> Unit,
    onPark: (BoardCard, Long) -> Unit,
    onSomeday: (BoardCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    var trayOpen by remember { mutableStateOf(false) }
    val cardWidth = laneWidth.coerceIn(140.dp, 200.dp)
    @Composable
    fun zone(key: Long, lit: Boolean, mod: Modifier, content: @Composable () -> Unit) {
        val glow by animateColorAsState(if (lit) Meka.colors.accent else Meka.colors.hairline, MekaMotion.appear(reduced), label = "zone-glow-$key")
        val fill by animateColorAsState(if (lit) Meka.colors.surfaceRaised else Meka.colors.surface, MekaMotion.appear(reduced), label = "zone-fill-$key")
        Box(
            mod.onGloballyPositioned { onBounds(key, it.boundsInRoot()) }
                .clip(RoundedCornerShape(MekaRadius.m)).background(fill)
                .border(1.dp, glow, RoundedCornerShape(MekaRadius.m)).padding(MekaSpace.xs),
        ) { content() }
    }
    Column(modifier.fillMaxWidth().padding(bottom = MekaSpace.xs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalAlignment = Alignment.Top) {
            zone(LANE, hovered == LANE, Modifier.weight(1f)) {
                Column {
                    Row(Modifier.clearAndSetSemantics { contentDescription = week.laneAccessibilityLabel }, verticalAlignment = Alignment.Bottom) {
                        Text(WeekBoardRules.NO_DAY, style = MekaType.metaStrong, color = Meka.colors.textPrimary, maxLines = 1)
                        Spacer(Modifier.width(MekaSpace.xs))
                        Text(week.laneLine, style = MekaType.micro, color = Meka.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (week.lane.isNotEmpty()) {
                        Row(
                            Modifier.padding(top = MekaSpace.xs).horizontalScroll(rememberScrollState(), enabled = dragging == null),
                            horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                        ) {
                            week.lane.forEach { card ->
                                BoardCardRow(
                                    card, BoardSource.WEEK, week.startEpochDay, emptyList(), board, dragging == card.id, onCardBounds, onPick, onDrag, onDrop,
                                    onCancel, { _, _, _ -> }, onMoveTo, onPark, onSomeday, null, { _, _ -> }, Modifier.width(cardWidth),
                                )
                            }
                        }
                    }
                }
            }
            zone(TRAY, hovered == TRAY, Modifier.width(cardWidth)) {
                Column {
                    Text(
                        board.somedayTitle + if (board.someday.isEmpty()) "" else if (trayOpen) "  ▴" else "  ▾",
                        style = MekaType.metaStrong, color = Meka.colors.textSecondary, maxLines = 1,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.s))
                            .clickable(enabled = board.someday.isNotEmpty(), role = Role.Button) { haptics.tick(); trayOpen = !trayOpen }
                            .semantics { contentDescription = board.somedayTitle + if (trayOpen) ", open" else ", folded" },
                    )
                    if (board.someday.isEmpty()) {
                        Text(WeekBoardRules.SOMEDAY_EMPTY, style = MekaType.micro, color = Meka.colors.textTertiary, maxLines = 1)
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = trayOpen && board.someday.isNotEmpty(),
                        enter = androidx.compose.animation.expandVertically(MekaMotion.appear(reduced)) + androidx.compose.animation.fadeIn(MekaMotion.appear(reduced)),
                        exit = androidx.compose.animation.shrinkVertically(MekaMotion.appear(reduced)) + androidx.compose.animation.fadeOut(MekaMotion.appear(reduced)),
                    ) {
                        Column(
                            Modifier.padding(top = MekaSpace.xs).height(220.dp).verticalScroll(rememberScrollState(), enabled = dragging == null),
                            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                        ) {
                            board.someday.forEach { card ->
                                BoardCardRow(
                                    card, BoardSource.SOMEDAY, 0L, emptyList(), board, dragging == card.id, onCardBounds, onPick, onDrag, onDrop,
                                    onCancel, { _, _, _ -> }, onMoveTo, onPark, onSomeday, null, { _, _ -> },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
