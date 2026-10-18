package os.meka.android.goals

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import os.meka.android.MekaApplication
import os.meka.android.shell.SearchNav
import os.meka.android.shell.ShellDestination
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.designsystem.TickRing
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaMotion
import os.meka.android.designsystem.MekaRadius
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaType
import os.meka.android.designsystem.appear
import os.meka.android.designsystem.rememberAppearance
import os.meka.android.designsystem.rememberMekaHaptics
import os.meka.android.today.SectionLabel
import os.meka.android.today.TypingSaves
import os.meka.core.domain.TextAutosave
import os.meka.core.domain.GoalHorizon
import os.meka.core.domain.GoalItem
import os.meka.core.domain.GoalMetricRules
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.drawscope.clipRect
import os.meka.core.domain.GoalRules
import os.meka.core.domain.Goals
import os.meka.core.domain.HabitItem
import os.meka.core.domain.HabitPace
import os.meka.core.domain.HabitTiming
import os.meka.core.domain.MoneyStreaksView
import os.meka.core.domain.ReadingGoalRules
import os.meka.core.domain.ReadingGoalView
import os.meka.core.domain.SessionRules
import os.meka.core.facade.MekaCore
import os.meka.android.designsystem.LocalPlaceTitleKey
import os.meka.android.designsystem.sharedPlace

/**
 * GOALS (build plan M1): fasting ([FastingCard]), habits with this week's pace and streaks, and goals with progress. A habit's circle pops with
 * a spring when ticked and its streak number rolls (light haptic); goal progress bars fill on appear. Tapping a row
 * unfolds its settings in place. Nothing is ticked for you. Reduced motion: colour changes and cross-fades only.
 */
