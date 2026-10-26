package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.today.NeedsYouRoute
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Taps feel instant (Meka 2026-10-25 01:45: "When I click on Done it takes at least 4 seconds, it should be
 * instantaneous"): the Compose test the item asked for. Done on Needs you's top card takes the card away as soon as its
 * fly-off ends, while the core's write is still waiting behind a busy core thread; once the core is free the write
 * lands and the card never comes back. Invented task only (rule 13).
 *
 * The core is held through its clock: every write stamps the time on the core's own thread, so while [held] the test
 * core's clock waits on any thread but the main one (the screen's own reads of the time are never held).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class, qualifiers = ScreenShots.COVER)
class NeedsYouInstantTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London
    private val hour = 3_600_000L

    @Volatile private var held = false
    private val gate = CountDownLatch(1)
    /** Calls on the core's thread waiting on the held clock right now. */
    private val waiting = AtomicInteger(0)

    private fun clock(): Long {
        val main = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
        if (held && !main) {
            waiting.incrementAndGet()
            try { gate.await(HOLD_LIMIT_S, TimeUnit.SECONDS) } finally { waiting.decrementAndGet() }
        }
        return now
    }

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(11), timeZone = { TimeZone.of("Europe/London") }, nowMs = ::clock,
    )

    private companion object {
        const val SETTLE_MS = 3_000L
        /** The card's fly-off (220 ms) and a frame or two. */
        const val FLY_MS = 300L
        const val HOLD_LIMIT_S = 20L
        const val TITLE = "Renew the parking permit"
    }

    private fun shows(text: String): Boolean =
        compose.onAllNodesWithText(text, substring = false, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun doneLeavesWhileTheCoreIsStillBusy() {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, ThemeChoice.DARK)
        val core = core()
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            val permit = core.addTask(TITLE)
            core.setDue(permit, now - 24 * hour) // yesterday 17:15: overdue, the top card
            core.tick()
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    NeedsYouRoute(core)
                }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        assertTrue("the overdue task is the top card", shows(TITLE))

        try {
            // The core's thread is busy (held): Done's write can't run yet.
            held = true
            compose.onNodeWithText("Done →", useUnmergedTree = true).performClick()
            compose.mainClock.advanceTimeBy(FLY_MS)
            compose.waitForIdle()
            assertTrue("the card is gone while the core still holds Done's write", !shows(TITLE))
            // The write really is still waiting: the core is caught on the held clock.
            val deadline = System.currentTimeMillis() + 5_000
            while (waiting.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertTrue("Done's write waits on the busy core", waiting.get() > 0)
        } finally {
            held = false
            gate.countDown()
        }

        // The core is free: the write lands, and the decided card never comes back.
        repeat(10) {
            compose.mainClock.advanceTimeBy(SETTLE_MS / 10)
            compose.waitForIdle()
            Thread.sleep(20)
        }
        assertTrue("a decided card never comes back", !shows(TITLE))
        // The bar says what happened (with Undo), so the write did land.
        assertTrue("the bar says Done", compose.onAllNodesWithText("Done · $TITLE", substring = true, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty())
        assertEquals("the task is done", 0, core.needsYouStack.value.cards.count { it.title == TITLE })
    }
}
