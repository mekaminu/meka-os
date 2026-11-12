package os.meka.android.goals

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import os.meka.android.ask.MoneyChip
import os.meka.android.ask.MoneyDetailSheet
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.minTouch
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.lists.Field
import os.meka.android.work.TimeStepper
import os.meka.core.domain.GoalRules
import os.meka.core.domain.HabitBoardGroup
import os.meka.core.domain.HabitBoardRow
import os.meka.core.domain.HabitBoardRules
import os.meka.core.domain.HabitHistory
import os.meka.core.domain.HabitMark
import os.meka.core.facade.MekaCore

const val HABITS_LIST_TAG = "habits-list"

/** + Add, or + New list (the add sheet with a list of his own to name). */
private data class HabitAdd(val newList: Boolean, val list: String)

/**
 * The Habits screen (approved 2026-11-11 22:19, `docs/design/habits/Habits.dc.html`; slice 1, the Fold): seven days
 * ending today (tap one to mark an earlier day), the day's "3 of 8 done" with a bar and the best streak, list chips
 * (All · Morning · All day · Night time · his own · + New list), the habits boxed by list, each Do (plus) or Avoid
 * (barred circle) with its streak and the strip's seven dots, and one 44 dp circle cycling done (green tick) → missed
 * (red cross) → clear. A habit's tap unfolds Do or Avoid, its list and Remove. + Add opens a sheet: name, Do or Avoid,
 * list, days a week. Built from [HabitBoardRules] (core), so the Mac draws the same.
 *
 * Motion: the pane springs up from Goals; the parts stagger in; the day pill blends to brass; the bar grows on the
 * replan spring; the circle pops (spring) as its tick or cross cross-fades in, light haptic for done, tick for missed
 * and clear; a row's settings unfold in place; sheets rise as Money's. Taps show at once (drawn over the core's view
 * until the call returns). Reduced motion: cross-fades, no pop.
 */