@Composable
fun GoalsRoute(core: MekaCore) {
    val view by core.goalsView.collectAsState()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val haptics = rememberMekaHaptics()
    val act: (suspend () -> Unit) -> Unit = { body -> scope.launch { runCatching { body() } } }
    val toggle: (String) -> Unit = { id -> open = if (open == id) null else id }
    // A search result opened here: unfold its row.
    val app = LocalContext.current.applicationContext as MekaApplication
    val openItem by app.openItem.collectAsState()
    LaunchedEffect(openItem) {
        val item = openItem ?: return@LaunchedEffect
        if (SearchNav.destination(item.target) != ShellDestination.GOALS) return@LaunchedEffect
        open = item.id
        app.openItem.value = null
    }

    LazyColumn(
        Modifier.fillMaxSize().background(Meka.colors.background),
        contentPadding = PaddingValues(horizontal = MekaSpace.gutter, vertical = MekaSpace.xl),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "title") {
            Text("Goals", style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedPlace(LocalPlaceTitleKey.current).appear(rememberAppearance(0)))
        }
        item(key = "pace") {
            val line = view.paceLine
            Text(
                line ?: "Add a habit and Navilife keeps count, and makes room in your plan when one falls behind.",
                style = MekaType.itemMeta,
                color = if (view.behind > 0) Meka.colors.accent else Meka.colors.textSecondary,
                modifier = Modifier.animateItem().padding(bottom = MekaSpace.m).appear(rememberAppearance(1)),
            )
        }
        item(key = "fasting-label") { SectionLabel("Fasting", Modifier.appear(rememberAppearance(2))) }
        item(key = "fasting") { FastingCard(core, Modifier.padding(bottom = MekaSpace.l).appear(rememberAppearance(2))) }
        item(key = "habits-label") { SectionLabel("Habits", Modifier.appear(rememberAppearance(2))) }
        items(view.habits, key = { "h-${it.id}" }) { h ->
            HabitRow(
                h, view.goals, open == h.id, { toggle(h.id) }, Modifier.animateItem(),
                tick = { haptics.light(); act { core.setHabitDone(h.id, !h.doneToday) } },
                edit = { perWeek, timing, minutes ->
                    act {
                        perWeek?.let { core.setHabitTarget(h.id, it) }
                        timing?.let { core.setHabitTiming(h.id, it) }
                        minutes?.let { core.setHabitMinutes(h.id, it) }
                    }
                },
                link = { goalId -> act { core.setHabitGoal(h.id, goalId) } },
                delete = { open = null; act { core.deleteHabit(h.id) } },
                book = { on -> haptics.tick(); act { core.setHabitBooked(h.id, on) } },
                rotate = { i -> haptics.tick(); act { core.setHabitRotation(h.id, i) } },
                appLink = { link, done -> TypingSaves.launch { done(core.setHabitAppLink(h.id, link)) } },
            )
        }
        item(key = "add-habit") {
            AddHabit { title, perWeek, timing -> act { core.addHabit(title, perWeek, timing, GoalRules.DEFAULT_MINUTES, null) } }
        }
        // A habit Meka made by hand named gym/workout/training: book it in place, keeping its ticks (Fold review 13:45, item 2).
        view.bookOffer?.let { h ->
            item(key = "book-${h.id}") {
                Column(Modifier.animateItem().padding(top = MekaSpace.m)) {
                    Action(SessionRules.bookOfferLabel(h.title)) { haptics.light(); act { core.letMekaBook(h.id); open = h.id } }
                    Text(SessionRules.BOOK_OFFER_CAPTION, style = MekaType.caption, color = Meka.colors.textTertiary)
                }
            }
        }
        // The Gym: one tap adds it with its sessions booked around the calendar (offered until a booked or gym-named habit exists).
        if (view.offersAddGym) item(key = "add-gym") {
            Column(Modifier.animateItem().padding(top = MekaSpace.m)) {
                Action("Add Gym") { haptics.light(); act { open = core.addGym() } }
                Text(
                    "Three times a week, evenings, an hour: Navilife books the sessions around your calendar and work, and rebooks a missed one.",
                    style = MekaType.caption, color = Meka.colors.textTertiary,
                )
            }
        }

        // Health Connect, slice 1: workouts tick the habits they match (off by default; this phone only).
        if (view.habits.isNotEmpty()) item(key = "workouts") {
            WorkoutsSection(core, Modifier.animateItem().padding(top = MekaSpace.xl))
        }
        // Health Connect, slice 3: a short night plans a lighter day (off by default; this phone only).
        item(key = "sleep") {
            SleepSection(core, Modifier.animateItem().padding(top = MekaSpace.xl))
        }

        // Money manager (15): the money challenges as streaks beside the habits, no amounts; a tap opens Money (fingerprint).
        view.money?.let { m ->
            item(key = "money-streaks") {
                MoneyStreaksSection(m, Modifier.animateItem().padding(top = MekaSpace.xl)) {
                    haptics.light()
                    app.openMoney.value = true
                    app.openDestination.value = ShellDestination.ASK
                }
            }
        }

        // Reading and learning (slice 4): the reading goal for the year with its pace; a tap opens Reading.
        view.reading?.let { g ->
            item(key = "reading-goal") {
                ReadingGoalSection(g, Modifier.animateItem().padding(top = MekaSpace.xl)) {
                    haptics.light()
                    app.openReading.value = true
                    app.openDestination.value = ShellDestination.ASK
                }
            }
        }

        item(key = "goals-label") { SectionLabel("Goals", Modifier.padding(top = MekaSpace.xl).appear(rememberAppearance(3))) }
        // Goals, properly, item 6: last month looked back on, for the first week of a month.
        view.month?.let { m ->
            item(key = "goals-month") {
                Text(
                    m.line, style = MekaType.caption, color = Meka.colors.textSecondary,
                    modifier = Modifier.animateItem().padding(bottom = MekaSpace.xs).appear(rememberAppearance(3))
                        .semantics { contentDescription = "Last month, ${m.line}" },
                )
            }
        }
        if (view.goals.isEmpty()) item(key = "goals-empty") {
            Text(
                "Say what you're working towards. Link habits and tasks to a goal and its progress counts itself.",
                style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.animateItem().padding(vertical = MekaSpace.xs),
            )
        }
        items(view.goals, key = { "g-${it.id}" }) { g ->
            GoalRow(
                g, open == g.id, { toggle(g.id) }, Modifier.animateItem(),
                step = { d -> act { core.setGoalProgress(g.id, g.progressPct + d) } },
                horizon = { hz -> act { core.setGoalHorizon(g.id, hz) } },
                finish = { haptics.light(); open = null; act { core.finishGoal(g.id) } },
                delete = { open = null; act { core.deleteGoal(g.id) } },
                details = GoalDetails(
                    today = core.todayEpochDay(),
                    parents = GoalRules.parentChoices(view.goals, g.id),
                    why = { w -> act { core.setGoalWhy(g.id, w) } },
                    due = { day -> haptics.tick(); act { core.setGoalDue(g.id, day) } },
                    parent = { p -> haptics.tick(); act { core.setGoalParent(g.id, p) } },
                    addStep = { t -> haptics.light(); act { core.addGoalStep(g.id, t) } },
                    candidates = { q -> core.goalLinkCandidates(g.id, q) },
                    link = { taskId -> haptics.light(); act { core.setTaskGoal(taskId, g.id) } },
                    target = { t -> act { core.editGoal(g.id, null, t ?: "", null) } },
                    addMilestone = { t -> haptics.light(); act { core.addGoalMilestone(g.id, t) } },
                    tickMilestone = { id, done -> haptics.light(); act { core.setGoalMilestoneDone(id, done) } },
                    removeMilestone = { id, then -> haptics.tick(); scope.launch { runCatching { core.removeGoalMilestone(id) }.getOrNull()?.let(then) } },
                    habits = view.habits,
                    number = { t, then -> scope.launch { val ok = runCatching { core.setGoalNumber(g.id, t) }.getOrDefault(false); if (ok) haptics.light() else haptics.tick(); then(ok) } },
                    addUpdate = { t, then ->
                        scope.launch {
                            val r = runCatching { core.addGoalUpdate(g.id, t) }
                            if (r.isSuccess) haptics.light() else haptics.tick()
                            then(r.isSuccess, r.getOrNull())
                        }
                    },
                    removeUpdate = { id -> haptics.tick(); act { core.removeGoalUpdate(id) } },
                    numberHabit = { h -> haptics.tick(); act { core.setGoalNumberHabit(g.id, h) } },
                    breakdown = BreakdownActions(
                        ask = { core.breakDownGoal(g.id) },
                        keep = { ms, ss -> runCatching { core.keepBreakdown(g.id, ms, ss) }.getOrNull() },
                        undo = { kept -> act { core.undoBreakdown(kept) } },
                    ),
                ),
            )
        }
        if (view.finishedGoals > 0) item(key = "finished") {
            Text(
                "${view.finishedGoals} finished", style = MekaType.caption, color = Meka.colors.textTertiary,
                modifier = Modifier.animateItem().padding(vertical = MekaSpace.xs),
            )
        }
        item(key = "add-goal") { AddGoal { title, target, hz -> act { core.addGoal(title, target, hz) } } }
    }
}

