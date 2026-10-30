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
import os.meka.core.domain.MoneyBills
import os.meka.core.domain.MoneyRefunds
import os.meka.core.domain.MoneyRules
import os.meka.core.domain.MoneyTab
import os.meka.core.domain.MoneyTabs
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore

/**
 * Money's layout rules on Bills (Meka 2026-10-26 22:18: "it's sentences and text, not segmented, boxed, grouped"): the
 * month's bills sit in one card, refunds on the way are compact one-line rows (three, then "1 more ›") each opening a
 * sheet, and the tab ends on one "Read-only · not financial advice" line whose ⓘ says where the bills come from. Shot on
 * the cover screen and the open Fold in Dark. Invented data (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class MoneyBillsLayoutScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object {
        const val SETTLE_MS = 3_000L
        const val CURRENT = "Date,Description,Amount\n" +
            "01/08/2026,OAKFIELD COUNCIL TAX,-142.00\n" +
            "15/08/2026,STREAMLY.COM,-10.99\n" +
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
            secureRandom = Random(37), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            core.openMoney()
            check(!core.importMoneyStatement("Current account", CURRENT).refused)
            check(!core.importMoneyStatement("Platinum Card", CARD).refused)
            core.tick()
            val card = core.moneyView.value.accounts.firstOrNull { it.name == "Platinum Card" } ?: error("no card account")
            check(!core.moneyCardKind(card.id, card = true, charge = false).refused)
            listOf("Refund · Shoe Shop · £40", "Refund · Lamp Store · £18", "Refund · Kettle Co · £25", "Refund · Book Barn · £9")
                .forEach { core.addWaiting(it, null, null) }
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
    fun billsInCardsWithCompactRefundsAndOneFootLine() {
        val core = open()
        pick(MoneyTab.BILLS)
        val rf = core.moneyView.value.refunds ?: fail("the refunds aren't on Bills")
        if (rf.rows.size != 4) fail("four refunds should be waiting, not ${rf.rows.size}")
        if (core.moneyView.value.bills?.rows.isNullOrEmpty()) fail("the bills aren't on Bills")
        scrollTo("money-bills-list")
        if (showsText(MoneyBills.FOOT)) fail("the bills' source line still sits under them")
        scrollTo("money-refunds-card")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-bills-layout"))
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-bills-layout")
        // Three compact rows, then "1 more ›"; the chase and the note live in the sheet.
        assertTrue(showsText("Waiting · since today"), "a refund row should say its state in a few words")
        if (showsText(MoneyRefunds.NOTE)) fail("the chase note sits on the page")
        // Money review item 9: each state starts at the same left edge (under its name), not in a ragged middle column.
        val lefts = rf.rows.take(3).map { r ->
            compose.onNodeWithTag("money-refund-brief-${r.id}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        }
        if (lefts.distinct().size != 1) fail("the refunds' states don't line up: $lefts")
        val nameLeft = compose.onNodeWithTag("money-refund-${rf.rows.first().id}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left
        if (lefts.first() - nameLeft > 48f) fail("a refund's state should sit under its name, not out in a middle column")
        if (shows("money-refund-${rf.rows[3].id}")) fail("a fourth refund shows before 1 more")
        tap("money-refunds-more")
        assertTrue(shows("money-refund-${rf.rows[3].id}"), "1 more should show the fourth refund")
        tap("money-refund-${rf.rows.first().id}")
        assertTrue(shows("money-refund-sheet"), "a refund's row should open its sheet")
        assertTrue(showsText("Not in your statements yet"), "the sheet says the full line")
        tap("money-refund-sheet-done")
        if (shows("money-refund-sheet")) fail("Done didn't close the refund's sheet")

        scrollTo("money-foot-info")
        assertTrue(showsText(MoneyTabs.FOOT_SHORT), "Bills should end on one foot line")
        if (showsText(MoneyRules.FOOT)) fail("the long read-only line sits on Bills")
        tap("money-foot-info")
        assertTrue(showsText(MoneyRules.FOOT) && showsText("Bills are found from"), "the foot's ⓘ says where the bills come from")
    }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun billsInCardsOnTheOpenFold() {
        open()
        pick(MoneyTab.BILLS)
        assertTrue(shows("money-bills-list") || shows("money-refunds-card"))
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "inner-dark-bills-layout"))
    }
}
