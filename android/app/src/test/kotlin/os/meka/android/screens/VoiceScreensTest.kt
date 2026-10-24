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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.ask.VoiceActions
import os.meka.android.ask.VoiceList
import os.meka.android.ask.VoiceScreen
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.CompanionMemoryRules
import os.meka.core.domain.CompanionRules
import os.meka.core.domain.DateGlue
import os.meka.core.domain.DeviceVoice
import os.meka.core.domain.DeviceVoiceRules
import os.meka.core.domain.DeviceVoiceSettings
import os.meka.core.domain.LiveTalkRules
import os.meka.core.domain.LocalCalendar
import os.meka.core.domain.OfferedVoice
import os.meka.core.domain.ServerSpeedRules
import os.meka.core.domain.ServerTurnTiming
import os.meka.core.domain.SpeechRules
import os.meka.core.domain.TurnKind
import os.meka.core.domain.TurnSpeedRules
import os.meka.core.domain.TurnTrace
import os.meka.core.domain.VoicePickerRules
import kotlin.test.fail

/**
 * The quality gate, slice 59 (build plan: "the rest of Ask's places' shots", Voice): Ask → Settings → Obi's voice drawn
 * from invented views: not connected yet (nothing to pick but the phone's own voice, no installed English voice), a
 * first look (Polly's British voices, Brian the default, Live off with its month, one phone voice, one hello timed) and
 * set up (Google's voices and Polly's, Live on with its voices, three kept notes, eight phone voices folded to six,
 * Speed and the server's timings), on the Fold's cover and inner screens, Dark (and the first look in Light). Saved
 * under `voice/` and read for nonsense, cut lines, broken words, split times and numbers, a grey line leaving a word
 * alone or starting a line with a dot, ▶ Sample whole on one line as a slim 32 dp pill inside a 48 dp touch, and every
 * button and chip answering a 48 dp touch. By hand: Sir, a voice, its Sample, "+2 more" and Live's On each do their job.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class VoiceScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London
    private val cal = LocalCalendar.fixedOffset(3_600_000L)

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    private val polly = listOf(
        OfferedVoice("Brian", "Male", "neural"), OfferedVoice("Arthur", "Male", "neural"),
        OfferedVoice("Amy", "Female", "generative"), OfferedVoice("Emma", "Female", "neural"),
    )
    private val google = listOf(
        OfferedVoice("Charon", "Male", "chirp3hd", VoicePickerRules.GOOGLE), OfferedVoice("Puck", "Male", "chirp3hd", VoicePickerRules.GOOGLE),
        OfferedVoice("Charon-US", "Male", "chirp3hd", VoicePickerRules.GOOGLE),
    )

    private fun phoneVoice(id: String, lang: String = "en-GB", q: Int = 400) = DeviceVoice(id, "", lang, q, "Speech Services by Google")

    private fun screen(fill: Fill): VoiceScreen = when (fill) {
        Fill.EMPTY -> VoiceScreen(
            sir = false, faceOn = true, faceReady = false, remembers = true, memoryRows = emptyList(),
            live = LiveTalkRules.view(on = false, voice = null, meterKnown = false, configured = false, level = "ok", spentPence = 0, capPence = 500),
            liveSampleFailed = false,
            view = VoicePickerRules.view(null, emptyList(), null, null, null, mac = false, connected = false),
            deviceVoices = emptyList(), deviceSettings = DeviceVoiceSettings.DEFAULT, showAll = false,
            speed = TurnSpeedRules.view(emptyList(), cal), serverSpeed = null,
        )
        Fill.ONE -> VoiceScreen(
            sir = false, faceOn = true, faceReady = true, remembers = true, memoryRows = emptyList(),
            live = LiveTalkRules.view(on = false, voice = null, meterKnown = true, configured = true, level = "ok", spentPence = 40, capPence = 500),
            liveSampleFailed = false,
            view = VoicePickerRules.view(SpeechRules.ON, polly, "Brian", null, "Navilife's voice · Brian · 1,240 of 1,000,000 characters in October", mac = false),
            deviceVoices = listOf(phoneVoice("en-gb-x-gba-local")), deviceSettings = DeviceVoiceSettings.DEFAULT, showAll = false,
            speed = TurnSpeedRules.view(listOf(TurnTrace(TurnKind.HELLO, now - 600_000, 520)), cal),
            serverSpeed = ServerSpeedRules.view(emptyList(), now),
        )
        Fill.MANY -> VoiceScreen(
            sir = true, faceOn = false, faceReady = true, remembers = true,
            memoryRows = listOf("Likes the 07:42 train", "Training for a 10k in spring", "Prefers short answers in the morning"),
            live = LiveTalkRules.view(on = true, voice = "Charon", meterKnown = true, configured = true, level = "ok", spentPence = 210, capPence = 500),
            liveSampleFailed = true,
            view = VoicePickerRules.view(SpeechRules.ON, google + polly, "Brian", "Charon", "Navilife's voice · Charon · 84,120 of 1,000,000 characters in October", mac = false)
                .copy(
                    googleLine = VoicePickerRules.googleLine("on", 84_120, 1_000_000, 900_000, null, "2026-10"),
                    sampleLine = VoicePickerRules.SAMPLE_FROM_BRIEF,
                ),
            deviceVoices = listOf(
                phoneVoice("en-gb-x-gba-local", q = 500), phoneVoice("en-gb-x-gbb-local"), phoneVoice("en-gb-x-gbc-local"),
                phoneVoice("en-gb-x-gbd-local"), phoneVoice("en-gb-x-rjs-local"), phoneVoice("en-us-x-iob-local", "en-US"),
                phoneVoice("en-au-x-aua-local", "en-AU"), phoneVoice("en-in-x-ahp-local", "en-IN", 300),
            ),
            deviceSettings = DeviceVoiceSettings("en-gb-x-gbb-local", rate = 1.1f, pitch = 0.95f), showAll = false,
            speed = TurnSpeedRules.view(
                listOf(
                    TurnTrace(TurnKind.HELLO, now - 3_600_000, 480),
                    TurnTrace(TurnKind.TALK, now - 3_000_000, 1_900, toSentMs = 100, toFirstPieceMs = 1_200, toClipMs = 600, clipMs = listOf(500, 0)),
                    TurnTrace(TurnKind.TALK, now - 2_400_000, 1_300, toSentMs = 80, toFirstPieceMs = 800, toClipMs = 420, clipMs = listOf(400)),
                    TurnTrace(TurnKind.HELLO, now - 1_200_000, 650, deviceVoice = true),
                    TurnTrace(TurnKind.TALK, now - 600_000, 1_450, toSentMs = 90, toFirstPieceMs = 900, toClipMs = 460, clipMs = listOf(450, 380)),
                ),
                cal,
            ),
            serverSpeed = ServerSpeedRules.view(
                listOf(
                    ServerTurnTiming(now - 3_000_000, open = false, spoken = true, model = "claude-haiku-4-5", firstTokenMs = 600, firstSentenceMs = 900, clipMs = 1_300, doneMs = 2_100),
                    ServerTurnTiming(now - 2_400_000, open = true, spoken = true, model = "claude-sonnet-4-5", webMs = 700, firstTokenMs = 800, firstSentenceMs = 1_200, clipMs = 1_600, doneMs = 3_400),
                ),
                now,
            ),
        )
    }

    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun onScreen(text: String): Boolean = ScreenShots.texts(compose).any { DateGlue.plain(it).contains(text) }

    /** Every button and chip drawn answers a 48 dp touch (▶ Sample was ~36 dp, the chips ~24 dp). */
    private fun assertTouchTargets(name: String) {
        val roles = setOf(Role.Button, Role.Switch, Role.RadioButton)
        val small = compose.onAllNodes(hasClickAction() and SemanticsMatcher("has a role") { it.config.getOrNull(SemanticsProperties.Role) in roles })
            .fetchSemanticsNodes()
            .filter { it.size.height / compose.density.density < 47.5f }
            .map { n ->
                val label = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                    ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "?"
                "\"$label\" ${(n.size.width / compose.density.density).toInt()}×${(n.size.height / compose.density.density).toInt()} dp"
            }
        if (small.isNotEmpty()) fail("$name: controls under 48 dp to tap:\n" + small.joinToString("\n") { "- $it" })
    }

    /** ▶ Sample stays a slim 32 dp pill, the touch around it, inside the pane. */
    private fun assertSamplePills(name: String) {
        val pane = compose.onNodeWithTag("voice-pane").fetchSemanticsNode().boundsInRoot
        compose.onAllNodes(hasTestTag("touch-pill"), useUnmergedTree = true).fetchSemanticsNodes().forEach { n ->
            val h = n.size.height / compose.density.density
            if (h > 34f) fail("$name: a Sample pill is drawn $h dp high")
            val right = n.positionInRoot.x + n.size.width
            if (n.positionInRoot.x < pane.left - 1f || right > pane.right + 1f) fail("$name: a Sample pill sits outside the pane")
        }
    }

    private fun pane(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK, log: MutableList<String> = mutableListOf()): VoiceScreen {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val s = screen(fill)
        val actions = VoiceActions(
            close = { log += "close" }, callSir = { log += "sir:$it" }, setFace = { log += "face:$it" }, remember = { log += "remember:$it" },
            forgetAll = { log += "forget" }, setLive = { log += "live:$it" }, liveVoice = { log += "liveVoice:$it" },
            sampleLive = { log += "sampleLive:$it" }, choose = { log += "choose:${it.id}" }, sample = { log += "sample:${it.id}" },
            chooseDevice = { log += "device:${it.id}" }, sampleDevice = { log += "sampleDevice:${it.id}" }, showAll = { log += "more" },
            saveDevice = { log += "save" }, step = {}, clearSpeed = { log += "clear" },
        )
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    VoiceList(s, playing = { false }, a = actions)
                }
            }
        }
        turns()
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("voice", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertClocksWhole(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertShows(compose, name, VoicePickerRules.TITLE)
        if (!onScreen("How Obi sounds")) fail("$name: the intro doesn't say it's Obi's voice")
        val v = s.view!!
        val d = DeviceVoiceRules.view(s.deviceVoices!!, s.deviceSettings, mac = false, expanded = s.showAll)
        val lines = listOfNotNull(
            VoicePickerRules.INTRO, CompanionRules.callLine(sir = s.sir == true), s.live?.intro, s.live?.sharing, v.help, d.note, v.statusLine,
        )
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        val chips = listOf(CompanionRules.CALL_BY_NAME, CompanionRules.CALL_SIR_LABEL, CompanionMemoryRules.ON_LABEL, CompanionMemoryRules.OFF_LABEL,
            LiveTalkRules.ON_LABEL, LiveTalkRules.OFF_LABEL)
        ScreenShots.assertEachOneLine(compose, name, chips + listOf("▶  Sample"))
        assertSamplePills(name)
        assertTouchTargets(name)
        ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
        when (fill) {
            Fill.EMPTY -> {
                if (v.statusLine == null || !onScreen("Until this device is connected")) fail("$name: doesn't say why only the phone's voice is offered")
                if (!onScreen("No English voice is installed")) fail("$name: doesn't say no phone voice is installed")
                if (!onScreen(TurnSpeedRules.EMPTY)) fail("$name: Speed doesn't say nothing's timed yet")
                if (v.choices.size != 1) fail("$name: ${v.choices.size} voices offered while not connected")
            }
            Fill.ONE -> {
                if (!onScreen("Navilife's default")) fail("$name: the default voice isn't marked")
                if (v.choices.single { it.selected }.id != "Brian") fail("$name: Brian isn't lit as the default")
                if (!onScreen(ServerSpeedRules.EMPTY)) fail("$name: the server's speed doesn't say it has nothing yet")
            }
            Fill.MANY -> {
                if (!onScreen(VoicePickerRules.GROUP_GOOGLE_GB.uppercase())) fail("$name: Google's British voices have no heading")
                if (!onScreen(LiveTalkRules.VOICE_TITLE.uppercase())) fail("$name: Live's voices don't show while Live is on")
                if (!onScreen("+2 more")) fail("$name: the phone's voices past six aren't folded")
                if (!onScreen("Speed · 1.1× faster")) fail("$name: the speed line doesn't read the setting")
                if (!onScreen(LiveTalkRules.SAMPLE_FAILED)) fail("$name: a failed Live sample isn't said")
                s.memoryRows.forEach { if (!onScreen(it)) fail("$name: kept note \"$it\" isn't shown") }
            }
        }
        return s
    }

    /** Works by hand: Sir, Arthur, Brian's Sample, "+2 more" and Live's On each reach their action. */
    private fun byHand() {
        val name = "voice-by-hand"
        val log = mutableListOf<String>()
        pane("cover-dark-by-hand", Fill.ONE, log = log)
        fun tap(what: String, matcher: SemanticsMatcher, expect: String) {
            val n = compose.onAllNodes(matcher and hasClickAction())
            if (n.fetchSemanticsNodes().isEmpty()) fail("$name: no $what to tap")
            n[0].performSemanticsAction(SemanticsActions.OnClick)
            turns(500)
            if (log.lastOrNull() != expect) fail("$name: $what did ${log.lastOrNull()} (wanted $expect)")
        }
        tap("Sir", hasContentDescription("Obi calls you sir"), "sir:true")
        tap("Arthur", hasText("Arthur"), "choose:Arthur")
        tap("Brian's Sample", hasContentDescription("Sample · Brian"), "sample:Brian")
        tap("Live's On", hasContentDescription("Live on"), "live:true")
        tap("Remember off", hasContentDescription("Obi doesn't remember your chats"), "remember:false")
        tap("Close", hasText("Close"), "close")
        if (log.size != 6) fail("$name: ${log.size} actions for six taps ($log)")
    }

    private fun byHandMore() {
        val name = "voice-by-hand-more"
        val log = mutableListOf<String>()
        pane("cover-dark-by-hand-more", Fill.MANY, log = log)
        val more = compose.onAllNodes(hasText("+2 more") and hasClickAction())
        if (more.fetchSemanticsNodes().isEmpty()) fail("$name: no +2 more to tap")
        more[0].performSemanticsAction(SemanticsActions.OnClick)
        turns(500)
        if (log.lastOrNull() != "more") fail("$name: +2 more did ${log.lastOrNull()}")
        val forget = compose.onAllNodes(hasText(CompanionMemoryRules.FORGET_ALL) and hasClickAction())
        if (forget.fetchSemanticsNodes().isEmpty()) fail("$name: no Forget all with notes kept")
        forget[0].performSemanticsAction(SemanticsActions.OnClick)
        turns(500)
        if (log.lastOrNull() != "forget") fail("$name: Forget all did ${log.lastOrNull()}")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverEmpty() { pane("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOne() { pane("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOneLight() { pane("cover-light-one", Fill.ONE, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverMany() { pane("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerOne() { pane("inner-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerMany() { pane("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun byHandTest() { byHand() }
    @Test @Config(qualifiers = ScreenShots.COVER) fun byHandMoreTest() { byHandMore() }
}