@Composable
internal fun HabitsPage(core: MekaCore, onClose: () -> Unit, backLabel: String = "‹ Goals and habits") {
    val goals by core.goalsView.collectAsState()
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    var day by rememberSaveable { mutableIntStateOf(HabitBoardRules.DAYS - 1) }
    var list by rememberSaveable { mutableStateOf(HabitBoardRules.ALL) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf<HabitAdd?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Map<String, HabitMark>>(emptyMap()) }
    LaunchedEffect(toast) { if (toast != null) { delay(2_200); toast = null } }
    BackHandler(enabled = adding == null) { onClose() }
    val shownView = remember(goals, pending) { HabitBoardRules.shown(goals, pending) }
    val b = remember(shownView, day, list) { core.habitBoard(shownView, day, list) }
    val mark: (HabitBoardRow) -> Unit = { r ->
        val next = r.mark.next()
        val key = HabitBoardRules.key(r.id, b.selectedDay)
        if (next == HabitMark.DONE) haptics.light() else haptics.tick()
        toast = HabitBoardRules.markedLine(next)
        pending = pending + (key to next)
        scope.launch {
            runCatching { core.setHabitMark(r.id, b.selectedDay, next) }
            if (pending[key] == next) pending = pending - key
        }
    }

    Box(Modifier.fillMaxSize().background(Meka.colors.background), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            Modifier.widthIn(max = 600.dp).fillMaxHeight().testTag(HABITS_LIST_TAG),
            contentPadding = PaddingValues(horizontal = MekaSpace.gutter, vertical = MekaSpace.l),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.m),
        ) {
            item(key = "back") {
                Text(backLabel, style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { haptics.tick(); onClose() }.minTouch())
            }
            item(key = "title") {
                Row(Modifier.fillMaxWidth().appear(rememberAppearance(0)), verticalAlignment = Alignment.CenterVertically) {
                    Text("Habits", style = MekaType.greeting, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f).semantics { heading() })
                    Text("+ Add", style = MekaType.metaStrong, color = Meka.colors.onAccent,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                            .clickable(role = Role.Button) { haptics.light(); adding = HabitAdd(false, defaultList(list)) }.minTouch()
                            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs).testTag("habits-add"))
                }
            }
            item(key = "days") {
                Row(Modifier.fillMaxWidth().appear(rememberAppearance(1)), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    b.days.forEachIndexed { i, d ->
                        val fill by animateColorAsState(if (d.selected) Meka.colors.accent else Meka.colors.surface, MekaMotion.appear(reduced), label = "habit-day-$i")
                        val ink = if (d.selected) Meka.colors.onAccent else Meka.colors.textPrimary
                        val share by animateFloatAsState(d.pct / 100f, MekaMotion.replan(reduced), label = "habit-day-bar-$i")
                        Column(
                            Modifier.weight(1f).height(62.dp).clip(RoundedCornerShape(MekaRadius.s)).background(fill)
                                .clickable(role = Role.Button) { haptics.tick(); day = i; open = null }
                                .semantics(mergeDescendants = true) { contentDescription = d.label; selected = d.selected }
                                .testTag("habits-day-$i"),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                        ) {
                            Text(d.weekday, style = MekaType.micro, color = ink)
                            Text("${d.dayOfMonth}", style = MekaType.metaStrong, color = ink)
                            Box(Modifier.padding(top = MekaSpace.xxs).width(22.dp).height(3.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(ink.copy(alpha = 0.2f))) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(share).background(if (d.selected) ink else Meka.colors.accent))
                            }
                        }
                    }
                }
            }
            item(key = "summary") {
                val share by animateFloatAsState(b.pct / 100f, MekaMotion.replan(reduced), label = "habits-bar")
                Column(
                    Modifier.fillMaxWidth().appear(rememberAppearance(2)).clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
                        .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs).testTag("habits-summary"),
                    verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Crossfade(b.doneLine, animationSpec = MekaMotion.appear(reduced), label = "habits-done", modifier = Modifier.weight(1f)) { l ->
                            Text(l, style = MekaType.metaStrong, color = Meka.colors.textPrimary)
                        }
                        b.streakLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
                    }
                    Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)) {
                        Box(Modifier.fillMaxHeight().fillMaxWidth(share).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent))
                    }
                }
            }
            item(key = "chips") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).appear(rememberAppearance(3)),
                    horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    b.chips.forEach { c ->
                        MoneyChip(c.name, on = c.selected, tag = "habits-chip-${c.name}") { haptics.tick(); list = c.name; open = null }
                    }
                    MoneyChip("+ New list", on = false, tag = "habits-new-list") { haptics.light(); adding = HabitAdd(true, "") }
                }
            }
            if (b.empty) item(key = "empty") {
                Text(HabitBoardRules.EMPTY_LINE, style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.appear(rememberAppearance(4)))
            }
            items(b.groups, key = { "g-${it.title}" }) { g ->
                GroupCard(
                    g, open, removing, b.lists, Modifier.animateItem().appear(rememberAppearance(4)),
                    onOpen = { id -> haptics.tick(); removing = null; open = if (open == id) null else id },
                    onMark = mark,
                    onAvoid = { r, avoid -> haptics.tick(); scope.launch { runCatching { core.setHabitAvoid(r.id, avoid) } } },
                    onList = { r, l -> haptics.tick(); scope.launch { runCatching { core.setHabitList(r.id, l) } } },
                    onRemind = { r, minute ->
                        haptics.tick()
                        toast = HabitBoardRules.reminderLine(minute)
                        scope.launch { runCatching { core.setHabitReminder(r.id, minute) } }
                    },
                    onRemove = { r ->
                        haptics.tick()
                        if (removing != r.id) removing = r.id else {
                            removing = null; open = null
                            scope.launch { runCatching { core.deleteHabit(r.id) } }
                        }
                    },
                )
            }
            if (!b.empty) item(key = "hint") {
                Text(HabitBoardRules.HINT + " · tap a habit to change it", style = MekaType.micro, color = Meka.colors.textTertiary, modifier = Modifier.animateItem())
            }
        }

        // The toast: "Done · nice", "Marked missed".
        AnimatedVisibility(toast != null, enter = unfold(), exit = fold(), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = MekaSpace.l)) {
            Text(toast.orEmpty(), style = MekaType.caption, color = Meka.colors.textPrimary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.s)).background(Meka.colors.surfaceRaised)
                    .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs).testTag("habits-toast"))
        }

        MoneyDetailSheet(adding, title = { if (it.newList) "New list" else "New habit" }, tag = "habits-add-sheet", done = "Close",
            onClose = { adding = null }) { a ->
            var name by rememberSaveable(a) { mutableStateOf("") }
            var avoid by rememberSaveable(a) { mutableStateOf(false) }
            var into by rememberSaveable(a) { mutableStateOf(a.list) }
            var own by rememberSaveable(a) { mutableStateOf("") }
            var perWeek by rememberSaveable(a) { mutableIntStateOf(7) }
            var remind by rememberSaveable(a) { mutableStateOf<Int?>(null) }
            var problem by remember(a) { mutableStateOf<String?>(null) }
            if (a.newList) Field("Name the list, like Weekend or Gym days", value = own, onValue = { own = it }, description = "List name", keep = true) { }
            Field(if (avoid) "Like No biscuits" else "Like Drink water", value = name, onValue = { name = it }, description = "Habit name", keep = true) { }
            Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                MoneyChip("Do", on = !avoid, tag = "habits-add-do") { haptics.tick(); avoid = false }
                MoneyChip("Avoid", on = avoid, tag = "habits-add-avoid") { haptics.tick(); avoid = true }
            }
            if (!a.newList) {
                Text("List", style = MekaType.caption, color = Meka.colors.textSecondary)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    b.lists.forEach { l -> MoneyChip(l, on = l == into, tag = "habits-add-list-$l") { haptics.tick(); into = l } }
                }
            }
            Text("Days", style = MekaType.caption, color = Meka.colors.textSecondary)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                GoalRules.TARGET_CHOICES.forEach { c -> MoneyChip(c.label, on = c.perWeek == perWeek, tag = "habits-add-days-${c.perWeek}") { haptics.tick(); perWeek = c.perWeek } }
            }
            Text("Reminder", style = MekaType.caption, color = Meka.colors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                MoneyChip("Off", on = remind == null, tag = "habits-add-remind-off") { haptics.tick(); remind = null }
                MoneyChip("Every day", on = remind != null, tag = "habits-add-remind-on") {
                    haptics.tick()
                    if (remind == null) remind = HabitBoardRules.defaultReminder(if (a.newList) own else into)
                }
            }
            remind?.let { m -> TimeStepper("Remind me at", m, Modifier.testTag("habits-add-remind-time")) { remind = it } }
            Text("Add habit", style = MekaType.metaStrong, color = Meka.colors.onAccent,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent)
                    .clickable(role = Role.Button) {
                        val target = if (a.newList) HabitBoardRules.cleanList(own) else into
                        when {
                            target == null -> { haptics.reject(); problem = "Name the list" }
                            name.isBlank() -> { haptics.reject(); problem = "Name the habit" }
                            else -> scope.launch {
                                val made = runCatching { core.addHabitToList(name, avoid, target, perWeek, remind) }
                                if (made.isSuccess) {
                                    haptics.light(); adding = null; list = if (a.newList) target else list
                                    toast = "Added ${name.trim()}"
                                } else { haptics.reject(); problem = made.exceptionOrNull()?.message ?: "Couldn't add that" }
                            }
                        }
                    }.minTouch().padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs).testTag("habits-add-save"))
            problem?.let { Text(it, style = MekaType.caption, color = Meka.colors.accent) }
        }
    }
}

