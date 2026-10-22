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
import os.meka.android.ask.AskRoute
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.HabitTiming
import os.meka.core.domain.LifeTileRules
import os.meka.core.domain.ObligationKind
import os.meka.core.domain.RenewalRepeat
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random

/**
 * The quality gate, slice 7 (build plan: "the same shots for every other screen, Ask next"): the Ask tab on the Fold's
 * cover screen (2 tile columns) and inner screen (3 columns), with nothing set up, with one thing (one item to buy) and
 * with a full life (shopping, a habit, dinners, an errand, a birthday, a trip, a renewal, someone waited on), Dark and
 * Light. Saved under `ask/` (the `fold-screens` artifact, then the `screens` branch's docs/screens/fold/ask/) and read
 * for nonsense on screen and for any short line cut before it can be read (a tile's line, a row's line). Invented data
 * only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class AskScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(11), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    private fun shoot(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK) {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core()
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            if (fill != Fill.EMPTY) core.addShopping("Milk")
            if (fill == Fill.MANY) {
                core.addShopping("Bread")
                core.addShopping("Washing-up liquid")
                core.addHabit("Read 20 pages", 5, HabitTiming.EVENING, 20, null)
                core.addMeal("Fish pie")
                core.addErrand("Return the parcel to the post office")
                core.addOccasion("Sam's birthday 3 Nov")
                core.addTrip("Lisbon 12 Nov to 15 Nov")
                core.addRenewal("Home insurance", ObligationKind.INSURANCE, core.todayEpochDay() + 60, RenewalRepeat.YEARLY, null, null)
                core.addWaiting("The quote for the fence", "Alex", 3)
            }
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    AskRoute(core, connected = true, openPlace = {}, openItem = {})
                }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("ask", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
        ScreenShots.assertShows(compose, name, "Ask")
        when (fill) {
            // Nothing set up: one line says so over the open set-up group, never an empty grid.
            Fill.EMPTY -> ScreenShots.assertShows(compose, name, LifeTileRules.WELCOME)
            Fill.ONE -> ScreenShots.assertShows(compose, name, "1 to buy")
            Fill.MANY -> ScreenShots.assertShows(compose, name, "3 to buy")
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
