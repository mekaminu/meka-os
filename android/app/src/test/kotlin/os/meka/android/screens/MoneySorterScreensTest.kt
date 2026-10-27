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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.ask.MoneyPane
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore

/**
 * Money review (Meka 2026-10-26 22:12): "N payments without a category · Sort them" opens the quick sorter on the
 * biggest payee; a chip sorts it and the next comes up; Skip on the last closes it. Shot on the cover screen in Dark.
 * Invented data (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class MoneySorterScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object {
        const val SETTLE_MS = 3_000L
        const val STATEMENT = "Date,Description,Amount\n" +
            "02/10/2026,QWERTY HOLDINGS,-41.25\n" +
            "05/10/2026,SALARY OAKFIELD LTD,2500.00\n" +
            "09/10/2026,COSTA COFFEE,-3.40\n" +
            "12/10/2026,QWERTY HOLDINGS,-20.00\n" +
            "20/10/2026,ZEBRA PLOVER,-12.60\n"
    }

    private fun open(): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, ThemeChoice.DARK)
        val core = MekaCore(
            householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
            secureRandom = Random(29), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
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
        repeat((SETTLE_MS / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(100)
    }

    private fun waitFor(done: () -> Boolean) {
        var i = 0
        while (!done() && i++ < 80) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        settle()
    }

    private fun shows(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun tap(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.OnClick)
        settle()
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun sortThemGoesPayeeByPayee() {
        val core = open()
        val u = core.moneyView.value.month?.unsorted ?: fail("no payments without a category")
        assertEquals(listOf("Qwerty Holdings", "Zebra Plover"), u.items.map { it.row.merchant })
        // A lazy list's scroll waits for a frame, so the clock runs while it scrolls.
        compose.mainClock.autoAdvance = true
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("money-unsorted"))
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        settle()
        tap("money-unsorted")
        assertTrue(shows("money-sort-head"), "the sorter didn't open")
        assertTrue(shows("money-sort-skip"), "no Skip in the sorter")
        // The sorter has no toggles: it only sorts.
        if (shows("money-sheet-mark-payment_shared")) fail("the sorter shows the payment's toggles")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-sorter"))
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-sorter")
        tap("money-sheet-cat-shopping")
        waitFor { core.moneyView.value.month?.unsorted?.items.orEmpty().none { it.row.merchant == "Qwerty Holdings" } }
        // Every payment from the payee moved, and the next payee is up.
        assertEquals(2, core.moneyView.value.month!!.txns.count { it.merchant == "Qwerty Holdings" && it.category == "shopping" })
        assertTrue(shows("money-sort-head"), "the sorter closed before the last payee")
        tap("money-sort-skip")
        if (shows("money-sort-head")) fail("Skip on the last payee didn't close the sorter")
        assertEquals(listOf("Zebra Plover"), core.moneyView.value.month!!.unsorted!!.items.map { it.row.merchant })
    }
}
