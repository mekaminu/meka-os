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
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
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
import os.meka.android.review.ReflectionActions
import os.meka.android.review.Week
import os.meka.core.domain.CivilDate
import os.meka.core.domain.DateGlue
import os.meka.core.domain.ReviewDone
import os.meka.core.domain.ReviewGoal
import os.meka.core.domain.ReviewHabit
import os.meka.core.domain.ReviewOpen
import os.meka.core.domain.ReviewRules
import os.meka.core.domain.ReviewTile
import os.meka.core.domain.WeeklyReviewView
import os.meka.core.domain.Win
import os.meka.core.domain.WinRow
import os.meka.core.domain.WinRules
import os.meka.core.domain.WinsView
import kotlin.test.fail

/**
 * The quality gate, slice 61 (build plan: "the rest of Ask's places' shots", Review): Ask → Daily → Review, the weekly
 * review, drawn from invented weeks: this week with nothing done yet, last week reviewed with one of everything, and a
 * full week (twelve done and three more, three habits, two goals, fasts, lists, five still open and two more, three
 * wins and the quarter's, counted north-star numbers), on the Fold's cover and inner screens, Dark (and one in Light).
 * Saved under `review/` and read for nonsense (no lone dash for a number not counted yet), cut lines, broken words,
 * split numbers and dates, a grey line leaving a word alone, and every button answering a 48 dp touch. By hand: ‹ goes
 * back a week, › is off on this week and goes forward on a past one, Done reviewing, a win kept from the field, Remove
 * on a win and "This quarter" unfolding.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class ReviewScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    /** Mon 19 Oct 2026; "today" is Saturday 24th. */
    private val ws = CivilDate.toEpochDay(2026, 10, 19)
    private val today = ws + 5

    private fun week(fill: Fill): WeeklyReviewView = when (fill) {
        Fill.EMPTY -> WeeklyReviewView.EMPTY.copy(
            weekStart = ws, rangeLabel = ReviewRules.rangeLabel(ws), canGoBack = true,
            tiles = listOf(ReviewTile("Done", 0, "tasks"), ReviewTile("Lists", 0, "closed")),
            aheadTitle = "Next week", aheadLines = listOf("Nothing in the diary yet"),
        )
        Fill.ONE -> WeeklyReviewView.EMPTY.copy(
            weekStart = ws - 7, offset = -1, title = ReviewRules.title(-1, ws - 7), rangeLabel = ReviewRules.rangeLabel(ws - 7),
            isCurrent = false, canGoBack = true, canGoForward = true,
            tiles = listOf(ReviewTile("Done", 1, "task"), ReviewTile("Habit ticks", 5, "of 7"), ReviewTile("Lists", 1, "closed")),
            comparedLine = ReviewRules.comparedLine(1, 3),
            done = listOf(ReviewDone("d1", "Book the boiler service", "Thu")),
            habits = listOf(ReviewHabit("h1", "Walk 10k steps", 5, 7, false, listOf(true, true, false, true, true, false, true), ReviewRules.habitLine(5, 7, false, false))),
            habitsLine = "0 of 1 habit met their week",
            goals = listOf(ReviewGoal("g1", "Run 5k", 40, "40 % · 1 task done this week")),
            listsLines = listOf("1 decision made"),
            stillOpen = listOf(ReviewOpen("o1", "Renew the car insurance", "Was due Wed 14 Oct")),
            aheadTitle = "This week", aheadLines = listOf("3 events · 2 tasks planned or due"),
            reviewed = true, reviewedLine = "Reviewed Sun 18 Oct at 18:42",
        )
        Fill.MANY -> {
            val days = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
            val titles = listOf(
                "Book the boiler service", "Send the school trip form back", "Call the garage about the MOT", "Pay the window cleaner",
                "Fix the gate latch", "Renew the parking permit", "Order the boys' football boots", "Reply to the landlord about the boiler",
                "Pick up the dry cleaning", "Back up the laptop", "Cancel the gym trial", "Write the quarterly report summary",
            )
            WeeklyReviewView.EMPTY.copy(
                weekStart = ws, rangeLabel = ReviewRules.rangeLabel(ws), canGoBack = true,
                tiles = listOf(ReviewTile("Done", 15, "tasks"), ReviewTile("Habit ticks", 14, "of 18"),
                    ReviewTile("Fasts", 4, "3 reached the goal"), ReviewTile("Lists", 5, "closed")),
                comparedLine = ReviewRules.comparedLine(15, 11),
                done = titles.mapIndexed { i, t -> ReviewDone("d$i", t, days[i % days.size]) },
                doneMore = 3,
                habits = listOf(
                    ReviewHabit("h1", "Stretch for ten minutes", 3, 6, false, listOf(true, false, true, false, true, false, false), ReviewRules.habitLine(3, 6, false, true)),
                    ReviewHabit("h2", "Read to the boys at bedtime", 5, 5, true, listOf(true, true, true, true, true, false, false), ReviewRules.habitLine(5, 5, true, true)),
                    ReviewHabit("h3", "Walk 10k steps", 6, 7, false, listOf(true, true, true, true, true, true, false), ReviewRules.habitLine(6, 7, false, true)),
                ),
                habitsLine = "1 of 3 habits met so far",
                goals = listOf(
                    ReviewGoal("g1", "Run 5k without stopping", 60, "60 % · 2 tasks done this week"),
                    ReviewGoal("g2", "Clear the garage", 25, "25 %"),
                ),
                fastingLine = "4 fasts · average 16 h 10 m · 3 reached the goal",
                listsLines = listOf("2 things you were waiting for arrived", "1 promise kept", "1 decision made", "1 renewal or bill dealt with"),
                stillOpen = listOf(
                    ReviewOpen("o1", "Renew the car insurance", "Was due Wed 21 Oct"),
                    ReviewOpen("o2", "Sort the loft boxes for the charity shop", "Planned Thu 22 Oct"),
                    ReviewOpen("o3", "Ring the dentist", "Planned Fri 23 Oct"),
                    ReviewOpen("o4", "Return the parcel", "Was due Fri 23 Oct"),
                    ReviewOpen("o5", "Book the half-term swimming lessons", "Planned Sat 24 Oct"),
                ),
                stillOpenMore = 2,
                aheadTitle = "Next week",
                aheadLines = listOf("9 events · 6 tasks planned or due · 1 renewal or bill due · 1 decision to review · 2 to chase"),
                northStar = ReviewRules.NORTH_STAR_PENDING.map {
                    when (it.key) {
                        "interruptions" -> it.copy(value = "7", line = "Heads-ups that reached a device this week")
                        "handled" -> it.copy(value = "12", line = "Cards Navilife dealt with for you")
                        else -> it
                    }
                },
            )
        }
    }

    private val wins = listOf(
        Win("w1", "Shipped the release notes a day early", ws + 1, 0),
        Win("w2", "Calm handover with the new starter", ws + 3, 0),
        Win("w3", "Closed the audit actions", ws + 4, 0),
        Win("w4", "Ran the team retro", ws - 10, 0),
        Win("w5", "Fixed the rota clash", ws - 4, 0),
    )

    private fun winsView(fill: Fill): WinsView = when (fill) {
        Fill.EMPTY -> WinRules.view(emptyList(), ws, today, 10 * 60)
        Fill.ONE -> WinRules.view(wins.filter { it.id == "w5" }, ws - 7, today, 10 * 60)
        Fill.MANY -> WinRules.view(wins, ws, today, 10 * 60)
    }

    private class Taps {
        val steps = mutableListOf<Int>()
        var reviewed = 0
        val added = mutableListOf<String>()
        val removed = mutableListOf<WinRow>()
        var quarter = 0
    }

    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun onScreen(text: String): Boolean = ScreenShots.texts(compose).any { DateGlue.plain(it).contains(text) }

    private fun draw(theme: ThemeChoice, fill: Fill, taps: Taps = Taps()) {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        compose.mainClock.autoAdvance = false
        val v = week(fill)
        val w = winsView(fill)
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    Week(
                        v, w, null, { taps.steps += it },
                        balance = null, balanceSaid = null, setTarget = { _, _ -> }, toggleTargets = {},
                        countSaid = null, countAs = { _, _ -> }, undoCount = {},
                        reflection = null, reflectSaid = null, removedEntry = null,
                        act = ReflectionActions(save = { _, _, _, _, _ -> }, skip = {}, remove = {}, undoRemove = {}, tick = {}),
                        reviewDone = { taps.reviewed++ },
                        addWin = { taps.added += it },
                        removeWin = { taps.removed += it },
                        toggleQuarter = { taps.quarter++ },
                    )
                }
            }
        }
        turns()
        compose.waitForIdle()
    }

    private fun scrollTo(text: String) {
        // Glued lines (LoneWordGlue) hold their last words with no-break spaces, so match them plain.
        val plain = SemanticsMatcher("shows \"$text\"") { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.any { DateGlue.plain(it.text).contains(text) } == true
        }
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(plain)
        turns()
    }

    /** Every button drawn answers a 48 dp touch (‹ › were 44 dp, a win's Remove ~32 dp). */
    private fun assertTouchTargets(name: String) {
        val small = compose.onAllNodes(hasClickAction() and SemanticsMatcher("is a button") { it.config.getOrNull(SemanticsProperties.Role) == Role.Button })
            .fetchSemanticsNodes()
            .filter { it.size.height / compose.density.density < 47.5f }
            .map { n ->
                val label = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                    ?: n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "?"
                "\"$label\" ${(n.size.width / compose.density.density).toInt()}×${(n.size.height / compose.density.density).toInt()} dp"
            }
        if (small.isNotEmpty()) fail("$name: controls under 48 dp to tap:\n" + small.joinToString("\n") { "- $it" })
    }

    private fun assertLines(name: String, fill: Fill) {
        val v = week(fill)
        val w = winsView(fill)
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertReadable(compose, name, v.done.map { it.title } + v.stillOpen.map { it.title }, mustShow = false)
        val lines = listOfNotNull(v.comparedLine, v.habitsLine, v.fastingLine, v.reviewedLine, w.weekLine) +
            v.listsLines + v.aheadLines + v.northStar.map { it.line } + v.goals.map { it.line }
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        ScreenShots.assertEachOneLine(compose, name, v.stillOpen.map { it.detail } + v.habits.map { it.line } + listOf(v.rangeLabel))
        assertTouchTargets(name)
    }

    private fun pane(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK) {
        val v = week(fill)
        draw(theme, fill)
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("review", name))
        if (!onScreen("Review")) fail("$name: no title")
        if (!onScreen(v.title)) fail("$name: the week's title \"${v.title}\" isn't on screen")
        if (!onScreen(v.rangeLabel)) fail("$name: the week's dates \"${v.rangeLabel}\" aren't on screen")
        v.tiles.forEach { if (!onScreen(it.label)) fail("$name: the ${it.label} tile isn't shown") }
        assertLines(name, fill)
        // Further down: the wins, then what's still open, the week ahead and the north star (the lone dash lived here).
        scrollTo("WINS")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("review", "$name-wins"))
        assertLines("$name-wins", fill)
        scrollTo("Time returned")
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("review", "$name-north"))
        assertLines("$name-north", fill)
        if (onScreen("—")) fail("$name: a north-star number not counted yet shows a dash")
        when (fill) {
            Fill.EMPTY -> {
                if (!onScreen("Counted once Navilife acts for you")) fail("$name: a pending number doesn't say when it's counted")
                scrollTo("Done reviewing")
                if (!onScreen("Done reviewing")) fail("$name: no Done reviewing")
            }
            Fill.ONE -> {
                if (!onScreen("Reviewed Sun 18 Oct at 18:42")) fail("$name: a reviewed week doesn't say when")
                if (onScreen("Done reviewing")) fail("$name: Done reviewing offered on a week already reviewed")
            }
            Fill.MANY -> {
                if (!onScreen("Interruptions")) fail("$name: Interruptions isn't listed")
                scrollTo("and 2 more")
                if (!onScreen("and 2 more")) fail("$name: still open's \"and 2 more\" isn't shown")
            }
        }
    }

    private fun clickNode(matcher: SemanticsMatcher, what: String) {
        val nodes = compose.onAllNodes(matcher and hasClickAction()).fetchSemanticsNodes()
        if (nodes.isEmpty()) fail("$what isn't a button")
        compose.onAllNodes(matcher and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        turns()
    }

    /** Works by hand: ‹ and ›, Done reviewing, keeping a win, Remove and "This quarter". */
    private fun byHand() {
        val name = "review-by-hand"
        val taps = Taps()
        draw(ThemeChoice.DARK, Fill.MANY, taps)
        clickNode(hasContentDescription("Previous week"), "$name: ‹")
        if (taps.steps != listOf(-1)) fail("$name: ‹ didn't step back a week (${taps.steps})")
        if (compose.onAllNodes(hasContentDescription("Next week") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()) {
            fail("$name: › can be tapped on this week")
        }
        scrollTo("What went well")
        compose.onNode(hasContentDescription(winsView(Fill.MANY).hint)).performTextInput("Led the planning session")
        compose.onNode(hasContentDescription(winsView(Fill.MANY).hint)).performImeAction()
        turns()
        if (taps.added != listOf("Led the planning session")) fail("$name: the field didn't keep the win (${taps.added})")
        clickNode(hasContentDescription("Remove Calm handover with the new starter"), "$name: a win's Remove")
        if (taps.removed.map { it.id } != listOf("w2")) fail("$name: Remove didn't remove that win (${taps.removed.map { it.id }})")
        scrollTo("This quarter")
        if (onScreen("Week of 5 Oct")) fail("$name: the quarter's weeks show before a tap")
        clickNode(hasText("This quarter", substring = true), "$name: This quarter")
        if (taps.quarter != 1 || !onScreen("Week of 5 Oct")) fail("$name: This quarter didn't unfold its weeks")
        scrollTo("Done reviewing")
        clickNode(hasText("Done reviewing"), "$name: Done reviewing")
        if (taps.reviewed != 1) fail("$name: Done reviewing did nothing")
    }

    /** Works by hand on a past week: › steps forward. */
    private fun byHandPast() {
        val name = "review-by-hand-past"
        val taps = Taps()
        draw(ThemeChoice.DARK, Fill.ONE, taps)
        clickNode(hasContentDescription("Next week"), "$name: ›")
        if (taps.steps != listOf(1)) fail("$name: › didn't step forward a week (${taps.steps})")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverEmpty() { pane("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOne() { pane("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOneLight() { pane("cover-light-one", Fill.ONE, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverMany() { pane("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerOne() { pane("inner-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerMany() { pane("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun byHandTest() { byHand() }
    @Test @Config(qualifiers = ScreenShots.COVER) fun byHandPastTest() { byHandPast() }
}