/** A habit: tick circle, title, this week's dots, pace and a rolling streak. Tapping the text unfolds its settings. */
@Composable
private fun HabitRow(
    h: HabitItem, goals: List<GoalItem>, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier,
    tick: () -> Unit, edit: (Int?, HabitTiming?, Int?) -> Unit, link: (String?) -> Unit, delete: () -> Unit,
    book: (Boolean) -> Unit, rotate: (Int) -> Unit, appLink: (String, (Boolean) -> Unit) -> Unit,
) {
    val reduced = Meka.reducedMotion
    val bg by animateColorAsState(if (expanded) Meka.colors.surface else Color.Transparent, MekaMotion.appear(reduced), label = "habit-bg")
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xs), verticalAlignment = Alignment.CenterVertically) {
            TickCircle(h.doneToday, h.title, tick)
            Spacer(Modifier.width(MekaSpace.m))
            Column(
                Modifier.weight(1f).clickable(role = Role.Button, onClickLabel = if (expanded) "Hide settings" else "Show settings") { onToggle() },
            ) {
                Text(h.title, style = MekaType.itemTitle, color = if (h.doneToday) Meka.colors.textSecondary else Meka.colors.textPrimary)
                Text(h.meta, style = MekaType.itemMeta, color = if (h.pace == HabitPace.BEHIND) Meka.colors.accent else Meka.colors.textSecondary)
                // A booked habit's week: "Booked Today 17:45 · Thu 17:45".
                h.sessionLine?.let { Text(it, style = MekaType.caption, color = Meka.colors.accent) }
                Row(Modifier.padding(top = MekaSpace.xxs), verticalAlignment = Alignment.CenterVertically) {
                    if (h.weekly) WeekSlots(h.slotsFilled, h.weekTarget, h.week) else WeekDots(h.week)
                    if (h.streak >= 2) {
                        Spacer(Modifier.width(MekaSpace.xs))
                        RollingNumber(h.streak)
                        Text("-${h.streakUnit} streak", style = MekaType.caption, color = Meka.colors.textTertiary)
                    }
                }
            }
        }
        AnimatedVisibility(expanded, enter = unfold(), exit = fold()) {
            Column(
                Modifier.fillMaxWidth().padding(start = MekaSpace.xs, end = MekaSpace.xs, bottom = MekaSpace.m),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.m),
            ) {
                Chips("How often", GoalRules.TARGET_CHOICES.map { it.label to (it.perWeek == h.targetPerWeek) }) { i -> edit(GoalRules.TARGET_CHOICES[i].perWeek, null, null) }
                Chips("When", GoalRules.TIMINGS.map { GoalRules.timingLabel(it) to (it == h.timing) }) { i -> edit(null, GoalRules.TIMINGS[i], null) }
                Chips("How long", GoalRules.MINUTE_CHOICES.map { "$it min" to (it == h.minutes) }) { i -> edit(null, null, GoalRules.MINUTE_CHOICES[i]) }
                Chips("Book my sessions", listOf("On" to h.booked, "Off" to !h.booked)) { i -> book(i == 0) }
                if (h.booked) {
                    Chips("Rotation", SessionRules.ROTATIONS.map { SessionRules.rotationLabel(it) to (it == h.rotation) }) { i -> rotate(i) }
                    AppLinkField(h.appLink, h.appName, appLink)
                    Text(
                        "Booked around your calendar and work, a rest day between when there's room. Missed ones are rebooked.",
                        style = MekaType.caption, color = Meka.colors.textTertiary,
                    )
                }
                if (goals.isNotEmpty()) {
                    val options = listOf<GoalItem?>(null) + goals
                    Chips("Towards", options.map { (it?.title ?: "No goal") to (it?.id == h.goalId) }) { i -> link(options[i]?.id) }
                }
                Action("Delete", critical = true) { delete() }
            }
        }
    }
}

/**
 * Gym: the workout app's link ("hevy.com"), so Today's card can open it. Done saves it; a link that isn't a web address
 * says so; Remove clears it. The line under the field cross-fades as it changes.
 */
