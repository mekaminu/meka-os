package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
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
import os.meka.android.ask.AiSpendRow
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaSpace
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.AiFeature
import os.meka.core.domain.AiMonthUse
import os.meka.core.domain.AiSpendRules
import os.meka.core.domain.AiStatusView
import os.meka.core.domain.AskRules
import os.meka.core.domain.DateGlue
import kotlin.test.fail

/**
 * The quality gate, slice 60 (build plan: "the rest of Ask's places' shots", AI): Ask → Settings → Navilife's AI drawn
 * from invented statuses: not connected (the row and what each AI feature does until it is), on with this month's
 * spend unfolded by feature, and Anthropic refusing the key (the long lit line, eight fallbacks and seven spend rows
 * unfolded), on the Fold's cover and inner screens, Dark (and On in Light). Saved under `ai/` and read for nonsense,
 * cut lines, broken words, split numbers, a grey line leaving a word alone or starting a line with a dot, and each
 * spend row's label and cost on one line. By hand: a tap on the row unfolds the month by feature and a second folds
 * it; a row with nothing metered isn't a button.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class AiScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    private val month = listOf(
        AiMonthUse("ask.talk", 120, 120), AiMonthUse("mail.triage", 340, 85), AiMonthUse("voice.polly", 412, 31),
        AiMonthUse("ask", 18, 9), AiMonthUse("ask.brief", 23, 14), AiMonthUse("extract.message", 96, 22),
        AiMonthUse("extract.callscam", 4, 0), AiMonthUse("handle.share", 1, 1),
    )

    private fun status(fill: Fill): AiStatusView = when (fill) {
        Fill.EMPTY -> AskRules.STATUS_NOT_CONNECTED
        Fill.ONE -> AskRules.statusView("on", null, spentCents = 205, budgetCents = 2_000, level = "ok", month = month.take(2))
        Fill.MANY -> AskRules.statusView("on", null, spentCents = 1_830, budgetCents = 2_000, level = "alert", month = month, keyRefused = true)
    }

    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun onScreen(text: String): Boolean = ScreenShots.texts(compose).any { DateGlue.plain(it).contains(text) }

    private fun tapRow() {
        val clickable = compose.onAllNodes(hasClickAction())
        if (clickable.fetchSemanticsNodes().isEmpty()) fail("the AI row isn't a button with spend to show")
        clickable[0].performSemanticsAction(SemanticsActions.OnClick)
        turns()
    }

    private fun draw(theme: ThemeChoice, ai: AiStatusView) {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    Column(Modifier.padding(MekaSpace.gutter)) { AiSpendRow(ai) }
                }
            }
        }
        turns()
        compose.waitForIdle()
    }

    private fun pane(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK) {
        val ai = status(fill)
        draw(theme, ai)
        if (ai.spend.isNotEmpty()) tapRow()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("ai", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        if (!onScreen("Navilife's AI")) fail("$name: the row has no title")
        if (!onScreen(ai.line)) fail("$name: its line \"${ai.line}\" isn't on screen")
        val lines = listOfNotNull(ai.line, ai.fallbackHeading, ai.spendHeading) + ai.fallbacks.map { it.detail }
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        ScreenShots.assertEachOneLine(compose, name, ai.spend.map { it.detail } + ai.spend.map { it.label } + ai.fallbacks.map { it.label })
        ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
        when (fill) {
            Fill.EMPTY -> {
                if (!onScreen("Until this device is connected")) fail("$name: doesn't say what works until it's connected")
                AiFeature.entries.forEach { if (!onScreen(it.label)) fail("$name: ${it.label}'s fallback isn't listed") }
                if (compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().isNotEmpty()) fail("$name: the row is a button with nothing to unfold")
            }
            Fill.ONE -> {
                if (!onScreen("On · \$2.05 of \$20 this month")) fail("$name: the line doesn't read the month's spend")
                if (!onScreen("This month by feature · \$2.05 in all")) fail("$name: the month by feature doesn't open")
                if (!onScreen("Talk")) fail("$name: Talk's row isn't shown")
                if (onScreen("While it's off")) fail("$name: fallbacks listed while the AI answers")
            }
            Fill.MANY -> {
                if (!onScreen("Key refused by Anthropic")) fail("$name: the refused key isn't said")
                if (!onScreen("Until it answers again")) fail("$name: what still works isn't headed")
                AiSpendRules.lines(month).forEach { if (!onScreen(it.detail)) fail("$name: \"${it.label} · ${it.detail}\" isn't shown") }
                if (!onScreen("412 clips")) fail("$name: Voice isn't counted in clips")
            }
        }
    }

    /** Works by hand: the row unfolds the month by feature, says so to TalkBack, and folds it again. */
    private fun byHand() {
        val name = "ai-by-hand"
        draw(ThemeChoice.DARK, status(Fill.ONE))
        if (onScreen("This month by feature")) fail("$name: the month by feature is open before a tap")
        tapRow()
        if (!onScreen("This month by feature")) fail("$name: a tap didn't unfold the month by feature")
        val state = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes().first().config.getOrNull(SemanticsProperties.StateDescription)
        if (state != "expanded") fail("$name: TalkBack hears \"$state\" once open")
        tapRow()
        if (onScreen("This month by feature")) fail("$name: a second tap didn't fold it")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverEmpty() { pane("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOne() { pane("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOneLight() { pane("cover-light-one", Fill.ONE, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverMany() { pane("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerOne() { pane("inner-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerMany() { pane("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun byHandTest() { byHand() }
}