/** The list a new habit goes in: the chip he's on, else All day. */
private fun defaultList(list: String): String = if (list == HabitBoardRules.ALL) HabitBoardRules.ALL_DAY else list

@Composable
private fun GroupCard(
    g: HabitBoardGroup, open: String?, removing: String?, lists: List<String>, modifier: Modifier,
    onOpen: (String) -> Unit, onMark: (HabitBoardRow) -> Unit, onAvoid: (HabitBoardRow, Boolean) -> Unit,
    onList: (HabitBoardRow, String) -> Unit, onRemind: (HabitBoardRow, Int?) -> Unit, onRemove: (HabitBoardRow) -> Unit,
) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(Meka.colors.surface)
            .padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs).testTag("habits-group-${g.title}"),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs), verticalAlignment = Alignment.CenterVertically) {
            Text(g.title, style = MekaType.metaStrong, color = Meka.colors.textPrimary, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.width(MekaSpace.xs))
            if (g.count.isNotEmpty()) Text(g.count, style = MekaType.micro, color = Meka.colors.textSecondary,
                modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
                    .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xxs))
            Spacer(Modifier.weight(1f))
            if (g.whenLine.isNotEmpty()) Text(g.whenLine, style = MekaType.caption, color = Meka.colors.textSecondary)
        }
        g.rows.forEachIndexed { i, r ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Meka.colors.hairline))
            HabitRow(r, onOpen = { onOpen(r.id) }, onMark = { onMark(r) })
            AnimatedVisibility(open == r.id, enter = unfold(), exit = fold()) {
                Column(Modifier.padding(bottom = MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                    HistoryGrid(r.history, r.avoid, Modifier.testTag("habits-row-history-${r.id}"))
                    Text("Reminder", style = MekaType.caption, color = Meka.colors.textSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                        MoneyChip("Off", on = r.remindAt == null, tag = "habits-row-remind-off-${r.id}") { if (r.remindAt != null) onRemind(r, null) }
                        MoneyChip("Every day", on = r.remindAt != null, tag = "habits-row-remind-on-${r.id}") {
                            if (r.remindAt == null) onRemind(r, HabitBoardRules.defaultReminder(r.list))
                        }
                    }
                    r.remindAt?.let { m -> TimeStepper("Remind me at", m, Modifier.testTag("habits-row-remind-time-${r.id}")) { onRemind(r, it) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                        MoneyChip("Do", on = !r.avoid, tag = "habits-row-do-${r.id}") { onAvoid(r, false) }
                        MoneyChip("Avoid", on = r.avoid, tag = "habits-row-avoid-${r.id}") { onAvoid(r, true) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                        lists.forEach { l -> MoneyChip(l, on = l == r.list, tag = "habits-row-list-${r.id}-$l") { if (l != r.list) onList(r, l) } }
                    }
                    val asking = removing == r.id
                    Text(if (asking) "Tap again to remove ${r.name} and its days" else "Remove habit",
                        style = MekaType.itemMeta, color = if (asking) Meka.colors.accent else Meka.colors.textSecondary,
                        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.s)).clickable(role = Role.Button) { onRemove(r) }.minTouch()
                            .testTag("habits-row-remove-${r.id}"))
                }
            }
        }
    }
}

