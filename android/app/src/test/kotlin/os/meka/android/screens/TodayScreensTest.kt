package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.fail
import os.meka.android.goals.FASTING_TODAY_TAG
import os.meka.core.domain.FastingScreenRules
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
import os.meka.android.fold.NOW_CHIP_TAG
import os.meka.android.fold.NOW_CARD_TAG
import os.meka.android.today.TASK_ROW_TAG
import os.meka.android.today.CAPTURE_BAR_TAG
import os.meka.android.today.TODAY_LIST_TAG
import os.meka.android.designsystem.MotionMath
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import os.meka.core.domain.TodayBesideRules
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import os.meka.android.today.TodayRoute
import os.meka.android.today.DoneFoldLine
import os.meka.core.domain.DoneTodayRules
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import os.meka.core.domain.HabitTiming
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.toInstant
import os.meka.core.domain.CommandCentreRules
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.EventFields
import os.meka.core.domain.MekaSchema
import os.meka.core.sync.HlcClock
import os.meka.core.sync.Replica
import os.meka.core.sync.ReplicaStore
import os.meka.core.sync.fv
import kotlin.random.Random

/**
 * The quality gate, slice 3 (build plan: "the same shots for every other screen, Today next"): Today drawn on the Fold's
 * cover screen (one column, the now card) and inner screen (the command centre: Today beside Needs you and Coming up),
 * empty, with one task and with a full day, Dark and Light. Saved under `today/` (the `fold-screens` artifact, then the
 * `screens` branch's docs/screens/fold/today/), and read for nonsense on screen ([ScreenShots.assertSensible]) and for
 * task titles cut before they can be read ([ScreenShots.assertReadable]). Invented tasks only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class TodayScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private val london = TimeZone.of("Europe/London")

    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime(2026, 10, day, h, m).toInstant(london).toEpochMilliseconds()

    private fun utcMidnight(day: Int) = LocalDateTime(2026, 10, day, 0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()

    /**
     * Walk-through 2026-11-02, A16's last piece: the open Fold's Coming up said "Nothing planned in the next 30 days" beside
     * the Calendar's week of events. The full day's shots carry the Calendar screen's coming days (the same invented events,
     * written as the server's calendar mirror does), and nothing on today, so Today's own timeline is unchanged.
     */
    private fun seedComingDays(store: ReplicaStore) {
        var n = 0
        val mirror = Replica("hh", "server", HlcClock("server", { now }), store, MekaSchema) { "seed${n++}" }
        fun event(id: String, title: String, start: Long, end: Long, calendar: String, place: String? = null, allDay: Boolean = false) =
            mirror.commitLocal(EntityTypes.EVENT, id, buildMap {
                put(EventFields.TITLE, title.fv()); put(EventFields.START_AT, start.fv()); put(EventFields.END_AT, end.fv())
                put(EventFields.PROVIDER, "google".fv()); put(EventFields.ACCOUNT, "jordan@example.com".fv())
                put(EventFields.CALENDAR, calendar.fv())
                if (allDay) put(EventFields.ALL_DAY, true.fv())
                place?.let { put(EventFields.LOCATION, it.fv()) }
            })
        event("ev4", COMING_TITLES[0], at(22, 10, 0), at(22, 11, 30), "Personal")
        event("ev5", COMING_TITLES[1], utcMidnight(23), utcMidnight(24), "Kids", allDay = true)
        event("ev6", COMING_TITLES[2], at(24, 10, 0), at(24, 11, 0), "Kids", place = "Oakfield Rec")
    }

    private fun core(store: ReplicaStore = InMemoryReplicaStore()): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = store, transport = null,
        secureRandom = Random(11), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private companion object {
        const val SETTLE_MS = 3_000L
        /** A two-line title adds ~22 dp to the mockup's ~122 dp card. */
        const val UP_NEXT_MAX_DP = 150f
        /** The setup card's label as drawn (SetupTodayCard). */
        const val SETUP_LABEL = "SET UP NAVILIFE"
        /** Titles that may take two lines on the cover screen, so their rows are taller by right. */
        val LONG_TITLES = listOf("Plan Saturday's football run with Alex")
        /** The Calendar screen's coming days (its ev4–ev6), seeded into the full day's shots. */
        val COMING_TITLES = listOf("Quarterly planning with the design and engineering leads", "Inset day", "Football training")
    }

    /** The tallest Up next / now card on screen in dp, or null when there is none. */
    private fun upNextCardHeightDp(): Float? {
        val density = ApplicationProvider.getApplicationContext<ShotApplication>().resources.displayMetrics.density
        return compose.onAllNodesWithTag(NOW_CARD_TAG, useUnmergedTree = true).fetchSemanticsNodes()
            .maxOfOrNull { it.boundsInRoot.height / density }
    }

    private enum class Fill { EMPTY, ONE, MANY, FASTING }

    /** Today's open tasks in each fill (the done one and yesterday's are checked for nonsense, not for their cut). */
    private fun titles(fill: Fill): List<String> = when (fill) {
        Fill.EMPTY -> emptyList()
        Fill.ONE, Fill.FASTING -> listOf("Book the MOT")
        Fill.MANY -> listOf("Book the MOT", "Fix the gate latch", "Pay the window cleaner", "Plan Saturday's football run with Alex")
    }

    private fun shoot(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK) {
        val core = show(fill, theme)
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("today", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertReadable(compose, name, titles(fill), mustShow = false)
        // The re-shot's findings (2026-10-22): Up next's chips on one row, and the greeting by the core's clock (17:15).
        ScreenShots.assertOneRow(compose, name, NOW_CHIP_TAG)
        // Calmer Today, slice 2 (the approved mockup): the Up next card at about half its old height (was ~185 dp with
        // a one-line title; the mockup's is ~122 dp), so it takes ~15% of the folded screen, not ~30%.
        upNextCardHeightDp()?.let { if (it > UP_NEXT_MAX_DP) fail("$name: the Up next card is $it dp tall (at most $UP_NEXT_MAX_DP)") }
        // Beside the calmer mockup (2026-10-29): a task row with a one-line title is the 48 dp target, its line under
        // it fitting inside (they were 60 dp, the tick padded 8 dp above and below).
        assertRowsCompact(name)
        ScreenShots.assertShows(compose, name, "Good afternoon")
        // The re-shot after that (2026-10-22): the greeting on one line beside the watch face, on both screens.
        ScreenShots.assertOneLine(compose, name, "Good afternoon")
        if (fill == Fill.FASTING) {
            // The live card reads like the design: the stage, when it ends, End; and the strip has no second fast tile.
            ScreenShots.assertShows(compose, name, "Switching to fat")
            // The first shots (c3582a9): on the cover screen the stage wrapped "Switching to / fat"; it reads on one line.
            ScreenShots.assertOneLine(compose, name, "Switching to fat")
            ScreenShots.assertShows(compose, name, "Ends in 2:30 · 19:45")
            ScreenShots.assertOneLine(compose, name, "Ends in 2:30")
            ScreenShots.assertShows(compose, name, "FASTING")
            if (shownText("goal 16 h")) fail("$name: the strip still has a fast tile beside the card")
        } else if (compose.onAllNodesWithTag(FASTING_TODAY_TAG).fetchSemanticsNodes().isNotEmpty()) {
            fail("$name: a Fasting card with no fast running")
        }
        check(core.fastingView.value.current != null == (fill == Fill.FASTING))
        if (name.startsWith("inner") && fill != Fill.EMPTY) assertDayLeadsInner(name, timeline = fill == Fill.ONE)
        if (fill == Fill.MANY) {
            // A16: the full day has the Calendar's coming days, so Coming up never says there's nothing ahead.
            val ahead = CommandCentreRules.comingUp(core.calendarView.value, CommandCentreRules.DAYS_SHARED, CommandCentreRules.LINES_SHARED)
            if (ahead.days.isEmpty()) fail("$name: Coming up has no days though the Calendar has events this week")
            if (name.startsWith("inner")) {
                if (shownText(CommandCentreRules.NOTHING_AHEAD)) fail("$name: Coming up says \"${CommandCentreRules.NOTHING_AHEAD}\" beside a week of events")
                ScreenShots.assertShows(compose, name, COMING_TITLES[1])
            }
        }
    }

    /**
     * Walk-through 2026-11-02, A5 (and 30 Oct's A3): the many and fasting shots stop mid-list, so the last Anytime row
     * and Up next's buttons looked to sit under the capture field. Scrolled to its end, Today's list shows its last
     * task row, every task row and Up next's buttons end above the capture bar and clear of the list's foot fade, and
     * the shot shows it.
     */
    private fun shootEnd(name: String, fill: Fill, lastTitle: String) {
        show(fill)
        compose.onNodeWithTag(TODAY_LIST_TAG).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100_000f) }
        settle()
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("today", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertShows(compose, name, lastTitle)
        val density = ApplicationProvider.getApplicationContext<ShotApplication>().resources.displayMetrics.density
        val bar = compose.onNodeWithTag(CAPTURE_BAR_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val clearBy = bar.top - MotionMath.FOOT_FADE_DP * density
        val rows = compose.onAllNodesWithTag(TASK_ROW_TAG, useUnmergedTree = true).fetchSemanticsNodes() +
            compose.onAllNodesWithTag(NOW_CHIP_TAG, useUnmergedTree = true).fetchSemanticsNodes()
        if (rows.isEmpty()) fail("$name: no task rows or Up next buttons at the list's end")
        // Today's own column only (on the open Fold Needs you and Coming up sit beside it, with no capture bar under them).
        rows.filter { it.boundsInRoot.bottom > 0f && it.boundsInRoot.center.x in bar.left..bar.right }.forEach { node ->
            if (node.boundsInRoot.bottom > clearBy + 0.5f) {
                fail("$name: a row ends at ${node.boundsInRoot.bottom / density} dp, under the capture bar's fade (${clearBy / density} dp)")
            }
        }
    }

    /**
     * Walk-through 2026-11-08, A1 (third week): on the open Fold the setup card and health line pushed Up next's buttons
     * and the TODAY · AT WORK timeline under the capture bar before any scroll. They now head the Needs you column, so
     * unscrolled, Up next's buttons (and with one task, the timeline's work row) end above the bar's fade, and the setup
     * card is not in Today's column.
     */
    private fun assertDayLeadsInner(name: String, timeline: Boolean) {
        val density = ApplicationProvider.getApplicationContext<ShotApplication>().resources.displayMetrics.density
        val bar = compose.onNodeWithTag(CAPTURE_BAR_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val clearBy = bar.top - MotionMath.FOOT_FADE_DP * density
        fun inToday(n: androidx.compose.ui.semantics.SemanticsNode) = n.boundsInRoot.center.x in bar.left..bar.right
        val chips = compose.onAllNodesWithTag(NOW_CHIP_TAG, useUnmergedTree = true).fetchSemanticsNodes().filter(::inToday)
        if (chips.isEmpty()) fail("$name: Up next's buttons aren't in Today's column")
        chips.forEach { n ->
            if (n.boundsInRoot.bottom > clearBy + 0.5f) {
                fail("$name: Up next's button ends at ${n.boundsInRoot.bottom / density} dp, under the capture bar's fade (${clearBy / density} dp)")
            }
        }
        if (timeline) {
            val work = compose.onAllNodes(hasText("09:00", substring = true), useUnmergedTree = true).fetchSemanticsNodes().filter(::inToday)
            if (work.isEmpty()) fail("$name: the timeline's work row isn't in Today's column")
            if (work.minOf { it.boundsInRoot.bottom } > clearBy + 0.5f) fail("$name: the timeline's work row is under the capture bar")
        }
        compose.onAllNodes(hasText(SETUP_LABEL), useUnmergedTree = true).fetchSemanticsNodes().forEach { n ->
            if (inToday(n)) fail("$name: the setup card is in Today's column, not beside it")
        }
    }

    /** Every task row whose title fits one line is at most [TodayBesideRules.ROW_WITH_LINE_MAX_DP] dp tall. */
    private fun assertRowsCompact(name: String) {
        val density = ApplicationProvider.getApplicationContext<ShotApplication>().resources.displayMetrics.density
        compose.onAllNodesWithTag(TASK_ROW_TAG, useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
            val words = node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
                ?: node.children.flatMap { c -> c.config.getOrNull(SemanticsProperties.Text).orEmpty() }.joinToString(" ") { it.text }
            if (LONG_TITLES.any { words.contains(it) }) return@forEach
            val dp = node.boundsInRoot.height / density
            if (dp > TodayBesideRules.ROW_WITH_LINE_MAX_DP) fail("$name: the task row \"$words\" is $dp dp tall (at most ${TodayBesideRules.ROW_WITH_LINE_MAX_DP})")
        }
    }

    private fun shownText(text: String) =
        compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun show(fill: Fill, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val store = InMemoryReplicaStore()
        if (fill == Fill.MANY) seedComingDays(store)
        val core = core(store)
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            val today = core.weekBoard.value.todayEpochDay
            when (fill) {
                Fill.EMPTY -> Unit
                Fill.ONE -> core.addOnBoardSized("Book the MOT", today, 30)
                // Fasting rebuild slice 4: a 16:8 fast 13½ hours in (started 03:45), Today's live card.
                Fill.FASTING -> {
                    core.addOnBoardSized("Book the MOT", today, 30)
                    core.startFast(810)
                }
                Fill.MANY -> {
                    core.addOnBoardSized("Order new bins", today - 1, null)
                    core.addOnBoardSized("Book the MOT", today, 30)
                    val done = core.addOnBoardSized("Reply to Oakfield School", today, 15)
                    core.addOnBoardSized("Fix the gate latch", today, 60)
                    core.addOnBoardSized("Pay the window cleaner", today, 15)
                    core.addOnBoardSized("Plan Saturday's football run with Alex", today, 120)
                    core.addHabit("Read 20 pages", 5, HabitTiming.EVENING, 20, null)
                    // Walk-through A16: the same three habits Goals has (Stretch done this morning), not one.
                    core.setHabitDone(core.addHabit("Stretch", 7, HabitTiming.MORNING, 10, null), true)
                    core.addHabit("Run", 3, HabitTiming.EVENING, 40, null)
                    done?.let { core.complete(it) }
                }
            }
            core.tick()
        }
        // The clock is driven by hand so the breathing orb and the Day ring (infinite animations) can't keep it busy.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    TodayRoute(core)
                }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        return core
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

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverFasting() = shoot("cover-dark-fasting", Fill.FASTING)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverFastingLight() = shoot("cover-light-fasting", Fill.FASTING, ThemeChoice.LIGHT)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerFasting() = shoot("inner-dark-fasting", Fill.FASTING)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverManyEnd() = shootEnd("cover-dark-many-end", Fill.MANY, "Plan Saturday's football run with Alex")

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerManyEnd() = shootEnd("inner-dark-many-end", Fill.MANY, "Plan Saturday's football run with Alex")

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerFastingEnd() = shootEnd("inner-dark-fasting-end", Fill.FASTING, "Book the MOT")

    /**
     * Walk-through 2026-11-02, A5: the now line said "5 anytime tasks" over "ANYTIME TODAY · 4". Up next is one of
     * them, so the line counts the others, the same number as the section over its rows.
     */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun theNowLineCountsWhatAnytimeTodayLists() {
        val core = show(Fill.MANY)
        val listed = core.today.value.timeline.anytime.size
        ScreenShots.assertShows(compose, "many", TodayBesideRules.anytimeLabel(listed).uppercase())
        ScreenShots.assertShows(compose, "many", "$listed more anytime task")
    }

    /** Works by hand (quality gate 3): the card opens the Fasting screen and back leaves it; End ends the fast with Undo. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun fastingCardOpensTheScreenAndEndEndsTheFast() {
        val core = show(Fill.FASTING)
        compose.onNodeWithTag(FASTING_TODAY_TAG).performClick()
        settle()
        ScreenShots.assertShows(compose, "card", "‹ Today")
        ScreenShots.assertShows(compose, "card", "End fast")
        compose.onAllNodes(hasText("‹ Today"), useUnmergedTree = false)[0].performClick()
        settle()
        if (shownText("‹ Today")) fail("the Fasting screen didn't close")
        compose.onNodeWithTag("$FASTING_TODAY_TAG-end").performClick()
        awaitCore("End") { core.fastingView.value.current == null }
        if (compose.onAllNodesWithTag(FASTING_TODAY_TAG).fetchSemanticsNodes().isNotEmpty()) fail("the card stayed after End")
        ScreenShots.assertShows(compose, "end", FastingScreenRules.TODAY_ENDED)
        // Undo on the bar brings the same fast back, and its card.
        compose.onAllNodes(hasText("Undo"), useUnmergedTree = false)[0].performClick()
        awaitCore("Undo") { core.fastingView.value.current != null }
        if (compose.onAllNodesWithTag(FASTING_TODAY_TAG).fetchSemanticsNodes().isEmpty()) fail("Undo didn't bring the card back")
    }

    /**
     * Calmer Today, slice 1, works by hand (quality gate 3): Done today is folded by default into "1 done today ›"; a tap
     * opens it for the day ("· hide", written to the core so the Mac follows) and a second tap folds it again.
     */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun doneTodayFoldOpensAndClosesByHand() {
        val core = core()
        runBlocking {
            val id = core.addTask("Reply to Oakfield School")
            core.complete(id)
            core.tick()
        }
        compose.setContent {
            MekaTheme {
                val scope = rememberCoroutineScope()
                val choice by core.doneTodayChoice.collectAsState()
                val today by core.today.collectAsState()
                val line = DoneTodayRules.foldLine(today.doneToday, choice) ?: "no line"
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    DoneFoldLine(line, DoneTodayRules.foldLabel(today.doneToday, choice),
                        { scope.launch { core.setDoneTodayOpen(!choice.openToday) } },
                        Modifier.testTag("done-fold"))
                }
            }
        }
        compose.waitForIdle()
        ScreenShots.assertShows(compose, "folded", "1 done today ›")
        compose.onNodeWithTag("done-fold").performClick()
        awaitCore("Open") { core.doneTodayChoice.value.openToday }
        ScreenShots.assertShows(compose, "opened", "1 done today · hide")
        compose.onNodeWithTag("done-fold").performClick()
        awaitCore("Close") { !core.doneTodayChoice.value.openToday }
        ScreenShots.assertShows(compose, "closed", "1 done today ›")
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
    }

    /** A command runs on the core's own thread, off the paused test clock: wait for it in real time (up to 3 s). */
    private fun awaitCore(what: String, done: () -> Boolean) {
        var waited = 0
        while (!done() && waited < 3_000) {
            Thread.sleep(20)
            waited += 20
            compose.mainClock.advanceTimeBy(16)
        }
        if (!done()) fail("$what: the core never did it")
        settle()
    }
}
