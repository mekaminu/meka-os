package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
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
import os.meka.core.domain.MoneyTab
import os.meka.core.domain.MoneyTabs
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore

/**
 * The payment sheet (Meka 2026-10-26 23:18: a tap on a payment opened an unthemed white dropdown). A tap now raises the
 * dark sheet with the payee, category chips and the toggles; a chip moves every payment from that payee and the line
 * says so under the chips with Undo; Done closes it. Shot on the cover screen in Dark and Light. Invented data (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class MoneyPaymentSheetScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object {
        const val SETTLE_MS = 3_000L
        const val STATEMENT = "Date,Description,Amount\n" +
            "11/09/2026,CORNER STORES 2041,-58.10\n" +
            "02/10/2026,CORNER STORES 2041,-71.25\n" +
            "05/10/2026,SALARY OAKFIELD LTD,2500.00\n" +
            "09/10/2026,COSTA COFFEE,-3.40\n" +
            "20/10/2026,CORNER STORES 2041,-55.60\n"
    }

    private fun open(theme: ThemeChoice): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
            secureRandom = Random(23), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            core.openMoney()
            check(!core.importMoneyStatement("Current account", STATEMENT).refused)
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) { MoneyPane(core, onClose = {}) }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        return core
    }

    /** Lets the main looper run between frames (a tap's state change recomposes from there), as BriefScreensTest does. */
    private fun settle() {
        repeat((SETTLE_MS / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)
    }

    private fun tapNewest(core: MekaCore): String {
        val row = core.moneyView.value.month?.spending?.latest?.firstOrNull()?.rows?.firstOrNull() ?: fail("no latest payment")
        val tag = "money-txn-${row.id}"
        // A lazy list's scroll waits for a frame, so the clock runs while it scrolls (as BriefScreensTest does).
        compose.mainClock.autoAdvance = true
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        settle()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
        settle()
        return row.id
    }

    private fun shows(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun aTapOpensTheSheetAndAChipMovesEveryPaymentFromThePayee() {
        val core = open(ThemeChoice.DARK)
        val id = tapNewest(core)
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-payment-sheet"))
        assertTrue(shows("money-sheet"), "the payment sheet didn't open")
        assertTrue(shows("money-sheet-cat-groceries"), "no Groceries chip")
        assertTrue(shows("money-sheet-mark-payment_shared"), "no Shared toggle")
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-payment-sheet")
        compose.onNodeWithTag("money-sheet-cat-fun", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        settle()
        // The write runs on the core's own thread; wait for it to land in the view.
        var i = 0
        while (core.moneyView.value.month?.txns?.firstOrNull { it.id == id }?.category != "fun" && i++ < 80) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        settle()
        val moved = core.moneyView.value.month?.txns?.first { it.id == id } ?: fail("the payment went")
        assertEquals("fun", moved.category)
        // Every payment from the payee moved, not only the one tapped.
        assertEquals(2, core.moneyView.value.month!!.txns.count { it.merchant == moved.merchant && it.category == "fun" })
        assertTrue(shows("money-sheet-said"), "no line after the pick")
        assertTrue(shows("money-sheet-undo"), "no Undo after the pick")
        compose.onNodeWithTag("money-sheet-done", useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        settle()
        if (shows("money-sheet")) fail("Done didn't close the sheet")
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun theSheetInLight() {
        val core = open(ThemeChoice.LIGHT)
        tapNewest(core)
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-light-payment-sheet"))
        assertTrue(shows("money-sheet"), "the payment sheet didn't open")
        ScreenShots.assertNoBrokenWords(compose, "cover-light-payment-sheet")
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    /** His own categories (Meka 2026-10-26 23:18, "a Vape category"): + New category, name, colour, icon, Create. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun aNewCategoryOfHisOwnTakesThePayee() {
        val core = open(ThemeChoice.DARK)
        val id = tapNewest(core)
        assertTrue(shows("money-sheet-new"), "no + New category chip")
        tap("money-sheet-new")
        assertTrue(shows("money-new-category"), "the New category form didn't open")
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("money-new-name")), useUnmergedTree = true).performTextInput("Treats")
        settle()
        tap("money-new-tone-3")
        tap("money-new-icon-cup")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-new-category"))
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-new-category")
        tap("money-new-create")
        var i = 0
        while (core.moneyView.value.month?.txns?.firstOrNull { it.id == id }?.categoryLabel != "Treats" && i++ < 80) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        settle()
        val v = core.moneyView.value
        val own = v.ownCategories.singleOrNull() ?: fail("the category wasn't made")
        assertEquals("Treats", own.name)
        assertEquals(3, own.tone)
        assertEquals("Treats", v.month!!.txns.first { it.id == id }.categoryLabel)
        // The form closed and the chips are back with his category lit.
        if (shows("money-new-category")) fail("the form stayed open after Create")
        assertTrue(shows("money-sheet-cat-${own.key}"), "no chip for his new category")
        assertTrue(shows("money-sheet-undo"), "no Undo after making it")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-new-category-made"))
    }

    private fun waitFor(core: MekaCore, done: () -> Boolean) {
        var i = 0
        while (!done() && i++ < 80) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        settle()
    }

    /** Slice 3: Accounts → YOUR CATEGORIES; a tap opens the category's sheet: edit, hide from the ring, merge, delete. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun hisCategoriesAreManagedFromAccounts() {
        val core = open(ThemeChoice.DARK)
        val costa = core.moneyView.value.month!!.txns.first { it.merchant == "Costa Coffee" }
        runBlocking { check(!core.addMoneyCategory("Treats", 3, "CUP", costa.id).refused) }
        settle()
        compose.onNodeWithContentDescription(MoneyTabs.spoken(MoneyTab.ACCOUNTS, false)).performSemanticsAction(SemanticsActions.OnClick)
        settle()
        val own = core.moneyView.value.ownRows.single()
        compose.mainClock.autoAdvance = true
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("money-own-card"))
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        settle()
        assertTrue(shows("money-own-${own.id}"), "no row for his category on Accounts")
        tap("money-own-${own.id}")
        assertTrue(shows("money-own-sheet"), "the category's sheet didn't open")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-own-category"))
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-own-category")
        tap("money-own-hide")
        waitFor(core) { core.moneyView.value.ownRows.firstOrNull()?.hidden == true }
        assertTrue(core.moneyView.value.ownRows.single().hidden, "Hide from the ring didn't land")
        tap("money-own-delete")
        assertTrue(shows("money-own-delete-yes"), "Delete didn't ask first")
        tap("money-own-delete-yes")
        waitFor(core) { core.moneyView.value.ownRows.isEmpty() }
        assertTrue(core.moneyView.value.ownRows.isEmpty(), "the category wasn't deleted")
        assertEquals("Other", core.moneyView.value.month!!.txns.first { it.id == costa.id }.categoryLabel)
        if (shows("money-own-sheet")) fail("the sheet stayed open after Delete")
    }
}
