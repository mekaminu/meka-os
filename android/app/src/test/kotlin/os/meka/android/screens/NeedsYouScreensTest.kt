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
import os.meka.android.today.NeedsYouRoute
import os.meka.core.domain.NeedsYouMeanwhileRules
import os.meka.core.domain.NeedsYouStackRules
import os.meka.core.domain.ObligationKind
import os.meka.core.domain.RenewalRepeat
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random

/**
 * The quality gate, slice 6 (build plan: "the same shots for every other screen, Needs you next"): the Needs you tab on
 * the Fold's cover screen (the stack alone) and inner screen (the stack beside the task's detail), empty, with one card
 * and with a full stack (two overdue tasks, one due today with no time planned, a renewal due from Lists, a reminder
 * under Coming up), Dark and Light. Saved under `needs-you/` (the `fold-screens` artifact, then the `screens` branch's
 * docs/screens/fold/needs-you/) and read for nonsense on screen and for card titles cut before they can be read.
 * Invented tasks only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class NeedsYouScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London
    private val hour = 3_600_000L

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(11), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    /** The task cards' titles in each fill (a stack shows its top card, so the others aren't required on screen). */
    private fun titles(fill: Fill): List<String> = when (fill) {
        Fill.EMPTY -> emptyList()
        Fill.ONE -> listOf("Renew the parking permit")
        Fill.MANY -> listOf("Renew the parking permit", "Send the football kit money", "Call the garage about the brakes")
    }

    private fun shoot(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK) {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core()
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            if (fill != Fill.EMPTY) {
                val permit = core.addTask("Renew the parking permit")
                core.setDue(permit, now - 24 * hour) // yesterday 17:15
            }
            if (fill == Fill.MANY) {
                val kit = core.addTask("Send the football kit money")
                core.setDue(kit, now - 3 * 24 * hour)
                val garage = core.addTask("Call the garage about the brakes")
                core.setDue(garage, now + 2 * hour) // 19:15 today, no time planned
                core.addRenewal("Car insurance", ObligationKind.INSURANCE, core.todayEpochDay(), RenewalRepeat.YEARLY, null, null)
                val passport = core.addTask("Check the passport dates")
                core.setReminder(passport, now + 16 * hour) // 09:15 tomorrow
            }
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    NeedsYouRoute(core)
                }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("needs-you", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertReadable(compose, name, titles(fill), mustShow = false)
        ScreenShots.assertShows(compose, name, "Needs you")
        when (fill) {
            // The empty stack says so in words, never just an empty box.
            Fill.EMPTY -> ScreenShots.assertShows(compose, name, NeedsYouStackRules.EMPTY_LINE)
            // The top card is the first overdue task, and says why it's here.
            else -> {
                ScreenShots.assertShows(compose, name, titles(fill).first())
                ScreenShots.assertShows(compose, name, "Overdue")
                // The card hugs its words: its padding (24 dp) and a little more under the hint, no empty band.
                ScreenShots.assertHugs(compose, name, NeedsYouStackRules.TOP_CARD_TAG, "↑ Open", maxGapDp = 32f)
            }
        }
        // Under a stack, the next reminder is in view (Coming up), not a blank page.
        if (fill == Fill.MANY) {
            ScreenShots.assertShows(compose, name, NeedsYouMeanwhileRules.COMING_LABEL.uppercase())
            ScreenShots.assertShows(compose, name, "Check the passport dates")
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
