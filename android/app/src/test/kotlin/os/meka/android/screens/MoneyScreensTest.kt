package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlin.test.fail
import org.junit.Rule
import android.os.Looper
import org.robolectric.Shadows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.ask.MoneyPane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.domain.MoneyAccountList
import os.meka.core.domain.MoneyRules
import os.meka.core.domain.MoneyTab
import os.meka.core.domain.MoneyTabs
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random

/**
 * The quality gate, slice 11 (build plan: "the same shots for every other screen (Money, …)"): Ask → Life → Money on the
 * Fold's cover and inner screens, opened (the fingerprint's yes stood in by [MekaCore.openMoney]): nothing imported yet,
 * one account with a few payments, and a full month (two accounts, September and October, groceries, eating out,
 * transport, bills, salary, a pension counted in net worth), Dark and Light. Saved under `money/` (the `fold-screens`
 * artifact, then the `screens` branch's docs/screens/fold/money/) and read for nonsense, cut lines and words broken in
 * two. Invented data only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class MoneyScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(23), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private companion object {
        const val SETTLE_MS = 3_000L

        const val ONE = "Date,Description,Amount\n" +
            "02/10/2026,TESCO STORES 2041,-40.00\n" +
            "03/10/2026,COSTA COFFEE,-3.20\n" +
            "05/10/2026,SALARY OAKFIELD LTD,2500.00\n"

        const val CURRENT = "Date,Description,Amount\n" +
            "01/09/2026,OAKFIELD COUNCIL TAX,-142.00\n" +
            "04/09/2026,TESCO STORES 2041,-62.40\n" +
            "05/09/2026,SALARY OAKFIELD LTD,2500.00\n" +
            "11/09/2026,TESCO STORES 2041,-58.10\n" +
            "15/09/2026,NETFLIX.COM,-10.99\n" +
            "18/09/2026,TFL TRAVEL CH,-38.50\n" +
            "25/09/2026,COSTA COFFEE,-3.20\n" +
            "01/10/2026,OAKFIELD COUNCIL TAX,-142.00\n" +
            "02/10/2026,TESCO STORES 2041,-71.25\n" +
            "03/10/2026,COSTA COFFEE,-3.20\n" +
            "05/10/2026,SALARY OAKFIELD LTD,2500.00\n" +
            "07/10/2026,PRET A MANGER,-6.85\n" +
            "09/10/2026,TESCO STORES 2041,-64.90\n" +
            "12/10/2026,TFL TRAVEL CH,-41.20\n" +
            "14/10/2026,COSTA COFFEE,-3.40\n" +
            "15/10/2026,NETFLIX.COM,-10.99\n" +
            "16/10/2026,SHELL FORECOURT 112,-58.00\n" +
            "17/10/2026,THE GARDEN SHOP OAKFIELD,-24.99\n" +
            "19/10/2026,COSTA COFFEE,-3.40\n" +
            "20/10/2026,TESCO STORES 2041,-55.60\n"

        const val CARD = "Date,Description,Amount\n" +
            "06/10/2026,AMAZON MARKETPLACE,-32.49\n" +
            "13/10/2026,DELIVEROO,-21.80\n" +
            "18/10/2026,CINEWORLD OAKFIELD,-17.50\n"
    }

    private enum class Fill { EMPTY, ONE, MANY }

    private fun settle() {
        repeat((SETTLE_MS / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)
    }

    private fun shoot(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK, tab: MoneyTab = MoneyTabs.FIRST) {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core()
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            // The fingerprint's yes: the pane only asks for it while Money is locked.
            core.openMoney()
            when (fill) {
                Fill.EMPTY -> Unit
                Fill.ONE -> check(!core.importMoneyStatement("Current account", ONE).refused)
                Fill.MANY -> {
                    check(!core.importMoneyStatement("Current account", CURRENT).refused)
                    check(!core.importMoneyStatement("Credit card", CARD).refused)
                    check(!core.moneyWorthAdd("Pension £42,000").refused)
                }
            }
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    MoneyPane(core, onClose = {})
                }
            }
        }
        settle()
        // Money redesign (B), slice 1: the tab's pill, as he'd tap it.
        if (tab != MoneyTabs.FIRST) {
            // The row scrolls sideways on the cover screen, so Accounts may start partly off the edge: its click action, not
            // a touch at its centre (and not performScrollTo, whose animated scroll never ends with the clock held).
            compose.onNodeWithContentDescription(MoneyTabs.spoken(tab, chosen = false), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.OnClick)
            // The main looper runs too (the view's flow and the tab's state land there), else the shot catches the tab
            // before it switched: the mockup check of 2026-10-28 found Bills, Plan and Trading shot empty under "Spending".
            settle()
        }
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertReadable(compose, name, emptyList(), mustShow = false)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertShows(compose, name, MoneyRules.TITLE)
        val v = core.moneyView.value
        if (v.locked) fail("$name: Money is still locked after opening")
        // Money's top as the mockup: the line under the title only when nothing is in yet ("2 accounts · …" is gone), and
        // the chosen tab's pill really is the chosen one in the shot.
        val summaryShown = compose.onAllNodesWithText(v.summary).fetchSemanticsNodes().isNotEmpty()
        if (summaryShown != os.meka.core.domain.MoneyHeader.showsSummary(v)) fail("$name: the line under the title shows=$summaryShown")
        if (compose.onAllNodesWithContentDescription(MoneyTabs.spoken(tab, chosen = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            fail("$name: the ${tab.label} pill isn't the chosen one")
        // Slice 1 of the redesign: five tabs, the chosen one selected, and only its own sections on screen.
        MoneyTabs.ALL.forEach { t ->
            val spoken = MoneyTabs.spoken(t, chosen = t == tab)
            if (compose.onAllNodesWithContentDescription(spoken, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
                fail("$name: no \"$spoken\" pill")
        }
        val importShown = compose.onAllNodesWithText(os.meka.core.domain.MoneyMore.DATA).fetchSemanticsNodes().isNotEmpty()
        // (A lazy list only lays out what's on screen, so Import is only required on Accounts with nothing above it.)
        if (importShown && tab != MoneyTab.ACCOUNTS) fail("$name: Import a statement shows on ${tab.label}")
        if (!importShown && tab == MoneyTab.ACCOUNTS && fill == Fill.EMPTY) fail("$name: Import a statement is missing from Accounts")
        if (tab != MoneyTab.SPENDING) {
            if (compose.onAllNodesWithText("WHERE IT WENT").fetchSemanticsNodes().isNotEmpty()) fail("$name: Spending's categories show on ${tab.label}")
            MoneyTabs.empty(tab, v)?.let { ScreenShots.assertShows(compose, name, it) }
            return
        }
        when (fill) {
            // Nothing in: the line says how to start, never a month of "£0".
            Fill.EMPTY -> {
                ScreenShots.assertShows(compose, name, MoneyRules.EMPTY_SUMMARY.substringBefore(" ·"))
                ScreenShots.assertShows(compose, name, MoneyTabs.EMPTY_SPENDING)
                // Slice 12: the how-to is said once (under Import a statement), never under the title as well.
                val said = compose.onAllNodesWithText(MoneyRules.EMPTY).fetchSemanticsNodes().size
                if (said > 1) fail("$name: the empty line is said $said times")
            }
            else -> {
                val m = v.month ?: fail("$name: no month shown with a statement in")
                ScreenShots.assertShows(compose, name, m.label)
                // Money redesign (B2): the ring's middle says what went out (the old "Spent … · in …" line is gone).
                val sp = m.spending ?: fail("$name: no Spending view with a statement in")
                ScreenShots.assertShows(compose, name, sp.centre.top)
                ScreenShots.assertShows(compose, name, sp.centre.big)
                if (compose.onAllNodesWithText(m.spentLine).fetchSemanticsNodes().isNotEmpty()) fail("$name: the old spent line still shows")
                // As the mockup: Categories · Needs vs wants sit under the ring and its tiles (over the rows), not above it.
                if (sp.needs != null) {
                    val chips = compose.onAllNodesWithTag("money-view-categories", useUnmergedTree = true).fetchSemanticsNodes()
                    val big = compose.onAllNodesWithText(sp.centre.big, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
                    if (chips.isNotEmpty() && big != null && chips.first().boundsInRoot.top < big.boundsInRoot.bottom)
                        fail("$name: the view chips sit above the ring")
                }
                // Slice 12: an average is named by the months it covers (two months of statements here, never "6-month").
                m.vsLine?.let { if ("6-month" in it) fail("$name: \"$it\" from ${v.accounts.size} account(s) of at most two months") }
                // Slice 13: a current account is never offered as a card (it waits behind "Show 1 more account"),
                // and the year's line doesn't repeat its head ("2026 SO FAR" over "Your year so far · …").
                if (compose.onAllNodesWithContentDescription("Current account is a card", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
                    fail("$name: the Current account is offered as a card")
                if (compose.onAllNodesWithText("Your year so far", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
                    fail("$name: the year's line repeats its head")
                // Slice 12: with no Hyperliquid address the set-up block waits below the month (and Import).
                val t = v.trading
                if (t != null && !v.tradingFirst) {
                    val monthTop = compose.onAllNodesWithText(m.label).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.top
                        ?: fail("$name: the month's label isn't on screen")
                    compose.onAllNodesWithText(t.head).fetchSemanticsNodes().forEach { n ->
                        if (n.boundsInRoot.top < monthTop) fail("$name: ${t.head} sits above the month")
                    }
                }
            }
        }
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverEmpty() = shoot("cover-dark-empty", Fill.EMPTY)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverOne() = shoot("cover-dark-one", Fill.ONE)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverMany() = shoot("cover-dark-many", Fill.MANY)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverManyLight() = shoot("cover-light-many", Fill.MANY, ThemeChoice.LIGHT)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverBills() = shoot("cover-dark-bills", Fill.MANY, tab = MoneyTab.BILLS)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverPlan() = shoot("cover-dark-plan", Fill.MANY, tab = MoneyTab.PLAN)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverTrading() = shoot("cover-dark-trading", Fill.MANY, tab = MoneyTab.TRADING)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverAccounts() = shoot("cover-dark-accounts", Fill.MANY, tab = MoneyTab.ACCOUNTS)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverEmptyAccounts() = shoot("cover-dark-empty-accounts", Fill.EMPTY, tab = MoneyTab.ACCOUNTS)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerAccountsLight() = shoot("inner-light-accounts", Fill.MANY, ThemeChoice.LIGHT, tab = MoneyTab.ACCOUNTS)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerEmpty() = shoot("inner-dark-empty", Fill.EMPTY)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerOne() = shoot("inner-dark-one", Fill.ONE)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerMany() = shoot("inner-dark-many", Fill.MANY)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerManyLight() = shoot("inner-light-many", Fill.MANY, ThemeChoice.LIGHT)

    // Money on the open Fold (MoneyColumns): each tab's body in two columns, as the mockup at Fold size; one on the cover.
    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerTwoColumns() {
        shoot("inner-dark-columns-spending", Fill.MANY)
        compose.onNodeWithTag("money-column-start", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("money-column-end", useUnmergedTree = true).assertExists()
    }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerBills() = shoot("inner-dark-columns-bills", Fill.MANY, tab = MoneyTab.BILLS)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerPlan() = shoot("inner-dark-columns-plan", Fill.MANY, tab = MoneyTab.PLAN)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerAccounts() = shoot("inner-dark-columns-accounts", Fill.MANY, tab = MoneyTab.ACCOUNTS)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverOneColumn() {
        shoot("cover-dark-one-column", Fill.MANY)
        compose.onAllNodesWithTag("money-columns", useUnmergedTree = true).assertCountEquals(0)
    }
}