/**
 * Slice 3: a habit's four weeks, Monday to Sunday under M T W T F S S: a green square done (kept off, for Avoid), red
 * missed, grey not marked, faint before it was added or still to come; today outlined. Then "Done 18 of the last 28
 * days · 2 missed" and "Best run 12 days".
 */
@Composable
private fun HistoryGrid(h: HabitHistory, avoid: Boolean, modifier: Modifier) {
    if (h.weeks.isEmpty()) return
    val green = Meka.colors.success
    val red = Meka.colors.critical
    Column(modifier.fillMaxWidth().padding(vertical = MekaSpace.xxs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(h.doneLine, style = MekaType.caption, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
            h.bestLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.textSecondary) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
            HabitBoardRules.HISTORY_HEADS.forEach { d ->
                Text(d, style = MekaType.micro, color = Meka.colors.textTertiary, modifier = Modifier.width(22.dp))
            }
        }
        h.weeks.forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
                week.forEach { c ->
                    val fill = when (c.mark) {
                        HabitMark.DONE -> green.copy(alpha = 0.7f)
                        HabitMark.MISSED -> red.copy(alpha = 0.7f)
                        HabitMark.CLEAR -> Meka.colors.surfaceRaised
                        null -> Meka.colors.surfaceRaised.copy(alpha = 0.35f)
                    }
                    Box(
                        Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(fill)
                            .let { if (c.isToday) it.border(1.dp, Meka.colors.accent, RoundedCornerShape(5.dp)) else it }
                            .semantics { contentDescription = c.label },
                    )
                }
            }
        }
    }
}

