package os.meka.android.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaChoreography
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.MotionMath
import os.meka.android.designsystem.SwipeBackdrop
import os.meka.android.designsystem.SwipeGlyph
import os.meka.android.designsystem.TickRing
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.ChecklistItem
import os.meka.core.domain.ChecklistRules
import os.meka.core.domain.ShopModeRules
import os.meka.core.domain.Task
import os.meka.core.domain.TextAutosave
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Checklist (Checklists in tasks, slice 1; "Routine steps" on a repeating task, whose occurrences bring them back
 * unticked). Still to tick first, then "Ticked". Typing or pasting several lines (or commas) adds each as an item.
 * Motion (catalogue "Checklist"): a tick draws its check and pops ([TickRing]) with a light haptic, then the row moves
 * under Ticked a beat later and fades into its new place while the list's height follows on the expand spring; swipe
 * left to remove (the backdrop washes in, a tick haptic arms it), the undo bar rises. Slice 2: drag a row by its handle
 * to reorder it within its group (it lifts onto the raised surface, the rows it passes slide out of its way on the
 * replan spring with a tick haptic each, and it settles where it's let go); "Shop mode" opens the list full screen
 * ([ShopModeScreen]). Reduced motion: at once.
 */
@Composable
internal fun ChecklistSection(task: Task, actions: TodayActions) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val view = ChecklistRules.view(task)
    // The order shown lags a beat behind a tick, so the check draws in place before the row moves under Ticked.
    val target = view.toTick + view.ticked
    var shown by remember(task.id) { mutableStateOf(target) }
    LaunchedEffect(target) {
        // Only a tick waits (so its check draws in place); a reorder or an add shows at once.
        val ticks = shown.map { it.id to it.checked }.toSet() != target.map { it.id to it.checked }.toSet()
        if (ticks && shown.map { it.id } != target.map { it.id } && !reduced && shown.size == target.size) delay(MekaChoreography.checkDrawMs.toLong())
        shown = target
    }
    var shopping by remember(task.id) { mutableStateOf(false) }
    // Drag to reorder (slice 2): the row being dragged and how far it has moved.
    var dragId by remember(task.id) { mutableStateOf<String?>(null) }
    var dragDy by remember(task.id) { mutableFloatStateOf(0f) }
    var rowPx by remember { mutableFloatStateOf(0f) }
    // Ticks follow the live data even while the order waits.
    val live = target.associateBy { it.id }
    val rows = shown.mapNotNull { live[it.id] }
    // "Ticked" heads the run of ticked rows at the end (a row just ticked waits a beat above it).
    var tail = rows.size
    while (tail > 0 && rows[tail - 1].checked) tail--
    val firstTicked = tail.takeIf { it < rows.size }
    // Each group (still to tick, then Ticked) reorders on its own.
    val parts = listOf(rows.subList(0, tail), rows.subList(tail, rows.size))
    val dragPart = parts.firstOrNull { p -> p.any { it.id == dragId } }
    val dragFrom = dragPart?.indexOfFirst { it.id == dragId } ?: -1
    val dragTo = if (dragPart == null) -1 else ChecklistRules.dropIndex(dragFrom, dragDy, rowPx, dragPart.size)
    var lastTo by remember { mutableIntStateOf(-1) }
    LaunchedEffect(dragTo) { if (dragTo >= 0 && lastTo >= 0 && dragTo != lastTo) haptics.tick(); lastTo = dragTo }

    Row(Modifier.fillMaxWidth().padding(top = MekaSpace.l), verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(view.label)
        Spacer(Modifier.weight(1f))
        view.line?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        if (ShopModeRules.offer(view.size)) {
            Text(
                ShopModeRules.OPEN, style = MekaType.caption, color = Meka.colors.accent,
                modifier = Modifier.padding(start = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m))
                    .clickable(role = Role.Button) { haptics.light(); shopping = true }
                    .padding(MekaSpace.xs),
            )
        }
    }
    if (shopping) {
        ShopModeScreen(
            view = ShopModeRules.ofChecklist(task),
            onTick = { row -> actions.setStepDone(row.id, !row.done) },
            onClose = { shopping = false },
        )
    }
    Column(Modifier.fillMaxWidth().animateContentSize(MekaMotion.expand(reduced))) {
        rows.forEachIndexed { i, item ->
            key(item.id) {
                if (i == firstTicked) {
                    Row(Modifier.fillMaxWidth().padding(top = MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
                        Text(ChecklistRules.TICKED_LABEL, style = MekaType.caption, color = Meka.colors.textTertiary)
                        Spacer(Modifier.weight(1f))
                        if (view.canUntickAll) {
                            Text(
                                ChecklistRules.UNTICK_ALL, style = MekaType.caption, color = Meka.colors.accent,
                                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.m))
                                    .clickable(role = Role.Button) { haptics.tick(); actions.untickAll(task.id) }
                                    .padding(MekaSpace.xs),
                            )
                        }
                    }
                }
                val part = if (i < tail) parts[0] else parts[1]
                val at = if (i < tail) i else i - tail
                val dragging = item.id == dragId
                val shift = when {
                    dragPart !== part || dragging || dragFrom < 0 -> 0f
                    dragFrom < dragTo && at in (dragFrom + 1)..dragTo -> -rowPx
                    dragTo < dragFrom && at in dragTo until dragFrom -> rowPx
                    else -> 0f
                }
                // Rows make room on the replan spring while a drag is on; once it's let go they sit at once in the
                // new order (no glide back from the room they made).
                val slide = remember { Animatable(0f) }
                LaunchedEffect(shift, dragId == null) { if (dragId == null) slide.snapTo(0f) else slide.animateTo(shift, MekaMotion.replan(reduced)) }
                ChecklistRow(
                    item, actions,
                    dy = when { dragging -> dragDy; dragId == null -> 0f; else -> slide.value },
                    lifted = dragging,
                    canDrag = part.size > 1,
                    onMeasured = { if (it > 0f) rowPx = it },
                    onDragStart = { haptics.light(); dragId = item.id; dragDy = 0f; lastTo = at },
                    onDrag = { dragDy += it },
                    onDragEnd = {
                        val to = ChecklistRules.dropIndex(at, dragDy, rowPx, part.size)
                        if (to != at) {
                            // Shown in its new place at once; the synced order follows.
                            val moved = part.toMutableList().apply { add(to, removeAt(at)) }
                            shown = if (i < tail) moved + parts[1] else parts[0] + moved
                            actions.moveStep(task.id, item.id, to)
                        }
                        dragId = null; dragDy = 0f
                    },
                )
            }
        }
    }
    AnimatedVisibility(
        visible = view.offerComplete,
        enter = if (reduced) fadeIn(MekaMotion.expand(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false)),
        exit = if (reduced) fadeOut(MekaMotion.expand(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = MekaSpace.xs).clip(RoundedCornerShape(MekaRadius.m))
                .background(Meka.colors.surfaceRaised).padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(ChecklistRules.COMPLETE_OFFER, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
            Text(
                "Complete", style = MekaType.itemMeta, color = Meka.colors.onAccent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                    .clickable(role = Role.Button) { haptics.light(); actions.complete(task.id) }
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
            )
        }
    }
    AddItemField(task, actions)
}