@Composable
private fun AppLinkField(current: String?, name: String?, save: (String, (Boolean) -> Unit) -> Unit) {
    val haptics = rememberMekaHaptics()
    val reduced = Meka.reducedMotion
    var text by rememberSaveable(current) { mutableStateOf(current.orEmpty()) }
    var bad by rememberSaveable(current) { mutableStateOf(false) }
    // Typing is never lost (TextAutosave): a changed link saves when the settings close, as if Done had been pressed
    // (one that isn't a web address is refused there as on Done, and the old one stays).
    val typed by rememberUpdatedState(text)
    val saved by rememberUpdatedState(current)
    val saveLink by rememberUpdatedState(save)
    DisposableEffect(Unit) {
        onDispose { TextAutosave.pendingAdd(typed)?.let { if (it != saved) saveLink(it) { } } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Text("Workout app", style = MekaType.caption, color = Meka.colors.textTertiary)
        Field("Link to your workout app · hevy.com (optional)", text, { text = it.take(SessionRules.MAX_LINK); bad = false }) {
            save(text) { ok -> bad = !ok; if (ok) haptics.light() else haptics.tick() }
        }
        val line = when {
            bad -> "That isn't a web address · try hevy.com"
            name != null -> "Today's card opens $name"
            else -> "Today's card can open the app you log workouts in"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = line,
                transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) },
                label = "app-link-line",
                modifier = Modifier.weight(1f),
            ) { l -> Text(l, style = MekaType.caption, color = if (bad) Meka.colors.critical else Meka.colors.textTertiary) }
            if (current != null) Action("Remove") { haptics.tick(); save("") { } }
        }
    }
}

/** The tick: the ring sweeps round, fills and draws the check, and the circle pops ([TickRing]; Off: at once). */
@Composable
private fun TickCircle(done: Boolean, title: String, onClick: () -> Unit) {
    TickRing(
        done,
        Modifier.size(28.dp).clip(CircleShape)
            .semantics { contentDescription = (if (done) "Untick " else "Tick ") + title + " for today" }
            .clickable(role = Role.Checkbox) { onClick() },
    )
}

/** Monday to Sunday: a filled dot where ticked. */
@Composable
private fun WeekDots(week: List<Boolean>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp), // rhythm: ok (the week's dots are one 6 dp mark, not rows)
        modifier = Modifier.semantics { contentDescription = "${week.count { it }} days ticked this week" },
    ) {
        week.forEach { on ->
            val c by animateColorAsState(if (on) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.complete(Meka.reducedMotion), label = "dot")
            Box(Modifier.size(6.dp).clip(CircleShape).background(c))
        }
    }
}

/**
 * An N-a-week habit's week (Fold review 2026-10-09 13:45, item 1): one ring per go this week, filled as they're done
 * (the colour blends on the complete spring), then the week's days as faint ticks only where it was done, so a day
 * off never reads as a miss.
 */
@Composable
private fun WeekSlots(filled: Int, total: Int, week: List<Boolean>) {
    val reduced = Meka.reducedMotion
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.semantics { contentDescription = "$filled of $total this week" },
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { // rhythm: ok (the slot rings are one mark)
            repeat(total) { i ->
                val on = i < filled
                val fill by animateColorAsState(if (on) Meka.colors.accent else Color.Transparent, MekaMotion.complete(reduced), label = "slot")
                Box(
                    Modifier.size(9.dp).clip(CircleShape).background(fill)
                        .border(1.5.dp, if (on) Meka.colors.accent else Meka.colors.textTertiary, CircleShape),
                )
            }
        }
        Spacer(Modifier.width(MekaSpace.xs))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) { // rhythm: ok (the day ticks are one mark)
            week.forEach { on ->
                Box(Modifier.size(4.dp).clip(CircleShape).background(if (on) Meka.colors.textTertiary else Color.Transparent))
            }
        }
    }
}

/** The streak number rolls up (or down) to its new value; reduced motion cross-fades. */
@Composable
private fun RollingNumber(n: Int) {
    val reduced = Meka.reducedMotion
    AnimatedContent(
        targetState = n,
        transitionSpec = {
            if (reduced) fadeIn(MekaMotion.appear(true)) togetherWith fadeOut(MekaMotion.appear(true))
            else {
                val up = targetState > initialState
                (slideInVertically(MekaMotion.replan(false)) { if (up) it else -it } + fadeIn(MekaMotion.appear(false))) togetherWith
                    (slideOutVertically(MekaMotion.replan(false)) { if (up) -it else it } + fadeOut(MekaMotion.appear(false)))
            }
        },
        label = "streak",
    ) { v -> Text("$v", style = MekaType.caption, color = Meka.colors.textSecondary) }
}

/** A goal: title, target, a bar that fills on appear, and how progress is counted. */
@Composable
private fun GoalRow(
    g: GoalItem, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier,
    step: (Int) -> Unit, horizon: (GoalHorizon) -> Unit, finish: () -> Unit, delete: () -> Unit, details: GoalDetails,
) {
    val reduced = Meka.reducedMotion
    val bg by animateColorAsState(if (expanded) Meka.colors.surface else Color.Transparent, MekaMotion.appear(reduced), label = "goal-bg")
    // A goal under a bigger one sits indented beneath it (year → month → week).
    Box(modifier.fillMaxWidth().padding(start = MekaSpace.l * g.depth.coerceAtMost(3))) { GoalRowBody(g, expanded, onToggle, bg, step, horizon, finish, delete, details) }
}

