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
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(11), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private companion object {
        const val SETTLE_MS = 3_000L
        /** A two-line title adds ~22 dp to the mockup's ~122 dp card. */
        const val UP_NEXT_MAX_DP = 150f
        /** Titles that may take two lines on the cover screen, so their rows are taller by right. */
        val LONG_TITLES = listOf("Plan Saturday's football run with Alex")
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
        val core = core()
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
