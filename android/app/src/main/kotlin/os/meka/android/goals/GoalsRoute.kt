@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package os.meka.android.goals

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.testTag
import os.meka.android.designsystem.minTouch
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import os.meka.android.designsystem.TickRing
import os.meka.android.designsystem.MekaPane
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
import os.meka.core.domain.GoalTextRules
import os.meka.core.domain.GoalNumberRules
import os.meka.core.domain.GoalMetricRules
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.drawscope.clipRect
import os.meka.core.domain.GoalRules
import os.meka.core.domain.GoalStepRules
import os.meka.core.domain.GoalHabitChoiceRules
import os.meka.core.domain.GoalSettingChoiceRules
import os.meka.android.notify.NotifyChoiceRowView
import os.meka.android.notify.NotifyChoiceSheetView
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

/** The Goals list (its width is checked on the open Fold). */
internal const val GOALS_LIST_TAG = "goals-list"

/** Goals' column on a wide screen: the same 600 dp as Study, Habits and Fasting. */
internal val GOALS_COLUMN_MAX = 600.dp

/**
 * GOALS (build plan M1): fasting (a small [FastingTile] opening the Fasting screen, [FastingPane]), habits with this week's pace and streaks, and goals with progress. A habit's circle pops with
 * a spring when ticked and its streak number rolls (light haptic); goal progress bars fill on appear. Tapping a row
 * unfolds its settings in place. Nothing is ticked for you. Reduced motion: colour changes and cross-fades only.
 */
