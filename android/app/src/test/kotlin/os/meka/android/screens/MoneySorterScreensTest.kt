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
import os.meka.core.domain.MoneySorter
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore

/**
 * Money review (Meka 2026-10-26 22:12): "N payments without a category · Sort them" opens the quick sorter on the
 * biggest payee; a chip sorts it and the next comes up; Skip on the last closes it. Shot on the cover screen in Dark,
 * on the same month as MoneyScreensTest's ([MoneyShotMonth], walk-through A32), so Spending's count and the sorter's
 * agree. Invented data (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class MoneySorterScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private companion object {
        const val SETTLE_MS = 3_000L
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
            MoneyShotMonth.fill(core)
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
        assertEquals(MoneyShotMonth.UNSORTED, u.items.map { it.row.merchant })
        assertEquals(MoneySorter.line(3), u.line)
        // A lazy list's scroll waits for a frame, so the clock runs while it scrolls.
        compose.mainClock.autoAdvance = true
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("money-unsorted"))
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        settle()
        tap("money-unsorted")
        assertTrue(shows("money-sort-head"), "the sorter didn't open")
        assertTrue(shows("money-sort-skip"), "no Skip in the sorter")
        // Walk-through A32: the head counts the same payees as Spending's line.
        val head = MoneySorter.head(MoneySorter.left(u, emptySet()))
        assertEquals("${MoneySorter.HEAD} · 2 LEFT", head)
        if (compose.onAllNodesWithText(head, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            fail("the sorter's head isn't \"$head\"")
        }
        // The sorter has no toggles: it only sorts.
        if (shows("money-sheet-mark-payment_shared")) fail("the sorter shows the payment's toggles")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("money", "cover-dark-sorter"))
        ScreenShots.assertNoBrokenWords(compose, "cover-dark-sorter")
        tap("money-sheet-cat-shopping")
        waitFor { core.moneyView.value.month?.unsorted?.items.orEmpty().none { it.row.merchant == MoneyShotMonth.UNSORTED[0] } }
        // Every payment from the payee moved, and the next payee is up.
        assertEquals(2, core.moneyView.value.month!!.txns.count { it.merchant == MoneyShotMonth.UNSORTED[0] && it.category == "shopping" })
        assertTrue(shows("money-sort-head"), "the sorter closed before the last payee")
        tap("money-sort-skip")
        if (shows("money-sort-head")) fail("Skip on the last payee didn't close the sorter")
        assertEquals(listOf(MoneyShotMonth.UNSORTED[1]), core.moneyView.value.month!!.unsorted!!.items.map { it.row.merchant })
    }
}
