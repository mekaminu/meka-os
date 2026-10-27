package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlin.random.Random
import kotlin.test.assertTrue
import kotlin.test.fail
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
import os.meka.core.domain.MoneySave
import os.meka.core.domain.MoneyTab
import os.meka.core.domain.MoneyTabs
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore

/**
 * Money redesign, Plan (mockup): What if I save… on the Plan tab. The card shows the slider's amount and the total by
 * its month; picking 12 months moves the total and month at once; Make it a goal makes a £ goal and says so with Undo.
 * Shot on the cover screen in Dark. Invented data (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class MoneySaveScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object {
        const val SETTLE_MS = 3_000L
        const val CURRENT = "Date,Description,Amount\n" +
            "01/09/2026,OAKFIELD COUNCIL TAX,-142.00\n" +
            "05/09/2026,SALARY OAKFIELD LTD,2500.00\n" +
            "15/09/2026,STREAMLY.COM,-10.99\n" +
            "01/10/2026,OAKFIELD COUNCIL TAX,-142.00\n" +
            "05/10/2026,SALARY OAKFIELD LTD,2500.00\n" +
            "09/10/2026,TESCO STORES 2041,-64.90\n" +
            "15/10/2026,STREAMLY.COM,-10.99\n"
        const val CARD = "Date,Description,Amount\n" +
            "06/10/2026,AMAZON MARKETPLACE,-32.49\n" +
            "13/10/2026,DELIVEROO,-21.80\n"
    }

    private fun open(): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, ThemeChoice.DARK)
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
            secureRandom = Random(31), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
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

    private fun pick(tab: MoneyTab) {
        compose.onNodeWithContentDescription(MoneyTabs.spoken(tab, chosen = false), useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    private fun scrollTo(tag: String) {
        // A lazy list's scroll waits for a frame, so the clock runs while it scrolls.
        compose.mainClock.autoAdvance = true
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        settle()
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun planWorksOutWhatIfISaveAndMakesItAGoal() {
        val core = open()
        pick(MoneyTab.PLAN)
        if (core.moneyView.value.save == null) fail("no save base on this month")
        scrollTo("money-save-card")
        assertTrue(showsText("£1,200"), "six months of £200 should total £1,200")
        assertTrue(showsText("by Apr 2027"))
        tap("money-save-months-12")
        assertTrue(showsText("£2,400"), "twelve months of £200 should total £2,400")
        assertTrue(showsText("by Oct 2027"))
        assertTrue(showsText("Once two whole months"), "Obi can't say whether it fits before two whole months are in")
        compose.onNodeWithTag("screen").captureRoboImage("build/outputs/roborazzi/money-plan-save-cover.png")
        tap("money-save-goal")
        // The goal is made on the core (off the main thread): give it a moment, then it's in Goals with a £ number.
        fun made() = core.goalsView.value.goals.any { it.title == "Save £2,400 by Oct 2027" && it.metric?.unit == "£" }
        repeat(40) { if (!made()) { Thread.sleep(50); runBlocking { core.tick() }; settle() } }
        assertTrue(made(), "Make it a goal didn't put the goal in Goals with a £ number")
    }
}
