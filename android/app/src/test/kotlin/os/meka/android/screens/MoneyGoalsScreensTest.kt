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
import os.meka.core.domain.CivilDate
import os.meka.core.domain.GoalHorizon
import os.meka.core.domain.MoneyGoals
import os.meka.core.domain.MoneyTab
import os.meka.core.domain.MoneyTabs
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore

/**
 * Money redesign, Plan's goals card (mockup): GOALS on the Plan tab as one card. Two invented £ goals, one due 15 Dec
 * with £240 in, one with no date; the card shows each one's amount, bar and pace note, a tap opens the goal's sheet and
 * a chip there makes it follow the savings account's balance. Shot on the cover screen and the open Fold in Dark.
 * Invented data (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class MoneyGoalsScreensTest {
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
            "09/10/2026,TESCO STORES 2041,-64.90\n"
        const val SAVINGS = "Date,Description,Amount,Balance\n" +
            "01/10/2026,TRANSFER FROM CURRENT,200.00,600.00\n" +
            "12/10/2026,TRANSFER FROM CURRENT,200.00,800.00\n"
    }

    private lateinit var christmas: String
    private lateinit var holiday: String

    private fun open(): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, ThemeChoice.DARK)
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
            secureRandom = Random(37), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            christmas = core.addGoal("Christmas", null, GoalHorizon.YEAR)
            check(core.setGoalNumber(christmas, "£600"))
            core.setGoalDue(christmas, CivilDate.toEpochDay(2026, 12, 15))
            check(core.addGoalUpdate(christmas, "+240") != null)
            holiday = core.addGoal("Holiday 2027", null, GoalHorizon.YEAR)
            check(core.setGoalNumber(holiday, "£3,000"))
            core.openMoney()
            check(!core.importMoneyStatement("Current account", CURRENT).refused)
            check(!core.importMoneyStatement("Savings", SAVINGS).refused)
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
        compose.mainClock.autoAdvance = true
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        settle()
    }

    private fun plan(core: MekaCore) {
        pick(MoneyTab.PLAN)
        if (core.moneyView.value.goals?.rows?.size != 2) fail("the two £ goals should be in the money view")
        scrollTo("money-goals-card")
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun theGoalsCardShowsEachGoalsPaceAndItsSheetFollowsAnAccount() {
        val core = open()
        plan(core)
        assertTrue(showsText("£240 of £600"), "Christmas should show what's in")
        assertTrue(showsText("a month to 15 Dec"), "Christmas should say what it needs a month")
        assertTrue(showsText("no date yet"), "the goal with no date should say so")
        assertTrue(!showsText(MoneyGoals.FOOT), "the foot lives in the sheet, not on the page")
        compose.onNodeWithTag("screen").captureRoboImage("build/outputs/roborazzi/money-plan-goals-cover.png")
        tap("money-goal-$holiday")
        assertTrue(shows("money-goal-sheet"), "a tap should open the goal's sheet")
        assertTrue(showsText(MoneyGoals.NOT_FOLLOWING))
        val savings = core.moneyView.value.accounts.first { it.name == "Savings" }.id
        tap("money-goal-follow-$savings")
        fun follows() = core.moneyView.value.goals?.rows?.firstOrNull { it.id == holiday }?.followsName == "Savings"
        repeat(40) { if (!follows()) { Thread.sleep(50); runBlocking { core.tick() }; settle() } }
        assertTrue(follows(), "the chip should make the goal follow Savings")
        compose.onNodeWithTag("screen").captureRoboImage("build/outputs/roborazzi/money-plan-goal-sheet-cover.png")
        tap("money-goal-sheet-done")
        assertTrue(!shows("money-goal-sheet"))
    }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun theGoalsCardOnTheOpenFold() {
        val core = open()
        plan(core)
        assertTrue(showsText("Christmas") && showsText("Holiday 2027"))
        compose.onNodeWithTag("screen").captureRoboImage("build/outputs/roborazzi/money-plan-goals-unfolded.png")
    }
}
