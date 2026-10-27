package os.meka.android.today

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.LocalSharedScope
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaPane
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.InstantTaskRules
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.ShutdownRules
import os.meka.core.domain.TaskMove
import os.meka.core.domain.TomorrowPeek
import os.meka.core.domain.TomorrowRow
import os.meka.core.facade.MekaCore

/**
 * The Tomorrow peek on Today (Meka 2026-10-26: "I asked the AI to add a task for tomorrow, it did, but on this screen how
 * do I see tomorrow's stuff?"): a slim card from 17:00 (or once the day is shut down), "Tomorrow · Tue 27 Oct · 2 tasks ·
 * first 07:40 Gym ›". Tapping opens [TomorrowPane]. The line cross-fades as tomorrow changes (only ever a fade).
 */
@Composable
internal fun TomorrowPeekCard(peek: TomorrowPeek, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val reduced = Meka.reducedMotion
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surfaceRaised)
            .clickable(role = Role.Button) { onOpen() }.minTouch()
            .padding(horizontal = MekaSpace.l, vertical = MekaSpace.xs)
            .semantics { contentDescription = peek.spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedContent(
            targetState = peek.line,
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
            label = "tomorrow-peek",
            modifier = Modifier.weight(1f),
        ) { line -> Text(LoneWordGlue.kept(line), style = MekaType.itemMeta, color = Meka.colors.textSecondary, maxLines = 2) }
        Text("›", style = MekaType.itemMeta, color = Meka.colors.accent, modifier = Modifier.padding(start = MekaSpace.xs))
    }
}

/**
 * The Tomorrow sheet, from the peek or the date under the greeting (any time of day): tomorrow's work line, train,
 * weather and school over its events and tasks in time order. A task ticks off (Today's ring and haptic), or moves to
 * **Today** or the **Day after** (its time kept; today only while it's still ahead). Synced with the Mac.
 *
 * Motion: the pane springs up (MekaPane); the header and rows stagger in; a ticked or moved row leaves in the same
 * frame (taps feel instant) and the rest glide into place; the line saying what moved fades in. Reduced motion:
 * cross-fades only.
 */
@Composable
fun TomorrowPane(core: MekaCore, onClose: () -> Unit, closeLabel: String = "‹ Today") {
    val v by core.shutdownView.collectAsState()
    val conflicts by core.conflicts.collectAsState()
    // Tomorrow on Today, slice 3: the task open for editing (its full detail over the sheet), while it's still tomorrow's.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val taps by TaskTaps.taps.collectAsState()
    val rows = remember(v, taps) { InstantTaskRules.tomorrow(v.tomorrow.rows, taps) }
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    var said by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    val onFail: (String) -> Unit = { failed = it }
    val tick: (String) -> Unit = { id -> failed = null; said = null; TaskTaps.move(scope, id, TaskMove.DONE, onFail) { core.complete(id) } }
    fun move(id: String, toToday: Boolean) {
        haptics.tick(); failed = null
        TaskTaps.move(scope, id, if (toToday) TaskMove.TODAY else TaskMove.DAY_AFTER, onFail) { said = core.moveTomorrowTask(id, toToday) }
    }

    Box(Modifier.fillMaxSize()) {
        // A swipe right goes back to Today, like the swipe left that brought it (Meka 2026-10-26).
        LazyColumn(
            Modifier.fillMaxSize().swipePages(toTomorrow = false) { haptics.tick(); onClose() },
            contentPadding = PaddingValues(horizontal = MekaSpace.gutter, vertical = MekaSpace.l),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
        ) {
            item(key = "close") {
                Text(closeLabel, style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { onClose() }.minTouch())
            }
            item(key = "title") {
                Text("Tomorrow", style = MekaType.greeting, color = Meka.colors.textPrimary,
                    modifier = Modifier.padding(bottom = MekaSpace.xs).appear(rememberAppearance(0)))
            }
            item(key = "header") { TomorrowHeader(v, Modifier.appear(rememberAppearance(1))) }
            item(key = "said") {
                AnimatedContent(
                    targetState = failed ?: said,
                    transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                    label = "tomorrow-said",
                ) { line ->
                    if (line != null) Text(LoneWordGlue.kept(line), style = MekaType.itemMeta,
                        color = if (line == failed) Meka.colors.critical else Meka.colors.textSecondary)
                }
            }
            if (rows.isEmpty() && v.tomorrow.bills == null && v.tomorrow.study == null) {
                item(key = "empty") {
                    Text("Nothing planned for tomorrow yet.", style = MekaType.itemMeta, color = Meka.colors.textTertiary,
                        modifier = Modifier.animateItem().appear(rememberAppearance(2)))
                }
            }
            items(rows, key = { "r-" + it.id }) { r ->
                val id = ShutdownRules.taskIdOf(r)
                if (id == null) TomorrowLine(r, Modifier.animateItem().appear(rememberAppearance(2)))
                else TomorrowTaskRow(r, id, tick, onToday = { move(id, true) }, onDayAfter = { move(id, false) },
                    onEdit = { haptics.light(); editingId = id },
                    modifier = Modifier.animateItem().appear(rememberAppearance(2)))
            }
        }

        // The task's detail springs up over the sheet: title, when, reminder, repeat, notes, checklist, as on Today. Moved
        // to another day, done or deleted, it's no longer tomorrow's and the detail closes (the sheet's row has gone too).
        val editing = ShutdownRules.taskOf(v.tomorrow, editingId)
        var shown by remember { mutableStateOf(editing) }
        if (editing != null) shown = editing
        val actions = todayActions(core, scope, { editingId }, { editingId = it })
        BackHandler(enabled = editing != null) { editingId = null }
        // Titles don't travel from here: the same task isn't a row on Today underneath.
        CompositionLocalProvider(LocalSharedScope provides null) {
            MekaPane(visible = editing != null) {
                shown?.let { t ->
                    DetailPane(t, conflicts.filter { it.taskId == t.id }, actions, Modifier.fillMaxSize(), onClose = { editingId = null })
                }
            }
        }
    }
}

