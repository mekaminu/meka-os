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
import os.meka.android.fold.NOW_CHIP_TAG
import os.meka.android.today.TodayRoute
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

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    /** Today's open tasks in each fill (the done one and yesterday's are checked for nonsense, not for their cut). */
    private fun titles(fill: Fill): List<String> = when (fill) {
        Fill.EMPTY -> emptyList()
        Fill.ONE -> listOf("Book the MOT")
        Fill.MANY -> listOf("Book the MOT", "Fix the gate latch", "Pay the window cleaner", "Plan Saturday's football run with Alex")
    }

    private fun shoot(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK) {
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
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("today", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertReadable(compose, name, titles(fill), mustShow = false)
        // The re-shot's findings (2026-10-22): Up next's chips on one row, and the greeting by the core's clock (17:15).
        ScreenShots.assertOneRow(compose, name, NOW_CHIP_TAG)
        ScreenShots.assertShows(compose, name, "Good afternoon")
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
