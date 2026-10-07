package os.meka.android.calendar

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.core.domain.CalendarEvent
import os.meka.core.domain.CalendarRules
import os.meka.core.domain.EventDetails
import os.meka.core.domain.ReminderRules
import os.meka.core.facade.MekaCore
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Calendar actions (build plan, M1): swipe right on an event for a prep task, swipe left to hide it from my day,
 * long-press for Remind me / Leave by (and the same two actions, and Open in Google Calendar / Outlook). MEKA-only: the real calendars are never changed. An
 * undo bar rises after each.
 */

/**
 * The first-time swipe hint (calendar actions, slice 3): the first swipeable event row MEKA ever shows nudges right
 * and left once, so the two actions behind it peek out. Once per install; one row only, even with Today and the
 * Calendar tab both on screen (the unfolded Fold).
 */
object SwipeHint {
    private const val PREFS = "meka.hints"
    private const val KEY = "eventSwipe"
    private var claimed = false

    /** True exactly once: the caller shows the hint. Marked as shown at once, so a crash mid-hint doesn't repeat it. */
    @Synchronized
    fun claim(context: Context): Boolean {
        if (claimed) return false
        claimed = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY, false)) return false
        prefs.edit().putBoolean(KEY, true).apply()
        return true
    }
}

/** What the undo bar is offering: "Hidden from your day" with Undo. */
data class UndoOffer(val message: String, val key: Long, val undo: (suspend () -> Unit)?)

/** The undo bar's state for one screen. */
@Stable
class EventUndo {
    var offer by mutableStateOf<UndoOffer?>(null)
        internal set
    private var n = 0L

    fun show(message: String, undo: (suspend () -> Unit)?) {
        offer = UndoOffer(message, ++n, undo)
    }

    fun clear() { offer = null }
}

@Composable
fun rememberEventUndo() = remember { EventUndo() }

/** The actions, wired to the core with the undo bar. */
class EventActionHandlers(
    val prep: (CalendarEvent) -> Unit,
    val hide: (CalendarEvent) -> Unit,
    /** Remind me this many minutes before; 0 turns it off. */
    val remind: (CalendarEvent, Int) -> Unit = { _, _ -> },
    /** Leave by: minutes to get there; 0 turns it off. */
    val leaveBy: (CalendarEvent, Int) -> Unit = { _, _ -> },
    /** What's set now: (reminder minutes, travel minutes), 0 for none. */
    val current: (CalendarEvent) -> Pair<Int, Int> = { 0 to 0 },
    /** "Make it a task" on an all-day entry (Today clarity; from any entry's long-press since the all-day polish). */
    val makeTask: (CalendarEvent) -> Unit = {},
    /** "Hide <calendar> from Today": (calendar key, its name). */
    val hideCalendar: (String, String) -> Unit = { _, _ -> },
)

fun eventActionHandlers(core: MekaCore, scope: CoroutineScope, undo: EventUndo) = EventActionHandlers(
    prep = { e ->
        scope.launch {
            val existing = core.eventMarks.value.prepTasks[e.id]?.takeIf { !it.isDone }
            val id = runCatching { core.addPrepTask(e) }.getOrNull() ?: return@launch
            if (existing == null) undo.show("Prep task added") { core.delete(id) } else undo.show("Already has a prep task", null)
        }
    },
    hide = { e ->
        scope.launch {
            runCatching { core.hideEvent(e.id) }
            undo.show("Hidden from your day") { core.showEvent(e.id) }
        }
    },
    remind = { e, minutes ->
        scope.launch {
            val before = core.eventMarks.value.reminderOf(e.id)
            if (runCatching { core.setEventReminder(e.id, minutes) }.isFailure) return@launch
            undo.show(if (minutes == 0) "Reminder off" else "Reminder ${ReminderRules.choiceLabel(minutes)}") { core.setEventReminder(e.id, before) }
        }
    },
    leaveBy = { e, minutes ->
        scope.launch {
            val before = core.eventMarks.value.travelOf(e.id)
            if (runCatching { core.setEventLeaveBy(e.id, minutes) }.isFailure) return@launch
            undo.show(if (minutes == 0) "Leave-by reminder off" else "Leave-by reminder · ${ReminderRules.travelLabel(minutes)}") {
                core.setEventLeaveBy(e.id, before)
            }
        }
    },
    current = { e -> core.eventMarks.value.let { it.reminderOf(e.id) to it.travelOf(e.id) } },
    makeTask = { e ->
        scope.launch {
            if (runCatching { core.makeAllDayTask(e) }.isFailure) return@launch
            undo.show("Made it a task") { core.undoAllDayTask(e.id) }
        }
    },
    hideCalendar = { key, label ->
        scope.launch {
            if (runCatching { core.hideCalendarFromToday(key, label) }.isFailure) return@launch
            undo.show(CalendarRules.hiddenLine(label)) { core.showCalendarOnToday(key) }
        }
    },
)

