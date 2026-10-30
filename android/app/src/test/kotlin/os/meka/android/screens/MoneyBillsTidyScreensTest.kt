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
import os.meka.core.domain.MoneyPayday
import os.meka.core.domain.MoneyTab
import os.meka.core.domain.MoneyTabs
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore

/**
 * Money review (Meka 2026-10-26 22:12, Bills: "remove the inline forms"): on Bills a card is one row ("Due ? · tap to
 * add") that opens its sheet with the fields, Until payday is one row that opens its sheet with the balance field, no
 * field sits open on the page and the regular payments don't repeat the bills; the accounts that could be a card are on
 * Accounts. Shot on the cover screen in Dark. Invented data (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class MoneyBillsTidyScreensTest {
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
            core.tick()
            val card = core.moneyView.value.accounts.firstOrNull { it.name == "Platinum Card" } ?: error("no card account")
            check(!core.moneyCardKind(card.id, card = true, charge = false).refused)
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
    fun billsHasNoOpenFormsAndEachRowOpensItsSheet() {
        val core = open()
        pick(MoneyTab.BILLS)
        val v = core.moneyView.value
        val cv = v.cards ?: fail("no cards view with a card marked")
        if (cv.rows.isEmpty()) fail("the card isn't on Bills")
        scrollTo("money-cards-card")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-bills-tidy"))
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-bills-tidy")
        assertTrue(showsText(MoneyCards.ADD_DETAILS), "the card's row doesn't say \"${MoneyCards.ADD_DETAILS}\"")
        // No form on the page: the card's fields and the balance field wait in their sheets.
        if (showsText(MoneyPayday.BALANCE_HINT)) fail("the balance field sits open on Bills")
        if (showsText("Statement balance, e.g.")) fail("a card's fields sit open on Bills")
        if (showsText("Edit details") || showsText("Done editing")) fail("the old Edit details toggle is still there")
        // The regular payments are the bills' list, and the accounts that could be a card moved to Accounts.
        if (showsText("REGULAR PAYMENTS")) fail("the regular payments repeat the bills")
        if (showsText(MoneyCards.OFFER_MORE) || showsText(MoneyCards.MARK)) fail("Bills still offers accounts as cards")
        tap("money-card-${cv.rows.first().id}")
        assertTrue(shows("money-card-sheet"), "the card's sheet didn't open")
        assertTrue(showsText("Statement balance, e.g."), "the card's sheet has no fields")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-card-sheet"))
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-card-sheet")
        tap("money-card-sheet-done")
        if (shows("money-card-sheet")) fail("Done didn't close the card's sheet")
        if (v.payday != null) {
            scrollTo("money-payday-row")
            tap("money-payday-row")
            assertTrue(shows("money-payday-sheet"), "Until payday's sheet didn't open")
            tap("money-payday-sheet-done")
        }
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun anAccountThatCouldBeACardIsMarkedInItsSheetOnAccounts() {
        val core = open()
        pick(MoneyTab.ACCOUNTS)
        // Money review, Accounts: no "Is one of these a card?" list on the page; each account's sheet has It's a card.
        if (showsText(MoneyCards.OFFERS_HEAD)) fail("Accounts still lists the accounts that could be a card")
        val current = core.moneyView.value.accounts.first { it.name == "Current account" }
        scrollTo("money-account-${current.id}")
        tap("money-account-${current.id}")
        assertTrue(shows("money-account-card"), "the account's sheet has no It's a card switch")
    }
}