/** What a goal's unfolded settings need beyond the basics (Goals, properly, slice 1). */
internal class GoalDetails(
    val today: Long,
    val parents: List<GoalItem>,
    val why: (String?) -> Unit,
    val due: (Long?) -> Unit,
    val parent: (String?) -> Unit,
    val addStep: (String) -> Unit,
    val candidates: suspend (String) -> List<os.meka.core.domain.Task>,
    val link: (String) -> Unit,
    /** Slice 2: what done looks like (null clears it), milestones. */
    val target: (String?) -> Unit,
    val addMilestone: (String) -> Unit,
    val tickMilestone: (String, Boolean) -> Unit,
    /** Removes one, then hands its title back for Undo. */
    val removeMilestone: (String, (String) -> Unit) -> Unit,
    /** Slice 3, numeric goals: the habits a number can count, the number to reach (then: understood), an update
     *  (then: understood, and its id for Undo, null when it changed nothing), Remove/Undo, and the habit feed. */
    val habits: List<HabitItem>,
    val number: (String, (Boolean) -> Unit) -> Unit,
    val addUpdate: (String, (Boolean, String?) -> Unit) -> Unit,
    val removeUpdate: (String) -> Unit,
    val numberHabit: (String?) -> Unit,
    /** Slice 4: Break it down (approval only). */
    val breakdown: BreakdownActions,
)

@Composable
private fun GoalRowBody(
    g: GoalItem, expanded: Boolean, onToggle: () -> Unit, bg: Color,
    step: (Int) -> Unit, horizon: (GoalHorizon) -> Unit, finish: () -> Unit, delete: () -> Unit, details: GoalDetails,
) {
    val reduced = Meka.reducedMotion
    val modifier = Modifier
    val fill = remember(g.id) { Animatable(if (reduced) g.progressPct / 100f else 0f) }
    LaunchedEffect(g.progressPct) { fill.animateTo(g.progressPct / 100f, MekaMotion.replan(reduced)) }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(bg)) {
        Column(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (expanded) "Hide actions" else "Show actions") { onToggle() }
                .padding(horizontal = MekaSpace.xs, vertical = MekaSpace.xs)
                .semantics { contentDescription = "${g.title}, ${g.progressPct} percent, ${g.meta}" },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The progress ring sweeps round on appear and glides to a new value (Goals, properly, slice 2).
                ProgressRing(fill.value, g.progressPct)
                Spacer(Modifier.width(MekaSpace.m))
                Column(Modifier.weight(1f)) {
                    Text(g.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary)
                    g.target?.let { Text(it, style = MekaType.body, color = Meka.colors.textSecondary) }
                    Text(g.meta, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
                    // A numeric goal with a due date: where it stands against the pace (slice 3).
                    g.metric?.paceLine?.let { p -> Text(p, style = MekaType.caption, color = if (p.startsWith("Behind")) Meka.colors.accent else Meka.colors.textSecondary) }
                    // The next step, always shown: the first open task linked to it, else the next milestone, or a prompt.
                    AnimatedContent(g.nextLine, transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) }, label = "next-step") { line ->
                        Text(line, style = MekaType.caption, color = if (g.nextStep != null) Meka.colors.accent else Meka.colors.textTertiary)
                    }
                }
            }
        }
        AnimatedVisibility(expanded, enter = unfold(), exit = fold()) {
            Column(
                Modifier.fillMaxWidth().padding(start = MekaSpace.xs, end = MekaSpace.xs, bottom = MekaSpace.m),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.m),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.l)) {
                    if (!g.counted) {
                        Action("−10%") { step(-10) }
                        Action("+10%") { step(10) }
                    }
                    Action("Finished") { finish() }
                    Action("Delete", critical = true) { delete() }
                }
                if (g.counted) Text("Progress counts itself from the habits, tasks and goals linked to this goal.", style = MekaType.caption, color = Meka.colors.textTertiary)
                Chips("Horizon", GoalRules.HORIZONS.map { GoalRules.horizonLabel(it) to (it == g.horizon) }) { i -> horizon(GoalRules.HORIZONS[i]) }
                val dues = remember(details.today) { GoalRules.dueChoices(details.today) }
                Chips("Done by", dues.map { it.label to (it.day == g.dueDay) }) { i -> details.due(dues[i].day) }
                if (details.parents.isNotEmpty() || g.parentId != null) {
                    Chips("Under", listOf("Nothing" to (g.parentId == null)) + details.parents.map { it.title to (it.id == g.parentId) }) { i ->
                        details.parent(if (i == 0) null else details.parents[i - 1].id)
                    }
                }
                GoalTarget(g, details.target)
                GoalNumber(g, details)
                GoalWhy(g, details.why)
                Milestones(g, details)
                BreakItDown(g, details.breakdown)
                AddStep(details.addStep)
                LinkTask(g, details)
            }
        }
    }
}

/** The goal's progress as a ring with its percentage inside; [fill] (0–1) is animated by the caller. */
@Composable
private fun ProgressRing(fill: Float, pct: Int) {
    val track = Meka.colors.surfaceRaised
    val accent = Meka.colors.accent
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(40.dp)) {
            val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            val inset = stroke.width / 2
            val arcSize = androidx.compose.ui.geometry.Size(size.width - stroke.width, size.height - stroke.width)
            val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
            drawArc(track, 0f, 360f, false, topLeft, arcSize, style = stroke)
            val sweep = GoalRules.ringSweep((fill.coerceIn(0f, 1f) * 100).toInt())
            if (sweep > 0f) drawArc(accent, -90f, sweep, false, topLeft, arcSize, style = stroke)
        }
        Text("$pct", style = MekaType.caption, color = Meka.colors.textSecondary)
    }
}