/**
 * Swipe across Today to page to Tomorrow ([toTomorrow], a left swipe) and back on the Tomorrow sheet (a right swipe),
 * by [ShutdownRules.swipePages]: a deliberate drag or a quick flick, never a sideways nudge while scrolling. Rows that
 * swipe themselves (an event's hide/prep) take their own drags first.
 */
internal fun Modifier.swipePages(toTomorrow: Boolean, onPage: () -> Unit): Modifier = composed {
    val page by rememberUpdatedState(onPage)
    pointerInput(toTomorrow) {
        val tracker = VelocityTracker()
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { tracker.resetTracking(); total = 0f },
            onDragEnd = {
                val velocity = tracker.calculateVelocity().x / density
                if (ShutdownRules.swipePages(total / density, velocity, size.width / density, toTomorrow)) page()
                total = 0f
            },
            onDragCancel = { total = 0f },
        ) { change, dx -> tracker.addPosition(change.uptimeMillis, change.position); total += dx }
    }
}

/** One of tomorrow's tasks: the tick ring, its time and title, and Today · Day after. */
@Composable
private fun TomorrowTaskRow(
    r: TomorrowRow, id: String, tick: (String) -> Unit, onToday: () -> Unit, onDayAfter: () -> Unit, onEdit: () -> Unit,
    modifier: Modifier,
) {
    Row(modifier.fillMaxWidth().padding(vertical = MekaSpace.xs), verticalAlignment = Alignment.Top) {
        CompleteButton(id, r.title, tick)
        Spacer(Modifier.width(MekaSpace.m))
        Column(Modifier.weight(1f)) {
            // Tap the title (or its time) to edit the task (Tomorrow on Today, slice 3).
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Edit") { onEdit() }.minTouch()) {
                // Task rows use the regular body weight (Fold review 2026-10-08).
                Text(r.title, style = MekaType.body, color = Meka.colors.textPrimary)
                val meta = listOfNotNull(r.time, r.detail).joinToString(" · ")
                if (meta.isNotEmpty()) Text(LoneWordGlue.kept(meta), style = MekaType.itemMeta, color = Meka.colors.textSecondary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                TomorrowChip("Today", onToday)
                TomorrowChip("Day after", onDayAfter)
                TomorrowChip("Edit", onEdit)
            }
        }
    }
}

@Composable
private fun TomorrowChip(label: String, onClick: () -> Unit) {
    Text(
        label, style = MekaType.caption, color = Meka.colors.textPrimary, maxLines = 1,
        // The chip answers a 48 dp touch; the pill itself stays its drawn size (quality gate slice 47).
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).clickable(role = Role.Button) { onClick() }.minTouch()
            .clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}