/**
 * An event row that swipes: right reveals "Prep task" in the accent colour, left reveals "Hide from my day". Past the
 * threshold the label pops, a light haptic marks it, and letting go does it; the row springs back either way. Screen
 * readers get both as custom actions. Reduced motion: no pop, a quick tween back.
 */
@Composable
fun SwipeableEvent(
    event: CalendarEvent?,
    handlers: EventActionHandlers?,
    modifier: Modifier = Modifier,
    /** Tapping the row opens the event; long-pressing opens the actions menu. */
    onOpen: ((CalendarEvent) -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    if (event == null || handlers == null) {
        content(modifier.opensEvent(event, onOpen))
        return
    }
    var menu by remember(event.id) { mutableStateOf(EventMenu.CLOSED) }
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    var dx by remember(event.id) { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    var armed by remember(event.id) { mutableIntStateOf(0) } // -1 hide, 1 prep, 0 neither
    val context = LocalContext.current
    val nudge = with(LocalDensity.current) { 48.dp.toPx() }
    // The swipe hint: half the way to arming, right then left, springing back each time. Reduced motion: no
    // movement, so no hint (screen readers have the custom actions); it still counts as shown.
    LaunchedEffect(event.id) {
        if (!SwipeHint.claim(context.applicationContext) || reduced) return@LaunchedEffect
        delay(900)
        for (to in listOf(nudge, 0f, -nudge, 0f)) {
            if (armed != 0) break
            animate(dx, to, animationSpec = MekaMotion.complete(false)) { v, _ -> dx = v }
            if (to == 0f) delay(120)
        }
    }

    BoxWithConstraints(
        modifier.fillMaxWidth().semantics {
            customActions = listOf(
                CustomAccessibilityAction("Prep task") { handlers.prep(event); true },
                CustomAccessibilityAction("Hide from my day") { handlers.hide(event); true },
                CustomAccessibilityAction("Remind me") { menu = EventMenu.MAIN; true },
            )
        },
    ) {
        EventActionsMenu(event, handlers, menu) { menu = it }
        val max = constraints.maxWidth.toFloat().coerceAtLeast(threshold * 1.5f)
        val x = dx
        val side = if (x > 0) 1 else if (x < 0) -1 else 0
        // What's behind the row: the colour shows as soon as it moves; the label pops at the threshold.
        if (side != 0) {
            val pop by animateFloatAsState(if (armed != 0) 1.15f else 1f, MekaMotion.approve(reduced), label = "swipe-pop")
            Box(
                Modifier.matchParentSize().clip(RoundedCornerShape(MekaRadius.m))
                    .background(if (side > 0) Meka.colors.accent else Meka.colors.surfaceRaised)
                    .padding(horizontal = MekaSpace.l),
                contentAlignment = if (side > 0) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Text(
                    if (side > 0) "Prep task" else "Hide from my day",
                    style = MekaType.itemMeta,
                    color = if (side > 0) Meka.colors.onAccent else Meka.colors.textSecondary,
                    modifier = Modifier.graphicsLayer { scaleX = pop; scaleY = pop },
                )
            }
        }
        content(
            Modifier
                .offset { IntOffset(x.roundToInt(), 0) }
                .background(Meka.colors.background)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        val next = (dx + delta).coerceIn(-max, max)
                        dx = next
                        val nowArmed = if (abs(next) >= threshold) (if (next > 0) 1 else -1) else 0
                        if (nowArmed != armed) {
                            if (nowArmed != 0) haptics.tick()
                            armed = nowArmed
                        }
                    },
                    onDragStopped = {
                        val act = armed
                        armed = 0
                        when (act) {
                            1 -> { haptics.light(); handlers.prep(event) }
                            -1 -> { haptics.light(); handlers.hide(event) }
                        }
                        animate(dx, 0f, animationSpec = MekaMotion.complete(reduced)) { v, _ -> dx = v }
                    },
                )
                .combinedClickable(
                    role = Role.Button,
                    onClick = { onOpen?.invoke(event) },
                    onLongClickLabel = "Event actions",
                    onLongClick = { haptics.tick(); menu = EventMenu.MAIN },
                ),
        )
    }
}

/** Which page of the long-press menu is showing. */
enum class EventMenu { CLOSED, MAIN, REMIND, LEAVE }

