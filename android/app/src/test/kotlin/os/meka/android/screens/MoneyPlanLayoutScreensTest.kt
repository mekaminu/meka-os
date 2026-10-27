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
import os.meka.core.domain.MoneyChallenges
import os.meka.core.domain.MoneyForecast
import os.meka.core.domain.MoneyPlanLayout
import os.meka.core.domain.MoneySave
import os.meka.core.domain.MoneyRules
import os.meka.core.domain.MoneyTab
import os.meka.core.domain.MoneyTabs
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore

/**
 * Money's layout rules on Plan (Meka 2026-10-26 22:18: "it's sentences and text, not segmented, boxed, grouped"): the
 * tips are one card of one-line rows each opening a sheet, the forecast is a card whose what-ifs sit behind one
 * "What if…" row, the challenges' fields live in a "Set a challenge" sheet, and the tab ends on one "Read-only · not
 * financial advice" line whose ⓘ says how each part is worked out. Shot on the cover screen and the open Fold in Dark.
 * Invented data (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class MoneyPlanLayoutScreensTest {
    @get:Rule
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
                    "15/$mm/2026,STREAMLY.COM,-10.99\n" +
                    (if (m < 10) "28/$mm/2026,TESCO STORES 2041,-58.20\n" else "")
            } +
            (1..12).joinToString("") { d -> "${d.toString().padStart(2, '0')}/10/2026,COSTA COFFEE,-4.80\n" }
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
    fun planInCardsWithTheWhatIfsAndChallengeFieldsInSheets() {
        val core = open()
        pick(MoneyTab.PLAN)
        val v = core.moneyView.value
        val tip = v.tips.firstOrNull() ?: fail("twelve coffees should make a tip")
        val f = v.forecast ?: fail("the forecast isn't on Plan")
        if (f.notReady != null) fail("three whole months should make a forecast: ${f.notReady}")
        if (v.challenges == null) fail("the challenges aren't on Plan")

        // Tips: one card of one-line rows; what a tip means lives in its sheet.
        scrollTo("money-tips-card")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-plan-layout"))
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-plan-layout")
        if (showsText(tip.line)) fail("a tip's meaning sits on the page")
        tap("money-tip-${tip.id}")
        assertTrue(shows("money-tip-sheet"), "a tip's row should open its sheet")
        assertTrue(showsText(tip.line), "the tip's sheet says what it means")
        tap("money-tip-sheet-done")
        if (shows("money-tip-sheet")) fail("Done didn't close the tip's sheet")

        // The forecast: no field on the page; What if… opens the cuts and the extra-a-month field.
        scrollTo("money-forecast-card")
        if (showsText(MoneyForecast.EXTRA_HINT)) fail("the extra-a-month field sits open on the page")
        assertTrue(showsText(MoneyPlanLayout.WHAT_IF_TRY), "the What if… row says what it's for while nothing is on")
        tap("money-whatif-row")
        assertTrue(shows("money-whatif-sheet") && showsText(MoneyForecast.EXTRA_HINT), "What if… should open the what-ifs")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-plan-whatif"))
        tap("money-whatif-sheet-done")

        // Challenges: the fields only in the Set a challenge sheet.
        scrollTo("money-set-challenge-row")
        if (showsText(MoneyChallenges.FUN_HINT)) fail("the fun money field sits open on the page")
        tap("money-set-challenge-row")
        assertTrue(showsText(MoneyChallenges.FUN_HINT) && showsText(MoneyChallenges.CAP_HINT), "Set a challenge should open both fields")
        tap("money-set-challenge-sheet-done")

        // One foot line; how What if I save and the forecast are worked out sits behind its ⓘ.
        scrollTo("money-foot-info")
        assertTrue(showsText(MoneyTabs.FOOT_SHORT), "Plan should end on one foot line")
        if (showsText(MoneySave.BASIS)) fail("What if I save's basis still sits under its card")
        if (showsText(MoneyChallenges.FOOT)) fail("the challenges' source line still sits on the page")
        tap("money-foot-info")
        assertTrue(showsText(MoneyTabs.PLAN_FROM) && showsText(MoneyRules.FOOT), "the foot's ⓘ says how Plan is worked out")
    }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun planInCardsOnTheOpenFold() {
        open()
        pick(MoneyTab.PLAN)
        assertTrue(shows("money-tips-card") || shows("money-save-card"))
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "inner-dark-plan-layout"))
    }
}
