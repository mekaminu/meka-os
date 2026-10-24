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
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
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
import os.meka.core.domain.BalanceArea
import os.meka.core.domain.BalanceChoices
import os.meka.core.domain.BalanceRules
import os.meka.core.domain.BalanceView
import os.meka.core.domain.CalendarEvent
import os.meka.core.domain.CivilDate
import os.meka.core.domain.DateGlue
import os.meka.core.domain.GoalHorizon
import os.meka.core.domain.GoalItem
import os.meka.core.domain.HolidayCalendar
import os.meka.core.domain.LocalCalendar
import os.meka.core.domain.Reflection
import os.meka.core.domain.ReflectionRules
import os.meka.core.domain.ReflectionView
import os.meka.core.domain.ReviewDone
import os.meka.core.domain.ReviewHabit
import os.meka.core.domain.ReviewOpen
import os.meka.core.domain.ReviewRules
import os.meka.core.domain.ReviewTile
import os.meka.core.domain.WeeklyReviewView
import os.meka.core.domain.WinRules
import os.meka.core.domain.WorkHours
import os.meka.core.domain.WorkSchedule
import kotlin.test.fail

/**
 * The quality gate, slice 62 (build plan: "the rest of Ask's places' shots", Review's Balance and Reflect): the weekly
 * review's Balance section (each area's bar against its target, what counted, the four-week trend, "Count as …" for
 * events with no clear area, Targets) and its Reflect card (the three questions with their suggestions, the saved
 * answers, the journal), drawn from invented weeks: this week with nothing counted yet, last week with one of each and
 * its reflection saved, and a full week (every area, a set target, the trend, four events to count, a choice of his,
 * the reflection asked for with suggestions, three earlier weeks in the journal), on the Fold's cover and inner screens,
 * Dark (and one in Light). Saved under `review/` and read for nonsense, cut lines, broken words, split numbers, dates
 * and durations, a grey line leaving a word alone or starting with a dot, and every button answering a 48 dp touch. By
 * hand: Targets opens and a chip sets a target, a "Count as" chip counts an event, Undo, Reflect now opens the
 * questions, a suggestion fills its field, Save keeps the three, Skip this week, the journal opens and Remove, and
 * Change offers the times.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = ShotApplication::class)
class ReviewBalanceReflectScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private companion object { const val SETTLE_MS = 3_000L }

    private enum class Fill { EMPTY, ONE, MANY }

    private val cal = LocalCalendar.fixedOffset(3_600_000L)
    private val work = WorkHours(WorkSchedule.DEFAULT, HolidayCalendar.NONE)

    /** Mon 19 Oct 2026; "now" is Saturday 24th at 14:00. */
    private val ws = CivilDate.toEpochDay(2026, 10, 19)
    private val today = ws + 5
    private val nowMin = 14 * 60
    private val nowMs = cal.toEpochMs(today, nowMin)

    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private var n = 0
    private fun ev(title: String, day: Long, h: Int, m: Int, endH: Int, endM: Int = 0) =
        CalendarEvent("e${n++}", title, at(day, h, m), at(day, endH, endM), false, null, "google", "alex@example.com", "Personal")

    private fun week(fill: Fill): WeeklyReviewView = when (fill) {
        Fill.EMPTY -> WeeklyReviewView.EMPTY.copy(
            weekStart = ws, rangeLabel = ReviewRules.rangeLabel(ws), canGoBack = true,
            tiles = listOf(ReviewTile("Done", 0, "tasks"), ReviewTile("Lists", 0, "closed")),
        )
        Fill.ONE -> WeeklyReviewView.EMPTY.copy(
            weekStart = ws - 7, offset = -1, title = ReviewRules.title(-1, ws - 7), rangeLabel = ReviewRules.rangeLabel(ws - 7),
            isCurrent = false, canGoBack = true, canGoForward = true,
            tiles = listOf(ReviewTile("Done", 1, "task")),
            done = listOf(ReviewDone("d1", "Book the boiler service", "Thu")),
            stillOpen = listOf(ReviewOpen("o1", "Renew the car insurance", "Was due Wed 14 Oct")),
            reviewed = true, reviewedLine = "Reviewed Sun 18 Oct at 18:42",
        )
        Fill.MANY -> WeeklyReviewView.EMPTY.copy(
            weekStart = ws, rangeLabel = ReviewRules.rangeLabel(ws), canGoBack = true,
            tiles = listOf(ReviewTile("Done", 9, "tasks"), ReviewTile("Habit ticks", 11, "of 13")),
            done = listOf(
                ReviewDone("d1", "Book the boiler service", "Mon"), ReviewDone("d2", "Send the school trip form back", "Tue"),
                ReviewDone("d3", "Call the garage about the MOT", "Wed"), ReviewDone("d4", "Pay the window cleaner", "Thu"),
            ),
            doneMore = 5,
            habits = listOf(
                ReviewHabit("h1", "Walk 10k steps", 6, 6, true, listOf(true, true, true, true, true, true, false), ReviewRules.habitLine(6, 6, true, true)),
                ReviewHabit("h2", "Read twenty pages", 5, 7, false, listOf(true, false, true, true, false, true, false), ReviewRules.habitLine(5, 7, false, true)),
            ),
            stillOpen = listOf(
                ReviewOpen("o1", "Renew the car insurance", "Was due Wed 21 Oct"),
                ReviewOpen("o2", "Sort the loft boxes for the charity shop", "Planned Thu 22 Oct"),
            ),
        )
    }

    private fun events(fill: Fill): List<CalendarEvent> = when (fill) {
        Fill.EMPTY -> emptyList()
        Fill.ONE -> {
            val m = ws - 7
            listOf(
                ev("Date night", m + 4, 19, 0, 21, 30),
                ev("Gym", m + 1, 7, 0, 8),
                ev("Cinema", m + 5, 15, 0, 17, 30),
                ev("Choir", m + 3, 19, 0, 20, 30),
            )
        }
        Fill.MANY -> listOf(
            ev("Gym", ws, 7, 0, 8),
            ev("Choir", ws, 19, 0, 20),
            ev("Volunteering at the community food bank", ws + 1, 18, 30, 20),
            ev("Guitar lesson", ws + 1, 20, 30, 21, 30),
            ev("Dentist", ws + 2, 14, 0, 15),
            ev("Pick up parcel", ws + 2, 12, 0, 12, 30),
            ev("Quiz night", ws + 2, 21, 0, 22, 30),
            ev("Choir", ws + 3, 19, 0, 20),
            ev("Client call", ws + 3, 18, 0, 18, 45),
            ev("Cinema", ws + 3, 20, 30, 22, 30),
            ev("Date night", ws + 4, 19, 0, 22),
            ev("Parkrun", ws + 5, 9, 0, 9, 45),
            ev("Sam's birthday party", ws + 5, 10, 30, 12, 30),
            ev("Allotment", ws + 5, 12, 45, 13, 45),
        )
    }

    private val habits = listOf(
        "Walk 10k steps" to listOf(true, true, true, true, true, true, false),
        "Read twenty pages" to listOf(true, false, true, true, false, true, false),
    )

    private fun balance(fill: Fill): BalanceView = when (fill) {
        Fill.EMPTY -> BalanceRules.view(ws, nowMs, cal, work, emptyList(), emptyList(), emptyMap())
        Fill.ONE -> BalanceRules.view(ws - 7, nowMs, cal, work, events(Fill.ONE), emptyList(), emptyMap())
        Fill.MANY -> {
            val trend = listOf(
                ws - 21 to mapOf(BalanceArea.WORK to 41 * 60, BalanceArea.FAMILY to 7 * 60, BalanceArea.HEALTH to 4 * 60, BalanceArea.SELF to 90),
                ws - 14 to mapOf(BalanceArea.WORK to 44 * 60, BalanceArea.FAMILY to 11 * 60, BalanceArea.HEALTH to 2 * 60, BalanceArea.SELF to 3 * 60),
                ws - 7 to mapOf(BalanceArea.WORK to 39 * 60, BalanceArea.FAMILY to 9 * 60 + 30, BalanceArea.HEALTH to 3 * 60, BalanceArea.SELF to 2 * 60),
                ws to emptyMap(),
            )
            val chosen = BalanceChoices(mapOf("quiz night" to BalanceArea.SELF), mapOf("quiz night" to "Quiz night"))
            BalanceRules.view(ws, nowMs, cal, work, events(Fill.MANY), habits, mapOf(BalanceArea.FAMILY to 10 * 60), trendWeeks = trend, chosen = chosen)
        }
    }

    private fun goal(id: String, title: String, weekOf: Long? = null) = GoalItem(
        id = id, title = title, target = null, horizon = GoalHorizon.MONTH, progressPct = 0, counted = true,
        tasksDone = 0, tasksTotal = 0, habitCount = 0, done = false, meta = "", hasConflict = false, weekOf = weekOf,
    )

    private fun saved(week: Long, wentWell: String, drop: String, matters: String) =
        Reflection(week, wentWell, drop, matters, false, at(week + 6, 18, 50))

    private fun reflection(fill: Fill): ReflectionView = when (fill) {
        Fill.EMPTY -> ReflectionRules.view(week(fill), emptyList(), emptyList(), emptyList(), today, nowMin, cal)
        Fill.ONE -> ReflectionRules.view(
            week(fill), listOf(saved(ws - 7, "Got the boiler booked\nWalked most days", "Late-night emails", "Run 5k without stopping")),
            listOf("Fixed the rota clash"), emptyList(), today, nowMin, cal,
        )
        Fill.MANY -> ReflectionRules.view(
            week(fill),
            listOf(
                saved(ws - 7, "Calm handover with the new starter", "Saying yes to every meeting", "Clear the garage"),
                saved(ws - 14, "Shipped the release notes a day early\nThree runs", "", "Book the half-term swimming lessons"),
                saved(ws - 21, "", "Scrolling after midnight", ""),
            ),
            listOf("Shipped the release notes a day early", "Closed the audit actions"),
            listOf(goal("g1", "Run 5k without stopping", weekOf = ws + 7), goal("g2", "Clear the garage")),
            today, nowMin, cal, requestedWeek = ws,
        )
    }

    private class Taps {
        val targets = mutableListOf<Pair<BalanceArea, Int>>()
        var toggled = 0
        val counted = mutableListOf<Pair<String, String>>()
        var undone = 0
        val saved = mutableListOf<List<String>>()
        val skipped = mutableListOf<Boolean>()
        val removed = mutableListOf<Long>()
        val times = mutableListOf<Int>()
    }

    private fun turns(ms: Long = SETTLE_MS) {
        repeat((ms / 100).toInt()) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(100)
        }
    }

    private fun onScreen(text: String): Boolean = ScreenShots.texts(compose).any { DateGlue.plain(it).contains(text) }

    private fun draw(theme: ThemeChoice, fill: Fill, taps: Taps = Taps(), countSaid: String? = null) {
        val context = ApplicationProvider.getApplicationContext<ShotApplication>()
        ThemeChoice.save(context, theme)
        compose.mainClock.autoAdvance = false
        val v = week(fill)
        val w = WinRules.view(emptyList(), v.weekStart, today, nowMin)
        val b = balance(fill)
        val r = reflection(fill)
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    Week(
                        v, w, null, {},
                        balance = b, balanceSaid = null,
                        setTarget = { a, m -> taps.targets += a to m }, toggleTargets = { taps.toggled++ },
                        countSaid = countSaid, countAs = { t, a -> taps.counted += t to a }, undoCount = { taps.undone++ },
                        reflection = r, reflectSaid = null, removedEntry = null,
                        act = ReflectionActions(
                            save = { a, d, m, _, _ -> taps.saved.add(listOf(a, d, m)) },
                            skip = { taps.skipped += it }, remove = { taps.removed += it.weekStart }, undoRemove = {}, tick = {},
                            setTime = { taps.times += it },
                        ),
                        reviewDone = {}, addWin = {}, removeWin = {}, toggleQuarter = {},
                    )
                }
            }
        }
        turns()
        compose.waitForIdle()
    }

    private fun scrollTo(text: String) {
        val plain = SemanticsMatcher("shows \"$text\"") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { DateGlue.plain(it.text).contains(text) } == true
        }
        // The clock runs while it scrolls: a lazy list's scroll waits for a frame (as in BriefScreensTest).
        compose.mainClock.autoAdvance = true
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(plain)
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        turns()
    }

    /** Every button and chip drawn answers a 48 dp touch. */
    private fun assertTouchTargets(name: String) {
        val tappable = SemanticsMatcher("is a button or a choice") {
            val role = it.config.getOrNull(SemanticsProperties.Role)
            role == Role.Button || role == Role.RadioButton
        }
        val small = compose.onAllNodes(hasClickAction() and tappable)
            .fetchSemanticsNodes()
            .filter { it.size.height / compose.density.density < 47.5f }
            .map { node ->
                val label = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
                    ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "?"
                "\"$label\" ${(node.size.width / compose.density.density).toInt()}×${(node.size.height / compose.density.density).toInt()} dp"
            }
        if (small.isNotEmpty()) fail("$name: controls under 48 dp to tap:\n" + small.joinToString("\n") { "- $it" })
    }

    /** Quality gate slice 64: the trend's bars are slim 18 dp bars, and a week with any time shows at least a sliver. */
    private fun assertTrendBars(name: String) {
        val d = compose.density.density
        val bars = compose.onAllNodesWithTag("trend-bar", useUnmergedTree = true).fetchSemanticsNodes()
        if (bars.isEmpty()) return
        val wide = bars.filter { it.size.width / d > 18.5f }
        if (wide.isNotEmpty()) fail("$name: trend bars drawn wider than 18 dp (${wide.map { (it.size.width / d).toInt() }})")
        val slivers = bars.filter { it.size.height > 0 && it.size.height / d < 1.5f }
        if (slivers.isNotEmpty()) fail("$name: a trend bar under 2 dp tall (${slivers.map { it.size.height / d }})")
    }

    /** Quality gate slice 64: the suggestions' rows touch (each chip's 48 dp touch is the gap), no spacing added. */
    private fun assertHintRowsTight(name: String) {
        val d = compose.density.density
        val rows = compose.onAllNodesWithTag("reflect-hint", useUnmergedTree = true).fetchSemanticsNodes()
            .map { it.boundsInRoot }.groupBy { it.top }.toSortedMap().values.map { r -> r.first().top / d to r.maxOf { it.bottom } / d }
        val gaps = rows.zipWithNext { a, b -> b.first - a.second }.filter { it > 0.5f && it < 60f }
        if (gaps.isNotEmpty()) fail("$name: ${gaps.map { it.toInt() }} dp added between suggestion rows (their 48 dp touch is enough)")
    }

    private fun assertLines(name: String, fill: Fill) {
        val b = balance(fill)
        val r = reflection(fill)
        ScreenShots.assertSensible(compose, name)
        ScreenShots.assertNoBrokenWords(compose, name)
        ScreenShots.assertNumbersWhole(compose, name)
        ScreenShots.assertDatesWhole(compose, name)
        ScreenShots.assertDurationsWhole(compose, name)
        val lines = listOfNotNull(b.line, b.sources, b.uncounted, r.line, r.facts, r.whenLine) +
            b.rows.map { it.line } + b.rows.flatMap { it.items } + b.rows.mapNotNull { it.trendLine }
        ScreenShots.assertNoLoneWords(compose, name, lines)
        ScreenShots.assertNoDotStartingALine(compose, name, lines)
        ScreenShots.assertNoDotEndingALine(compose, name, lines)
        // An event to count reads its days and hours on one line beside its title; a row's status on one line.
        ScreenShots.assertEachOneLine(compose, name, b.unclear.map { it.line } + b.rows.mapNotNull { it.status })
        assertTouchTargets(name)
        assertTrendBars(name)
        assertHintRowsTight(name)
    }

    private fun shoot(name: String) = compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("review", name))

    private fun pane(name: String, fill: Fill, theme: ThemeChoice = ThemeChoice.DARK) {
        val b = balance(fill)
        val r = reflection(fill)
        draw(theme, fill)
        scrollTo("BALANCE")
        // Shot first, so a failing screen is still in the `fold-screens` artifact to look at.
        shoot("$name-balance")
        if (!onScreen(b.line)) fail("$name: the balance's line \"${b.line}\" isn't on screen")
        assertLines("$name-balance", fill)
        scrollTo("Targets")
        shoot("$name-balance-foot")
        assertLines("$name-balance-foot", fill)
        if (fill == Fill.MANY) {
            if (b.unclear.size < 3) fail("$name: the fixture should have events to count (${b.unclear.map { it.title }})")
            if (!onScreen("Count as")) fail("$name: no \"Count as\" under an event with no clear area")
        }
        scrollTo("REFLECT")
        shoot("$name-reflect")
        if (!onScreen(r.title)) fail("$name: the reflection's title \"${r.title}\" isn't on screen")
        assertLines("$name-reflect", fill)
        when (fill) {
            Fill.EMPTY -> {
                if (onScreen(r.wentWellPrompt)) fail("$name: the questions show on a Saturday before Reflect now")
                if (!onScreen(ReflectionRules.QUIET_WEEK)) fail("$name: a week with nothing counted doesn't say so")
            }
            Fill.ONE -> {
                if (!onScreen("Late-night emails")) fail("$name: the saved answers aren't shown")
                if (!onScreen("Edit")) fail("$name: a saved reflection has no Edit")
            }
            Fill.MANY -> {
                if (!onScreen(r.wentWellPrompt)) fail("$name: the questions aren't open when asked for")
                if (!onScreen("+ Shipped the release notes a day early")) fail("$name: went well's suggestions aren't shown")
                scrollTo("Save")
                shoot("$name-reflect-form")
                assertLines("$name-reflect-form", fill)
                scrollTo(r.journalTitle)
                shoot("$name-journal")
                assertLines("$name-journal", fill)
            }
        }
    }

    private fun clickNode(matcher: SemanticsMatcher, what: String) {
        val nodes = compose.onAllNodes(matcher and hasClickAction()).fetchSemanticsNodes()
        if (nodes.isEmpty()) fail("$what isn't a button")
        compose.onAllNodes(matcher and hasClickAction())[0].performSemanticsAction(SemanticsActions.OnClick)
        turns()
    }

    /** Works by hand: Targets and a target chip, a "Count as" chip, Undo, the questions, Save, the journal, Change. */
    private fun byHand() {
        val name = "review-balance-by-hand"
        val taps = Taps()
        draw(ThemeChoice.DARK, Fill.MANY, taps, countSaid = BalanceRules.countAsLine("Choir", BalanceArea.SELF, false))
        scrollTo("Targets")
        if (onScreen("Family · a week")) fail("$name: the targets show before a tap")
        clickNode(hasText("Targets"), "$name: Targets")
        if (taps.toggled != 1 || !onScreen("Family · a week")) fail("$name: Targets didn't open the targets")
        shoot("cover-dark-many-targets")
        assertLines("$name-targets", Fill.MANY)
        scrollTo("Family · a week")
        clickNode(hasText("14 h"), "$name: Family's 14 h")
        if (taps.targets != listOf(BalanceArea.FAMILY to 14 * 60)) fail("$name: a target chip didn't set it (${taps.targets})")
        if (!onScreen("Quiz night · Self")) fail("$name: his choice isn't listed under Targets")
        scrollTo("Count as")
        val first = balance(Fill.MANY).unclear.first().title
        clickNode(hasText("Health"), "$name: Count as Health")
        if (taps.counted.firstOrNull() != (first to "HEALTH")) fail("$name: Count as didn't count \"$first\" (${taps.counted})")
        scrollTo("Undo")
        clickNode(hasText("Undo"), "$name: Undo")
        if (taps.undone != 1) fail("$name: Undo did nothing")

        scrollTo("+ Shipped the release notes a day early")
        clickNode(hasText("+ Shipped the release notes a day early"), "$name: a went-well suggestion")
        if (!onScreen("✓ Shipped the release notes a day early")) fail("$name: the suggestion didn't go into its field")
        compose.mainClock.autoAdvance = true
        compose.onNode(hasContentDescription(ReflectionRules.DROP_PROMPT) and hasSetTextAction()).performTextInput("Late-night emails")
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        turns()
        scrollTo("Save")
        clickNode(hasText("Save"), "$name: Save")
        if (taps.saved.singleOrNull() != listOf("Shipped the release notes a day early", "Late-night emails", "")) {
            fail("$name: Save didn't keep the answers (${taps.saved})")
        }
        scrollTo("Skip this week")
        clickNode(hasText("Skip this week"), "$name: Skip this week")
        if (taps.skipped != listOf(true)) fail("$name: Skip this week did nothing (${taps.skipped})")

        val journal = reflection(Fill.MANY).journalTitle
        scrollTo(journal)
        if (onScreen("Week of 5 Oct")) fail("$name: the journal's weeks show before a tap")
        clickNode(hasText(journal), "$name: $journal")
        if (!onScreen("Week of 5 Oct")) fail("$name: the journal didn't open")
        shoot("cover-dark-many-journal-open")
        assertLines("$name-journal-open", Fill.MANY)
        clickNode(hasContentDescription("Remove Week of 5 Oct's reflection"), "$name: Remove")
        if (taps.removed != listOf(ws - 14)) fail("$name: Remove didn't remove that week (${taps.removed})")

        scrollTo("Change")
        clickNode(hasText("Change"), "$name: Change")
        if (!onScreen("19:00")) fail("$name: Change didn't offer the times")
        clickNode(hasText("19:00"), "$name: 19:00")
        if (taps.times != listOf(19 * 60)) fail("$name: a time didn't set when it comes (${taps.times})")
    }

    /** Works by hand on a week not asked for: Reflect now opens the questions. */
    private fun byHandFolded() {
        val name = "review-reflect-by-hand"
        draw(ThemeChoice.DARK, Fill.EMPTY)
        scrollTo("REFLECT")
        clickNode(hasText(ReflectionRules.TITLE_THIS_WEEK), "$name: Reflect now")
        if (!onScreen(ReflectionRules.WENT_WELL)) fail("$name: Reflect now didn't open the questions")
    }

    @Test @Config(qualifiers = ScreenShots.COVER) fun coverEmpty() { pane("cover-dark-empty", Fill.EMPTY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOne() { pane("cover-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverOneLight() { pane("cover-light-one", Fill.ONE, ThemeChoice.LIGHT) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun coverMany() { pane("cover-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerOne() { pane("inner-dark-one", Fill.ONE) }
    @Test @Config(qualifiers = ScreenShots.INNER) fun innerMany() { pane("inner-dark-many", Fill.MANY) }
    @Test @Config(qualifiers = ScreenShots.COVER) fun byHandTest() { byHand() }
    @Test @Config(qualifiers = ScreenShots.COVER) fun byHandFoldedTest() { byHandFolded() }
}
