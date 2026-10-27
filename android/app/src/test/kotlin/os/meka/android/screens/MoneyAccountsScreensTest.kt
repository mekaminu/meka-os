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
import os.meka.core.domain.MoneyAccountList
import os.meka.core.domain.MoneyBusiness
import os.meka.core.domain.MoneyCards
import os.meka.core.domain.MoneyRules
import os.meka.core.domain.MoneyWorth
import os.meka.core.domain.MoneyTab
import os.meka.core.domain.MoneyTabs
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore

/**
 * Money review (Meka 2026-10-26 22:12, Accounts: "one clean list … tap → account sheet"): Accounts lists each account
 * once (a short name, its balance or Add balance), no Remove links, Side hustle switches, import chips or "Which
 * account?" field on the page; a row opens the account's sheet with its balance, It's a card, Side hustle account,
 * Import and Remove (which asks first). Shot on the cover screen in Dark. Invented data (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class MoneyAccountsScreensTest {
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
    fun accountsIsOneCleanListAndARowOpensItsSheet() {
        val core = open()
        pick(MoneyTab.ACCOUNTS)
        scrollTo("money-accounts-card")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-accounts-list"))
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-accounts-list")
        val v = core.moneyView.value
        val items = MoneyAccountList.items(v)
        assertTrue(items.size == 2, "expected the two accounts, got ${items.map { it.name }}")
        items.forEach { assertTrue(showsText(it.name), "\"${it.name}\" isn't in the list") }
        // Nothing scattered on the page any more.
        if (showsText("Remove it?") || compose.onAllNodesWithText("Remove", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            fail("a Remove link is still on the page")
        // (Exact text: the side hustle's intro line names the switch, which is fine.)
        if (compose.onAllNodesWithText(MoneyBusiness.SWITCH, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
            showsText(MoneyBusiness.SWITCH + " ✓")) fail("a Side hustle account switch is still on the page")
        if (showsText(MoneyRules.ACCOUNT_HINT)) fail("the Which account? field sits open on the page")
        if (showsText(MoneyWorth.BALANCE_HINT)) fail("a balance field sits open on the page")
        val current = v.accounts.first { it.name == "Current account" }
        tap("money-account-${current.id}")
        assertTrue(shows("money-account-sheet"), "the account's sheet didn't open")
        assertTrue(shows("money-account-card"), "no It's a card in the sheet")
        assertTrue(shows("money-account-import"), "no Import in the sheet")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-account-sheet"))
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-account-sheet")
        // Remove asks first, and Cancel keeps it.
        tap("money-account-remove")
        assertTrue(showsText("Remove Current account and its"), "Remove didn't ask first")
        assertTrue(core.moneyView.value.accounts.any { it.id == current.id }, "Remove removed before asking")
        tap("money-account-remove-yes")
        runBlocking { core.tick() }
        settle()
        if (core.moneyView.value.accounts.any { it.id == current.id }) fail("Remove didn't remove the account")
        if (shows("money-account-sheet")) fail("the sheet stayed open on a removed account")
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun importIsOneRowWithItsFieldInASheet() {
        open()
        pick(MoneyTab.ACCOUNTS)
        scrollTo("money-import-row")
        tap("money-import-row")
        assertTrue(shows("money-import-sheet"), "Import a statement's sheet didn't open")
        assertTrue(showsText(MoneyRules.ACCOUNT_HINT), "the sheet has no account field")
        tap("money-import-sheet-done")
        if (shows("money-import-sheet")) fail("Done didn't close the import sheet")
    }
}
