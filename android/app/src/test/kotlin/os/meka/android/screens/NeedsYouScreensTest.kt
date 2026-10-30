package os.meka.android.screens

import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionCollection
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import os.meka.android.designsystem.Meka
import os.meka.android.designsystem.MekaTheme
import os.meka.android.designsystem.ThemeChoice
import os.meka.android.today.NEEDS_YOU_LIST_TAG
import os.meka.android.today.NeedsYouRoute
import os.meka.core.domain.BoardFeedRules
import os.meka.core.domain.DecisionCard
import os.meka.core.domain.NeedsYouMeanwhileRules
import os.meka.core.domain.NeedsYouStackRules
import os.meka.core.domain.ObligationKind
import os.meka.core.domain.RenewalRepeat
import os.meka.core.facade.MekaCore
import os.meka.core.sync.InMemoryReplicaStore
import kotlin.random.Random
import kotlin.test.fail

/**
 * The quality gate, slice 6 (build plan: "the same shots for every other screen, Needs you next"): the Needs you tab on
 * the Fold's cover screen (the stack alone) and inner screen (the stack beside the task's detail), empty, with one card
 * and with a full stack (two overdue tasks, one due today with no time planned, a renewal due from Lists, a reminder
 * under Coming up), Dark and Light. Saved under `needs-you/` (the `fold-screens` artifact, then the `screens` branch's
 * docs/screens/fold/needs-you/) and read for nonsense on screen and for card titles cut before they can be read.
 * Invented tasks only (rule 13).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class NeedsYouScreensTest {
    /** Outermost: a hang fails within minutes with every thread's stack, instead of holding CI to its limit. */
    @get:Rule(order = 0)
    val hangWatch = HangWatch()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London
    private val hour = 3_600_000L

    private fun core(): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(11), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    /** The task cards' titles in each fill (a stack shows its top card, so the others aren't required on screen). */
    private fun titles(fill: Fill): List<String> = when (fill) {
        Fill.EMPTY -> emptyList()
        Fill.ONE -> listOf("Renew the parking permit")
        Fill.MANY -> listOf("Renew the parking permit", "Send the football kit money", "Call the garage about the brakes")
    }

    private fun shoot(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK): MekaCore {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        val core = core()
        context.useCoreForScreens(core)
        runBlocking {
            core.tick()
            if (fill != Fill.EMPTY) {
                val permit = core.addTask("Renew the parking permit")
                core.setDue(permit, now - 24 * hour) // yesterday 17:15
            }
            if (fill == Fill.MANY) {
                val kit = core.addTask("Send the football kit money")
                core.setDue(kit, now - 3 * 24 * hour)
                val garage = core.addTask("Call the garage about the brakes")
                core.setDue(garage, now + 2 * hour) // 19:15 today, no time planned
                core.addRenewal("Car insurance", ObligationKind.INSURANCE, core.todayEpochDay(), RenewalRepeat.YEARLY, null, null)
                val passport = core.addTask("Check the passport dates")
                core.setReminder(passport, now + 16 * hour) // 09:15 tomorrow
            }
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
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("needs-you", name))
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertReadable(compose, name, titles(fill), mustShow = false)
        ScreenShots.assertShows(compose, name, "Needs you")
        when (fill) {
            // The empty stack says so in words, never just an empty box.
            Fill.EMPTY -> ScreenShots.assertShows(compose, name, NeedsYouStackRules.EMPTY_LINE)
            // The top card is the first overdue task, and says why it's here.
            else -> {
                ScreenShots.assertShows(compose, name, titles(fill).first())
                ScreenShots.assertShows(compose, name, "Overdue")
                // The card hugs its words: its padding (24 dp) and a little more under the hint, no empty band.
                ScreenShots.assertHugs(compose, name, NeedsYouStackRules.TOP_CARD_TAG, "↑ Open", maxGapDp = 32f)
            }
        }
        // Under a stack, the next reminder is in view (Coming up), not a blank page.
        if (fill == Fill.MANY) {
            ScreenShots.assertShows(compose, name, NeedsYouMeanwhileRules.COMING_LABEL.uppercase())
            ScreenShots.assertShows(compose, name, "Check the passport dates")
        }
        return core
    }

    /** Runs the main looper and the frames by turns (a tap's change reaches the recomposer through the looper). */
    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    /** Waits (core thread, looper and frames by turns) until [done] holds, then lets the screen settle. */
    private fun until(done: () -> Boolean) {
        repeat(40) {
            if (done()) return@repeat
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
            compose.mainClock.advanceTimeBy(100)
        }
        turns()
    }

    private fun shows(m: SemanticsMatcher) = compose.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()

    /**
     * Scrolls Needs you's own list ([NEEDS_YOU_LIST_TAG]) until a node matching [m] is composed, then returns the
     * matches (failing with [what] if none). Never performScrollToNode (with the clock paused it asks a lazy list's
     * animated scroll for ever): each step asks for 60 % of the list's height and runs the clock; at most 20 steps
     * down, then 40 up, so it always ends.
     */
    private fun reach(name: String, m: SemanticsMatcher, what: String): SemanticsNodeInteractionCollection {
        if (!shows(m)) {
            val list = compose.onNodeWithTag(NEEDS_YOU_LIST_TAG)
            val step = list.fetchSemanticsNode().size.height * 0.6f
            for (dy in List(20) { step } + List(40) { -step }) {
                if (shows(m)) break
                list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, dy) }
                compose.mainClock.advanceTimeBy(1_000)
            }
            turns(500)
        }
        val n = compose.onAllNodes(m)
        if (n.fetchSemanticsNodes().isEmpty()) fail("$name: no $what")
        return n
    }

    /** Taps the first node [matcher] finds that answers a tap, scrolling the list to it first. */
    private fun tap(name: String, what: String, matcher: SemanticsMatcher) {
        reach(name, matcher and hasClickAction(), "\"$what\" to tap")[0].performSemanticsAction(SemanticsActions.OnClick)
    }

    /** Every button, checkbox and tab drawn answers a 48 dp touch. */
    private fun assertTouchTargets(name: String) {
        val tappable = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button) or
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox) or
            SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        val small = compose.onAllNodes(hasClickAction() and tappable)
            .fetchSemanticsNodes()
            .filter { it.size.height / compose.density.density < 47.5f }
            .map { n ->
                val label = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                    ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "?"
                "\"$label\" ${(n.size.height / compose.density.density).toInt()} dp"
            }
        if (small.isNotEmpty()) fail("$name: buttons under 48 dp to tap:\n" + small.joinToString("\n") { "- $it" })
    }

    /** The card on top of the stack as the screen orders it (nothing set aside in these tests). */
    private fun top(core: MekaCore): DecisionCard? = NeedsYouStackRules.ordered(core.needsYouStack.value, emptyList()).firstOrNull()

    private fun inStack(core: MekaCore, id: String) = core.needsYouStack.value.cards.any { it.id == id }

    /** Open's detail is up (its Close shows), then Close takes it away again. */
    private fun openedThenClosed(name: String, title: String) {
        val close = hasText("Close") and hasClickAction()
        until { shows(close) }
        if (!shows(close)) fail("$name: opening \"$title\" showed no detail")
        compose.onAllNodes(close)[0].performSemanticsAction(SemanticsActions.OnClick)
        until { !shows(close) }
        if (shows(close)) fail("$name: Close left \"$title\"'s detail open")
    }

    /**
     * Works by hand, Needs you (the full stack on the cover screen): every button and tick answers a 48 dp touch;
     * ← Tomorrow moves the top card's task to tomorrow and the next card rises; To a day… unfolds its day chips (each a
     * 48 dp touch) and a chip puts the task on that day, off the stack; ↑ Open opens the task's detail and Close shuts
     * it; Done → finishes the task; a reminder under Coming up opens its task.
     */
    private fun needsYouByHand(core: MekaCore) {
        val name = "needs-you-by-hand"
        assertTouchTargets(name)

        val first = top(core) ?: fail("$name: no card on the stack")
        tap(name, "Tomorrow", hasText(NeedsYouStackRules.buttonLabels(first)[0]))
        until { !inStack(core, first.id) }
        if (inStack(core, first.id)) fail("$name: ← ${first.laterLabel} left \"${first.title}\" on the stack")
        until { compose.onAllNodes(hasText(first.title)).fetchSemanticsNodes().isEmpty() }
        assertTouchTargets(name) // the undo bar's Undo too

        val second = top(core)?.takeIf(BoardFeedRules::canSendToDay) ?: fail("$name: no task card to send to a day")
        tap(name, BoardFeedRules.TO_DAY, hasContentDescription(BoardFeedRules.needsSpoken(second.title)))
        val day = core.needsYouFeedDays().firstOrNull() ?: fail("$name: no days to offer")
        val chip = hasContentDescription("${day.spoken}: ${second.title}")
        until { shows(chip and hasClickAction()) }
        assertTouchTargets(name)
        tap(name, day.label, chip)
        until { !inStack(core, second.id) }
        if (inStack(core, second.id)) fail("$name: the ${day.label} chip left \"${second.title}\" on the stack")

        val third = top(core)?.takeIf { it.taskId != null } ?: fail("$name: no task card left to open")
        tap(name, "Open", hasText(NeedsYouStackRules.buttonLabels(third)[1]))
        openedThenClosed(name, third.title)
        tap(name, "Done", hasText(NeedsYouStackRules.buttonLabels(third)[2]))
        until { !inStack(core, third.id) }
        if (inStack(core, third.id)) fail("$name: ${third.yesLabel} → left \"${third.title}\" on the stack")

        tap(name, "Check the passport dates", hasText("Check the passport dates"))
        openedThenClosed(name, "Check the passport dates")
        assertTouchTargets(name)
    }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverEmpty() { shoot("cover-dark-empty", Fill.EMPTY) }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverOne() { shoot("cover-dark-one", Fill.ONE) }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverMany() { shoot("cover-dark-many", Fill.MANY) }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverManyLight() { shoot("cover-light-many", Fill.MANY, ThemeChoice.LIGHT) }

    @Test @Config(qualifiers = ScreenShots.COVER)
    fun coverByHand() { needsYouByHand(shoot("cover-dark-by-hand", Fill.MANY)) }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerEmpty() { shoot("inner-dark-empty", Fill.EMPTY) }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerOne() { shoot("inner-dark-one", Fill.ONE) }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerMany() { shoot("inner-dark-many", Fill.MANY) }

    @Test @Config(qualifiers = ScreenShots.INNER)
    fun innerManyLight() { shoot("inner-light-many", Fill.MANY, ThemeChoice.LIGHT) }
}
