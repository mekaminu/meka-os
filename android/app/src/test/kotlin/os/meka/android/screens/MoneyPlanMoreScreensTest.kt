package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertTextEquals
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
import kotlin.math.abs
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
import os.meka.core.domain.MoneyPlanMore
import os.meka.core.domain.MoneyTab
import os.meka.core.domain.MoneyTabs
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore

/**
 * Plan's lower half, slice 1 (approved mockup `Phone-PlanMore.dc.html`): with no £ goal, GOALS is a card of start tiles
 * and Add a £ goal opens its sheet; the forecast is In / Out / Left over tiles, the 6 · 12 months toggle, the bars and
 * the By tiles, with What if I cut… under them. Three whole months of invented statements; shot on the cover screen
 * and the open Fold in Dark (rule 13: invented data only).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class MoneyPlanMoreScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object {
        const val SETTLE_MS = 3_000L
        val CURRENT = "Date,Description,Amount\n" +
            listOf(7, 8, 9, 10).joinToString("") { m ->
                val mm = m.toString().padStart(2, '0')
                "01/$mm/2026,OAKFIELD COUNCIL TAX,-142.00\n" +
                    "05/$mm/2026,SALARY OAKFIELD LTD,2500.00\n" +
                    "09/$mm/2026,TESCO STORES 2041,-64.90\n" +
                    (if (m < 10) "28/$mm/2026,TESCO STORES 2041,-58.20\n" else "")
            }
        /**
         * Slice 2: a week away on the card in invented statements — two payments abroad, each with a foreign fee.
         * Walk-through 2026-11-08 A32: they end on 15 Oct like the other Money shots' statements (they ended on 13).
         */
        const val CARD = "Date,Description,Amount\n" +
            "14/10/2026,RESTAURANTE SOL EUR 46.20,-40.00\n" +
            "14/10/2026,NON-STERLING TRANSACTION FEE,-1.20\n" +
            "15/10/2026,MERCADO CENTRAL 23.10 EUR,-20.00\n" +
            "15/10/2026,NON-STERLING TRANSACTION FEE,-0.60\n"
        /** The last day of [CARD], the day every Money shot's statements run to. */
        const val LAST_DAY = "15/10/2026"
    }

    private fun open(card: Boolean = false): MekaCore {
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
            if (card) check(!core.importMoneyStatement("Credit card", CARD).refused)
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
    private fun showsText(text: String) = compose.onAllNodes(ScreenShots.plainText(text, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

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
        val v = core.moneyView.value
        val f = v.forecast ?: fail("the forecast isn't on Plan")
        if (MoneyPlanMore.forecast(f, 12, 0) == null) fail("three whole months with pay should make the forecast's tiles: ${f.notReady}")
        if (v.goals?.rows?.isNotEmpty() != false) fail("there should be no £ goal yet")
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun theForecastIsTilesAndBarsAndGoalsStartFromATile() {
        val core = open()
        plan(core)
        scrollTo("money-forecast-tiles")
        assertTrue(showsText(MoneyPlanMore.IN) && showsText(MoneyPlanMore.LEFT), "the forecast should open on its tiles")
        assertTrue(shows("money-forecast-bars") && shows("money-cut-slider"), "the bars and What if I cut… should be on the card")
        assertTrue(showsText(MoneyPlanMore.CUT_HINT))
        assertTrue(showsText("BY OCT 2027"), "the 12-month tile should name its month")
        // Walk-through A11 (2026-11-08): the tiles' labels whole ("OUT / MONTH", never "OUT /").
        ScreenShots.assertNotCut(compose, "plan-forecast-tiles-cover", listOf(MoneyPlanMore.IN, MoneyPlanMore.OUT, MoneyPlanMore.LEFT, "BY OCT 2027"))
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "plan-forecast-tiles-cover"))
        // Walk-through A32: the month names under the twelve bars never touch ("Apr" ran into "May" on the cover).
        val names = compose.onAllNodes(
            SemanticsMatcher("a forecast month") { n -> n.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("money-forecast-month-") == true },
            useUnmergedTree = true,
        ).fetchSemanticsNodes().map { it.boundsInRoot }.sortedBy { it.left }
        assertTrue(names.size == 6, "over 12 months every other month should be named, not ${names.size}")
        names.zipWithNext().forEach { (a, b) -> assertTrue(a.right < b.left, "two month names touch: $a and $b") }
        // Walk-through A32: What if I cut…'s track runs edge to edge under the bars (it stopped ~10 dp short each side).
        val bars = compose.onNodeWithTag("money-forecast-bars").fetchSemanticsNode().boundsInRoot
        val track = compose.onNodeWithTag("money-cut-slider-track", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(abs(track.left - bars.left) <= 1f && abs(track.right - bars.right) <= 1f, "the slider's track $track should span the bars $bars")
        tap("money-forecast-span-6")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "plan-forecast-six-cover"))
        // Walk-through A20: when the goals' start card already sits under the six-month forecast, that shot shows it and
        // a second one would be the same bytes; only a scroll that moved the page earns its own shot.
        val goalsInSix = ScreenShots.wholeOnScreen(compose, "money-goals-empty")
        scrollTo("money-goals-empty")
        assertTrue(showsText(MoneyPlanMore.GOALS_EMPTY_TITLE), "no £ goal should show the start card")
        assertTrue(shows("money-goal-start-add"), "Add a £ goal should always be a tile")
        if (!goalsInSix) compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "plan-goals-empty-cover"))
        tap("money-goal-start-add")
        assertTrue(shows("money-add-goal-sheet") && showsText(MoneyPlanMore.ADD_AMOUNT_HINT), "Add a £ goal should open its sheet")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "plan-add-goal-cover"))
        // Walk-through A26: one gold button (Add goal); the way out is a quiet Cancel, not a second gold Done.
        compose.onNodeWithTag("money-add-goal-sheet-done").assertTextEquals(MoneyPlanMore.CANCEL)
    }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun theForecastTilesOnTheOpenFold() {
        val core = open()
        plan(core)
        scrollTo("money-forecast-tiles")
        assertTrue(showsText(MoneyPlanMore.OUT))
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "plan-forecast-tiles-unfolded"))
    }

    private fun until(core: MekaCore, check: () -> Boolean) {
        repeat(40) { if (!check()) { Thread.sleep(50); runBlocking { core.tick() }; settle() } }
    }

    /** Slice 2: CHALLENGES as two tiles (No-spend days, Fun money with £40/£60/£80) and PAYING ABROAD's fees tile and bars. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun challengesAreTwoTilesAndPayingAbroadShowsItsFees() {
        val core = open(card = true)
        pick(MoneyTab.PLAN)
        scrollTo("money-challenge-tiles")
        assertTrue(showsText(MoneyPlanMore.NO_SPEND) && showsText(MoneyPlanMore.FUN), "the challenges should be two tiles")
        assertTrue(showsText(MoneyPlanMore.START_COUNTING) && showsText(MoneyPlanMore.FUN_NOT_SET), "nothing set yet")
        // Walk-through A28: "Pick a week's fun money" keeps "fun money" together, and the cap row wraps whole.
        ScreenShots.assertNoLoneWords(compose, "plan-challenges-tiles-cover", listOf(MoneyPlanMore.FUN_PICK, MoneyPlanMore.CAP_ROW))
        // Walk-through A32: the week's dots are round 24 dp marks (they were ~17 dp ovals), seven of them inside the
        // tile, none a tap target (Start counting is the tile's one control).
        val d = compose.density.density
        val dots = compose.onAllNodes(hasTestTag("money-nospend-dot"), useUnmergedTree = true).fetchSemanticsNodes()
        if (dots.size != 7) fail("the week should have seven no-spend dots, not ${dots.size}")
        val tile = compose.onNodeWithTag("money-nospend-tile").fetchSemanticsNode().boundsInRoot
        dots.forEach { n ->
            val w = n.size.width / d
            val h = n.size.height / d
            if (w < MoneyPlanMore.DAY_DOT - 0.5f || kotlin.math.abs(w - h) > 0.5f)
                fail("a no-spend dot is ${w.toInt()} × ${h.toInt()} dp, not a round ${MoneyPlanMore.DAY_DOT} dp mark")
            val b = n.boundsInRoot
            if (b.left < tile.left - 0.5f || b.right > tile.right + 0.5f) fail("a no-spend dot runs out of its tile")
        }
        if (compose.onAllNodes(hasTestTag("money-nospend-dots") and androidx.compose.ui.test.hasClickAction())
                .fetchSemanticsNodes().isNotEmpty()) fail("the no-spend dots only show the week; they shouldn't be a target")
        tap("money-nospend-toggle")
        until(core) { showsText(MoneyPlanMore.STOP_COUNTING) }
        assertTrue(showsText(MoneyPlanMore.STOP_COUNTING), "Start counting should switch no-spend days on")
        tap("money-fun-chip-60")
        until(core) { showsText("of £60") }
        assertTrue(showsText("of £60"), "£60 should set the week's fun money")
        assertTrue(shows("money-cap-row"), "Cap a category for the month should follow the tiles")
        val tv = core.moneyView.value.travel ?: fail("the card's week abroad should make PAYING ABROAD")
        if (MoneyPlanMore.abroadTiles(tv) == null) fail("PAYING ABROAD should have the card's row: ${tv.tip}")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "plan-challenges-tiles-cover"))
        scrollTo("money-abroad-fees")
        assertTrue(showsText(MoneyPlanMore.ABROAD_HEAD) && showsText("£1.80"), "the year's foreign fees should be the big tile")
        assertTrue(shows("money-abroad-payments-toggle"))
        tap("money-abroad-payments-toggle")
        assertTrue(shows("money-abroad-payments") && showsText("£1.20 fee"), "See the payments behind it should list each fee")
        // A32: the fees' days are the card's own, ending on the 15th like the other Money shots.
        assertTrue(CARD.trimEnd().lines().last().startsWith(LAST_DAY) && showsText("Thu 15 Oct"),
            "the last fee should be on Thu 15 Oct, the day the other Money shots' statements end")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "plan-abroad-tiles-cover"))
    }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun challengesAndPayingAbroadOnTheOpenFold() {
        open(card = true)
        pick(MoneyTab.PLAN)
        scrollTo("money-challenge-tiles")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "plan-challenges-tiles-unfolded"))
        scrollTo("money-abroad-fees")
        assertTrue(showsText(MoneyPlanMore.FEES_LABEL))
        // Walk-through 2026-11-08 A23: scrolling only to the fees left PAYING ABROAD at the screen's bottom edge, so
        // scroll on to its last row and check the fees and the bars' toggle both sit wholly on screen.
        scrollTo("money-abroad-payments-toggle")
        if (!ScreenShots.wholeOnScreen(compose, "money-abroad-fees")) fail("PAYING ABROAD's fees tile isn't wholly in the shot")
        if (!ScreenShots.wholeOnScreen(compose, "money-abroad-payments-toggle")) fail("PAYING ABROAD's last row isn't wholly in the shot")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "plan-abroad-tiles-unfolded"))
    }
}
