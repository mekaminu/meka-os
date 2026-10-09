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
import androidx.compose.foundation.layout.height
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
import os.meka.core.domain.GoalRules
import os.meka.core.domain.HabitItem
import os.meka.core.domain.HabitPace
import os.meka.core.domain.HabitTiming
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
                line ?: "Add a habit and MEKA keeps count, and makes room in your plan when one falls behind.",
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
                Column(Modifier.animateItem().padding(top = MekaSpace.s)) {
                    Action(SessionRules.bookOfferLabel(h.title)) { haptics.light(); act { core.letMekaBook(h.id); open = h.id } }
                    Text(SessionRules.BOOK_OFFER_CAPTION, style = MekaType.caption, color = Meka.colors.textTertiary)
                }
            }
        }
        // The Gym: one tap adds it with its sessions booked around the calendar (offered until a booked or gym-named habit exists).
        if (view.offersAddGym) item(key = "add-gym") {
            Column(Modifier.animateItem().padding(top = MekaSpace.s)) {
                Action("Add Gym") { haptics.light(); act { open = core.addGym() } }
                Text(
                    "Three times a week, evenings, an hour: MEKA books the sessions around your calendar and work, and rebooks a missed one.",
                    style = MekaType.caption, color = Meka.colors.textTertiary,
                )
            }
        }

        item(key = "goals-label") { SectionLabel("Goals", Modifier.padding(top = MekaSpace.xl).appear(rememberAppearance(3))) }
        if (view.goals.isEmpty()) item(key = "goals-empty") {
            Text(
                "Say what you're working towards. Link habits and tasks to a goal and its progress counts itself.",
                style = MekaType.body, color = Meka.colors.textSecondary, modifier = Modifier.animateItem().padding(vertical = MekaSpace.s),
            )
        }
        items(view.goals, key = { "g-${it.id}" }) { g ->
            GoalRow(
                g, open == g.id, { toggle(g.id) }, Modifier.animateItem(),
                step = { d -> act { core.setGoalProgress(g.id, g.progressPct + d) } },
                horizon = { hz -> act { core.setGoalHorizon(g.id, hz) } },
                finish = { haptics.light(); open = null; act { core.finishGoal(g.id) } },
                delete = { open = null; act { core.deleteGoal(g.id) } },
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
        Row(Modifier.fillMaxWidth().padding(horizontal = MekaSpace.s, vertical = MekaSpace.s), verticalAlignment = Alignment.CenterVertically) {
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
                        Spacer(Modifier.width(MekaSpace.s))
                        RollingNumber(h.streak)
                        Text("-${h.streakUnit} streak", style = MekaType.caption, color = Meka.colors.textTertiary)
                    }
                }
            }
        }
        AnimatedVisibility(expanded, enter = unfold(), exit = fold()) {
            Column(
                Modifier.fillMaxWidth().padding(start = MekaSpace.s, end = MekaSpace.s, bottom = MekaSpace.m),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.s),
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
        horizontalArrangement = Arrangement.spacedBy(3.dp),
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
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
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
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
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
    step: (Int) -> Unit, horizon: (GoalHorizon) -> Unit, finish: () -> Unit, delete: () -> Unit,
) {
    val reduced = Meka.reducedMotion
    val bg by animateColorAsState(if (expanded) Meka.colors.surface else Color.Transparent, MekaMotion.appear(reduced), label = "goal-bg")
    val fill = remember(g.id) { Animatable(if (reduced) g.progressPct / 100f else 0f) }
    LaunchedEffect(g.progressPct) { fill.animateTo(g.progressPct / 100f, MekaMotion.replan(reduced)) }
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(MekaRadius.m)).background(bg)) {
        Column(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = if (expanded) "Hide actions" else "Show actions") { onToggle() }
                .padding(horizontal = MekaSpace.s, vertical = MekaSpace.s)
                .semantics { contentDescription = "${g.title}, ${g.progressPct} percent, ${g.meta}" },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(g.title, style = MekaType.itemTitle, color = Meka.colors.textPrimary, modifier = Modifier.weight(1f))
                Text("${g.progressPct}%", style = MekaType.itemMeta, color = Meka.colors.textSecondary)
            }
            g.target?.let { Text(it, style = MekaType.body, color = Meka.colors.textSecondary) }
            Box(Modifier.padding(vertical = MekaSpace.xs).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.surfaceRaised)) {
                Box(Modifier.fillMaxWidth(fill.value.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(MekaRadius.pill)).background(Meka.colors.accent))
            }
            Text(g.meta, style = MekaType.itemMeta, color = Meka.colors.textTertiary)
        }
        AnimatedVisibility(expanded, enter = unfold(), exit = fold()) {
            Column(
                Modifier.fillMaxWidth().padding(start = MekaSpace.s, end = MekaSpace.s, bottom = MekaSpace.m),
                verticalArrangement = Arrangement.spacedBy(MekaSpace.s),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(MekaSpace.l)) {
                    if (!g.counted) {
                        Action("−10%") { step(-10) }
                        Action("+10%") { step(10) }
                    }
                    Action("Finished") { finish() }
                    Action("Delete", critical = true) { delete() }
                }
                if (g.counted) Text("Progress counts itself from the habits and tasks linked to this goal.", style = MekaType.caption, color = Meka.colors.textTertiary)
                Chips("Horizon", GoalRules.HORIZONS.map { GoalRules.horizonLabel(it) to (it == g.horizon) }) { i -> horizon(GoalRules.HORIZONS[i]) }
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
    Column(Modifier.padding(top = MekaSpace.m), verticalArrangement = Arrangement.spacedBy(MekaSpace.s)) {
        Field("New habit…", title, { title = it }) { submit() }
        AnimatedVisibility(title.isNotBlank(), enter = unfold(), exit = fold()) {
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.s)) {
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
    var horizon by rememberSaveable { mutableStateOf(GoalHorizon.MEDIUM) }
    val submit = { if (title.isNotBlank()) { add(title, target.ifBlank { null }, horizon); title = ""; target = ""; horizon = GoalHorizon.MEDIUM } }
    Column(Modifier.padding(top = MekaSpace.m), verticalArrangement = Arrangement.spacedBy(MekaSpace.s)) {
        Field("New goal…", title, { title = it }) { submit() }
        AnimatedVisibility(title.isNotBlank(), enter = unfold(), exit = fold()) {
            Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.s)) {
                Field("What does done look like? (optional)", target, { target = it }) { submit() }
                Chips(null, GoalRules.HORIZONS.map { GoalRules.horizonLabel(it) to (it == horizon) }) { i -> horizon = GoalRules.HORIZONS[i] }
                Action("Add") { submit() }
            }
        }
    }
}
