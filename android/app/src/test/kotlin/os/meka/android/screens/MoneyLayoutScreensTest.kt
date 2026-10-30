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
import os.meka.core.domain.MoneyRules
import os.meka.core.domain.MoneyTabs
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore

/**
 * Money's layout rules on Spending (Meka 2026-10-26 22:18): on 5 Oct last month's review leads as one card of Spent ·
 * Came in · Left with at most two short lines (its sentences behind ⓘ), the category rows and the latest payments sit in
 * cards, and the tab ends on one "Read-only · not financial advice" line whose ⓘ says where the numbers came from. Shot
 * on the cover screen and the open Fold in Dark. Invented data (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class MoneyLayoutScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_791_190_800_000L // Mon 5 Oct 2026, 10:00 in London

    private companion object {
        const val SETTLE_MS = 3_000L
        const val CURRENT = "Date,Description,Amount\n" +
            "01/08/2026,OAKFIELD COUNCIL TAX,-142.00\n" +
            "05/08/2026,SALARY OAKFIELD LTD,2500.00\n" +
            "09/08/2026,TESCO STORES 2041,-80.00\n" +
            "15/08/2026,STREAMLY.COM,-10.99\n" +
            "20/08/2026,PIZZA PLACE,-30.00\n" +
            "01/09/2026,OAKFIELD COUNCIL TAX,-142.00\n" +
            "05/09/2026,SALARY OAKFIELD LTD,2500.00\n" +
            "09/09/2026,TESCO STORES 2041,-64.90\n" +
            "15/09/2026,STREAMLY.COM,-10.99\n" +
            "18/09/2026,PIZZA PLACE,-60.00\n" +
            "26/09/2026,PIZZA PLACE,-45.00\n" +
            "30/09/2026,TESCO STORES 2041,-51.20\n" +
            "01/10/2026,OAKFIELD COUNCIL TAX,-142.00\n" +
            "02/10/2026,TESCO STORES 2041,-38.40\n" +
            "03/10/2026,CORNER CAFE,-4.20\n"
    }

    private fun open(): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, ThemeChoice.DARK)
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
            secureRandom = Random(41), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
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
        if (core.moneyView.value.review == null) fail("September's review should lead on 5 Oct")
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

    private fun scrollTo(tag: String) {
        compose.mainClock.autoAdvance = true
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        settle()
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun theReviewIsTilesAndItsSentencesAndTheSourceSitBehindInfo() {
        val core = open()
        scrollTo("money-review-card")
        assertTrue(showsText("Came in"), "the review should show its tiles")
        assertTrue(!showsText("In September you spent"), "the review's sentences live behind ⓘ")
        compose.onNodeWithTag("screen").captureRoboImage("build/outputs/roborazzi/money-layout-review-cover.png")
        tap("money-review-info")
        assertTrue(shows("money-info-sheet"), "ⓘ should open the review's sheet")
        assertTrue(showsText("In September you spent"))
        compose.onNodeWithTag("screen").captureRoboImage("build/outputs/roborazzi/money-layout-review-info-cover.png")
        tap("money-info-sheet-done")
        assertTrue(!shows("money-info-sheet"))

        scrollTo("money-spend-card")
        assertTrue(shows("money-spend-card"), "the category rows should sit in a card")
        scrollTo("money-latest-card")
        scrollTo("money-foot-info")
        assertTrue(showsText(MoneyTabs.FOOT_SHORT), "Spending should end on one foot line")
        assertTrue(!showsText(MoneyRules.FOOT), "the long read-only line lives behind ⓘ")
        compose.onNodeWithTag("screen").captureRoboImage("build/outputs/roborazzi/money-layout-foot-cover.png")
        tap("money-foot-info")
        assertTrue(showsText(MoneyRules.FOOT) && showsText("From your statements"), "the foot's ⓘ says where the numbers came from")
        if (core.moneyView.value.month == null) fail("the month should still show")
    }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun spendingInCardsOnTheOpenFold() {
        open()
        // Ask Obi's bar sits under the tabs, so the review card can start below the first screen.
        scrollTo("money-review-card")
        assertTrue(shows("money-review-card"))
        compose.onNodeWithTag("screen").captureRoboImage("build/outputs/roborazzi/money-layout-spending-unfolded.png")
    }
}
