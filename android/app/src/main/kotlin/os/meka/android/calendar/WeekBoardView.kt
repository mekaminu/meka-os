package os.meka.android.calendar

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import os.meka.core.domain.BoardCard
import os.meka.core.domain.BoardDay
import os.meka.core.domain.CalendarEvent
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
    val columns = remember { mutableStateMapOf<Long, Rect>() }
    val cards = remember { mutableStateMapOf<String, Rect>() }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val hovered by remember { derivedStateOf { drag?.let { d -> columns.entries.firstOrNull { d.pointer.x >= it.value.left && d.pointer.x < it.value.right }?.key } } }
    LaunchedEffect(hovered) { if (drag != null && hovered != null) haptics.tick() }

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

        Column(Modifier.fillMaxSize()) {
            WeekHeader(board, scroll, colPx, Modifier.padding(horizontal = MekaSpace.gutter).appear(rememberAppearance(2)))
            Row(
                Modifier.fillMaxSize().horizontalScroll(scroll, enabled = drag == null)
                    .padding(horizontal = if (wide) MekaSpace.gutter else MekaSpace.xs)
                    .appear(rememberAppearance(3)),
            ) {
                board.days.forEach { day ->
                    DayColumn(
                        day = day,
                        width = colWidth,
                        hovered = drag != null && hovered == day.epochDay && day.canDrop,
                        dragging = drag?.card?.id,
                        onBounds = { columns[day.epochDay] = it },
                        onCardBounds = { id, r -> cards[id] = r },
                        onPick = { card, at -> haptics.tick(); drag = BoardDrag(card, at, at - (cards[card.id]?.topLeft ?: at)) },
                        onDrag = { delta -> drag = drag?.let { it.copy(pointer = it.pointer + delta) } },
                        onDrop = {
                            val d = drag
                            drag = null
                            val target = hovered?.let { board.day(it) }
                            if (d != null && target != null && target.canDrop) {
                                val index = target.openCards.filter { it.id != d.card.id }.count { c -> (cards[c.id]?.center?.y ?: 0f) < d.pointer.y }
                                scope.launch {
                                    val line = runCatching { core.moveOnBoard(d.card.id, target.epochDay, index) }.getOrNull()
                                    if (line != null) { haptics.light(); undo.show(line) { core.undoBoardMove() } }
                                }
                            }
                        },
                        onCancel = { drag = null },
                        onMove = { card, days, places ->
                            scope.launch {
                                val line = runCatching { core.nudgeOnBoard(card.id, days, places) }.getOrNull()
                                if (line != null) { haptics.light(); undo.show(line) { core.undoBoardMove() } }
                            }
                        },
                        onMoveTo = { card, to ->
                            scope.launch {
                                val line = runCatching { core.moveOnBoard(card.id, to, Int.MAX_VALUE) }.getOrNull()
                                if (line != null) { haptics.light(); undo.show(line) { core.undoBoardMove() } }
                            }
                        },
                        onDone = { card ->
                            haptics.light()
                            scope.launch {
                                runCatching { core.complete(card.id) }
                                undo.show("Done “${card.title}”") { core.reopen(card.id) }
                            }
                        },
                        onAdd = { text -> scope.launch { runCatching { core.addOnBoard(text, day.epochDay) } } },
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

/** "This week · 19–25 Oct" with ‹ › stepping a week; follows the board as it scrolls. */
@Composable
private fun WeekHeader(board: WeekBoard, scroll: ScrollState, colPx: Float, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    val week by remember(board, colPx) { derivedStateOf { ((scroll.value + colPx / 2) / colPx / 7).toInt().coerceIn(0, board.weeks.size - 1) } }
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
    onDone: (BoardCard) -> Unit,
    onAdd: (String) -> Unit,
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
                BoardCardRow(card, day, board, dragging == card.id, onCardBounds, onPick, onDrag, onDrop, onCancel, onMove, onMoveTo, onDone)
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
    day: BoardDay,
    board: WeekBoard,
    beingDragged: Boolean,
    onCardBounds: (String, Rect) -> Unit,
    onPick: (BoardCard, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
    onMove: (BoardCard, Int, Int) -> Unit,
    onMoveTo: (BoardCard, Long) -> Unit,
    onDone: (BoardCard) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val ghost by animateFloatAsState(if (beingDragged) 0.3f else 1f, MekaMotion.appear(Meka.reducedMotion), label = "board-ghost")
    Box(
        Modifier.fillMaxWidth().alpha(ghost)
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
        CardFace(card, lifted = false, onDone = { onDone(card) })
        if (card.canMove) {
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                @Composable
                fun entry(label: String, onClick: () -> Unit) = DropdownMenuItem(
                    text = { Text(label, style = MekaType.itemMeta, color = Meka.colors.textPrimary) },
                    onClick = { menu = false; onClick() },
                )
                val index = day.openCards.indexOfFirst { it.id == card.id }
                if (index > 0) entry("Move up") { onMove(card, 0, -1) }
                if (index in 0 until day.openCards.size - 1) entry("Move down") { onMove(card, 0, 1) }
                // The day strip: today and the next six days, except the day it's on.
                (board.todayEpochDay until board.todayEpochDay + 7).filter { it != day.epochDay }.forEach { d ->
                    val label = when (d) {
                        board.todayEpochDay -> "Today"
                        board.todayEpochDay + 1 -> "Tomorrow"
                        else -> board.day(d)?.let { "${it.weekday} ${it.number}" } ?: return@forEach
                    }
                    entry(label) { onMoveTo(card, d) }
                }
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
            val meta = listOfNotNull(card.time, card.line).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(meta, style = MekaType.micro, color = if (card.carried) Meka.colors.accent else Meka.colors.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
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
