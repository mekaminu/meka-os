package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasAnyAncestor
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
import os.meka.core.domain.MoneyForecast
import os.meka.core.domain.MoneyRules
import os.meka.core.domain.MoneySave
import os.meka.core.domain.MoneyTab
import os.meka.core.domain.MoneyTabs
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore

/**
 * Money redesign, Plan (mockup): What if I save… on the Plan tab. The card shows the slider's amount and the total by
 * its month; picking 12 months moves the total and month at once; Make it a goal makes a £ goal and says so with Undo.
 * Shot on the cover screen in Dark, on [MoneyShotMonth]'s shared month. Invented data (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class MoneySaveScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object {
        const val SETTLE_MS = 3_000L
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
            // Found and fixing week 5, A5 (g1a 4): this shot imported a statement of its own from 1 Sep, one whole month, so
            // Obi said "Once two whole months are in" beside the account sheet's 1 Aug–20 Oct; it opens on the shared month.
            MoneyShotMonth.fill(core)
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
    private fun showsText(text: String) = compose.onAllNodes(ScreenShots.plainText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

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
        val start = core.moneyView.value.save?.startPence ?: fail("no save base on this month")
        // The slider starts at half his average month (£1,125 on the shared month), as on Plan's other shots.
        assertEquals(112_500L, start, "the slider doesn't start where Plan's other shots do")
        scrollTo("money-save-card")
        assertTrue(showsText(MoneyRules.fmt(start * 6)), "six months of ${MoneyRules.fmt(start)} should total ${MoneyRules.fmt(start * 6)}")
        // Found and fixing week 5, A5: the total says it's what's saved, so it isn't read as the forecast's left over by
        // then ("LEFT BY APR 2027" on the forecast beside it).
        assertTrue(showsText("saved by Apr 2027"), "the saving's total doesn't say it's what's saved")
        // Walk-through A28 slice 5: on the cover screen no month chip sits alone on its row ("24 months" under three).
        val tops = MoneySave.MONTHS.map { m ->
            compose.onNodeWithTag("money-save-months-$m", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top.toInt()
        }
        tops.groupingBy { it }.eachCount().forEach { (top, n) ->
            if (tops.toSet().size > 1 && n < 2) fail("a month chip is alone on its row at y=$top: $tops")
        }
        tap("money-save-months-12")
        assertTrue(showsText(MoneyRules.fmt(start * 12)), "twelve months should total ${MoneyRules.fmt(start * 12)}")
        assertTrue(showsText("saved by Oct 2027"))
        // A5 (g1a 4, 5): the shared month has two whole months, so Obi says what's spare, from the same average month as
        // Plan's tiles and Ask Obi (£2,270 left over less the £200).
        val base = core.moneyView.value.save ?: fail("no save base")
        val left = base.leftPence ?: fail("the shared month's two whole months should make a forecast")
        assertEquals(MoneyShotMonth.FORECAST_LEFT_PENCE, MoneyForecast.roundTen(left), "Plan's average month isn't the shared month's")
        assertFalse(showsText("Once two whole months"), "Obi still says two whole months aren't in")
        val spare = MoneyRules.fmt(MoneySave.spareAfter(base.startPence, left))
        assertTrue(MoneySave.obi(base.startPence, left).first.contains("leaves about $spare spare"), "Obi's sum isn't the split line's")
        assertTrue(showsText("leaves about $spare spare"), "Obi's line isn't what's spare after the amount ($spare)")
        // Found and fixing week 5, A15 (Plan open): the split line no longer says the same spare above Obi's line.
        if (showsText("after saving this")) fail("What if I save says what's spare twice (the split line and Obi's)")
        assertEquals(1, compose.onAllNodes(ScreenShots.plainText(spare, substring = true) and hasAnyAncestor(hasTestTag("money-save-card")), useUnmergedTree = true).fetchSemanticsNodes().size,
            "$spare is said more than once on the save card")
        compose.onNodeWithTag("screen").captureRoboImage("build/outputs/roborazzi/money-plan-save-cover.png")
        tap("money-save-goal")
        // The goal is made on the core (off the main thread): give it a moment, then it's in Goals with a £ number.
        fun made() = core.goalsView.value.goals.any { it.title == "Save ${MoneyRules.fmt(start * 12)} by Oct 2027" && it.metric?.unit == "£" }
        repeat(40) { if (!made()) { Thread.sleep(50); runBlocking { core.tick() }; settle() } }
        assertTrue(made(), "Make it a goal didn't put the goal in Goals with a £ number")
    }
}
