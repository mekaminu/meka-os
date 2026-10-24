package os.meka.android.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.ask.HelloStage
import os.meka.android.ask.StageCards
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.EntranceKind
import os.meka.core.domain.StageClose
import os.meka.core.domain.StageRules
import os.meka.core.domain.TalkPhase
import os.meka.core.domain.TalkProblem

/**
 * Meka 2026-10-24 14:34 (build 0.1.2125): tapping the orb flew it to the middle and about two seconds later the stage
 * closed, nothing heard. The stage as the shell draws it, driven through tap → hello → listen → answer → end: it stays
 * up until he has spoken; a conversation that ends before that, or a microphone that can't start, keeps it up saying
 * why (with Open settings when the microphone is off), and a tap tries again. Shots under `hello-stage/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class, qualifiers = ScreenShots.COVER)
class HelloStageFlowTest {
    @get:Rule
    val compose = createComposeRule()

    private var phase by mutableStateOf(TalkPhase.ENDED)
    private var heard by mutableStateOf("")
    private var said by mutableStateOf("")
    private var problem by mutableStateOf<TalkProblem?>(null)
    private val whys = mutableListOf<StageClose>()
    private var closed = 0
    private var greets = 0
    private var retries = 0
    private var settings = 0

    private fun stage() {
        ThemeChoice.save(ApplicationProvider.getApplicationContext<ShotApplication>(), ThemeChoice.DARK)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().testTag("screen")) {
                    HelloStage(
                        EntranceKind.BIG, Offset.Unspecified, phase, 0.3f, heard, said, needsYou = 0, cards = StageCards(),
                        onGreet = { greets++ }, onStop = {}, onClosed = { closed++ },
                        problem = problem, onRetry = { retries++ }, onOpenSettings = { settings++ }, onWhy = { whys += it },
                    )
                }
            }
        }
    }

    private fun at(ms: Long) {
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }

    @Test
    fun tapHelloListenAnswerClose() {
        stage()
        at(300)
        assertEquals("the hello is asked for as the points re-form", 1, greets)
        phase = TalkPhase.SPEAKING; said = "Afternoon, Alex. It's Obi."
        at(1_700)
        phase = TalkPhase.LISTENING // the hello ended: listening (where the 14:34 stage closed)
        at(6_000)
        heard = "what's on tomorrow"
        at(800)
        phase = TalkPhase.THINKING
        at(1_000)
        phase = TalkPhase.SPEAKING; said = "Just the dentist at 9."
        at(3_000)
        heard = ""; phase = TalkPhase.LISTENING
        at(8_000)
        assertEquals("nothing closed while the turn ran", 0, closed)
        assertTrue(whys.isEmpty())
        phase = TalkPhase.ENDED
        at(2_000)
        assertEquals(listOf(StageClose.SPOKE), whys)
        assertEquals(1, closed)
    }

    @Test
    fun anEndBeforeHeSpeaksKeepsTheStageUpSayingSo() {
        stage()
        at(300)
        phase = TalkPhase.SPEAKING; said = "Afternoon, Alex. It's Obi."
        at(1_700)
        phase = TalkPhase.ENDED // the 14:34 case: over two seconds in, nothing heard
        at(10_000)
        assertEquals(0, closed)
        compose.onNodeWithText(StageRules.line(os.meka.core.domain.StageNote.NOTHING_HEARD, null)!!).assertExists()
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("hello-stage", "cover-dark-nothing-heard"))
        // A tap listens again; nothing more for a minute and it shrinks back, saying why.
        compose.onNodeWithTag("screen").performClick()
        at(100)
        assertEquals(1, retries)
        at(StageRules.IDLE_CLOSE_MS + 2_000)
        assertEquals(listOf(StageClose.NO_SPEECH), whys)
        assertEquals(1, closed)
    }

    @Test
    fun aMicrophoneThatsOffSaysSoWithOpenSettings() {
        stage()
        at(300)
        phase = TalkPhase.LISTENING
        at(200)
        problem = TalkProblem.NO_PERMISSION; phase = TalkPhase.ENDED
        at(3_000)
        assertEquals(0, closed)
        compose.onNodeWithText("I can't hear you: the microphone is off for Navilife.").assertExists()
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("hello-stage", "cover-dark-mic-off"))
        compose.onNodeWithText(StageRules.OPEN_SETTINGS).performClick()
        at(100)
        assertEquals(1, settings)
        assertEquals("a tap on the stage doesn't retry while Open settings shows", 0, retries)
    }

    @Test
    fun aHelloThatNeverStartsSaysSoInsteadOfVanishing() {
        stage()
        at(300)
        at(5_000) // nothing started: the case of Ask's field scrolled out of view
        assertEquals(0, closed)
        compose.onNodeWithText(StageRules.line(os.meka.core.domain.StageNote.NOT_STARTED, null)!!).assertExists()
    }
}
