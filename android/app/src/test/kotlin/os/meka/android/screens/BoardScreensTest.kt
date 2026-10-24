package os.meka.android.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import os.meka.android.calendar.BUDGET_TAG
import os.meka.android.calendar.PLANNER_CARD_TAG
import os.meka.core.domain.BoardHandRules
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.calendar.EventUndo
import os.meka.android.calendar.WeekBoardView
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random

/**
 * The quality gate, slice 1 (Meka 2026-10-21 20:21: "Why am I the one discovering what needs improvement?"): the Week
 * board drawn on the Fold's cover screen (≈ 904 × 2316 px) and inner screen (≈ 2176 × 1812 px), empty, with one task and
 * with a full week, Dark and Light, saved as images (CI keeps them as the `fold-screens` artifact, and a green run on main
 * pushes them to the `screens` branch's docs/screens/fold/board/, tools/publish-screens.sh). Every shot is also read for nonsense on screen ([ScreenShots.assertSensible]) and fails if it
 * finds any. Invented tasks only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = android.app.Application::class)
class BoardScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(7), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private companion object { const val SETTLE_MS = 2_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    private fun titles(fill: Fill): List<String> = when (fill) {
        Fill.EMPTY -> emptyList()
        Fill.ONE -> listOf("Book the MOT")
        Fill.MANY -> listOf(
            "Book the MOT", "Reply to Oakfield School", "Fix the gate latch", "Order new bins", "Pay the window cleaner",
            "Plan Saturday's football run with Alex", "Renew the car insurance", "Batch cook for the week",
        )
    }

    /** Each open lane's first card in the full week (unfolded, all lanes are side by side). */
    private val firstCards = listOf("Fix the gate latch", "Order new bins", "Plan Saturday's football run with Alex", "Renew the car insurance", "Batch cook for the week")

    private fun shoot(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK, level: Boolean = false) {
        ThemeChoice.save(ApplicationProvider.getApplicationContext(), theme)
        val core = core()
        runBlocking {
            core.tick()
            val today = core.weekBoard.value.todayEpochDay
            when (fill) {
                Fill.EMPTY -> Unit
                Fill.ONE -> core.addOnBoardSized("Book the MOT", today, 30)
                Fill.MANY -> {
                    core.addOnBoardSized("Book the MOT", today, 30)
                    val done = core.addOnBoardSized("Reply to Oakfield School", today, 15)
                    core.addOnBoardSized("Fix the gate latch", today, 60)
                    core.addOnBoardSized("Order new bins", today + 1, null)
                    core.addOnBoardSized("Pay the window cleaner", today + 1, 15)
                    core.addOnBoardSized("Plan Saturday's football run with Alex", today + 2, 120)
                    core.addOnBoardSized("Renew the car insurance", today + 3, 30)
                    core.addOnBoardSized("Batch cook for the week", today + 4, 120)
                    done?.let { core.complete(it) }
                }
            }
        }
        // The clock is driven by hand so a breathing bar (an infinite animation) can't keep the screen from settling.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    WeekBoardView(core, EventUndo(), onEvent = {}, Modifier.fillMaxSize())
                }
            }
        }
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("board", name))
        ScreenShots.assertSensible(compose, name)
        // Slice 8 of the Week board (the gate's first findings): no title cut to a word or two, no header line clipped.
        ScreenShots.assertReadable(compose, name, titles(fill))
        // Slice 9 (the re-shot of slice 8): every lane's first card starts level, tag or no tag, today or not.
        // Option B slice 1: measured by the planner card around each title (some cards have a time line above it).
        if (level) ScreenShots.assertLevelBy(compose, name, firstCards, PLANNER_CARD_TAG)
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverEmpty() = shoot("cover-dark-empty", Fill.EMPTY)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverOne() = shoot("cover-dark-one", Fill.ONE)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverMany() = shoot("cover-dark-many", Fill.MANY)

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverManyLight() = shoot("cover-light-many", Fill.MANY, ThemeChoice.LIGHT)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerEmpty() = shoot("inner-dark-empty", Fill.EMPTY)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerOne() = shoot("inner-dark-one", Fill.ONE)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerMany() = shoot("inner-dark-many", Fill.MANY, level = true)

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerManyLight() = shoot("inner-light-many", Fill.MANY, ThemeChoice.LIGHT, level = true)

    /**
     * Works by hand (quality gate 3), Option B slice 1: a planner card shows its duration on top ("0:30") and the day's
     * budget pill says "planned"; ticking the card marks it done, and its title stays plain (faded, never struck
     * through); ticking again un-ticks it.
     */
    @Test @Config(qualifiers = ScreenShots.INNER)
    fun aPlannerCardTicksAndFadesWithoutStrikingThrough() {
        ThemeChoice.save(ApplicationProvider.getApplicationContext(), ThemeChoice.DARK)
        val core = core()
        val id = runBlocking {
            core.tick()
            core.addOnBoardSized("Book the MOT", core.weekBoard.value.todayEpochDay, 30)
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    WeekBoardView(core, EventUndo(), onEvent = {}, Modifier.fillMaxSize())
                }
            }
        }
        settle()
        ScreenShots.assertShows(compose, "planner", "0:30")
        if (compose.onAllNodesWithTag(PLANNER_CARD_TAG).fetchSemanticsNodes().isEmpty()) fail("no planner card drawn")
        val budget = compose.onAllNodesWithTag(BUDGET_TAG).fetchSemanticsNodes()
        if (budget.isEmpty()) fail("no budget pill under today's header")
        val spoken = budget.first().config[SemanticsProperties.ContentDescription].joinToString()
        assertTrue("the budget pill says what is planned: $spoken", spoken.contains("planned"))

        compose.onNodeWithContentDescription(BoardHandRules.tickLabel("Book the MOT", false)).performClick()
        awaitCore("tick") { core.weekBoard.value.days.flatMap { it.cards }.any { it.id == id && it.done } }
        assertEquals("a done card's title is not struck through", null, titleDecoration("Book the MOT"))

        compose.onNodeWithContentDescription(BoardHandRules.tickLabel("Book the MOT", true)).performClick()
        awaitCore("un-tick") { core.weekBoard.value.days.flatMap { it.cards }.any { it.id == id && !it.done } }
    }

    private fun titleDecoration(title: String): TextDecoration? {
        val node = compose.onNodeWithText(title).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.firstOrNull()?.layoutInput?.style?.textDecoration?.takeIf { it != TextDecoration.None }
    }

    private fun settle() {
        compose.mainClock.advanceTimeBy(SETTLE_MS)
        compose.waitForIdle()
    }

    /** A command runs on the core's own thread, off the paused test clock: wait for it in real time (up to 3 s). */
    private fun awaitCore(what: String, done: () -> Boolean) {
        var waited = 0
        while (!done() && waited < 3_000) {
            Thread.sleep(20)
            waited += 20
            compose.mainClock.advanceTimeBy(16)
        }
        if (!done()) fail("$what: the core never did it")
        settle()
    }
}
