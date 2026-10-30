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
import androidx.compose.ui.test.hasText
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
import os.meka.android.ask.AutonomyPane
import os.meka.android.ask.SpeakUpPane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.AutonomyRules
import os.meka.core.domain.DateGlue
import os.meka.core.domain.DonePillSpec
import os.meka.core.domain.LoneWordGlue
import os.meka.core.domain.MailAutopilotPanelRules
import os.meka.core.domain.MailFindAutoRules
import os.meka.core.domain.SpeakMoment
import os.meka.core.domain.SpeakOften
import os.meka.core.domain.SpeakUpRules
import os.meka.core.domain.TrustKind
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 54 (build plan: "the rest of Ask's places' shots", Speak up and Autonomy next): Ask → Settings
 * → Speaking up (as it comes, muted, and changed: the reply moment on, up to 3 a day, muted) and Ask → More → What
 * Navilife may do (nothing trusted, one kind trusted, and a full page: two kinds trusted, one "keep asking", renewals
 * from mail on), each on the Fold's cover and inner screens, Dark (and the full ones in Light). Saved under `speak-up/`
 * and `autonomy/` and read for nonsense, cut lines, broken words, split dates, times and numbers, the panes' lines
 * leaving a word alone or a dot starting or ending a line, and every button answering a 48 dp touch; on the cover
 * screen each What Navilife may do button sits under its words. By hand: Speaking up's Turn off, How often and Mute;
 * What Navilife may do's Pause / Resume, Ask first and Inbox autopilot's Turn off. Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class SpeakUpAutonomyScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object {
        const val SETTLE_MS = 3_000L
    }

    private enum class Fill { EMPTY, ONE, MANY }

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(54), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    /** Runs the main looper and the frames by turns (a tap's change reaches the recomposer through the looper). */
    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    /** Waits (core thread, looper and frames by turns) until [done] holds, then lets the screen settle. */
    private fun until(done: () -> Boolean) {
        repeat(60) {
            if (done()) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns()
    }

    /** [text] is on screen, read as plain words (the panes keep their last words together with no-break spaces). */
    private fun onScreen(text: String): Boolean = ScreenShots.texts(compose).any { DateGlue.plain(it).contains(text) }

    /** Every button drawn answers a 48 dp touch. */
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

    /**
     * Quality gate slice 55: every settings pill is drawn at most [DonePillSpec.MAX_DRAWN_HEIGHT_DP] high (its 48 dp
     * touch outside it), and with [maxGapDp] each sits close under the words above it, no tall gap.
     */
    private fun assertSlimPills(name: String, maxGapDp: Float? = null) {
        val d = compose.density.density
        val pills = compose.onAllNodes(hasTestTag("touch-pill"), useUnmergedTree = true).fetchSemanticsNodes()
        if (pills.isEmpty()) fail("$name: no pill drawn")
        val tall = pills.filter { it.size.height / d > DonePillSpec.MAX_DRAWN_HEIGHT_DP + 0.5f }
        if (tall.isNotEmpty()) fail("$name: pills drawn ${tall.map { (it.size.height / d).toInt() }} dp high, not ~32 dp")
        if (maxGapDp == null) return
        val words = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
        pills.forEach { p ->
            val above = words.filter { it.boundsInRoot.bottom <= p.boundsInRoot.top + 0.5f }.maxOfOrNull { it.boundsInRoot.bottom }
                ?: return@forEach
            val gap = (p.boundsInRoot.top - above) / d
            if (gap > maxGapDp) fail("$name: a pill sits ${gap.toInt()} dp under the words above it (at most ${maxGapDp.toInt()})")
        }
    }

    private fun checks(name: String, area: String, title: String, lines: List<String>) {
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path(area, name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertShows(compose, name, title)
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        ScreenShots.assertNoDotEndingALine(compose, name, lines)
        assertTouchTargets(name)
        ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
    }

    private fun setUp(theme: ThemeChoice, fill: suspend (MekaCore) -> Unit): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core()
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            fill(core)
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        return core
    }

    // ---- Speaking up ----

    private suspend fun fillSpeak(core: MekaCore, fill: Fill) {
        when (fill) {
            Fill.EMPTY -> Unit
            Fill.ONE -> core.speakUpAction(SpeakUpRules.MUTE_LABEL)
            Fill.MANY -> {
                core.speakUpAction(SpeakMoment.REPLY.label)
                core.speakUpAction(SpeakMoment.PRESSURE.label)
                core.speakUpAction(SpeakUpRules.OFTEN_LABEL)
                core.speakUpAction(SpeakUpRules.MUTE_LABEL)
            }
        }
    }

    private fun speak(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val core = setUp(theme) { fillSpeak(it, fill) }
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { SpeakUpPane(core, onClose = {}) }
            }
        }
        val v = runBlocking { core.speakUpView() }
        until { onScreen(SpeakMoment.URGENT.label.uppercase()) }
        compose.waitForIdle()
        val lines = listOf(v.introShort, v.footer) + v.sections.flatMap { it.steps + it.status }
        checks(name, "speak-up", SpeakUpRules.TITLE, lines)
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(v.footer))
        // Slice 55: the intro said short, the full how-to folded away under How it works; slim pills close under the words.
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(SpeakUpRules.INTRO_SHORT))
        ScreenShots.assertShows(compose, name, MailAutopilotPanelRules.HOW_IT_WORKS)
        if (compose.onAllNodes(hasTestTag("speak-up-how"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            fail("$name: the full how-to shows before How it works is tapped")
        }
        assertSlimPills(name, maxGapDp = 16f)
        val mute = v.sections.last()
        when (fill) {
            Fill.EMPTY -> {
                if (mute.lit) fail("$name: muted with nothing set")
                if (v.sections.first().status != "On") fail("$name: urgent from family isn't on to start (${v.sections.first()})")
            }
            Fill.ONE -> {
                if (mute.status != "Muted till 19:15") fail("$name: the mute reads \"${mute.status}\", not till 19:15")
                ScreenShots.assertShows(compose, name, SpeakUpRules.UNMUTE_ACTION)
            }
            Fill.MANY -> {
                val reply = v.sections.first { it.label == SpeakMoment.REPLY.label }
                if (reply.status != "On") fail("$name: the reply moment isn't on")
                val often = v.sections.first { it.label == SpeakUpRules.OFTEN_LABEL }
                if (often.status != SpeakOften.LESS.label) fail("$name: How often reads \"${often.status}\"")
                if (!onScreen("Said today: 0 of 3")) fail("$name: \"Said today: 0 of 3\" isn't on screen")
            }
        }
        return core
    }

    /** Works by hand: Urgent's Turn off turns it off; How often swaps; Mute mutes for 2 hours and Speak again now ends it. */
    private fun speakByHand(core: MekaCore) {
        val name = "speak-up-by-hand"
        fun tap(description: String) {
            val n = compose.onAllNodes(hasContentDescription(description) and hasClickAction())
            if (n.fetchSemanticsNodes().isEmpty()) fail("$name: no \"$description\"")
            n[0].performSemanticsAction(SemanticsActions.OnClick)
        }
        fun howShown() = compose.onAllNodes(hasTestTag("speak-up-how"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        val how = compose.onAllNodes(hasText(MailAutopilotPanelRules.HOW_IT_WORKS) and hasClickAction())
        if (how.fetchSemanticsNodes().isEmpty()) fail("$name: no How it works")
        how[0].performSemanticsAction(SemanticsActions.OnClick)
        until { howShown() }
        if (!howShown()) fail("$name: How it works didn't open the full how-to")
        compose.onAllNodes(hasText(MailAutopilotPanelRules.HIDE_HOW) and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        until { !howShown() }
        if (howShown()) fail("$name: Hide left the how-to open")

        tap("${SpeakUpRules.TURN_OFF}: ${SpeakMoment.URGENT.label}")
        until { runBlocking { core.speakUpView() }.sections.first().status == "Off" }
        if (runBlocking { core.speakUpView() }.sections.first().status != "Off") fail("$name: Turn off left urgent from family on")

        tap("${SpeakOften.LESS.label}: ${SpeakUpRules.OFTEN_LABEL}")
        until { onScreen("Said today: 0 of 3") }
        if (!onScreen("Said today: 0 of 3")) fail("$name: How often didn't change to up to 3 a day")

        tap("${SpeakUpRules.MUTE_ACTION}: ${SpeakUpRules.MUTE_LABEL}")
        until { onScreen("Muted till 19:15") }
        if (!onScreen("Muted till 19:15")) fail("$name: Mute for 2 hours didn't say till 19:15")
        tap("${SpeakUpRules.UNMUTE_ACTION}: ${SpeakUpRules.MUTE_LABEL}")
        until { onScreen("Not muted") }
        if (!onScreen("Not muted")) fail("$name: Speak again now left it muted")
    }

    // ---- What Navilife may do ----

    private suspend fun fillAutonomy(core: MekaCore, fill: Fill) {
        when (fill) {
            Fill.EMPTY -> Unit
            Fill.ONE -> core.answerTrustOffer(TrustKind.ADD_TASK.key, yes = true)
            Fill.MANY -> {
                core.answerTrustOffer(TrustKind.ADD_TASK.key, yes = true)
                core.answerTrustOffer(TrustKind.ADD_SHOPPING.key, yes = true)
                core.answerTrustOffer(TrustKind.ADD_WIN.key, yes = false)
                core.resetTrust(MailFindAutoRules.ENTITY_ID)
            }
        }
    }

    /** On the cover screen a row's button sits under its words (below them), never beside them. */
    private fun assertButtonUnder(name: String, button: String, words: String) {
        val b = compose.onAllNodes(hasText(button) and hasClickAction()).fetchSemanticsNodes().firstOrNull()
            ?: fail("$name: no \"$button\"")
        val w = compose.onAllNodes(hasText(words), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
            ?: fail("$name: \"$words\" isn't on screen")
        if (b.boundsInRoot.top < w.boundsInRoot.bottom - 1f) fail("$name: \"$button\" sits beside \"$words\", not under it")
    }

    private fun autonomy(name: String, fill: Fill, cover: Boolean, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val core = setUp(theme) { fillAutonomy(it, fill) }
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { AutonomyPane(core, onClose = {}) }
            }
        }
        turns()
        compose.waitForIdle()
        val v = core.autonomyView.value
        val rows = v.sections.flatMap { it.rows }
        val lines = listOf(v.summary, AutonomyRules.PAUSE_TITLE, AutonomyRules.PAUSE_LINE, AutonomyRules.NOTE) +
            rows.map { it.line } + rows.map { it.title }
        checks(name, "autonomy", "What Navilife may do", lines)
        assertSlimPills(name)
        ScreenShots.assertShows(compose, name, LoneWordGlue.kept(v.summary))
        if (cover) {
            assertButtonUnder(name, AutonomyRules.PAUSE, LoneWordGlue.kept(AutonomyRules.PAUSE_LINE))
            rows.filter { it.action != null && !it.locked }.forEach { r ->
                val b = compose.onAllNodes(hasText(r.action!!) and hasClickAction()).fetchSemanticsNodes()
                val w = compose.onAllNodes(hasText(LoneWordGlue.kept(r.line)), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
                    ?: return@forEach // below the fold
                if (b.none { it.boundsInRoot.top >= w.boundsInRoot.bottom - 1f }) fail("$name: \"${r.action}\" sits beside \"${r.title}\"'s words")
            }
        }
        when (fill) {
            Fill.EMPTY -> {
                if (v.paused) fail("$name: paused with nothing set")
                if (v.summary != AutonomyRules.NONE_LINE) fail("$name: the summary reads \"${v.summary}\"")
            }
            Fill.ONE -> {
                if (!v.summary.startsWith("Navilife does 1 thing")) fail("$name: the summary reads \"${v.summary}\"")
                ScreenShots.assertShows(compose, name, AutonomyRules.ASK_FIRST)
            }
            Fill.MANY -> {
                if (!v.summary.startsWith("Navilife does 3 things")) fail("$name: the summary reads \"${v.summary}\"")
                ScreenShots.assertShows(compose, name, LoneWordGlue.kept(AutonomyRules.DECLINED_LINE))
            }
        }
        return core
    }

    /** Works by hand: Pause pauses (Resume resumes); a trusted kind's Ask first sends it back to asking. */
    private fun autonomyByHand(core: MekaCore) {
        val name = "autonomy-by-hand"
        /** Taps the [label] button; [last] for the last of several (Add what you ask for's Ask first comes first). */
        fun tap(label: String, last: Boolean = false) {
            val n = compose.onAllNodes(hasText(label) and hasClickAction())
            val count = n.fetchSemanticsNodes().size
            if (count == 0) fail("$name: no \"$label\"")
            n[if (last) count - 1 else 0].performSemanticsAction(SemanticsActions.OnClick)
        }
        tap(AutonomyRules.PAUSE)
        until { core.autonomyView.value.paused && onScreen(AutonomyRules.RESUME) }
        if (!core.autonomyView.value.paused) fail("$name: Pause left Navilife running")
        if (!onScreen("Paused since 17:15")) fail("$name: the summary doesn't say paused since 17:15")
        tap(AutonomyRules.RESUME)
        until { !core.autonomyView.value.paused }
        if (core.autonomyView.value.paused) fail("$name: Resume left it paused")

        // Add a task's Ask first (the last one: Add what you ask for's leads Your lists).
        tap(AutonomyRules.ASK_FIRST, last = true)
        until { core.autonomyView.value.summary == AutonomyRules.NONE_LINE }
        if (core.autonomyView.value.summary != AutonomyRules.NONE_LINE) fail("$name: Ask first left a kind trusted (${core.autonomyView.value.summary})")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun speakCoverEmpty() { speak("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun speakCoverOne() { speak("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun speakCoverMany() { speak("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun speakCoverManyLight() { speak("cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun speakInnerMany() { speak("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun speakByHand() { speakByHand(speak("cover-dark-by-hand", Fill.EMPTY)) }

    @Test @Config(qualifiers = ScreenShots.COVER) fun autonomyCoverEmpty() { autonomy("cover-dark-empty", Fill.EMPTY, cover = true) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun autonomyCoverOne() { autonomy("cover-dark-one", Fill.ONE, cover = true) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun autonomyCoverMany() { autonomy("cover-dark-many", Fill.MANY, cover = true) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun autonomyCoverManyLight() { autonomy("cover-light-many", Fill.MANY, cover = true, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun autonomyInnerMany() { autonomy("inner-dark-many", Fill.MANY, cover = false) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun autonomyByHand() { autonomyByHand(autonomy("cover-dark-by-hand", Fill.ONE, cover = true)) }
}
