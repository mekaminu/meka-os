package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
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
import os.meka.android.ask.WorkingOnPane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.DateGlue
import os.meka.core.domain.HelpPlace
import os.meka.core.domain.ObligationKind
import os.meka.core.domain.RenewalRepeat
import os.meka.core.domain.StandingErrand
import os.meka.core.domain.WorkingOnRules
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate for Ask → Daily → Obi is working on (Ada plans with you, part 3 slice 1): nothing to watch, one
 * chase due, and all three standing errands needing him, on the Fold's cover and inner screens, Dark (and the full one
 * in Light). Saved under `working-on/` and read for nonsense, cut lines, broken words, split dates and numbers, lone
 * words and stray dots, and every button answering a 48 dp touch. By hand: a card's button opens its place; Turn off
 * takes the card away and Turn on brings it back. Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class WorkingOnScreensTest {
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object {
        const val SETTLE_MS = 3_000L
    }

    private enum class Fill { EMPTY, ONE, MANY }

    private var opened: HelpPlace? = null

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(61), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun until(done: () -> Boolean) {
        repeat(60) {
            if (done()) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns()
    }

    private fun onScreen(text: String): Boolean = ScreenShots.texts(compose).any { DateGlue.plain(it).contains(text) }

    private fun assertTouchTargets(name: String) {
        val small = compose.onAllNodes(hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .fetchSemanticsNodes()
            .filter { it.size.height / compose.density.density < 47.5f }
            .map { n ->
                val label = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                    ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "?"
                "\"$label\" ${(n.size.height / compose.density.density).toInt()} dp"
            }
        if (small.isNotEmpty()) fail("$name: buttons under 48 dp to tap:\n" + small.joinToString("\n") { "- $it" })
    }

    private suspend fun fill(core: MekaCore, fill: Fill) {
        val today = core.todayEpochDay()
        when (fill) {
            Fill.EMPTY -> Unit
            Fill.ONE -> core.addWaiting("The drill back", "Alex", 0)
            Fill.MANY -> {
                core.addRenewal("Home insurance", ObligationKind.INSURANCE, today + 15, RenewalRepeat.YEARLY, null, null)
                core.addOccasion("Sam's birthday 25 Oct")
                core.addWaiting("The drill back", "Alex", 0)
            }
        }
    }

    private fun pane(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core()
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            fill(core, fill)
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    WorkingOnPane(core, onClose = {}, onOpen = { opened = it })
                }
            }
        }
        val v = runBlocking { core.workingOnView() }
        until { onScreen(WorkingOnRules.SWITCHES.uppercase()) }
        compose.waitForIdle()
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("working-on", name))
        val lines = listOf(v.summary, WorkingOnRules.INTRO, WorkingOnRules.NEXT) + v.cards.flatMap { listOfNotNull(it.did, it.need) } +
            v.switches.map { it.line }
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertShows(compose, name, WorkingOnRules.TITLE)
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        ScreenShots.assertNoDotEndingALine(compose, name, lines)
        assertTouchTargets(name)
        ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
        val cards = compose.onAllNodes(hasTestTag("working-on-card"), useUnmergedTree = true).fetchSemanticsNodes().size
        when (fill) {
            Fill.EMPTY -> {
                if (v.summary != WorkingOnRules.EMPTY_LINE) fail("$name: the summary reads \"${v.summary}\"")
                if (cards != 0) fail("$name: $cards cards with nothing to watch")
            }
            Fill.ONE -> {
                if (cards != 1) fail("$name: $cards cards, not 1")
                if (!onScreen("Time to chase: The drill back from Alex")) fail("$name: the chase isn't said")
            }
            Fill.MANY -> {
                if (cards != 3) fail("$name: $cards cards, not 3")
                if (v.summary != "Keeping an eye on 3 things · 3 need you") fail("$name: the summary reads \"${v.summary}\"")
                if (!onScreen(WorkingOnRules.NEED.uppercase())) fail("$name: no WHAT I NEED FROM YOU")
            }
        }
        return core
    }

    /** Works by hand: Open Waiting for opens Lists; Turn off takes the chaser's card away, Turn on brings it back. */
    private fun byHand(core: MekaCore) {
        val name = "working-on-by-hand"
        fun tap(description: String) {
            val n = compose.onAllNodes(hasContentDescription(description) and hasClickAction())
            if (n.fetchSemanticsNodes().isEmpty()) fail("$name: no \"$description\"")
            n[0].performSemanticsAction(SemanticsActions.OnClick)
        }
        val chaser = StandingErrand.FOLLOW_UPS
        tap("${chaser.openLabel}: ${chaser.title}")
        until { opened != null }
        if (opened != HelpPlace.LISTS) fail("$name: ${chaser.openLabel} opened $opened, not Lists")
        fun cardShown() = runBlocking { core.workingOnView() }.cards.any { it.errand == chaser } &&
            compose.onAllNodes(hasTestTag("working-on-card"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        tap("Turn off: ${chaser.title}")
        until { !cardShown() }
        if (cardShown()) fail("$name: Turn off left the chaser's card")
        tap("Turn on: ${chaser.title}")
        until { cardShown() }
        if (!cardShown()) fail("$name: Turn on didn't bring the card back")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverEmpty() { pane("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOne() { pane("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverMany() { pane("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverManyLight() { pane("cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerMany() { pane("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun byHand() { byHand(pane("cover-dark-by-hand", Fill.ONE)) }
}
