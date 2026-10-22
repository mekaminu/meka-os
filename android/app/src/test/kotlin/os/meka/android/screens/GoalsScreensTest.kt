package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
import os.meka.android.goals.GoalsRoute
import os.meka.core.domain.FastingRules
import os.meka.core.domain.GoalHorizon
import os.meka.core.domain.HabitTiming
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random

/**
 * The quality gate, slice 9 (build plan: "the same shots for every other screen, Goals and habits next"): the Goals
 * screen on the Fold's cover and inner screens, with nothing set up, with one habit and with a full set (three habits,
 * one ticked today and one linked to a goal, two goals with a long title, a fast that has ended), Dark and Light. Saved
 * under `goals/` (the `fold-screens` artifact, then the `screens` branch's docs/screens/fold/goals/) and read for
 * nonsense on screen (a "0 m" average, a ring with "—"), cut lines and words broken in two. No fast is left running:
 * the card's timer reads the device clock, not the core's, so a running fast would show the test's own clock gap.
 * Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class GoalsScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(17), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    private val longGoal = "Run a half marathon before the end of next spring"

    private fun titles(fill: Fill): List<String> = when (fill) {
        Fill.EMPTY -> emptyList()
        Fill.ONE -> listOf("Read 20 pages")
        Fill.MANY -> listOf("Read 20 pages", "Stretch", "Run", longGoal, "Learn Spanish")
    }

    private fun shoot(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK) {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core()
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            if (fill != Fill.EMPTY) core.addHabit("Read 20 pages", 5, HabitTiming.EVENING, 20, null)
            if (fill == Fill.MANY) {
                val race = core.addGoal(longGoal, "21 km", GoalHorizon.YEAR)
                core.setGoalProgress(race, 40)
                core.addGoal("Learn Spanish", null, GoalHorizon.QUARTER)
                val stretch = core.addHabit("Stretch", 7, HabitTiming.MORNING, 10, null)
                core.setHabitDone(stretch, true)
                core.addHabit("Run", 3, HabitTiming.EVENING, 40, race)
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
        // No fast runs in any fill: the card says so, and its ring shows the plan's goal, never a lone dash.
        ScreenShots.assertShows(compose, name, "Not fasting")
        ScreenShots.assertShows(compose, name, FastingRules.IDLE_RING_CAPTION)
        when (fill) {
            // Nothing set up: the pace line says what a habit does, never a bare heading.
            Fill.EMPTY -> ScreenShots.assertShows(compose, name, "Add a habit")
            Fill.ONE -> ScreenShots.assertShows(compose, name, "Read 20 pages")
            Fill.MANY -> {
                ScreenShots.assertShows(compose, name, "Read 20 pages")
                ScreenShots.assertShows(compose, name, "Stretch")
            }
        }
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
}
