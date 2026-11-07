package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.work.ObiCallsPane
import os.meka.core.domain.CallsSettingsRules
import os.meka.core.domain.LiveCallRules
import os.meka.core.domain.LoneWordGlue
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate for Work mode → Obi on calls (slice 4): the page on the Fold's cover and inner screens, Dark and
 * Light, read for nonsense, cut lines, broken words and whole touch targets. By hand: £30 sets the cap, Booking calls
 * switches off, Where you work lights. Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class ObiCallsScreensTest {
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object { const val SETTLE_MS = 3_000L }

    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun page(name: String, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
            secureRandom = Random(53), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            core.setCallAssistant(true)
            core.setCallLive(true)
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { ObiCallsPane(core, onClose = {}) }
            }
        }
        turns()
        compose.waitForIdle()
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("obi-calls", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertWholeTargets(compose, name)
        ScreenShots.assertShows(compose, name, CallsSettingsRules.TITLE)
        ScreenShots.assertShows(compose, name, "cap £20")
        ScreenShots.assertShows(compose, name, CallsSettingsRules.SLOTS_TITLE)
        ScreenShots.assertShows(compose, name, "Businesses only")
        ScreenShots.assertShows(compose, name, CallsSettingsRules.FOOTER)
        val lines = listOf(CallsSettingsRules.CAP_NOTE, CallsSettingsRules.NEVER, CallsSettingsRules.RECORDINGS_NOTE, CallsSettingsRules.SLOTS_SUB, CallsSettingsRules.BOOKING_SUB, LiveCallRules.SUB)
        ScreenShots.assertNoLoneWords(compose, name, lines.map { LoneWordGlue.kept(it) })
        return core
    }

    private fun tap(description: String) {
        val n = compose.onAllNodes(hasContentDescription(description) and hasClickAction())
        if (n.fetchSemanticsNodes().isEmpty()) fail("Obi on calls: nothing to tap for $description")
        n[0].performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun waitFor(what: String, ok: () -> Boolean) {
        var i = 0
        while (!ok() && i++ < 40) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        if (!ok()) fail("Obi on calls: $what")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverDark() { page("cover-dark") }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverLight() { page("cover-light", ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerDark() { page("inner-dark") }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun byHand() {
        val core = page("cover-dark-by-hand")
        tap("Monthly cap £30")
        waitFor("£30 didn't set the cap") { core.workMode.value.callLiveCapGbp == 30 }
        tap(CallsSettingsRules.BOOKING_TITLE)
        waitFor("Booking calls didn't switch off") { !core.workMode.value.callBooking }
        tap("${CallsSettingsRules.KNOWS_TITLE}: Where you work")
        waitFor("Where you work didn't light") { LiveCallRules.Knows.WORK in core.workMode.value.callKnows }
        turns()
        ScreenShots.awaitShows(compose, "obi-calls-by-hand", "cap £30")
        ScreenShots.reshootAfterHand(compose, "obi-calls", "cover-dark-by-hand")
    }
}
