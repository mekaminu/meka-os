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
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "plan-forecast-tiles-cover"))
        tap("money-forecast-span-6")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "plan-forecast-six-cover"))
        scrollTo("money-goals-empty")
        assertTrue(showsText(MoneyPlanMore.GOALS_EMPTY_TITLE), "no £ goal should show the start card")
        assertTrue(shows("money-goal-start-add"), "Add a £ goal should always be a tile")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "plan-goals-empty-cover"))
        tap("money-goal-start-add")
        assertTrue(shows("money-add-goal-sheet") && showsText(MoneyPlanMore.ADD_AMOUNT_HINT), "Add a £ goal should open its sheet")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "plan-add-goal-cover"))
    }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun theForecastTilesOnTheOpenFold() {
        val core = open()
        plan(core)
        scrollTo("money-forecast-tiles")
        assertTrue(showsText(MoneyPlanMore.OUT))
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "plan-forecast-tiles-unfolded"))
    }
}
