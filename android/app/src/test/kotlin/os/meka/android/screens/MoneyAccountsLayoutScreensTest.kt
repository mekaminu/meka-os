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
import os.meka.core.domain.MoneyWorth
import os.meka.core.domain.MoneyYear
import os.meka.core.domain.MoneyRules
import os.meka.core.domain.MoneyTab
import os.meka.core.domain.MoneyTabs
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore

/**
 * Money's layout rules on Accounts (Meka 2026-10-26 22:18: "it's sentences and text, not segmented, boxed, grouped"): his
 * banks, net worth and the year each sit in one card, net worth's and the year's source lines leave the page, and the tab
 * ends on one "Read-only · not financial advice" line whose ⓘ says where the accounts, net worth and the year come from.
 * Shot on the cover screen and the open Fold in Dark. Invented data (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class MoneyAccountsLayoutScreensTest {
    @get:Rule
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
            secureRandom = Random(41), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
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
    fun accountsInCardsWithOneFootLine() {
        val core = open()
        pick(MoneyTab.ACCOUNTS)
        val v = core.moneyView.value
        val w = v.worth ?: fail("net worth isn't on Accounts")
        val yv = v.year ?: fail("the year so far isn't on Accounts")
        assertTrue(shows("money-worth-card"), "net worth should sit in one card")
        if (showsText(w.foot)) fail("net worth's source line still sits on the page")
        if (showsText(MoneyWorth.FOOT)) fail("net worth's source line still sits on the page")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-accounts-layout"))
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-accounts-layout")
        scrollTo("money-year-card")
        if (showsText(yv.foot) || showsText(MoneyYear.FOOT)) fail("the year's source line still sits on the page")

        scrollTo("money-foot-info")
        assertTrue(showsText(MoneyTabs.FOOT_SHORT), "Accounts should end on one foot line")
        if (showsText(MoneyRules.FOOT)) fail("the long read-only line sits on Accounts")
        tap("money-foot-info")
        assertTrue(showsText(MoneyRules.FOOT) && showsText(MoneyTabs.ACCOUNTS_FROM), "the foot's ⓘ says where the accounts come from")
        assertTrue(showsText(MoneyWorth.FOOT.substringBefore(";")), "the foot's ⓘ says how net worth is worked out")
    }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun accountsInCardsOnTheOpenFold() {
        open()
        pick(MoneyTab.ACCOUNTS)
        assertTrue(shows("money-worth-card"))
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "inner-dark-accounts-layout"))
    }
}
