package os.meka.android.screens

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
import androidx.compose.ui.test.onNodeWithTag
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.ask.MoneyPane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
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

    private fun settle() {
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
    }

    private fun tapNewest(core: MekaCore): String {
        val row = core.moneyView.value.month?.spending?.latest?.firstOrNull()?.rows?.firstOrNull() ?: fail("no latest payment")
        val tag = "money-txn-${row.id}"
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag(tag))
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
        compose.waitUntil(10_000) { core.moneyView.value.month?.txns?.firstOrNull { it.id == id }?.category == "fun" }
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
}
