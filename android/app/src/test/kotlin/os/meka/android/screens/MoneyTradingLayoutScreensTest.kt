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
import os.meka.core.domain.MoneyRules
import os.meka.core.domain.MoneyTradingLayout
import os.meka.core.domain.TradingTracker
import os.meka.core.domain.MoneyTab
import os.meka.core.domain.MoneyTabs
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore

/**
 * Money's layout rules on Trading (Meka 2026-10-26 22:18; the approved mockup's Trading tab): the tracker as cards under
 * their labels (equity with a Read-only mark, YOUR ADDRESSES, ALERTS, EXPORT FOR TAX), its long source line off the page,
 * and the tab ending on one "Read-only · not trading advice" line whose ⓘ says where the figures come from. One invented
 * public address (never a key), not yet read. Shot on the cover screen and the open Fold in Dark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class MoneyTradingLayoutScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object {
        const val SETTLE_MS = 3_000L
        const val CURRENT = "Date,Description,Amount\n" +
            "05/10/2026,SALARY OAKFIELD LTD,2500.00\n" +
            "09/10/2026,TESCO STORES 2041,-64.90\n"
        // Invented: 0x and 40 hex digits, a public address shape only.
        val ADDRESS = "0x" + "ab12".repeat(10)
    }

    private fun open(): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, ThemeChoice.DARK)
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
            secureRandom = Random(43), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            core.openMoney()
            check(!core.importMoneyStatement("Current account", CURRENT).refused)
            check(!core.tradingWalletAdd("Main $ADDRESS").refused)
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
    fun tradingInCardsWithOneFootLine() {
        val core = open()
        pick(MoneyTab.TRADING)
        val t = core.moneyView.value.trading ?: fail("Trading has no view with an address in")
        assertTrue(shows("money-trading-card"), "equity should sit in one card")
        assertTrue(shows("money-trading-readonly"), "the equity card should carry the Read-only mark")
        assertTrue(showsText(TradingTracker.WAITING), "before the first read the equity card says it's reading")
        if (showsText(TradingTracker.FOOT)) fail("the tracker's long source line still sits on the page")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-trading-layout"))
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-trading-layout")
        scrollTo("money-addresses-card")
        assertTrue(showsText(MoneyTradingLayout.ADDRESSES_HEAD), "the addresses sit under their own label")
        assertTrue(showsText(t.wallets.first().name), "the address's name shows in its card")

        scrollTo("money-foot-info")
        assertTrue(showsText(MoneyTradingLayout.FOOT_SHORT), "Trading should end on one foot line")
        if (showsText(MoneyRules.FOOT)) fail("Money's long read-only line sits on Trading")
        tap("money-foot-info")
        assertTrue(showsText(TradingTracker.FOOT.substringBefore(" · ")), "the foot's ⓘ says where the figures come from")
    }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun tradingInCardsOnTheOpenFold() {
        open()
        pick(MoneyTab.TRADING)
        assertTrue(shows("money-trading-card"))
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "inner-dark-trading-layout"))
    }
}