@Composable
fun GoalsRoute(core: MekaCore) {
    // Taps feel instant: a habit's tick shows in the same frame, drawn over the core's view ([HabitTaps]).
    val view = shownGoals(core.goalsView)
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

    // Fasting rebuild slice 2: Goals' tile opens the Fasting screen as a pane; back drops it.
    var fastingOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = fastingOpen) { fastingOpen = false }
    var habitsOpen by rememberSaveable { mutableStateOf(false) }
    // Which add sheet is open over Goals (walk-through week 5, A17), if any.
    var adding by rememberSaveable { mutableStateOf<Adding?>(null) }
    // Study plan, slice 1: a course opens as a pane like Fasting; a removed course offers Undo here.
    val study by core.studyView.collectAsState()
    var studyOpen by rememberSaveable { mutableStateOf<String?>(null) }
    var studyShown by rememberSaveable { mutableStateOf<String?>(null) }
    var studyRemoved by remember { mutableStateOf<os.meka.core.domain.StudyRemoved?>(null) }
    LaunchedEffect(studyRemoved) { if (studyRemoved != null) { kotlinx.coroutines.delay(os.meka.core.domain.TripRules.UNDO_MS); studyRemoved = null } }
    val openStudy: (String) -> Unit = { id -> studyShown = id; studyOpen = id }
    // The Week board's study line: Goals opens with that course up.
    val boardStudy by app.openStudy.collectAsState()
    LaunchedEffect(boardStudy) {
        val id = boardStudy ?: return@LaunchedEffect
        openStudy(id)
        app.openStudy.value = null
    }
    // One-tap course import: a course link or shared course waits here as "Add <course> …?" until Add or Cancel.
    val importText by app.studyImport.collectAsState()
    var importOffer by remember { mutableStateOf<os.meka.core.domain.StudyImportOffer?>(null) }
    LaunchedEffect(importText) {
        val t = importText ?: return@LaunchedEffect
        importOffer = runCatching { core.studyImportOffer(t) }.getOrNull()
    }
    // Walk-through 2026-11-15 A17: on the open Fold Goals sits in the same centred 600 dp column as Study, Habits and
    // Fasting (it ran edge to edge, the fasting card's › ~1,700 px from its words); folded it fills the width as before.
    Box(Modifier.fillMaxSize().background(Meka.colors.background), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
        Modifier.widthIn(max = GOALS_COLUMN_MAX).fillMaxHeight().testTag(GOALS_LIST_TAG),
        contentPadding = PaddingValues(horizontal = MekaSpace.gutter, vertical = MekaSpace.xl),
        verticalArrangement = Arrangement.spacedBy(MekaSpace.xs),
    ) {
        item(key = "title") {
            Text("Goals", style = MekaType.greeting, color = Meka.colors.textPrimary,
                modifier = Modifier.sharedPlace(LocalPlaceTitleKey.current).appear(rememberAppearance(0)))
        }
        item(key = "study-import") {
            AnimatedVisibility(importText != null && importOffer != null, enter = unfold(), exit = fold()) {
                importOffer?.let { o ->
                    StudyImportCard(
                        o, Modifier.padding(bottom = MekaSpace.m),
                        onAdd = {
                            val t = importText ?: return@StudyImportCard
                            app.studyImport.value = null
                            haptics.light()
                            scope.launch {
                                val id = runCatching { core.addStudyImport(t) }.getOrNull()?.courseId
                                if (id != null) openStudy(id) else haptics.reject()
                            }
                        },
                        onCancel = { haptics.tick(); app.studyImport.value = null },
                    )
                }
            }
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
        item(key = "fasting") {
            FastingTile(core, Modifier.padding(bottom = MekaSpace.l).appear(rememberAppearance(2))) { haptics.light(); fastingOpen = true }
        }
        item(key = "habits-label") {
            // The Habits screen (approved 2026-11-11): the day strip, lists, Do and Avoid, done → missed → clear.
            Row(Modifier.fillMaxWidth().appear(rememberAppearance(2)), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("Habits", Modifier.weight(1f))
                Text("Open Habits ›", style = MekaType.itemMeta, color = Meka.colors.accent,
                    modifier = Modifier.clickable(role = Role.Button) { haptics.light(); habitsOpen = true }.minTouch().testTag("goals-open-habits"))
            }
        }
        items(view.habits, key = { "h-${it.id}" }) { h ->
            HabitRow(
                h, view.goals, open == h.id, { toggle(h.id) }, Modifier.animateItem(),
                tick = { haptics.light(); HabitTaps.tick(scope, core, h.id, !h.doneToday) },
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
        // Walk-through week 5, A17: one card row opening its sheet, not an open "New habit…" box on the page.
        item(key = "add-habit") {
            GoalAddRowView(os.meka.core.domain.GoalAddRules.habitRow, Modifier.animateItem().padding(top = MekaSpace.m).testTag(GOALS_ADD_HABIT_TAG)) { adding = Adding.HABIT }
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
        item(key = "add-goal") {
            GoalAddRowView(os.meka.core.domain.GoalAddRules.goalRow, Modifier.animateItem().padding(top = MekaSpace.m).testTag(GOALS_ADD_GOAL_TAG)) { adding = Adding.GOAL }
        }
        // Study plan, slice 1: courses with their modules and lessons, under the goals.
        item(key = "study") {
            StudySection(
                study, Modifier.animateItem().padding(top = MekaSpace.xl), studyRemoved,
                onUndo = { r -> studyRemoved = null; act { core.undoStudyRemoval(r.token) } },
                onAdd = { text ->
                    scope.launch {
                        val r = runCatching { core.addStudyCourse(text) }.getOrNull()
                        val id = r?.courseId
                        if (id != null) { haptics.light(); openStudy(id) } else haptics.reject()
                    }
                },
                onOpen = openStudy,
            )
        }
    }
    when (adding) {
        Adding.HABIT -> HabitAddSheetView(
            onAdd = { title, perWeek, timing -> act { core.addHabit(title, perWeek, timing, GoalRules.DEFAULT_MINUTES, null) } },
            onClose = { adding = null },
        )
        Adding.GOAL -> GoalAddSheetView(
            onAdd = { title, target, hz -> act { core.addGoal(title, target, hz) } },
            onClose = { adding = null },
        )
        null -> Unit
    }
    MekaPane(visible = fastingOpen) { FastingPane(core, onClose = { fastingOpen = false }) }
    MekaPane(visible = habitsOpen) { HabitsPage(core, onClose = { habitsOpen = false }) }
    MekaPane(visible = studyOpen != null) {
        studyShown?.let { id -> StudyPane(core, id, onClose = { studyOpen = null }, onRemoved = { studyRemoved = it }) }
    }
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
    var sheet by rememberSaveable(h.id, "setting") { mutableStateOf<HabitSetting?>(null) }
    sheet?.let { kind ->
        val R = GoalHabitChoiceRules
        NotifyChoiceSheetView(
            when (kind) {
                HabitSetting.OFTEN -> R.oftenSheet(h)
                HabitSetting.WHEN -> R.whenSheet(h)
                HabitSetting.LONG -> R.longSheet(h)
                HabitSetting.BOOK -> R.bookSheet(h)
                HabitSetting.ROTATION -> R.rotationSheet(h)
                HabitSetting.TOWARDS -> R.towardsSheet(h, goals)
            },
            onChoose = { name ->
                when (kind) {
                    HabitSetting.OFTEN -> R.oftenNamed(h, name)?.let { edit(it, null, null) }
                    HabitSetting.WHEN -> R.whenNamed(h, name)?.let { edit(null, it, null) }
                    HabitSetting.LONG -> R.longNamed(h, name)?.let { edit(null, null, it) }
                    HabitSetting.BOOK -> R.bookNamed(h, name)?.let(book)
                    HabitSetting.ROTATION -> R.rotationNamed(h, name)?.let(rotate)
                    HabitSetting.TOWARDS -> R.towardsNamed(h, goals, name)?.let { link(it.goalId) }
                }
            },
            onClose = { sheet = null },
        )
    }
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
                // Walk-through week 5, A17: one card row per setting ("How often" · "5 a week" ›) opening its sheet, each
                // choice saying what it does, not six open chip rows under the habit.
                val R = GoalHabitChoiceRules
                NotifyChoiceRowView(R.oftenRow(h), Modifier.testTag("goals-habit-often-${h.id}"), raised = true) { sheet = HabitSetting.OFTEN }
                NotifyChoiceRowView(R.whenRow(h), Modifier.testTag("goals-habit-when-${h.id}"), raised = true) { sheet = HabitSetting.WHEN }
                NotifyChoiceRowView(R.longRow(h), Modifier.testTag("goals-habit-long-${h.id}"), raised = true) { sheet = HabitSetting.LONG }
                NotifyChoiceRowView(R.bookRow(h), Modifier.testTag("goals-habit-book-${h.id}"), raised = true) { sheet = HabitSetting.BOOK }
                if (h.booked) {
                    NotifyChoiceRowView(R.rotationRow(h), Modifier.testTag("goals-habit-rotation-${h.id}"), raised = true) { sheet = HabitSetting.ROTATION }
                    AppLinkField(h.appLink, h.appName, appLink)
                    Text(
                        "Booked around your calendar and work, a rest day between when there's room. Missed ones are rebooked.",
                        style = MekaType.caption, color = Meka.colors.textTertiary,
                    )
                }
                if (goals.isNotEmpty()) {
                    NotifyChoiceRowView(R.towardsRow(h, goals), Modifier.testTag("goals-habit-towards-${h.id}"), raised = true) { sheet = HabitSetting.TOWARDS }
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
    var sheet by rememberSaveable(g.id, "goal-setting") { mutableStateOf<GoalSetting?>(null) }
    sheet?.let { kind ->
        val R = GoalSettingChoiceRules
        NotifyChoiceSheetView(
            when (kind) {
                GoalSetting.HORIZON -> R.horizonSheet(g, details.today)
                GoalSetting.DUE -> R.dueSheet(g, details.today)
                GoalSetting.UNDER -> R.underSheet(g, details.parents)
                GoalSetting.HABIT -> R.habitSheet(g, details.habits)
            },
            onChoose = { name ->
                when (kind) {
                    GoalSetting.HORIZON -> R.horizonNamed(g, name)?.let(horizon)
                    GoalSetting.DUE -> R.dueNamed(g, details.today, name)?.let { details.due(it.day) }
                    GoalSetting.UNDER -> R.underNamed(g, details.parents, name)?.let { details.parent(it.goalId) }
                    GoalSetting.HABIT -> R.habitNamed(g, details.habits, name)?.let { details.numberHabit(it.habitId) }
                }
            },
            onClose = { sheet = null },
        )
    }
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
                // Walk-through week 5, A17: one card row per setting ("Horizon" · "This month" ›) opening its sheet, each
                // choice saying what it does, not open chip rows under the goal.
                val R = GoalSettingChoiceRules
                NotifyChoiceRowView(R.horizonRow(g), Modifier.testTag("goals-goal-horizon-${g.id}"), raised = true) { sheet = GoalSetting.HORIZON }
                NotifyChoiceRowView(R.dueRow(g, details.today), Modifier.testTag("goals-goal-due-${g.id}"), raised = true) { sheet = GoalSetting.DUE }
                if (R.showsUnder(g, details.parents)) {
                    NotifyChoiceRowView(R.underRow(g), Modifier.testTag("goals-goal-under-${g.id}"), raised = true) { sheet = GoalSetting.UNDER }
                }
                GoalTarget(g, details.target)
                GoalNumber(g, details) { sheet = GoalSetting.HABIT }
                GoalWhy(g, details.why)
                Milestones(g, details)
                BreakItDown(g, details.breakdown)
                AddStep(g, details.addStep)
                LinkTask(g, details)
            }
        }
    }
}

/** The goal's progress as a ring with its percentage inside; [fill] (0–1) is animated by the caller. */
@Composable
internal fun ProgressRing(fill: Float, pct: Int) {
    // Walk-through 2026-11-15, A21: the empty ring's track in the bars' ink, seen in Light (surfaceRaised was ~1.1:1 there).
    val track = os.meka.android.designsystem.LightInk.barTrack(Meka.colors, Meka.theme.isDark)
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

/**
 * "What done looks like" (walk-through week 5, A17): one card row over the words kept ("Not said yet · …" while there are
 * none) opening its sheet with the words to change, Close · Save; not an open box on the page.
 */
@Composable
private fun GoalTarget(g: GoalItem, save: (String?) -> Unit) {
    var open by rememberSaveable(g.id, "goal-target") { mutableStateOf(false) }
    GoalAddRowView(GoalTextRules.targetRow(g), Modifier.testTag("goals-goal-target-${g.id}")) { open = true }
    if (open) GoalTextSheetView(
        GoalTextRules.targetSheet, g.target, GoalTextRules.MAX_TARGET, singleLine = true,
        onSave = save, onClose = { open = false },
    )
}

/**
 * Milestones: each with its tick ring (it draws its check with a light haptic and counts towards the ring), Remove
 * (tick haptic; "Removed … · Undo" cross-fades in under the list), and "Add a milestone…".
 */
@Composable
private fun Milestones(g: GoalItem, details: GoalDetails) {
    val reduced = Meka.reducedMotion
    var adding by rememberSaveable(g.id, "goal-milestone-add") { mutableStateOf(false) }
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
        // Walk-through week 5, A17: "+ Add a milestone" is a card row opening its sheet, not an open box.
        if (GoalStepRules.showsMilestoneRow(g)) {
            GoalAddRowView(GoalStepRules.milestoneRow(g), Modifier.testTag("goals-goal-milestone-add-${g.id}")) { adding = true }
        }
    }
    if (adding) LineAddSheetView(
        GoalStepRules.milestoneSheet(g), { GoalStepRules.canAddMilestone(it, g) }, GoalStepRules.MAX_MILESTONE,
        onAdd = { t -> GoalStepRules.cleaned(t, GoalStepRules.MAX_MILESTONE)?.let(details.addMilestone); removed = null },
        onClose = { adding = false },
    )
}

/**
 * A numeric goal (slice 3): the number to reach ("£2,000", "12 books"), then the chart, "Add to it" (+200, -1, =1200)
 * with Undo, the latest updates with Remove, and a habit whose ticks add 1. Walk-through week 5, A17: Number to reach and
 * Add to it are card rows opening sheets (GoalNumberRules), not open boxes; a line that isn't a number keeps its sheet
 * open and says so under it. The chart draws itself left to right on appear (reduced motion: at once); lines cross-fade.
 */
@Composable
private fun GoalNumber(g: GoalItem, details: GoalDetails, openHabit: () -> Unit) {
    val reduced = Meka.reducedMotion
    val m = g.metric
    var numberOpen by rememberSaveable(g.id, "goal-number") { mutableStateOf(false) }
    var addOpen by rememberSaveable(g.id, "goal-add-to-it") { mutableStateOf(false) }
    var updateLine by remember(g.id) { mutableStateOf<Pair<String, String?>?>(null) } // text, id to undo
    Column(verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
        GoalAddRowView(GoalNumberRules.numberRow(g), Modifier.testTag("goals-goal-number-${g.id}")) { numberOpen = true }
        if (m != null) {
            // Money manager (6): a £ goal that follows a Money account says so (linked and unlinked in Money, behind the lock).
            AnimatedContent(m.moneyLine, transitionSpec = { fadeIn(MekaMotion.appear(reduced)) togetherWith fadeOut(MekaMotion.appear(reduced)) }, label = "money-line") { l ->
                if (l != null) Text(l, style = MekaType.caption, color = Meka.colors.accent)
            }
            MetricChart(m, details.today)
            GoalAddRowView(GoalNumberRules.addRow(m), Modifier.testTag("goals-goal-add-to-it-${g.id}")) { addOpen = true }
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
                // Walk-through week 5, A17: "Counts a habit" · "Swim" › opening its sheet, not a chip per habit.
                NotifyChoiceRowView(GoalSettingChoiceRules.habitRow(g, details.habits), Modifier.testTag("goals-goal-habit-${g.id}"), raised = true) { openHabit() }
            }
            Action("Stop counting a number") { details.number("") { } }
        }
    }
    if (numberOpen) CheckedLineSheetView(
        GoalNumberRules.numberSheet(g), GoalNumberRules.numberInitial(g),
        canSave = { GoalNumberRules.canSaveNumber(it, g) }, maxLength = GoalNumberRules.MAX_TYPED,
        submit = { typed, then -> details.number(typed.trim()) { ok -> then(if (ok) null else GoalNumberRules.NOT_A_NUMBER) } },
        onClose = { numberOpen = false },
    )
    if (addOpen && m != null) CheckedLineSheetView(
        GoalNumberRules.addSheet(m), "",
        canSave = GoalNumberRules::canAdd, maxLength = GoalNumberRules.MAX_TYPED,
        submit = { typed, then ->
            details.addUpdate(typed) { ok, id ->
                if (ok) updateLine = GoalNumberRules.addedLine(typed, id != null) to id
                then(if (ok) null else GoalNumberRules.NOT_AN_UPDATE)
            }
        },
        onClose = { addOpen = false },
    )
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

/**
 * "Why it matters" (walk-through week 5, A17): one card row over the words kept opening its sheet (a few lines, Return a
 * new line), Close · Save; not an open box on the page.
 */
@Composable
private fun GoalWhy(g: GoalItem, save: (String?) -> Unit) {
    var open by rememberSaveable(g.id, "goal-why") { mutableStateOf(false) }
    GoalAddRowView(GoalTextRules.whyRow(g), Modifier.testTag("goals-goal-why-${g.id}")) { open = true }
    if (open) GoalTextSheetView(
        GoalTextRules.whySheet, g.why, GoalTextRules.MAX_WHY, singleLine = false,
        onSave = save, onClose = { open = false },
    )
}

/**
 * "+ Add a step": a new task linked to the goal; it shows as the next step when nothing is planned sooner. Walk-through
 * week 5, A17: a card row opening its sheet, not an open box.
 */
@Composable
private fun AddStep(g: GoalItem, add: (String) -> Unit) {
    var open by rememberSaveable(g.id, "goal-step-add") { mutableStateOf(false) }
    GoalAddRowView(GoalStepRules.stepRow, Modifier.testTag("goals-goal-step-add-${g.id}")) { open = true }
    if (open) LineAddSheetView(
        GoalStepRules.stepSheet, GoalStepRules::canAddStep, GoalStepRules.MAX_STEP,
        onAdd = { t -> GoalStepRules.cleaned(t, GoalStepRules.MAX_STEP)?.let(add) },
        onClose = { open = false },
    )
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
        modifier = Modifier.clip(RoundedCornerShape(MekaRadius.s)).clickable(role = Role.Button) { onClick() }.padding(vertical = MekaSpace.xxs).minTouch(),
    )
}

/** A label and its chips, wrapping onto more lines rather than sliding one off the edge (walk-through A11, News' "Football E"); [lit] marks the current one. */
@Composable
internal fun Chips(label: String?, options: List<Pair<String, Boolean>>, choose: (Int) -> Unit) {
    Column {
        label?.let { Text(it, style = MekaType.caption, color = Meka.colors.textTertiary) }
        FlowRow(Modifier.padding(top = MekaSpace.xxs), horizontalArrangement = Arrangement.spacedBy(MekaSpace.xs), verticalArrangement = Arrangement.spacedBy(MekaSpace.xs)) {
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

/** The goal setting whose sheet is open on Goals (walk-through week 5, A17). */
internal enum class GoalSetting { HORIZON, DUE, UNDER, HABIT }

/** The habit setting whose sheet is open on Goals (walk-through week 5, A17). */
internal enum class HabitSetting { OFTEN, WHEN, LONG, BOOK, ROTATION, TOWARDS }

/** The add sheet open over Goals. */
private enum class Adding { HABIT, GOAL }

/** The tags the screen tests find Goals' add rows by. */
const val GOALS_ADD_HABIT_TAG = "goals-add-habit"
const val GOALS_ADD_GOAL_TAG = "goals-add-goal"

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