/** "What done looks like": Done saves it, and so does closing the settings (typing is never lost). */
@Composable
private fun GoalTarget(g: GoalItem, save: (String?) -> Unit) {
    var target by rememberSaveable(g.id) { mutableStateOf(g.target ?: "") }
    val typed by rememberUpdatedState(target)
    val saved by rememberUpdatedState(g.target ?: "")
    val saveTarget by rememberUpdatedState(save)
    DisposableEffect(Unit) {
        onDispose { if (typed.trim() != saved) saveTarget(typed.trim().ifBlank { null }) }
    }
    Column {
        Text("What done looks like", style = MekaType.caption, color = Meka.colors.textTertiary)
        Field("Say what done looks like (optional)", target, { target = it.take(Goals.MAX_TITLE) }) { save(target.trim().ifBlank { null }) }
    }
}

/**
 * Milestones: each with its tick ring (it draws its check with a light haptic and counts towards the ring), Remove
 * (tick haptic; "Removed … · Undo" cross-fades in under the list), and "Add a milestone…".
 */
@Composable
private fun Milestones(g: GoalItem, details: GoalDetails) {
    val reduced = Meka.reducedMotion
    var title by rememberSaveable(g.id) { mutableStateOf("") }
    var removed by remember(g.id) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text(if (g.milestones.isEmpty()) "Milestones" else "Milestones · ${g.milestonesDone} of ${g.milestones.size}", style = MekaType.caption, color = Meka.colors.textTertiary)
        g.milestones.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                TickRing(
                    m.done,
                    Modifier.size(24.dp).clip(CircleShape)
                        .semantics { contentDescription = (if (m.done) "Untick " else "Tick ") + m.title }
                        .clickable(role = Role.Checkbox) { details.tickMilestone(m.id, !m.done) },
                )
                Spacer(Modifier.width(MekaSpace.m))
                Text(m.title, style = MekaType.body, color = if (m.done) Meka.colors.textSecondary else Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                Action("Remove") { details.removeMilestone(m.id) { t -> removed = t } }
            }
        }
        AnimatedContent(removed, transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) }, label = "milestone-undo") { r ->
            if (r != null) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Removed “$r”", style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
                Action("Undo") { details.addMilestone(r); removed = null }
            }
        }
        if (g.milestones.size < GoalRules.MAX_MILESTONES) {
            Field("Add a milestone…", title, { title = it.take(GoalRules.MAX_MILESTONE_TITLE) }) { details.addMilestone(title.trim()); title = ""; removed = null }
        }
    }
}

/**
 * A numeric goal (slice 3): the number to reach ("£2,000", "12 books"; the title's number is offered as one tap), then
 * the chart, "Add to it" (+200, -1, =1200) with Undo, the latest updates with Remove, and a habit whose ticks add 1.
 * The chart draws itself left to right on appear (reduced motion: at once); lines under the fields cross-fade.
 */
@Composable
private fun GoalNumber(g: GoalItem, details: GoalDetails) {
    val reduced = Meka.reducedMotion
    val m = g.metric
    var target by rememberSaveable(g.id, m?.targetText) { mutableStateOf(m?.targetText ?: "") }
    var badTarget by remember(g.id) { mutableStateOf(false) }
    var update by rememberSaveable(g.id) { mutableStateOf("") }
    var updateLine by remember(g.id) { mutableStateOf<Pair<String, String?>?>(null) } // text, id to undo
    val suggestion = remember(g.title) { GoalMetricRules.suggestFromTitle(g.title) }
    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Text("Number to reach", style = MekaType.caption, color = Meka.colors.textTertiary)
        Field("A number · £2,000 or 12 books (optional)", target, { target = it.take(40); badTarget = false }) {
            details.number(target) { ok -> badTarget = !ok }
        }
        AnimatedContent(
            when { badTarget -> "That isn't a number · try £2,000 or 12 books"; m != null -> "${m.line}${m.paceLine?.let { " · $it" } ?: ""}"; else -> null },
            transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) }, label = "number-line",
        ) { l -> if (l != null) Text(l, style = MekaType.caption, color = if (badTarget) Meka.colors.critical else Meka.colors.textSecondary) }
        if (m == null && suggestion != null && !badTarget) Action("Count “$suggestion”") { target = suggestion; details.number(suggestion) { ok -> badTarget = !ok } }
        if (m != null) {
            MetricChart(m, details.today)
            Field("Add to it · +200, -1 or =1200", update, { update = it.take(40) }) {
                val typed = update
                details.addUpdate(typed) { ok, id ->
                    updateLine = if (!ok) "Type a number, like +200 or =1200" to null else if (id == null) "No change" to null else "Added ${typed.trim()}" to id
                    if (ok) update = ""
                }
            }
            AnimatedContent(updateLine, transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) }, label = "update-line") { u ->
                if (u != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(u.first, style = MekaType.caption, color = Meka.colors.textSecondary, modifier = Modifier.weight(1f))
                    u.second?.let { id -> Action("Undo") { details.removeUpdate(id); updateLine = null } }
                }
            }
            m.recent.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.label, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                    Action("Remove") { details.removeUpdate(e.id) }
                }
            }
            if (details.habits.isNotEmpty()) {
                val options = listOf<HabitItem?>(null) + details.habits
                Chips("Each tick of a habit adds 1", options.map { (it?.title ?: "No habit") to (it?.id == m.habitId) }) { i -> details.numberHabit(options[i]?.id) }
            }
            Action("Stop counting a number") { target = ""; details.number("") { } }
        }
    }
}

