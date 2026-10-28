package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlin.random.Random
import kotlin.test.assertEquals
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
import os.meka.core.domain.ApprovalKind
import os.meka.core.domain.ApprovalRules
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MekaSchema
import os.meka.core.domain.MoneyRules
import os.meka.core.domain.TradingCoinStats
import os.meka.core.domain.TradingCurvePoint
import os.meka.core.domain.TradingEquity
import os.meka.core.domain.TradingPosition
import os.meka.core.domain.TradingWalletFields
import os.meka.core.domain.MoneyTradingLayout
import os.meka.core.domain.TradingTracker
import os.meka.core.domain.MoneyTab
import os.meka.core.domain.MoneyTabs
import os.meka.core.facade.MekaCore
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

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

    /**
     * What the server would have written: the address read (equity, a BTC long 5× about 17% from liquidation, funding
     * paid over the month) and one request from Kestrel waiting in the approvals inbox. All invented.
     */
    private fun seedKestrelAndRisk(store: InMemoryReplicaStore, equityCard: Boolean = false) {
        var k = 0
        val server = Replica("hh", "server", HlcClock("server", { now }), store, MekaSchema) { "seed${k++}" }
        val id = TradingTracker.walletId(ADDRESS)
        server.commitLocal(EntityTypes.TRADING_WALLET, id, mapOf(
            TradingWalletFields.ADDRESS to ADDRESS.fv(), TradingWalletFields.LABEL to "Main".fv(),
            TradingWalletFields.STATE to TradingTracker.OK.fv(), TradingWalletFields.READ_AT to now.fv(),
            TradingWalletFields.EQUITY to 650_000L.fv(), TradingWalletFields.UNREALISED to (-1_000L).fv(),
            TradingWalletFields.POSITIONS to TradingTracker.encode(listOf(TradingPosition("BTC", "0.1", "60000", "60000", "49800", -1_000, 5))).fv(),
            TradingWalletFields.TRADES to TradingTracker.encodeTrades(listOf(TradingCoinStats("BTC", 3, 2, 1, 4_200, 300, -3_820, 6_000, -1_800, 4_000, -1_800))).fv(),
            TradingWalletFields.TRADES_AT to now.fv(),
        ) + if (!equityCard) emptyMap() else mapOf(
            // The equity card as the mockup: the month's P&L, a fortnight's day ends and the ECB's rate (all invented).
            TradingWalletFields.PNL_DAY to 1_200L.fv(), TradingWalletFields.PNL_WEEK to (-2_400L).fv(),
            TradingWalletFields.PNL_MONTH to 47_000L.fv(), TradingWalletFields.PNL_ALL to 120_000L.fv(),
            TradingWalletFields.CURVE to TradingTracker.encodeCurve((14 downTo 1).map { d ->
                TradingCurvePoint(now.floorDiv(86_400_000L) - d, 600_000L + (14 - d) * 3_000L + (d % 3) * 2_000L, (14 - d) * 3_000L + (d % 3) * 2_000L)
            }).fv(),
            TradingWalletFields.CURVE_NOW to 47_000L.fv(), TradingWalletFields.GBP_PER_USD to 775_000L.fv(),
            TradingWalletFields.FX_DAY to now.floorDiv(86_400_000L).fv(),
        ))
        ApprovalRules.requestFields(ApprovalKind.TRADE, "Kestrel", "Go long ETH 0.8 at 3,420", listOf("About £2,140", "Stop 3,330"),
            now - 5 * 60_000L, now + 40 * 60_000L)!!.let { server.commitLocal(EntityTypes.APPROVAL, ApprovalRules.entityId("Kestrel", "r1"), it) }
    }

    private fun open(seed: Boolean = false, equityCard: Boolean = false): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, ThemeChoice.DARK)
        val store = InMemoryReplicaStore()
        if (seed) seedKestrelAndRisk(store, equityCard)
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = store, transport = null,
            secureRandom = Random(43), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            core.openMoney()
            check(!core.importMoneyStatement("Current account", CURRENT).refused)
            if (!seed) check(!core.tradingWalletAdd("Main $ADDRESS").refused)
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    MoneyPane(core, onClose = {}, onNeedsYou = { reviewed++ })
                }
            }
        }
        settle()
        return core
    }

    private var reviewed = 0

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

    /** Layout rule (e) on Trading: no field on the page; "Add an address ›" opens a sheet with it, and adding works there. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun theAddressFieldSitsInASheet() {
        val core = open()
        pick(MoneyTab.TRADING)
        scrollTo("money-addresses-card")
        val field = hasContentDescription(MoneyTradingLayout.ADD_HINT) and hasSetTextAction()
        if (compose.onAllNodes(field, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) fail("the address field still sits open on the page")
        assertTrue(showsText(MoneyTradingLayout.ADD_TITLE), "YOUR ADDRESSES should end on one Add an address row")
        tap("money-address-add")
        assertTrue(shows("money-address-sheet"), "Add an address should open its sheet")
        assertTrue(showsText(MoneyTradingLayout.ADD_SHEET_LINE.substringBefore(".")), "the sheet should say it's the public address only")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-trading-add-address"))
        compose.onAllNodes(field, useUnmergedTree = true)[0].performTextInput("Spare 0x" + "cd34".repeat(10))
        settle()
        compose.onAllNodes(field, useUnmergedTree = true)[0].performImeAction()
        repeat(40) { if (core.moneyView.value.trading?.wallets?.size != 2) { Thread.sleep(50); runBlocking { core.tick() }; settle() } }
        assertEquals(2, core.moneyView.value.trading?.wallets?.size, "the second address should be followed")
        assertTrue(showsText("Added Spare"), "the sheet should say the address was added")
    }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun tradingInCardsOnTheOpenFold() {
        open()
        pick(MoneyTab.TRADING)
        assertTrue(shows("money-trading-card"))
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "inner-dark-trading-layout"))
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun obisRiskLineAndKestrelsCard() {
        val core = open(seed = true)
        pick(MoneyTab.TRADING)
        val risk = core.moneyView.value.trading?.risk ?: fail("Trading has no risk line with a position read")
        assertTrue(risk.line.contains("Your BTC long is 5×: a 17% drop reaches its liquidation price."), risk.line)
        assertTrue(risk.line.contains("Funding has cost $38.20"), risk.line)
        assertTrue(shows("money-trading-risk-card"), "Obi's risk line sits in its own card")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-trading-risk"))
        scrollTo("money-kestrel-card")
        assertTrue(showsText("1 trade waiting for your OK"), "Kestrel's card says what's waiting")
        assertTrue(showsText("Go long ETH 0.8 at 3,420"), "the waiting request's title shows")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-trading-kestrel"))
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-trading-kestrel")
        // Review takes him to Needs you; nothing on Trading approves.
        if (showsText(ApprovalRules.APPROVE)) fail("Trading must not offer Approve")
        tap("money-kestrel-review")
        assertTrue(reviewed == 1, "Review should open Needs you")
    }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun obisRiskLineAndKestrelsCardOnTheOpenFold() {
        open(seed = true)
        pick(MoneyTab.TRADING)
        assertTrue(shows("money-trading-risk-card"))
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "inner-dark-trading-risk"))
    }

    /**
     * README item 5: once read, Trading leads with the equity card as the mockup — its label inside (no TRADING ·
     * HYPERLIQUID above), the value with "≈ £" beside it, the month's change, its line, 1D · 1W · 1M — and the separate
     * P&L card and the Dollars · Pounds row are gone. A range chip changes the line; a tap on "≈ £" shows pounds.
     */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun theEquityCardLeadsAsTheMockup() {
        val core = open(seed = true, equityCard = true)
        pick(MoneyTab.TRADING)
        val c = core.moneyView.value.trading?.equity ?: fail("no equity card once the address is read")
        assertTrue(showsText(c.label), "the card carries its own label")
        if (showsText(TradingTracker.HEAD)) fail("TRADING · HYPERLIQUID still sits above the card")
        assertTrue(shows("money-trading-other"), "the other currency sits beside the value")
        val month = TradingEquity.range(c, TradingEquity.MONTH).change!!
        assertTrue(showsText(month), "the month's change shows first: $month")
        if (shows("money-curve-card")) fail("the P&L line still has a card of its own")
        if (showsText("Dollars")) fail("the Dollars · Pounds row still sits on the card")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-trading-equity"))
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-trading-equity")
        compose.onNodeWithContentDescription(TradingEquity.chips(c, TradingEquity.MONTH)[1].spoken, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.OnClick)
        settle()
        assertTrue(showsText(TradingEquity.range(c, TradingEquity.WEEK).change!!), "1W shows the week's change")
        tap("money-trading-other")
        repeat(40) { if (core.moneyView.value.trading?.equity?.total?.startsWith("£") != true) { Thread.sleep(50); runBlocking { core.tick() }; settle() } }
        assertTrue(core.moneyView.value.trading?.equity?.total?.startsWith("£") == true, "a tap on ≈ £ shows Trading in pounds")
    }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun theEquityCardOnTheOpenFold() {
        open(seed = true, equityCard = true)
        pick(MoneyTab.TRADING)
        assertTrue(shows("money-trading-other"))
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "inner-dark-trading-equity"))
    }
}
