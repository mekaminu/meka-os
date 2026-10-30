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
import os.meka.core.domain.MoneyCards
import os.meka.core.domain.MoneyClear
import os.meka.core.domain.MoneyTab
import os.meka.core.domain.MoneyTabs
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore

/**
 * Money redesign, Plan (mockup): Clear my cards on the Plan tab. Two invented credit cards with their statements and
 * APRs typed in their sheets; the card lists both with their rates, the Debt-free and Interest tiles fill, switching to
 * Smallest balance first changes Obi's line, and Use this plan puts "Clear my cards by …" in Goals. Shot on the cover
 * screen and the open Fold in Dark. Invented data (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class MoneyClearScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
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
        const val CARD2 = "Date,Description,Amount\n" +
            "08/10/2026,OAKFIELD GARAGE,-45.00\n"
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
            check(!core.importMoneyStatement("Platinum Card", CARD).refused)
            check(!core.importMoneyStatement("Rewards Card", CARD2).refused)
            core.tick()
            for ((name, owed, apr) in listOf(Triple("Platinum Card", "£4,200", "24.9%"), Triple("Rewards Card", "£1,800", "22.9%"))) {
                val card = core.moneyView.value.accounts.firstOrNull { it.name == name } ?: error("no $name")
                check(!core.moneyCardKind(card.id, card = true, charge = false).refused)
                check(!core.moneyCardField(card.id, MoneyCards.STATEMENT, owed).refused)
                check(!core.moneyCardField(card.id, MoneyCards.APR, apr).refused)
            }
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

    private fun plan(core: MekaCore) {
        pick(MoneyTab.PLAN)
        if (core.moneyView.value.clear == null) fail("no cards to clear on this month")
        scrollTo("money-clear-card")
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun planClearsTheCardsInEitherOrderAndMakesItAGoal() {
        val core = open()
        plan(core)
        assertTrue(showsText("24.9% APR"), "the typed APR should show on its card")
        assertTrue(showsText("Pay each month, both cards"))
        assertTrue(shows("money-clear-free") && shows("money-clear-interest"))
        assertTrue(showsText("Paying only the minimums"))
        assertTrue(showsText("first saves about"), "highest interest first should say what it saves")
        tap("money-clear-order-${MoneyClear.BY_SMALL}")
        assertTrue(showsText("Smallest-first clears"), "smallest first should say it costs more")
        compose.onNodeWithTag("screen").captureRoboImage("build/outputs/roborazzi/money-plan-clear-cover.png")
        tap("money-clear-use")
        fun made() = core.goalsView.value.goals.any { it.title.startsWith("Clear my cards by ") }
        repeat(40) { if (!made()) { Thread.sleep(50); runBlocking { core.tick() }; settle() } }
        assertTrue(made(), "Use this plan didn't put the goal in Goals")
    }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun clearMyCardsOnTheOpenFold() {
        val core = open()
        plan(core)
        assertTrue(showsText("Rewards Card") && showsText("Platinum Card"))
        compose.onNodeWithTag("screen").captureRoboImage("build/outputs/roborazzi/money-plan-clear-unfolded.png")
    }
}