/** The number's chart: its line in the accent, the pace line and the target faint; it draws on from the left. */
@Composable
private fun MetricChart(m: os.meka.core.domain.GoalMetric, today: Long) {
    val reduced = Meka.reducedMotion
    val plot = remember(m, today) { GoalMetricRules.plot(m, today) }
    val reveal = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, MekaMotion.replan(reduced)) }
    val accent = Meka.colors.accent
    val faint = Meka.colors.textTertiary
    val track = Meka.colors.surfaceRaised
    Canvas(
        Modifier.fillMaxWidth().height(56.dp)
            .semantics { contentDescription = "Chart: ${m.line}${m.paceLine?.let { ", $it" } ?: ""}" },
    ) {
        val w = size.width; val h = size.height
        fun at(p: os.meka.core.domain.PlotPoint) = androidx.compose.ui.geometry.Offset(p.x * w, h - p.y * h)
        val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        drawLine(track, androidx.compose.ui.geometry.Offset(0f, h - plot.targetY * h), androidx.compose.ui.geometry.Offset(w, h - plot.targetY * h), 1.dp.toPx(), pathEffect = dash)
        if (plot.pace.size == 2) drawLine(faint, at(plot.pace[0]), at(plot.pace[1]), 1.dp.toPx(), pathEffect = dash)
        if (plot.line.isNotEmpty()) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(at(plot.line[0]).x, at(plot.line[0]).y)
                plot.line.drop(1).forEach { lineTo(at(it).x, at(it).y) }
            }
            clipRect(right = w * reveal.value) {
                drawPath(path, accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                drawCircle(accent, 3.dp.toPx(), at(plot.line.last()))
            }
        }
    }
}

/** "Why it matters": Done saves it, and so does closing the settings (typing is never lost, TextAutosave). */
@Composable
private fun GoalWhy(g: GoalItem, save: (String?) -> Unit) {
    var why by rememberSaveable(g.id) { mutableStateOf(g.why ?: "") }
    val typed by rememberUpdatedState(why)
    val saved by rememberUpdatedState(g.why ?: "")
    val saveWhy by rememberUpdatedState(save)
    DisposableEffect(Unit) {
        onDispose { if (typed.trim() != saved) saveWhy(typed.trim().ifBlank { null }) }
    }
    Column {
        Text("Why it matters", style = MekaType.caption, color = Meka.colors.textTertiary)
        Field("Say why, in a line (optional)", why, { why = it.take(GoalRules.MAX_WHY) }) { save(why.trim().ifBlank { null }) }
    }
}

/** "Add a step": a new task linked to the goal; it shows as the next step when nothing is planned sooner. */
@Composable
private fun AddStep(add: (String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    Field("Add a step…", title, { title = it }) { add(title.trim()); title = "" }
}

/** "Link a task": type a few words, tap one of your open tasks to count it towards the goal. */
@Composable
private fun LinkTask(g: GoalItem, details: GoalDetails) {
    var query by rememberSaveable(g.id) { mutableStateOf("") }
    var found by remember(g.id) { mutableStateOf<List<os.meka.core.domain.Task>>(emptyList()) }
    LaunchedEffect(g.id, query, g.tasksTotal) { found = if (query.isBlank()) emptyList() else runCatching { details.candidates(query) }.getOrDefault(emptyList()) }
    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xxs)) {
        Field("Link a task…", query, { query = it }) {}
        AnimatedVisibility(found.isNotEmpty(), enter = unfold(), exit = fold()) {
            Column {
                found.forEach { t -> Action("+ ${t.title}") { details.link(t.id); query = "" } }
            }
        }
    }
}

@Composable
internal fun unfold() = if (Meka.reducedMotion) fadeIn(MekaMotion.expand(true)) else expandVertically(MekaMotion.expand(false)) + fadeIn(MekaMotion.appear(false))

@Composable
internal fun fold() = if (Meka.reducedMotion) fadeOut(MekaMotion.expand(true)) else shrinkVertically(MekaMotion.expand(false)) + fadeOut(MekaMotion.appear(false))

@Composable
internal fun Action(label: String, critical: Boolean = false, onClick: () -> Unit) {
    Text(
        label, style = MekaType.itemTitle, color = if (critical) Meka.colors.critical else Meka.colors.accent,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.s)).clickable(role = Role.Button) { onClick() }.padding(vertical = MekaSpace.xxs),
    )
}

