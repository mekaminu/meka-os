package os.meka.android.today

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
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
import os.meka.core.domain.Task
import os.meka.core.domain.TextAutosave
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Checklist (Checklists in tasks, slice 1; "Routine steps" on a repeating task, whose occurrences bring them back
 * unticked). Still to tick first, then "Ticked". Typing or pasting several lines (or commas) adds each as an item.
 * Motion (catalogue "Checklist"): a tick draws its check and pops ([TickRing]) with a light haptic, then the row moves
 * under Ticked a beat later and fades into its new place while the list's height follows on the expand spring; swipe
 * left to remove (the backdrop washes in, a tick haptic arms it), the undo bar rises. Reduced motion: at once.
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
        if (shown.map { it.id } != target.map { it.id } && !reduced && shown.size == target.size) delay(MekaChoreography.checkDrawMs.toLong())
        shown = target
    }
    // Ticks follow the live data even while the order waits.
    val live = target.associateBy { it.id }
    val rows = shown.mapNotNull { live[it.id] }
    // "Ticked" heads the run of ticked rows at the end (a row just ticked waits a beat above it).
    var tail = rows.size
    while (tail > 0 && rows[tail - 1].checked) tail--
    val firstTicked = tail.takeIf { it < rows.size }

    Row(Modifier.fillMaxWidth().padding(top = MekaSpace.l), verticalAlignment = Alignment.CenterVertically) {
        SectionLabel(view.label)
        Spacer(Modifier.weight(1f))
        view.line?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
    }
    Column(Modifier.fillMaxWidth().animateContentSize(MekaMotion.expand(reduced))) {
        rows.forEachIndexed { i, item ->
            key(item.id) {
                if (i == firstTicked) {
                    Row(Modifier.fillMaxWidth().padding(top = MekaSpace.s), verticalAlignment = Alignment.CenterVertically) {
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
                ChecklistRow(item, actions)
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
                .background(Meka.colors.surfaceRaised).padding(horizontal = MekaSpace.m, vertical = MekaSpace.s),
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
private fun ChecklistRow(item: ChecklistItem, actions: TodayActions) {
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
    BoxWithConstraints(
        Modifier.fillMaxWidth().semantics {
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
                .background(Meka.colors.background)
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
        }
    }
}