@Composable
private fun AddItemField(task: Task, actions: TodayActions) {
    // Not saveable: leaving (an unfold included) adds what was typed, so it mustn't come back to be added twice.
    val textState = remember(task.id) { mutableStateOf("") }
    var text by textState
    // An item typed but not yet added is added when the detail closes or shows another task, as if Done had been
    // pressed ([TextAutosave]: typing is never lost). Reads this task's own field, not the next task's.
    val add by rememberUpdatedState(actions.addStep)
    DisposableEffect(task.id) { onDispose { TextAutosave.pendingAdd(textState.value)?.let { add(task.id, it) } } }
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = MekaSpace.xs), contentAlignment = Alignment.CenterStart) {
        if (text.isEmpty()) Text(ChecklistRules.ADD_HINT, style = MekaType.body, color = Meka.colors.textTertiary)
        BasicTextField(
            value = text,
            // A paste with several lines (or Enter on a keyboard) adds them all at once, each line an item.
            onValueChange = { v ->
                if ('\n' in v) { if (v.isNotBlank()) actions.addStep(task.id, v); text = "" } else text = v
            },
            textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
            cursorBrush = SolidColor(Meka.colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) { actions.addStep(task.id, text); text = "" } }),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Add checklist items" },
        )
    }
}

@Composable
private fun ChecklistRow(
    item: ChecklistItem, actions: TodayActions,
    dy: Float = 0f, lifted: Boolean = false, canDrag: Boolean = false,
    onMeasured: (Float) -> Unit = {}, onDragStart: () -> Unit = {}, onDrag: (Float) -> Unit = {}, onDragEnd: () -> Unit = {},
) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { MekaChoreography.swipeArmDistanceDp.dp.toPx() }
    var dx by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableIntStateOf(0) }
    // Moving under Ticked (or back): the row fades into its new place.
    val fade = remember { Animatable(1f) }
    val seen = remember { arrayOfNulls<Boolean>(1) }
    LaunchedEffect(item.checked) {
        val was = seen[0]
        seen[0] = item.checked
        if (was != null && was != item.checked && !reduced) {
            delay(MekaChoreography.checkDrawMs.toLong())
            fade.snapTo(0.2f)
            fade.animateTo(1f, MekaMotion.appear(false))
        }
    }
    // The handle's gesture lives across recompositions: always call the latest callbacks.
    val dragStart by rememberUpdatedState(onDragStart)
    val dragBy by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val lift by animateFloatAsState(if (lifted && !reduced) 1f else 0f, MekaMotion.approve(reduced), label = "checklist-lift")
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .zIndex(if (lifted) 1f else 0f)
            .onSizeChanged { onMeasured(it.height.toFloat()) }
            .graphicsLayer { translationY = dy; scaleX = 1f + 0.02f * lift; scaleY = 1f + 0.02f * lift }
            .semantics {
                customActions = listOf(CustomAccessibilityAction("Remove") { actions.removeStep(item); true })
            },
    ) {
        val max = constraints.maxWidth.toFloat().coerceAtLeast(threshold * 1.5f)
        val progress = MotionMath.swipeProgress(with(LocalDensity.current) { dx.toDp().value })
        SwipeBackdrop(
            side = if (dx < 0) -1 else 0, progress = progress, armed = armed != 0, glyph = SwipeGlyph.HIDE,
            label = "Remove", fill = Meka.colors.critical, content = Meka.colors.background,
        )
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .offset { IntOffset(dx.roundToInt(), 0) }
                .graphicsLayer { alpha = fade.value }
                .clip(RoundedCornerShape(MekaRadius.m))
                .background(if (lifted) Meka.colors.surfaceRaised else Meka.colors.background)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        dx = (dx + delta).coerceIn(-max, 0f)
                        val now = if (abs(dx) >= threshold) -1 else 0
                        if (now != armed) { if (now != 0) haptics.tick(); armed = now }
                    },
                    onDragStopped = {
                        val remove = armed != 0
                        armed = 0
                        if (remove) { haptics.light(); actions.removeStep(item) }
                        scope.launch { animate(dx, 0f, animationSpec = MekaMotion.complete(reduced)) { v, _ -> dx = v } }
                    },
                )
                .clickable(role = Role.Checkbox) { haptics.light(); actions.setStepDone(item.id, !item.checked) }
                .semantics { contentDescription = (if (item.checked) "Untick " else "Tick ") + item.text },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TickRing(item.checked, Modifier.size(22.dp).clip(CircleShape))
            Spacer(Modifier.width(MekaSpace.m))
            Text(
                item.text, style = MekaType.body,
                color = if (item.checked) Meka.colors.textTertiary else Meka.colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            if (canDrag) {
                // The handle: drag up or down to reorder within the group.
                val muted = Meka.colors.textTertiary
                Canvas(
                    Modifier.size(48.dp)
                        .semantics { contentDescription = "Reorder ${item.text}" }
                        .pointerInput(item.id) {
                            detectVerticalDragGestures(
                                onDragStart = { dragStart() },
                                onDragEnd = { dragEnd() },
                                onDragCancel = { dragEnd() },
                                onVerticalDrag = { change, d -> change.consume(); dragBy(d) },
                            )
                        },
                ) {
                    val w = 16.dp.toPx(); val x0 = (size.width - w) / 2; val gap = 5.dp.toPx()
                    for (k in -1..1) {
                        val y = size.height / 2 + k * gap
                        drawLine(muted, Offset(x0, y), Offset(x0 + w, y), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
                    }
                }
            }
        }
    }
}
