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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.ask.SetupList
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.AiStatusView
import os.meka.core.domain.BedsideDreamRules
import os.meka.core.domain.CommuteSetting
import os.meka.core.domain.DateGlue
import os.meka.core.domain.HealthAccount
import os.meka.core.domain.PeopleLists
import os.meka.core.domain.RequestWatch
import os.meka.core.domain.ServerHealth
import os.meka.core.domain.SetupDevice
import os.meka.core.domain.SetupFacts
import os.meka.core.domain.SetupRecovery
import os.meka.core.domain.SetupRules
import os.meka.core.domain.SetupScreenSaver
import os.meka.core.domain.SetupState
import os.meka.core.domain.SetupStep
import os.meka.core.domain.SetupView
import os.meka.core.domain.SignIn
import os.meka.core.domain.SignInState
import kotlin.test.fail

/**
 * The quality gate, slice 58 (build plan: "the rest of Ask's places' shots", Setup): Ask → More → Setup drawn from
 * invented checks, nothing reachable (every server step "Couldn't check"), a fresh Fold (most steps to do, each with
 * its button) and all set (Google's voices speaking), each on the Fold's cover and inner screens, Dark (and the fresh
 * one in Light). Saved under `setup/` and read for nonsense, cut lines, broken words, split times and numbers, a step's
 * line leaving a word alone or starting a line with a dot, every button whole on one line under its step's words, drawn
 * as a slim 32 dp pill and answering a 48 dp touch. By hand: a step's button hands that step over to be done.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class SetupScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    private val ai = AiStatusView("On · £1.20 of £20 this month", lit = false, canAsk = true)

    private fun view(fill: Fill): SetupView = when (fill) {
        Fill.EMPTY -> SetupRules.view(
            SetupFacts(
                device = SetupDevice(mac = false), connected = false, server = null, accounts = null, ai = null,
                callAssistantOn = false, voiceMessageSeen = false, voiceChosen = null, nowMs = now,
            ),
            SetupRecovery(null), SetupScreenSaver(null), null,
        )
        Fill.ONE -> SetupRules.view(
            SetupFacts(
                device = SetupDevice(
                    mac = false, notificationsAllowed = false, batteryExempt = false, notificationAccess = false, callRoleHeld = false,
                    people = PeopleLists(), watch = RequestWatch(),
                ),
                connected = true, server = ServerHealth(ServerHealth.PUSH_ON, calls = true, speech = true, atMs = now, macs = 0),
                accounts = emptyList(), ai = ai, callAssistantOn = false, voiceMessageSeen = false, voiceChosen = null, nowMs = now,
            ),
            SetupRecovery(null), SetupScreenSaver(false), CommuteSetting(80, 80),
        )
        Fill.MANY -> SetupRules.view(
            SetupFacts(
                device = SetupDevice(
                    mac = false, notificationsAllowed = true, batteryExempt = true, notificationAccess = true, callRoleHeld = true,
                    people = PeopleLists(family = setOf("Alex", "Sam", "Jo"), numbers = mapOf("Alex" to setOf("+447700900123"), "Sam" to setOf("+447700900124"), "Jo" to setOf("+447700900125"))),
                    watch = RequestWatch(people = setOf("Alex")),
                ),
                connected = true,
                server = ServerHealth(ServerHealth.PUSH_ON, calls = true, speech = true, atMs = now, macs = 1, speechGoogle = ServerHealth.GOOGLE_ON),
                accounts = listOf(
                    HealthAccount("google", "alex@example.com", "Personal", "ok", now),
                    HealthAccount("microsoft", "alex@example.org", "Work", "ok", now),
                    HealthAccount("weather", "home", "Weather", "ok", now),
                    HealthAccount("travel", "Google Routes · drive times to football", "Travel times", "ok", now),
                ),
                signIns = listOf(SignIn("google", "alex@example.com", SignInState.OK, null, canEdit = true)),
                ai = ai, callAssistantOn = true, voiceMessageSeen = true, voiceChosen = "Kore",
                homePlace = "Oakfield", workPlace = "Riverside", nowMs = now,
            ),
            SetupRecovery(null), SetupScreenSaver(true), CommuteSetting(80, 75, chosen = true),
        )
    }

    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun onScreen(text: String): Boolean = ScreenShots.texts(compose).any { DateGlue.plain(it).contains(text) }

    /** Every button drawn answers a 48 dp touch (the steps' buttons were ~30 dp). */
    private fun assertTouchTargets(name: String) {
        val small = compose.onAllNodes(hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).fetchSemanticsNodes()
            .filter { it.size.height / compose.density.density < 47.5f }
            .map { n ->
                val label = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                    ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "?"
                "\"$label\" ${(n.size.width / compose.density.density).toInt()}×${(n.size.height / compose.density.density).toInt()} dp"
            }
        if (small.isNotEmpty()) fail("$name: buttons under 48 dp to tap:\n" + small.joinToString("\n") { "- $it" })
    }

    /** The drawn pills stay slim (32 dp), the touch around them, never stretched into tall slabs. */
    private fun assertSlimPills(name: String) {
        compose.onAllNodes(hasTestTag("touch-pill")).fetchSemanticsNodes().forEach { n ->
            val h = n.size.height / compose.density.density
            if (h > 34f) fail("$name: a step's button is drawn $h dp high")
        }
    }

    /** Each button sits under its step's words, inside the pane (it sat beside them, squeezing the line). */
    private fun assertButtonsInView(name: String, steps: List<SetupStep>) {
        val pane = compose.onNodeWithTag("setup-pane").fetchSemanticsNode().boundsInRoot
        steps.filter(SetupRules::hasButton).forEach { s ->
            val nodes = compose.onAllNodes(hasContentDescription("${s.fixLabel} · ${s.title}") and hasClickAction()).fetchSemanticsNodes()
            if (nodes.isEmpty()) fail("$name: \"${s.title}\" has no ${s.fixLabel} button")
            val n = nodes[0]
            val right = n.positionInRoot.x + n.size.width
            if (n.positionInRoot.x < pane.left - 1f || right > pane.right + 1f) fail("$name: ${s.fixLabel} sits outside the pane")
        }
    }

    private fun pane(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK, fixed: MutableList<SetupStep> = mutableListOf()): SetupView {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val v = view(fill)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    SetupList(v, checking = false, onClose = {}) { fixed += it }
                }
            }
        }
        turns()
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("setup", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertShows(compose, name, "Setup")
        if (!onScreen(v.summary)) fail("$name: the summary \"${v.summary}\" isn't on screen")
        // The first sections are in view without scrolling; the rest of the page is checked by its lines laid out.
        val lines = listOf(v.summary) + v.steps.map { it.line }
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        val labels = v.steps.filter(SetupRules::hasButton).mapNotNull { it.fixLabel }.toSet()
        ScreenShots.assertEachOneLine(compose, name, labels)
        assertButtonsInView(name, v.steps)
        assertSlimPills(name)
        assertTouchTargets(name)
        ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
        when (fill) {
            Fill.EMPTY -> {
                // Of the steps that couldn't be checked only the screen saver's (Android's own setting) has a button;
                // the ones done in Navilife (Work mode, the voice) keep theirs.
                val unknown = v.steps.filter { it.state == SetupState.UNKNOWN && SetupRules.hasButton(it) }.map { it.key }
                if (unknown != listOf(BedsideDreamRules.SETUP_KEY)) fail("$name: buttons on unchecked steps $unknown")
                if (!onScreen("Couldn't check")) fail("$name: doesn't say what couldn't be checked")
            }
            Fill.ONE -> {
                if (v.toDo < 8) fail("$name: a fresh Fold has only ${v.toDo} steps to do")
                if (labels.isEmpty()) fail("$name: no buttons")
            }
            Fill.MANY -> {
                if (v.toDo != 0) fail("$name: ${v.toDo} left on a set-up Fold (${v.steps.filter { it.toDo }})")
                if (!onScreen("All set")) fail("$name: doesn't say all set")
                if (!onScreen("Google Chirp 3 HD")) fail("$name: the natural voice doesn't name Google's voices")
            }
        }
        return v
    }

    /** Works by hand: Pick a voice's Choose hands that step over; Battery's Keep Navilife awake its own. */
    private fun byHand() {
        val name = "setup-by-hand"
        val fixed = mutableListOf<SetupStep>()
        val v = pane("cover-dark-by-hand", Fill.ONE, fixed = fixed)
        fun tap(step: SetupStep) {
            val n = compose.onAllNodes(hasContentDescription("${step.fixLabel} · ${step.title}") and hasClickAction())
            if (n.fetchSemanticsNodes().isEmpty()) fail("$name: no ${step.fixLabel} for ${step.title}")
            n[0].performSemanticsAction(SemanticsActions.OnClick)
            turns(500)
        }
        val voice = v.steps.first { it.key == "voice.picked" }
        tap(voice)
        if (fixed.lastOrNull()?.key != voice.key) fail("$name: Choose handed over ${fixed.lastOrNull()?.key}")
        val battery = v.steps.first { it.key == "device.battery" }
        tap(battery)
        if (fixed.lastOrNull()?.key != battery.key) fail("$name: Keep Navilife awake handed over ${fixed.lastOrNull()?.key}")
        if (fixed.size != 2) fail("$name: ${fixed.size} hand-overs for two taps")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverEmpty() { pane("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOne() { pane("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOneLight() { pane("cover-light-one", Fill.ONE, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverMany() { pane("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerOne() { pane("inner-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerMany() { pane("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun byHandTest() { byHand() }
}