@Composable
private fun HabitRow(r: HabitBoardRow, onOpen: () -> Unit, onMark: () -> Unit) {
    val reduced = Meka.reducedMotion
    val green = Meka.colors.success
    val red = Meka.colors.critical
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
        KindIcon(r.avoid)
        Spacer(Modifier.width(MekaSpace.m))
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(MekaRadius.s)).clickable(role = Role.Button, onClick = onOpen)
                .semantics(mergeDescendants = true) { contentDescription = "${r.name}, ${r.meta}" }
                .padding(vertical = MekaSpace.xs).testTag("habits-row-${r.id}"),
            verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs),
        ) {
            Text(r.name, style = MekaType.taskTitle, color = Meka.colors.textPrimary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.meta, style = MekaType.micro, color = Meka.colors.textSecondary)
                Spacer(Modifier.width(MekaSpace.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { // rhythm: ok, the mockup's 3 px gap between the week's dots
                    r.week.forEachIndexed { i, m ->
                        val c = when (m) { HabitMark.DONE -> green; HabitMark.MISSED -> red; HabitMark.CLEAR -> Meka.colors.surfaceRaised }
                        Box(
                            Modifier.size(6.dp).let { if (i == r.selectedIndex) it.border(1.dp, Meka.colors.textTertiary, CircleShape) else it }
                                .clip(CircleShape).background(c),
                        )
                    }
                }
            }
        }
        MarkCircle(r.mark, r.markLabel, reduced, onMark, Modifier.testTag("habits-mark-${r.id}"))
    }
}

/** Do: a plus on green; Avoid: a barred circle on red. 36 dp, rounded. */
@Composable
private fun KindIcon(avoid: Boolean) {
    val ink = if (avoid) Meka.colors.critical else Meka.colors.success
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(MekaRadius.s)).background(ink.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(16.dp)) {
            val w = 2.dp.toPx()
            val s = size.minDimension
            if (avoid) {
                drawCircle(ink, radius = s * 0.4f, style = Stroke(w))
                drawLine(ink, Offset(s * 0.22f, s * 0.22f), Offset(s * 0.78f, s * 0.78f), w, StrokeCap.Round)
            } else {
                drawLine(ink, Offset(s / 2, s * 0.15f), Offset(s / 2, s * 0.85f), w, StrokeCap.Round)
                drawLine(ink, Offset(s * 0.15f, s / 2), Offset(s * 0.85f, s / 2), w, StrokeCap.Round)
            }
        }
    }
}

/** The one 44 dp circle: a brass outline when clear, a green tick when done, a red cross when missed. */
@Composable
private fun MarkCircle(mark: HabitMark, label: String, reduced: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val green = Meka.colors.success
    val red = Meka.colors.critical
    val pop = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(mark) }
    LaunchedEffect(mark) {
        if (mark != seen && mark != HabitMark.CLEAR && !reduced) {
            pop.snapTo(0.7f); pop.animateTo(1f, MekaMotion.complete(false))
        }
        seen = mark
    }
    val fill by animateColorAsState(
        when (mark) { HabitMark.DONE -> green.copy(alpha = 0.2f); HabitMark.MISSED -> red.copy(alpha = 0.2f); HabitMark.CLEAR -> Color.Transparent },
        MekaMotion.appear(reduced), label = "habit-mark-fill",
    )
    Box(
        modifier.minTouch().clip(CircleShape).clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(44.dp).graphicsLayer { scaleX = pop.value; scaleY = pop.value }.clip(CircleShape).background(fill)
                .let { if (mark == HabitMark.CLEAR) it.border(1.5.dp, Meka.colors.accent, CircleShape) else it },
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(mark, animationSpec = MekaMotion.appear(reduced), label = "habit-mark") { m ->
                Canvas(Modifier.size(18.dp)) {
                    val w = 2.6.dp.toPx()
                    val s = size.minDimension
                    when (m) {
                        HabitMark.DONE -> {
                            drawLine(green, Offset(s * 0.2f, s * 0.52f), Offset(s * 0.42f, s * 0.72f), w, StrokeCap.Round)
                            drawLine(green, Offset(s * 0.42f, s * 0.72f), Offset(s * 0.8f, s * 0.3f), w, StrokeCap.Round)
                        }
                        HabitMark.MISSED -> {
                            drawLine(red, Offset(s * 0.25f, s * 0.25f), Offset(s * 0.75f, s * 0.75f), w, StrokeCap.Round)
                            drawLine(red, Offset(s * 0.75f, s * 0.25f), Offset(s * 0.25f, s * 0.75f), w, StrokeCap.Round)
                        }
                        HabitMark.CLEAR -> Unit
                    }
                }
            }
        }
    }
}