/**
 * The long-press menu: Remind me… and Leave by… (when the event has a place) open their choices in place; Prep task
 * and Hide from my day as on the swipe. Only times still ahead are offered; the one that's set has a tick and "Off".
 */
@Composable
private fun EventActionsMenu(event: CalendarEvent, handlers: EventActionHandlers, page: EventMenu, set: (EventMenu) -> Unit) {
    val now = System.currentTimeMillis()
    val (remind, travel) = handlers.current(event)
    val remindChoices = ReminderRules.remindChoices(event, now)
    val travelChoices = ReminderRules.travelChoices(event, now)
    val uriHandler = LocalUriHandler.current
    val openIn = EventDetails.openLink(event)
    @Composable
    fun item(label: String, onClick: () -> Unit) = DropdownMenuItem(
        text = { Text(label, style = MekaType.itemMeta, color = Meka.colors.textPrimary) },
        onClick = onClick,
    )
    DropdownMenu(expanded = page != EventMenu.CLOSED, onDismissRequest = { set(EventMenu.CLOSED) }) {
        when (page) {
            EventMenu.MAIN -> {
                if (remindChoices.isNotEmpty() || remind != 0) {
                    item(if (remind != 0) "Remind me · ${ReminderRules.choiceLabel(remind)} ›" else "Remind me ›") { set(EventMenu.REMIND) }
                }
                if (travelChoices.isNotEmpty() || travel != 0) {
                    item(if (travel != 0) "Leave by · ${ReminderRules.travelLabel(travel)} ›" else "Leave by ›") { set(EventMenu.LEAVE) }
                }
                item("Prep task") { set(EventMenu.CLOSED); handlers.prep(event) }
                item("Hide from my day") { set(EventMenu.CLOSED); handlers.hide(event) }
                // The real event in Google Calendar / Outlook, to change it there (MEKA itself stays read-only).
                openIn?.let { o -> item(o.label) { set(EventMenu.CLOSED); runCatching { uriHandler.openUri(o.url) } } }
            }
            EventMenu.REMIND -> {
                remindChoices.forEach { m ->
                    item((if (m == remind) "✓ " else "") + ReminderRules.choiceLabel(m)) { set(EventMenu.CLOSED); if (m != remind) handlers.remind(event, m) }
                }
                if (remind != 0) item("Off") { set(EventMenu.CLOSED); handlers.remind(event, 0) }
            }
            EventMenu.LEAVE -> {
                travelChoices.forEach { m ->
                    item((if (m == travel) "✓ " else "") + ReminderRules.travelLabel(m)) { set(EventMenu.CLOSED); if (m != travel) handlers.leaveBy(event, m) }
                }
                if (travel != 0) item("Off") { set(EventMenu.CLOSED); handlers.leaveBy(event, 0) }
            }
            EventMenu.CLOSED -> Unit
        }
    }
}

/**
 * The undo bar: rises from the bottom with the message and Undo; goes after 5 seconds or on Undo (light haptic).
 * Reduced motion: fades.
 */
@Composable
fun EventUndoBar(undo: EventUndo, modifier: Modifier = Modifier) {
    val reduced = Meka.reducedMotion
    val haptics = rememberMekaHaptics()
    val scope = rememberCoroutineScope()
    val offer = undo.offer
    var last by remember { mutableStateOf<UndoOffer?>(null) }
    if (offer != null) last = offer
    LaunchedEffect(offer?.key) {
        if (offer != null) {
            delay(5_000)
            if (undo.offer?.key == offer.key) undo.clear()
        }
    }
    AnimatedVisibility(
        visible = offer != null,
        enter = if (reduced) fadeIn() else slideInVertically(MekaMotion.appear(reduced)) { it } + fadeIn(),
        exit = if (reduced) fadeOut() else slideOutVertically(MekaMotion.appear(reduced)) { it } + fadeOut(),
        modifier = modifier,
    ) {
        val shown = last ?: return@AnimatedVisibility
        Row(
            Modifier.fillMaxWidth().padding(MekaSpace.m).clip(RoundedCornerShape(MekaRadius.l))
                .background(Meka.colors.surfaceRaised).padding(horizontal = MekaSpace.l, vertical = MekaSpace.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(shown.message, style = MekaType.itemMeta, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
            val action = shown.undo
            if (action != null) {
                Text(
                    "Undo", style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) {
                        haptics.light()
                        undo.clear()
                        scope.launch { runCatching { action() } }
                    }.padding(start = MekaSpace.m, top = MekaSpace.xs, bottom = MekaSpace.xs),
                )
            }
        }
    }
}
