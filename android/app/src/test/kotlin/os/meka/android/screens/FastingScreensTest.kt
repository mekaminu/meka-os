package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
import os.meka.android.goals.FastingPane
import os.meka.android.goals.GoalsRoute
import os.meka.android.goals.HISTORY_ROW_TAG
import os.meka.android.goals.STAGE_ROW_TAG
import os.meka.core.domain.FastingBesideRules
import os.meka.core.domain.FastingRules
import os.meka.core.domain.FastingScreenRules
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * Fasting rebuild slice 2 with the quality gate: the Fasting screen (docs/design/fasting/fasting.dc.html) on the Fold's
 * cover and inner screens, with no fast ever, with a fast running 13½ hours and with a full week (four finished
 * fasts, one short, and one running), Dark and Light; saved under `fasting/` and read for nonsense, cut lines and
 * broken words. Then "works by hand": Goals' tile opens it; Start fast becomes End fast; the Started tile, a stage and
 * More options each open what they promise. The screen reads the core's clock, so a running fast shows the test's own
 * times. Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class FastingScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London
    private val hourMs = 3_600_000L
    private var clock = now

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(23), timeZone = { TimeZone.of("Europe/London") }, nowMs = { clock },
    )

    private companion object {
        const val SETTLE_MS = 3_000L
        const val MAX_SWIPES = 8
    }

    private enum class Fill { EMPTY, ONE, MANY }

    private fun shown(text: String) = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun settle() {
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
    }

    private fun prepared(fill: Fill, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        clock = now
        val core = core()
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            if (fill == Fill.MANY) {
                // Four evenings this week, 20:00 → the next day: 16h 40m, 9h 30m (short), 17h 10m, 16h 05m.
                listOf(16 * 60 + 40, 9 * 60 + 30, 17 * 60 + 10, 16 * 60 + 5).forEachIndexed { i, minutes ->
                    clock = now - (5 - i) * 24 * hourMs + (20 - 17) * hourMs - 15 * 60_000L // that day's 20:00
                    core.startFast(0)
                    clock += minutes * 60_000L
                    core.endFast()
                }
                clock = now
            }
            // A fast running 13½ hours: started at 03:45 today.
            if (fill != Fill.EMPTY) core.startFast(13 * 60 + 30)
            core.tick()
        }
        return core
    }

    private fun shoot(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK) {
        stageRowsSeen = 0
        val core = prepared(fill, theme)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    FastingPane(core, onClose = {})
                }
            }
        }
        settle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("fasting", name))
        check(name)
        ScreenShots.assertShows(compose, name, "Fasting")
        ScreenShots.assertShows(compose, name, "16:8")
        when (fill) {
            Fill.EMPTY -> {
                // No fast: the ring shows the plan's goal and when the next one starts, never a dash; Start fast.
                ScreenShots.assertShows(compose, name, "16 h")
                ScreenShots.assertShows(compose, name, FastingRules.IDLE_RING_CAPTION)
                ScreenShots.assertShows(compose, name, "Next fast from 20:00")
                ScreenShots.assertShows(compose, name, "Start fast")
            }
            else -> {
                // The Started and Goal tiles keep one height (the first shots: the Goal tile's pencil wrapped alone).
                val tiles = compose.onAllNodes(hasTestTag("fasting-edit-tile"), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot.height }
                if (tiles.size != 2 || kotlin.math.abs(tiles[0] - tiles[1]) > 1f) fail("$name: the Started and Goal tiles differ in height ($tiles)")
                ScreenShots.assertShows(compose, name, "13:30")
                ScreenShots.assertShows(compose, name, "Ends in 2:30")
                ScreenShots.assertShows(compose, name, "End fast")
                ScreenShots.assertShows(compose, name, "Started")
            }
        }
        // Down the screen: the stages, this week and History, each read the same way.
        val last = when (fill) {
            Fill.EMPTY -> FastingScreenRules.HISTORY_EMPTY
            Fill.ONE -> FastingScreenRules.HISTORY_EMPTY
            Fill.MANY -> "16h 40m"
        }
        var swipes = 0
        while (!shown(last) && swipes < MAX_SWIPES) {
            compose.onNodeWithTag("screen").performTouchInput { swipeUp(startY = bottom * 0.85f, endY = bottom * 0.25f, durationMillis = 400) }
            settle()
            swipes++
            check(name)
        }
        ScreenShots.assertShows(compose, name, last)
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("fasting", "$name-below"))
        when (fill) {
            // No fast this week: a line where the rings go, never seven empty rings.
            Fill.EMPTY -> ScreenShots.assertShows(compose, name, FastingScreenRules.WEEK_EMPTY)
            Fill.ONE -> ScreenShots.assertShows(compose, name, "Wed, fasting now")
            Fill.MANY -> {
                ScreenShots.assertShows(compose, name, "on goal")
                ScreenShots.assertShows(compose, name, "short")
                // Each History span on one line, even beside "6h 30m short" (the first shots' finding).
                ScreenShots.assertOneLine(compose, name, "→")
            }
        }
        if (stageRowsSeen == 0) fail("$name: no stage row was ever on screen to measure")
    }

    /** The most stage rows seen measured in one look, over a shot's scroll. */
    private var stageRowsSeen = 0

    /**
     * Beside the mockup (2026-10-30, FastingBesideRules): every closed stage and History row is the 48 dp touch target
     * (at most [FastingBesideRules.ROW_MAX_DP]), and stage rows sit [FastingBesideRules.ROW_GAP_DP] dp apart (they
     * were 64 dp tall, 16 dp apart).
     */
    private fun assertRowsAsTheMockup(name: String) {
        val density = ApplicationProvider.getApplicationContext<ShotApplication>().resources.displayMetrics.density
        for (tag in listOf(STAGE_ROW_TAG, HISTORY_ROW_TAG)) {
            val rows = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
            if (tag == STAGE_ROW_TAG) stageRowsSeen = maxOf(stageRowsSeen, rows.size)
            rows.forEach { r ->
                val dp = r.height / density
                if (dp > FastingBesideRules.ROW_MAX_DP) fail("$name: a $tag is $dp dp tall (at most ${FastingBesideRules.ROW_MAX_DP})")
            }
            // Gaps only between rows wholly on screen (a row cut by the screen's edge reports its visible part).
            rows.filter { it.height / density >= FastingBesideRules.ROW_DP - 1 }.sortedBy { it.top }.zipWithNext { a, b ->
                val gap = (b.top - a.bottom) / density
                if (kotlin.math.abs(gap - FastingBesideRules.ROW_GAP_DP) > 1f) fail("$name: $gap dp between two ${tag}s (the mockup's ${FastingBesideRules.ROW_GAP_DP})")
            }
        }
    }

    private fun check(name: String) {
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertReadable(compose, name, FastingScreenRules.STAGES.map { it.name }, mustShow = false)
        ScreenShots.assertNoBrokenWords(compose, name)
        assertRowsAsTheMockup(name)
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

    // ---- Works by hand (the quality gate's (3)) ----

    private fun tap(text: String) {
        val nodes = compose.onAllNodes(hasText(text), useUnmergedTree = false)
        if (nodes.fetchSemanticsNodes().isEmpty()) fail("\"$text\" isn't on screen to tap")
        nodes[0].performClick()
        settle()
    }

    /**
     * A button's command runs on the core's own thread (MekaCore's confined dispatcher), off the paused test clock:
     * wait for it in real time (up to 3 s), then let the screen settle.
     */
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

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun goalsTileOpensTheScreenAndBackLeavesIt() {
        val core = prepared(Fill.EMPTY)
        compose.mainClock.autoAdvance = false
        compose.setContent { MekaTheme { GoalsRoute(core) } }
        settle()
        // The tile is small: the habits' heading shows on the cover screen without scrolling.
        ScreenShots.assertShows(compose, "goals", "Not fasting")
        ScreenShots.assertShows(compose, "goals", "HABITS")
        compose.onNodeWithTag("fasting-tile").performClick()
        settle()
        ScreenShots.assertShows(compose, "goals", "‹ Goals and habits")
        ScreenShots.assertShows(compose, "goals", "Start fast")
        tap("‹ Goals and habits")
        if (shown("‹ Goals and habits")) fail("the Fasting screen didn't close")
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun startEndTilesStagesAndMoreOptionsAllDoSomething() {
        val core = prepared(Fill.EMPTY)
        compose.mainClock.autoAdvance = false
        compose.setContent { MekaTheme { FastingPane(core, onClose = {}) } }
        settle()
        // More options: a fast started earlier, longer fasts with the GP note first.
        tap(FastingScreenRules.MORE_OPTIONS)
        ScreenShots.assertShows(compose, "more", "Started earlier")
        ScreenShots.assertShows(compose, "more", "check with your GP first")
        tap("Close")
        // The plan chip opens the plans.
        tap("16:8")
        ScreenShots.assertShows(compose, "plan", "18:6")
        tap("16:8")
        // Start fast: the button becomes End fast and the Started and Goal tiles appear.
        compose.onNodeWithTag("fasting-main").performClick()
        awaitCore("Start fast") { core.fastingView.value.current != null }
        ScreenShots.assertShows(compose, "start", "End fast")
        ScreenShots.assertShows(compose, "start", "Goal")
        // The Started tile opens its choices.
        compose.onAllNodes(hasText("Started", substring = false), useUnmergedTree = true).fetchSemanticsNodes()
            .ifEmpty { fail("no Started tile") }
        tap("Started")
        ScreenShots.assertShows(compose, "tile", "Move the start")
        // Two hours on, End fast ends it and offers Undo end.
        clock += 2 * hourMs
        compose.onNodeWithTag("fasting-main").performClick()
        awaitCore("End fast") { core.fastingView.value.current == null }
        ScreenShots.assertShows(compose, "end", "Start fast")
        ScreenShots.assertShows(compose, "end", FastingScreenRules.UNDO_END)
        // Down to the stages: one opens its words and evidence.
        var swipes = 0
        while (!shown("Switching to fat") && swipes < MAX_SWIPES) {
            compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.85f, endY = bottom * 0.45f, durationMillis = 400) }
            settle()
            swipes++
        }
        tap("Switching to fat")
        ScreenShots.assertShows(compose, "stage", "As glycogen runs low")
    }
}
