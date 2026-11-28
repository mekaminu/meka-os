package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import android.os.Looper
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performSemanticsAction
import org.robolectric.Shadows
import os.meka.android.goals.GOALS_ADD_GOAL_TAG
import os.meka.android.goals.GOALS_ADD_HABIT_TAG
import os.meka.android.goals.GOAL_ADD_SHEET_TAG
import os.meka.android.goals.STUDY_ADD_COURSE_TAG
import os.meka.core.domain.GoalAddRules
import os.meka.core.domain.GoalHabitChoiceRules
import os.meka.core.domain.GoalSettingChoiceRules
import os.meka.core.domain.GoalTextRules
import os.meka.core.domain.GoalRules
import os.meka.android.notify.NOTIFY_CHOICE_ROW_TAG
import os.meka.android.notify.NOTIFY_CHOICE_SHEET_TAG
import kotlin.test.fail
import kotlin.math.abs
import kotlin.test.assertTrue
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.goals.GOALS_COLUMN_MAX
import os.meka.android.goals.GOALS_LIST_TAG
import os.meka.android.goals.GoalsRoute
import os.meka.core.domain.GoalHorizon
import os.meka.core.domain.HabitTiming
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random

/**
 * The quality gate, slice 9 (build plan: "the same shots for every other screen, Goals and habits next"): the Goals
 * screen on the Fold's cover and inner screens, with nothing set up, with one habit and with a full set (the Habits
 * screen's eight habits from ShotLife, three done today and one linked to a goal, two goals with a long title, a fast that has ended), Dark and Light. Saved
 * under `goals/` (the `fold-screens` artifact, then the `screens` branch's docs/screens/fold/goals/) and read for
 * nonsense on screen (a "0 m" average, a ring with "—"), cut lines and words broken in two. No fast is left running
 * (the Fasting screen's own shots, FastingScreensTest, cover a running one).
 * Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class GoalsScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London
    private val dayMs = 86_400_000L
    private var clock = now

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(17), timeZone = { TimeZone.of("Europe/London") }, nowMs = { clock },
    )

    private companion object {
        const val SETTLE_MS = 3_000L
        const val MAX_SWIPES = 10
    }

    private fun shown(text: String) = compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private enum class Fill { EMPTY, ONE, MANY }

    private val longGoal = "Run a half marathon before the end of next spring"

    private fun titles(fill: Fill): List<String> = when (fill) {
        Fill.EMPTY -> emptyList()
        Fill.ONE -> listOf("Read 20 pages")
        // A13 (walk-through 2026-11-15): the Habits screen's eight habits (ShotLife), not three of Goals' own.
        Fill.MANY -> ShotLife.HABITS.map { it.name } + listOf(longGoal, "Learn Spanish")
    }

    private fun shoot(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK) {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core()
        context.useCoreForScreens(core)
        clock = now
        runBlocking {
            core.tick()
            if (fill == Fill.ONE) core.addHabit("Read 20 pages", 5, HabitTiming.EVENING, 20, null)
            if (fill == Fill.MANY) {
                // The Habits screen's habits, added six days ago and marked the same (3 of 8 done today), so "Open
                // Habits ›" leads to the habits Goals lists.
                clock = now - 6 * dayMs
                val habits = ShotLife.addHabits(core)
                clock = now
                ShotLife.markHabits(core, habits, now / dayMs)
                val race = core.addGoal(longGoal, "21 km", GoalHorizon.YEAR)
                core.setGoalProgress(race, 40)
                core.addGoal("Learn Spanish", null, GoalHorizon.QUARTER)
                // One habit counts towards a goal, as before.
                core.setHabitGoal(habits.getValue("Walk 20 minutes"), race)
                // A 16-hour fast, started yesterday evening and ended now.
                core.startFast(16 * 60)
                core.endFast()
            }
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    GoalsRoute(core)
                }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("goals", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertReadable(compose, name, titles(fill), mustShow = false)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertShows(compose, name, "Goals")
        // Walk-through week 5, A17: no open "New habit…" / "New goal…" box on the page, only the rows that open a sheet.
        listOf("New habit…", "New goal…", os.meka.core.domain.StudyRules.ADD_COURSE_HINT, GoalAddRules.habitSheet.spokenHint, GoalAddRules.goalSheet.spokenHint, GoalAddRules.courseSheet.spokenHint).forEach { hint ->
            if (compose.onAllNodes(hasContentDescription(hint) and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()) fail("$name: an open \"$hint\" box on the page")
        }
        // Walk-through 2026-11-15 A17: never wider than Study's, Habits' and Fasting's column; centred on the open Fold.
        val list = compose.onNodeWithTag(GOALS_LIST_TAG).getUnclippedBoundsInRoot()
        val screen = compose.onNodeWithTag("screen").getUnclippedBoundsInRoot()
        assertTrue(list.right - list.left <= GOALS_COLUMN_MAX + 1.dp, "$name: Goals runs ${list.right - list.left} wide")
        assertTrue(abs(((list.left - screen.left) - (screen.right - list.right)).value) <= 2f, "$name: Goals isn't centred")
        // No fast runs in any fill: the small Fasting tile (Fasting rebuild slice 2) says so and when the next begins.
        ScreenShots.assertShows(compose, name, "Not fasting")
        ScreenShots.assertShows(compose, name, "Next fast from")
        when (fill) {
            // Nothing set up: the pace line says what a habit does, never a bare heading.
            Fill.EMPTY -> ScreenShots.assertShows(compose, name, "Add a habit")
            // With habits the pace line under the title counts them (the habits sit under the Fasting tile and may
            // be below the fold, so they are scrolled to and read below).
            else -> core.goalsView.value.paceLine?.let { ScreenShots.assertShows(compose, name, it) }
        }
        if (fill == Fill.EMPTY) return
        // Down to the habits: each whole, nothing cut or broken there either.
        // Swiped by finger, a bounded number of times: the test clock is paused, so a semantics scroll (which animates)
        // would never move.
        val habits = titles(fill).filter { !it.startsWith("Run a") && it != "Learn Spanish" }
        var swipes = 0
        while (habits.any { !shown(it) } && swipes < MAX_SWIPES) {
            compose.onNodeWithTag("screen").performTouchInput { swipeUp(startY = bottom * 0.8f, endY = bottom * 0.45f, durationMillis = 400) }
            compose.mainClock.advanceTimeBy(SETTLE_MS)
            compose.waitForIdle()
            swipes++
        }
        habits.forEach { ScreenShots.assertShows(compose, name, it) }
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertReadable(compose, name, titles(fill), mustShow = false)
        ScreenShots.assertNoBrokenWords(compose, name)
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverEmpty() = shoot("cover-dark-empty", Fill.EMPTY)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverOne() = shoot("cover-dark-one", Fill.ONE)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverMany() = shoot("cover-dark-many", Fill.MANY)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverManyLight() = shoot("cover-light-many", Fill.MANY, ThemeChoice.LIGHT)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerEmpty() = shoot("inner-dark-empty", Fill.EMPTY)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerOne() = shoot("inner-dark-one", Fill.ONE)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerMany() = shoot("inner-dark-many", Fill.MANY)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerManyLight() = shoot("inner-light-many", Fill.MANY, ThemeChoice.LIGHT)

    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun sheetOpen() = compose.onAllNodes(hasTestTag(GOAL_ADD_SHEET_TAG), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(what: String, ok: () -> Boolean) {
        repeat(40) {
            if (ok()) return
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        if (!ok()) fail("Goals: $what")
    }

    /** The sheet is a dialog, a second root: tap across both by what a screen reader says. */
    private fun tapSaid(said: String) {
        val n = compose.onAllNodes(hasContentDescription(said) and hasClickAction(), useUnmergedTree = true)
        if (n.fetchSemanticsNodes().isEmpty()) fail("Goals: nothing to tap says \"$said\"")
        n[0].performSemanticsAction(SemanticsActions.OnClick)
        turns(1_000)
    }

    private fun tapText(text: String) {
        val n = compose.onAllNodes(hasText(text) and hasClickAction(), useUnmergedTree = true)
        if (n.fetchSemanticsNodes().isEmpty()) fail("Goals: no \"$text\" to tap")
        n[0].performSemanticsAction(SemanticsActions.OnClick)
        turns(500)
    }

    private fun typeInto(spoken: String, text: String) {
        val f = compose.onAllNodes(hasContentDescription(spoken) and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText), useUnmergedTree = true)
        if (f.fetchSemanticsNodes().isEmpty()) fail("Goals: the sheet has no \"$spoken\" field")
        f[0].performSemanticsAction(SemanticsActions.SetText) { it(androidx.compose.ui.text.AnnotatedString(text)) }
        turns(300)
    }

    private fun addEnabled(sheetTitle: String) = compose.onAllNodes(hasContentDescription("Add, $sheetTitle"), useUnmergedTree = true)
        .fetchSemanticsNodes().firstOrNull()?.config?.contains(SemanticsProperties.Disabled) == false

    /**
     * By hand (walk-through week 5, A17): + New habit opens its sheet; Add is dark with no name; a name, "3 a week" and
     * Evening add the habit with those choices and the sheet goes. + New goal likewise with what done looks like and
     * This year. Close leaves nothing added.
     */
    @Test @Config(qualifiers = ScreenShots.INNER)
    fun addByHand() {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, ThemeChoice.DARK)
        val core = core()
        context.useCoreForScreens(core)
        clock = now
        runBlocking { core.tick() }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { GoalsRoute(core) }
            }
        }
        turns()
        compose.onNodeWithTag(GOALS_LIST_TAG).performScrollToKey("add-habit")
        turns()
        // Close leaves nothing added.
        compose.onNodeWithTag(GOALS_ADD_HABIT_TAG, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        turns(1_000)
        if (!sheetOpen()) fail("Goals: + New habit opened no sheet")
        if (addEnabled(GoalAddRules.habitSheet.title)) fail("Goals: the habit sheet's Add is lit with no name")
        tapText(GoalAddRules.CLOSE)
        waitFor("Close left the habit sheet open") { !sheetOpen() }
        if (core.goalsView.value.habits.isNotEmpty()) fail("Goals: Close added a habit")

        compose.onNodeWithTag(GOALS_ADD_HABIT_TAG, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        turns(1_000)
        typeInto(GoalAddRules.habitSheet.spokenHint, "  Stretch for ten minutes ")
        if (!addEnabled(GoalAddRules.habitSheet.title)) fail("Goals: Add stayed dark with a name")
        tapText("3 a week")
        tapText("Evening")
        tapSaid("Add, ${GoalAddRules.habitSheet.title}")
        waitFor("the habit wasn't added") { core.goalsView.value.habits.any { it.title == "Stretch for ten minutes" } }
        val h = core.goalsView.value.habits.first { it.title == "Stretch for ten minutes" }
        if (h.targetPerWeek != 3 || h.timing != HabitTiming.EVENING) fail("Goals: the habit was added as ${h.targetPerWeek} a week, ${h.timing}")
        waitFor("the habit sheet stayed open after Add") { !sheetOpen() }

        compose.onNodeWithTag(GOALS_LIST_TAG).performScrollToKey("add-goal")
        turns()
        compose.onNodeWithTag(GOALS_ADD_GOAL_TAG, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        turns(1_000)
        if (!sheetOpen()) fail("Goals: + New goal opened no sheet")
        typeInto(GoalAddRules.goalSheet.spokenHint, "Swim a mile")
        typeInto(GoalAddRules.DONE_LOOKS_LIKE, "1.6 km without stopping")
        tapText(GoalRulesLabels.YEAR)
        tapSaid("Add, ${GoalAddRules.goalSheet.title}")
        waitFor("the goal wasn't added") { core.goalsView.value.goals.any { it.title == "Swim a mile" } }
        val g = core.goalsView.value.goals.first { it.title == "Swim a mile" }
        if (g.target != "1.6 km without stopping" || g.horizon != GoalHorizon.YEAR) fail("Goals: the goal was added as ${g.target}, ${g.horizon}")
        waitFor("the goal sheet stayed open after Add") { !sheetOpen() }
    }

    private fun choiceSheetOpen() = compose.onAllNodes(hasTestTag(NOTIFY_CHOICE_SHEET_TAG), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    /**
     * By hand (walk-through week 5, A17): a habit's settings on Goals are rows opening sheets, not open chip rows. Its
     * How often row opens a sheet saying what each target does; 3 a week saves and closes it; Book my sessions On brings
     * its Rotation row; Towards links the goal.
     */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun habitSettingsByHand() {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, ThemeChoice.DARK)
        val core = core()
        context.useCoreForScreens(core)
        clock = now
        val (id, goal) = runBlocking {
            core.tick()
            val id = core.addHabit("Read 20 pages", 5, HabitTiming.EVENING, 20, null)
            val goal = core.addGoal("Swim a mile", null, GoalHorizon.YEAR)
            core.tick()
            id to goal
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { GoalsRoute(core) }
            }
        }
        turns()
        fun h() = core.goalsView.value.habits.first { it.id == id }
        compose.onNodeWithTag(GOALS_LIST_TAG).performScrollToKey("h-$id")
        turns()
        val open = compose.onAllNodes(hasText("Read 20 pages") and hasClickAction())
        if (open.fetchSemanticsNodes().isEmpty()) fail("Goals: the habit has nothing to tap to show its settings")
        open[0].performSemanticsAction(SemanticsActions.OnClick)
        turns()
        // No open chip of any setting on the page: each is behind its row.
        listOf("Twice a week", "Morning", "45 min", "No goal").forEach { label ->
            if (compose.onAllNodes(hasText(label) and hasClickAction() and !hasTestTag(NOTIFY_CHOICE_ROW_TAG), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
                fail("Goals: a \"$label\" chip on the page; it belongs in its row's sheet")
        }
        tapSaid(GoalHabitChoiceRules.oftenRow(h()).spoken)
        waitFor("How often opened no sheet") { choiceSheetOpen() }
        val three = GoalHabitChoiceRules.oftenSheet(h()).choices.first { it.name == "3" }
        if (!three.line.contains("weekly streak")) fail("Goals: 3 a week says \"${three.line}\"")
        tapSaid(three.spoken)
        waitFor("3 a week didn't save") { h().targetPerWeek == 3 }
        waitFor("the sheet stayed open after a choice") { !choiceSheetOpen() }

        tapSaid(GoalHabitChoiceRules.bookRow(h()).spoken)
        waitFor("Book my sessions opened no sheet") { choiceSheetOpen() }
        tapSaid(GoalHabitChoiceRules.bookSheet(h()).choices.first { it.name == GoalHabitChoiceRules.ON }.spoken)
        waitFor("booking didn't turn on") { h().booked }
        waitFor("no Rotation row once booked") {
            compose.onAllNodes(hasContentDescription(GoalHabitChoiceRules.rotationRow(h()).spoken), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

        val goals = core.goalsView.value.goals
        tapSaid(GoalHabitChoiceRules.towardsRow(h(), goals).spoken)
        waitFor("Towards opened no sheet") { choiceSheetOpen() }
        tapSaid(GoalHabitChoiceRules.towardsSheet(h(), goals).choices.first { it.name == goal }.spoken)
        waitFor("Towards didn't link the goal") { h().goalId == goal }
        waitFor("the Towards sheet stayed open") { !choiceSheetOpen() }
    }

    /**
     * By hand (walk-through week 5, A17): a goal's own settings on Goals are rows opening sheets, not open chip rows.
     * Horizon opens a sheet saying when each ends and This quarter saves; Done by's end of year saves; Under puts it
     * beneath the bigger goal; a number goal's Counts a habit links the habit's ticks. Each sheet closes on a choice.
     */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun goalSettingsByHand() {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, ThemeChoice.DARK)
        val core = core()
        context.useCoreForScreens(core)
        clock = now
        val (id, big, habit) = runBlocking {
            core.tick()
            val big = core.addGoal("Get fit", null, GoalHorizon.YEAR)
            val id = core.addGoal("Swim a mile", null, GoalHorizon.MONTH)
            val habit = core.addHabit("Swim", 3, HabitTiming.MORNING, 30, null)
            core.setGoalNumber(id, "64 lengths")
            core.tick()
            Triple(id, big, habit)
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { GoalsRoute(core) }
            }
        }
        turns()
        fun g() = core.goalsView.value.goals.first { it.id == id }
        val today = core.todayEpochDay()
        compose.onNodeWithTag(GOALS_LIST_TAG).performScrollToKey("g-$id")
        turns()
        val open = compose.onAllNodes(hasText("Swim a mile") and hasClickAction())
        if (open.fetchSemanticsNodes().isEmpty()) fail("Goals: the goal has nothing to tap to show its settings")
        open[0].performSemanticsAction(SemanticsActions.OnClick)
        turns()
        // No open chip of any setting on the page: each is behind its row.
        listOf("This quarter", "Someday", "No date", "Nothing", "No habit").forEach { label ->
            if (compose.onAllNodes(hasText(label) and hasClickAction() and !hasTestTag(NOTIFY_CHOICE_ROW_TAG), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
                fail("Goals: a \"$label\" chip on the page; it belongs in its row's sheet")
        }

        tapSaid(GoalSettingChoiceRules.horizonRow(g()).spoken)
        waitFor("Horizon opened no sheet") { choiceSheetOpen() }
        val quarter = GoalSettingChoiceRules.horizonSheet(g(), today).choices.first { it.name == GoalHorizon.QUARTER.name }
        if (!quarter.line.startsWith("Ends ")) fail("Goals: This quarter says \"${quarter.line}\"")
        tapSaid(quarter.spoken)
        waitFor("This quarter didn't save") { g().horizon == GoalHorizon.QUARTER }
        waitFor("the Horizon sheet stayed open after a choice") { !choiceSheetOpen() }

        tapSaid(GoalSettingChoiceRules.dueRow(g(), today).spoken)
        waitFor("Done by opened no sheet") { choiceSheetOpen() }
        val endOfYear = GoalSettingChoiceRules.dueSheet(g(), today).choices.last()
        tapSaid(endOfYear.spoken)
        waitFor("end of year didn't save") { g().dueDay?.toString() == endOfYear.name }
        waitFor("the Done by sheet stayed open") { !choiceSheetOpen() }

        val parents = GoalRules.parentChoices(core.goalsView.value.goals, id)
        tapSaid(GoalSettingChoiceRules.underRow(g()).spoken)
        waitFor("Under opened no sheet") { choiceSheetOpen() }
        tapSaid(GoalSettingChoiceRules.underSheet(g(), parents).choices.first { it.name == big }.spoken)
        waitFor("Under didn't put it beneath Get fit") { g().parentId == big }
        waitFor("the Under sheet stayed open") { !choiceSheetOpen() }

        compose.onNodeWithTag(GOALS_LIST_TAG).performScrollToKey("g-$id")
        turns()
        val habits = core.goalsView.value.habits
        tapSaid(GoalSettingChoiceRules.habitRow(g(), habits).spoken)
        waitFor("Counts a habit opened no sheet") { choiceSheetOpen() }
        tapSaid(GoalSettingChoiceRules.habitSheet(g(), habits).choices.first { it.name == habit }.spoken)
        waitFor("the habit's ticks didn't count") { g().metric?.habitId == habit }
        waitFor("the habit sheet stayed open") { !choiceSheetOpen() }
    }

    /**
     * By hand (walk-through week 5, A17): a goal's What done looks like and Why it matters are rows opening sheets, not
     * open boxes. Neither box is on the open goal; each row opens its sheet with the words kept, Save dark until they
     * change; Save keeps the new words and the row shows them; blank clears them.
     */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun goalWordsByHand() {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, ThemeChoice.DARK)
        val core = core()
        context.useCoreForScreens(core)
        clock = now
        val id = runBlocking {
            core.tick()
            val id = core.addGoal("Run a 10k", "Under an hour", GoalHorizon.MONTH)
            core.tick()
            id
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { GoalsRoute(core) }
            }
        }
        turns()
        fun g() = core.goalsView.value.goals.first { it.id == id }
        fun saveDark(title: String) = compose.onAllNodes(hasContentDescription("${GoalTextRules.SAVE}, $title"), useUnmergedTree = true)
            .fetchSemanticsNodes().firstOrNull()?.config?.contains(SemanticsProperties.Disabled) != false
        compose.onNodeWithTag(GOALS_LIST_TAG).performScrollToKey("g-$id")
        turns()
        val open = compose.onAllNodes(hasText("Run a 10k") and hasClickAction())
        if (open.fetchSemanticsNodes().isEmpty()) fail("Goals: the goal has nothing to tap to show its settings")
        open[0].performSemanticsAction(SemanticsActions.OnClick)
        turns()
        // The goal's other boxes (milestones, steps, linking a task) come in later slices; these two are rows now.
        listOf("Say what done looks like (optional)", "Say why, in a line (optional)").forEach { hint ->
            if (compose.onAllNodes(hasContentDescription(hint) and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()) fail("Goals: \"$hint\" is an open box on the open goal")
        }
        if (sheetOpen()) fail("Goals: a sheet is open before a row is tapped")

        tapSaid(GoalAddRules.rowSpoken(GoalTextRules.targetRow(g())))
        if (!sheetOpen()) fail("Goals: What done looks like opened no sheet")
        if (!saveDark(GoalTextRules.TARGET_LABEL)) fail("Goals: Save is lit before the words change")
        typeInto(GoalTextRules.targetSheet.spokenHint, "Under 55 minutes")
        if (saveDark(GoalTextRules.TARGET_LABEL)) fail("Goals: Save stayed dark with new words")
        tapSaid("${GoalTextRules.SAVE}, ${GoalTextRules.TARGET_LABEL}")
        waitFor("What done looks like didn't save") { g().target == "Under 55 minutes" }
        waitFor("the What done looks like sheet stayed open") { !sheetOpen() }

        compose.onNodeWithTag(GOALS_LIST_TAG).performScrollToKey("g-$id")
        turns()
        tapSaid(GoalAddRules.rowSpoken(GoalTextRules.whyRow(g())))
        if (!sheetOpen()) fail("Goals: Why it matters opened no sheet")
        typeInto(GoalTextRules.whySheet.spokenHint, "  More energy  ")
        tapSaid("${GoalTextRules.SAVE}, ${GoalTextRules.WHY_LABEL}")
        waitFor("Why it matters didn't save") { g().why == "More energy" }
        waitFor("the Why it matters sheet stayed open") { !sheetOpen() }
        waitFor("the row doesn't show the why") {
            compose.onAllNodes(hasContentDescription(GoalAddRules.rowSpoken(GoalTextRules.whyRow(g()))), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }

        tapSaid(GoalAddRules.rowSpoken(GoalTextRules.whyRow(g())))
        typeInto(GoalTextRules.whySheet.spokenHint, "")
        tapSaid("${GoalTextRules.SAVE}, ${GoalTextRules.WHY_LABEL}")
        waitFor("a blank Why it matters didn't clear it") { g().why == null }
    }

    /**
     * By hand (walk-through week 5, A17): Study's "+ Add course" row opens a sheet, not an open box; Add is dark with no
     * name and with "Course:" alone; a name adds the course (its pane opens next) and the sheet goes.
     */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun addCourseByHand() {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, ThemeChoice.DARK)
        val core = core()
        context.useCoreForScreens(core)
        clock = now
        runBlocking { core.tick() }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { GoalsRoute(core) }
            }
        }
        turns()
        compose.onNodeWithTag(GOALS_LIST_TAG).performScrollToKey("study")
        turns()
        if (compose.onAllNodes(hasText(GoalAddRules.courseRow.label), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            fail("Goals: Study has no \"${GoalAddRules.courseRow.label}\" row")
        compose.onNodeWithTag(STUDY_ADD_COURSE_TAG, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        turns(1_000)
        if (!sheetOpen()) fail("Goals: + Add course opened no sheet")
        if (addEnabled(GoalAddRules.courseSheet.title)) fail("Goals: the course sheet's Add is lit with no name")
        typeInto(GoalAddRules.courseSheet.spokenHint, "Course: ")
        if (addEnabled(GoalAddRules.courseSheet.title)) fail("Goals: the course sheet's Add is lit for \"Course:\" alone")
        tapText(GoalAddRules.CLOSE)
        waitFor("Close left the course sheet open") { !sheetOpen() }
        if (core.studyView.value.courses.isNotEmpty()) fail("Goals: Close added a course")

        compose.onNodeWithTag(STUDY_ADD_COURSE_TAG, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        turns(1_000)
        typeInto(GoalAddRules.courseSheet.spokenHint, "  Data analytics ")
        if (!addEnabled(GoalAddRules.courseSheet.title)) fail("Goals: Add stayed dark with a course name")
        tapSaid("Add, ${GoalAddRules.courseSheet.title}")
        waitFor("the course wasn't added") { core.studyView.value.courses.any { it.title == "Data analytics" } }
        waitFor("the course sheet stayed open after Add") { !sheetOpen() }
    }

    private object GoalRulesLabels { val YEAR = os.meka.core.domain.GoalRules.horizonLabel(GoalHorizon.YEAR) }
}