/** A label and a scrolling row of chips; [lit] marks the current one. */
@Composable
internal fun Chips(label: String?, options: List<Pair<String, Boolean>>, choose: (Int) -> Unit) {
    Column {
        label?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = MekaSpace.xxs), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
            options.forEachIndexed { i, (text, lit) -> Chip(text, lit) { choose(i) } }
        }
    }
}

@Composable
private fun Chip(label: String, lit: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(if (lit) Meka.colors.accent else Meka.colors.surfaceRaised, MekaMotion.appear(Meka.reducedMotion), label = "chip")
    Text(
        label, style = MekaType.caption, color = if (lit) Meka.colors.onAccent else Meka.colors.textPrimary, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.pill)).background(bg)
            .semantics { selected = lit }
            .clickable(role = Role.Button) { onClick() }.padding(horizontal = MekaSpace.m, vertical = MekaSpace.xs),
    )
}

/** A one-line field in a raised pill; Done submits. */
@Composable
private fun Field(hint: String, value: String, onValue: (String) -> Unit, onDone: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)
            .padding(horizontal = MekaSpace.l, vertical = MekaSpace.m),
    ) {
        if (value.isEmpty()) Text(hint, style = MekaType.body, color = Meka.colors.textTertiary)
        BasicTextField(
            value = value,
            onValueChange = onValue,
            singleLine = true,
            textStyle = MekaType.body.copy(color = Meka.colors.textPrimary),
            cursorBrush = SolidColor(Meka.colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (value.isNotBlank()) onDone() }),
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
        )
    }
}

/** Add a habit: name, then (once typing) how often and when. */
@Composable
private fun AddHabit(add: (String, Int, HabitTiming) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var perWeek by rememberSaveable { mutableStateOf(7) }
    var timing by rememberSaveable { mutableStateOf(HabitTiming.ANYTIME) }
    val submit = { if (title.isNotBlank()) { add(title, perWeek, timing); title = ""; perWeek = 7; timing = HabitTiming.ANYTIME } }
    Column(Modifier.padding(top = MekaSpace.m), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Field("New habit…", title, { title = it }) { submit() }
        AnimatedVisibility(title.isNotBlank(), enter = unfold(), exit = fold()) {
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Chips(null, GoalRules.TARGET_CHOICES.map { it.label to (it.perWeek == perWeek) }) { i -> perWeek = GoalRules.TARGET_CHOICES[i].perWeek }
                Chips(null, GoalRules.TIMINGS.map { GoalRules.timingLabel(it) to (it == timing) }) { i -> timing = GoalRules.TIMINGS[i] }
                Action("Add") { submit() }
            }
        }
    }
}

/** Add a goal: name, then (once typing) what done looks like and the horizon. */
@Composable
private fun AddGoal(add: (String, String?, GoalHorizon) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }
    var horizon by rememberSaveable { mutableStateOf(GoalHorizon.MONTH) }
    val submit = { if (title.isNotBlank()) { add(title, target.ifBlank { null }, horizon); title = ""; target = ""; horizon = GoalHorizon.MONTH } }
    Column(Modifier.padding(top = MekaSpace.m), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        Field("New goal…", title, { title = it }) { submit() }
        AnimatedVisibility(title.isNotBlank(), enter = unfold(), exit = fold()) {
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
                Field("What does done look like? (optional)", target, { target = it }) { submit() }
                Chips(null, GoalRules.HORIZONS.map { GoalRules.horizonLabel(it) to (it == horizon) }) { i -> horizon = GoalRules.HORIZONS[i] }
                Action("Add") { submit() }
            }
        }
    }
}

/**
 * Goals' MONEY CHALLENGES (Money manager 15): each challenge he set in Money with its streak, brass while a run is going.
 * No amounts, so no fingerprint here; a tap opens Money, which asks for it.
 */
@Composable
private fun MoneyStreaksSection(m: MoneyStreaksView, modifier: Modifier, open: () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        SectionLabel(m.head)
        m.rows.forEach { row ->
            Column(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = open)
                    .semantics(mergeDescendants = true) { contentDescription = row.spoken },
            ) {
                Text(row.title, style = MekaType.body, color = Meka.colors.textPrimary)
                AnimatedContent(row.line, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "streak") { line ->
                    Text(line, style = MekaType.itemMeta, color = if (row.lit) Meka.colors.accent else Meka.colors.textSecondary)
                }
            }
        }
        m.note?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        Text(m.foot, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}

/** Goals' READING GOAL (reading and learning, slice 4): the year's goal, how many so far and the pace, brass on pace. */
@Composable
private fun ReadingGoalSection(g: ReadingGoalView, modifier: Modifier, open: () -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        SectionLabel(ReadingGoalRules.HEAD)
        Column(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = open)
                .semantics(mergeDescendants = true) { contentDescription = g.spoken },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(g.title, style = MekaType.body, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                AnimatedContent(g.count, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "goal-count") { c ->
                    Text(c, style = MekaType.itemMeta, color = Meka.colors.textSecondary)
                }
            }
            AnimatedContent(g.line, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "goal-pace") { line ->
                Text(line, style = MekaType.itemMeta, color = if (g.lit) Meka.colors.accent else Meka.colors.textSecondary)
            }
        }
        Text(ReadingGoalRules.FOOT, style = MekaType.caption, color = Meka.colors.textTertiary)
    }
}
