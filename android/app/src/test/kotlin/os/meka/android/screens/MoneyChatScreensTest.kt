package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlin.random.Random
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.ask.MoneyPane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.MoneyChat
import os.meka.core.domain.MoneyTab
import os.meka.core.domain.MoneyTabs
import os.meka.core.facade.MekaCore
import os.meka.core.domain.AskContext
import os.meka.core.domain.MoneyChatObi
import os.meka.core.domain.MoneyChatSpoken
import os.meka.core.domain.TalkTurn
import os.meka.core.facade.AiApi
import os.meka.core.facade.AskReply
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.wire.MoneyChatCodec

/**
 * Money redesign, Ask Obi (slice 1): the bar under the tabs on every Money tab opens Obi · money; a Try question is
 * answered on the device with its buttons, and Make it a goal makes the goal (Undo in the sheet's line). Shot on the
 * cover screen and the open Fold in Dark. Invented data (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class MoneyChatScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object {
        const val SETTLE_MS = 3_000L
        val CURRENT = "Date,Description,Amount\n" +
            listOf(7, 8, 9, 10).joinToString("") { m ->
                val mm = m.toString().padStart(2, '0')
                "01/$mm/2026,OAKFIELD COUNCIL TAX,-142.00\n" +
                    "05/$mm/2026,SALARY OAKFIELD LTD,2500.00\n" +
                    "09/$mm/2026,TESCO STORES 2041,-64.90\n" +
                    "15/$mm/2026,STREAMLY.COM,-10.99\n"
            } +
            (1..12).joinToString("") { d -> "${d.toString().padStart(2, '0')}/10/2026,COSTA COFFEE,-4.80\n" }
    }

    /** Slice 2: a server whose AI answers the money chat (sync goes to an in-memory server). */
    private class ObiServer : SyncTransport, AiApi {
        private val sync = SyncService(InMemoryServerOpStore())
        val asked = mutableListOf<MoneyChatCodec.Request>()
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply =
            AskReply.Unavailable("off", null)
        override suspend fun moneyChat(request: MoneyChatCodec.Request): MoneyChatCodec.Response {
            asked += request
            return MoneyChatCodec.Response("answered", "Yes, a little: Costa came to £57.60 this month, your biggest everyday spend after Tesco at £64.90.")
        }
    }

    private fun open(transport: SyncTransport? = null): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, ThemeChoice.DARK)
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = transport,
            secureRandom = Random(43), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            core.openMoney()
            check(!core.importMoneyStatement("Current account", CURRENT).refused)
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { MoneyPane(core, onClose = {}) }
            }
        }
        settle()
        return core
    }

    private fun settle() {
        repeat((SETTLE_MS / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)
    }

    private fun shows(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun showsText(text: String) = compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    /** The core answers off the main thread: tick until [done]. */
    private fun until(core: MekaCore, done: () -> Boolean) {
        repeat(40) { if (!done()) { Thread.sleep(50); runBlocking { core.tick() }; settle() } }
    }

    private fun pick(tab: MoneyTab) {
        compose.onNodeWithContentDescription(MoneyTabs.spoken(tab, chosen = false), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun askObiAnswersAWhatIfAndMakesTheGoal() {
        val core = open()
        assertTrue(shows("money-ask-bar"), "the Ask Obi bar isn't on Spending")
        pick(MoneyTab.BILLS)
        assertTrue(shows("money-ask-bar"), "the Ask Obi bar isn't on Bills")
        tap("money-ask-bar")
        until(core) { shows("money-chat-try-0") }
        assertTrue(shows("money-chat-sheet"), "the chat's sheet didn't open")
        assertTrue(showsText(MoneyChat.HELLO), "Obi's hello isn't in the chat")
        assertTrue(shows("money-chat-try-0"), "no Try questions")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-ask-obi"))
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-ask-obi")

        tap("money-chat-try-0")
        until(core) { shows("money-chat-answer-0") }
        assertTrue(showsText("a month for 6 months puts by"), "the what-if wasn't answered")
        assertTrue(shows("money-chat-act-goal-0"), "no Make it a goal under the answer")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-ask-obi-answer"))
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-ask-obi-answer")

        tap("money-chat-act-goal-0")
        until(core) { showsText("Made a goal: Save") }
        assertTrue(showsText("Made a goal: Save"), "Make it a goal didn't make one")
        assertTrue(core.goalsView.value.goals.any { it.title.startsWith("Save £") }, "the goal isn't in Goals")
    }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun askObiOnTheOpenFold() {
        val core = open()
        tap("money-ask-bar")
        until(core) { shows("money-chat-try-0") }
        tap("money-chat-try-1")
        until(core) { shows("money-chat-answer-0") }
        assertTrue(shows("money-chat-answer-0"), "Can I afford wasn't answered")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "inner-dark-ask-obi"))
        ScreenShots.assertNoBrokenWords(compose, "inner-dark-ask-obi")
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun askObiAQuestionTheRulesCantAnswer() {
        val server = ObiServer()
        val core = open(server)
        tap("money-ask-bar")
        until(core) { shows("money-chat-try-0") }
        val field = hasContentDescription(MoneyChat.FIELD) and hasSetTextAction()
        compose.onAllNodes(field, useUnmergedTree = true)[0].performTextInput("Is coffee becoming a habit?")
        settle()
        compose.onAllNodes(field, useUnmergedTree = true)[0].performImeAction()
        until(core) { shows("money-chat-by-0") }
        assertTrue(server.asked.isNotEmpty(), "the question didn't go to Obi")
        assertTrue(shows("money-chat-by-0"), "no \"${MoneyChatObi.BY}\" under Obi's answer")
        assertTrue(showsText("Costa came to £57.60"), "Obi's answer isn't in the chat")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-ask-obi-ai"))
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-ask-obi-ai")
    }

    /**
     * Slice 3: the bar's mic opens Obi · money and starts the mic at once: it is listening ("Listening · …" under the
     * field) or, with no recogniser, says why under the field, which stays for typing.
     */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun theBarsMicOpensTheChatListening() {
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<ShotApplication>()).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val core = open()
        assertTrue(shows("money-ask-mic"), "no mic on the Ask Obi bar")
        tap("money-ask-mic")
        val started = { shows("money-chat-listening") || shows("money-chat-heard") }
        until(core) { started() }
        assertTrue(shows("money-chat-sheet"), "the mic didn't open the chat")
        assertTrue(shows("money-chat-mic"), "no mic beside the chat's field")
        assertTrue(started(), "the mic neither listened nor said why it couldn't")
        assertTrue(compose.onAllNodes(hasContentDescription(MoneyChatSpoken.MIC_SPOKEN), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
            "the mic has no spoken name")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-ask-obi-mic"))
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-ask-obi-mic")
    }
}
