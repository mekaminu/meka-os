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
 * money check is one card of one-line ideas each opening a sheet (why, the payments behind it, Remind me, Not for me), the forecast is a card whose what-ifs sit behind one
 * "What if…" row, the challenges' fields live in a "Set a challenge" sheet, and the tab ends on one "Read-only · not
 * financial advice" line whose ⓘ says how each part is worked out. Shot on the cover screen and the open Fold in Dark.
 * Invented data (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class MoneyPlanLayoutScreensTest {
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
        val idea = v.check?.ideas?.firstOrNull { it.kind == "habit" } ?: fail("twelve coffees should make an idea in Obi's money check")
        val f = v.forecast ?: fail("the forecast isn't on Plan")
        if (f.notReady != null) fail("three whole months should make a forecast: ${f.notReady}")
        if (v.challenges == null) fail("the challenges aren't on Plan")

        // Obi's money check as the mockup: the brass head card with the yearly total, the lead idea as its own card (its
        // why and buttons on the page), the rest one-line rows; the payments behind each live in its sheet.
        scrollTo("money-check-card")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-plan-layout"))
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-plan-layout")
        assertTrue(shows("money-check-total"), "the money check leads with what the ideas save a year")
        val lead = v.check?.ideas?.firstOrNull() ?: fail("no lead idea")
        assertTrue(shows("money-check-lead") && showsText(lead.why), "the lead idea is its own card with its why")
        assertTrue(shows("money-lead-remind") && shows("money-lead-not-for-me"), "the lead card has Remind me and Not for me")
        if (idea.id != lead.id && showsText(idea.why)) fail("a row idea's why sits on the page")
        if (shows("money-idea-behind")) fail("the payments behind an idea sit on the page")
        tap("money-idea-${idea.id}")
        assertTrue(shows("money-idea-sheet"), "an idea's row should open its sheet")
        assertTrue(showsText(idea.why) && shows("money-idea-behind"), "the idea's sheet says why, with the payments behind it")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-plan-idea"))
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-plan-idea")
        // Its step: Set a cap opens Set a challenge.
        tap("money-idea-act")
        if (shows("money-idea-sheet")) fail("the idea's step should close its sheet")
        assertTrue(showsText(MoneyChallenges.CAP_HINT), "Set a cap should open the challenge fields")
        tap("money-set-challenge-sheet-done")
        // Remind me puts it away (on both devices) and Show them again brings it back.
        tap("money-idea-${idea.id}")
        tap("money-idea-remind")
        // The write and the fresh view run off the main thread: tick until it lands.
        repeat(40) { if (shows("money-idea-${idea.id}")) { Thread.sleep(50); runBlocking { core.tick() }; settle() } }
        if (shows("money-idea-${idea.id}")) fail("Remind me should put the idea away")
        assertTrue(showsText("1 put away"), "the card should say one is put away")
        tap("money-ideas-show-again")
        repeat(40) { if (!shows("money-idea-${idea.id}")) { Thread.sleep(50); runBlocking { core.tick() }; settle() } }
        assertTrue(shows("money-idea-${idea.id}"), "Show them again should bring the idea back")
        // Obi's wording: Ask Obi to look again sits in the card's foot; with no server here it says so and comes back.
        assertTrue(shows("money-check-look-again"), "the money check should offer Ask Obi to look again")
        tap("money-check-look-again")
        repeat(40) { if (!shows("money-check-look-again")) { Thread.sleep(50); runBlocking { core.tick() }; settle() } }
        assertTrue(shows("money-check-look-again"), "Ask Obi to look again should come back once answered")
        // The lead card's own Remind me puts the lead away, and the next idea leads; Show them again brings it back.
        scrollTo("money-check-lead")
        tap("money-lead-remind")
        repeat(40) { if (shows("money-idea-${lead.id}")) { Thread.sleep(50); runBlocking { core.tick() }; settle() } }
        if (shows("money-idea-${lead.id}")) fail("the lead card's Remind me should put the idea away")
        tap("money-ideas-show-again")
        repeat(40) { if (!shows("money-idea-${lead.id}")) { Thread.sleep(50); runBlocking { core.tick() }; settle() } }
        assertTrue(shows("money-idea-${lead.id}"), "Show them again should bring the lead back")

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
        assertTrue(shows("money-check-card") || shows("money-save-card"))
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "inner-dark-plan-layout"))
    }
}
