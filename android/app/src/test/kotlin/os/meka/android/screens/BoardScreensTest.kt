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
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import os.meka.android.calendar.AREA_DOT_TAG
import os.meka.android.calendar.BUDGET_TAG
import os.meka.android.calendar.NUDGE_TAG
import os.meka.android.calendar.TIMELINE_TAB_TAG
import os.meka.android.calendar.TIMELINE_TAG
import os.meka.android.calendar.TIMELINE_TOGGLE_TAG
import os.meka.android.calendar.PLANNER_CARD_TAG
import os.meka.android.calendar.ADD_TAG
import os.meka.android.calendar.LIST_TITLE_TAG
import os.meka.android.calendar.LISTS_RULE_TAG
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

    private fun core(at: Long = now): MekaCore = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = null,
        secureRandom = Random(7), timeZone = { TimeZone.of("Europe/London") }, nowMs = { at },
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

    /**
     * Each open lane's first card in the full week, row by row (Option B slice 2, unfolded: Wed–Fri across with Mon and
     * Tue folded narrow, the weekend underneath).
     */
    private val firstWeekdayCards = listOf("Fix the gate latch", "Order new bins", "Plan Saturday's football run with Alex")
    private val firstWeekendCards = listOf("Renew the car insurance", "Batch cook for the week")

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
        // Option B slice 2: level along each row (the weekend's row sits under the weekdays').
        if (level) {
            ScreenShots.assertLevelBy(compose, name, firstWeekdayCards, PLANNER_CARD_TAG)
            ScreenShots.assertLevelBy(compose, name, firstWeekendCards, PLANNER_CARD_TAG)
        }
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

    /**
     * Option B slice 2 (the layout), unfolded: Monday to Friday across, the weekend's columns underneath and shorter,
     * then No day yet and Someday as lists below them; ‹ › steps a week and back.
     */
    @Test @Config(qualifiers = ScreenShots.INNER)
    fun theWeekendSitsUnderTheWeekdaysWithTheListsBelow() {
        ThemeChoice.save(ApplicationProvider.getApplicationContext(), ThemeChoice.DARK)
        val core = core()
        runBlocking {
            core.tick()
            val today = core.weekBoard.value.todayEpochDay
            core.addOnBoardSized("Fix the gate latch", today, 60)
            core.addOnBoardSized("Renew the car insurance", today + 3, 30)
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
        val weekday = cardBounds("Fix the gate latch")
        val weekend = cardBounds("Renew the car insurance")
        assertTrue("the weekend's card sits under the weekdays' ($weekend vs $weekday)", weekend.top >= weekday.bottom)
        ScreenShots.assertShows(compose, "layout", "No day yet")
        ScreenShots.assertShows(compose, "layout", "Nothing on Someday")
        compose.onNodeWithContentDescription("Next week").performClick()
        settle()
        assertTrue(
            "next week shows its own days, not this week's card",
            compose.onAllNodesWithText("Fix the gate latch", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithContentDescription("Previous week").performClick()
        settle()
        cardBounds("Fix the gate latch")
    }

    /**
     * Option B slice 3 (the colour categories), unfolded: a Someday card Navilife reads as House sits in the House list
     * with "House" by its dot; filed under Family (the menu's AREA row), it moves back to the Someday list.
     */
    @Test @Config(qualifiers = ScreenShots.INNER)
    fun aCardsAreaShowsByItsDotAndItsMenuRefilesIt() {
        ThemeChoice.save(ApplicationProvider.getApplicationContext(), ThemeChoice.DARK)
        val core = core()
        val id = runBlocking {
            core.tick()
            val id = core.addOnBoardSized("Boiler service", core.weekBoard.value.todayEpochDay, 30)!!
            core.somedayOnBoard(id)
            id
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
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("board", "inner-dark-areas"))
        ScreenShots.assertSensible(compose, "inner-dark-areas")
        ScreenShots.assertShows(compose, "areas", "Side hustle")
        if (compose.onAllNodesWithContentDescription("House, 1 task").fetchSemanticsNodes().isEmpty()) fail("the House list doesn't hold the card")
        if (compose.onAllNodesWithTag(AREA_DOT_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) fail("no area dot on the card")
        ScreenShots.assertShows(compose, "areas", "House")

        // The menu's Family row calls this (Robolectric can't reach a card's dropdown popup); the screen must follow.
        runBlocking { core.areaOnBoard(id, os.meka.core.domain.BoardArea.FAMILY) }
        awaitCore("area") { core.weekBoard.value.find(id)?.card?.task?.area == "FAMILY" }
        if (compose.onAllNodesWithContentDescription("Someday, 1 task").fetchSemanticsNodes().isEmpty()) fail("the card didn't move to Someday's list")
    }

    /**
     * Option B slice 4 (Obi's nudge): today (Wed from 17:15, under 4h free) holds five hours of cards, so the header
     * says Today is over and offers to move one of them to another day; the line on screen is the core's, and a tap
     * moves that card there.
     */
    @Test @Config(qualifiers = ScreenShots.INNER)
    fun obisNudgeSaysTodayIsOverAndATapMovesACard() {
        ThemeChoice.save(ApplicationProvider.getApplicationContext(), ThemeChoice.DARK)
        val core = core()
        runBlocking {
            core.tick()
            val today = core.weekBoard.value.todayEpochDay
            listOf("Fix the gate latch", "Batch cook for the week", "Paint the fence", "Order new bins", "Sort the loft")
                .forEach { core.addOnBoardSized(it, today, 60) }
        }
        val board = core.weekBoard.value
        val expected = core.boardNudge(board, board.weeks.first().startEpochDay)
            ?: throw AssertionError("the core has no nudge for this week")
        assertEquals(os.meka.core.domain.BoardNudgeKind.OVER, expected.kind)
        assertTrue("Today is over: ${expected.line}", expected.line.startsWith("Today is ") && expected.line.contains(" over · move “"))
        val fix = expected.fix ?: throw AssertionError("no card offered: ${expected.line}")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    WeekBoardView(core, EventUndo(), onEvent = {}, Modifier.fillMaxSize())
                }
            }
        }
        settle()
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("board", "inner-dark-nudge"))
        ScreenShots.assertSensible(compose, "inner-dark-nudge")
        val nudge = compose.onAllNodesWithTag(NUDGE_TAG).fetchSemanticsNodes()
        if (nudge.isEmpty()) fail("no nudge in the header")
        assertEquals(expected.spoken, nudge.first().config[SemanticsProperties.ContentDescription].joinToString())
        // Match slice 3: unfolded, the pill sits in the header's row just before Today, as the mockup draws it.
        val pill = nudge.first().boundsInRoot
        val today = compose.onAllNodesWithText(os.meka.core.domain.BoardMatchRules.TODAY).fetchSemanticsNodes()
            .map { it.boundsInRoot }.minByOrNull { it.top } ?: throw AssertionError("no Today in the header")
        assertTrue("the pill sits in Today's row ($pill vs $today)", pill.top < today.bottom && pill.bottom > today.top)
        assertTrue("the pill sits before Today ($pill vs $today)", pill.right <= today.left + 1f)
        val month = compose.onAllNodesWithTag(os.meka.android.calendar.MONTH_TAG, useUnmergedTree = true).fetchSemanticsNodes()
            .single().boundsInRoot
        assertTrue("the pill sits after the month ($pill vs $month)", pill.left >= month.right - 1f)
        compose.onNodeWithTag(NUDGE_TAG).performClick()
        awaitCore("nudge") { core.weekBoard.value.dayOf(fix.taskId)?.epochDay == fix.toDay }
    }

    /**
     * Option B match slice 3: on the cover screen Obi's pill is its own line between the month and the week's buttons
     * (the mockup at 390 wide), never under the whole header.
     */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun foldedObisNudgeSitsUnderTheMonthAboveTheButtons() {
        ThemeChoice.save(ApplicationProvider.getApplicationContext(), ThemeChoice.DARK)
        val core = core()
        runBlocking {
            core.tick()
            val today = core.weekBoard.value.todayEpochDay
            listOf("Fix the gate latch", "Batch cook for the week", "Paint the fence", "Order new bins", "Sort the loft")
                .forEach { core.addOnBoardSized(it, today, 60) }
        }
        show(core)
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("board", "cover-dark-nudge"))
        ScreenShots.assertSensible(compose, "cover-dark-nudge")
        val pill = compose.onAllNodesWithTag(NUDGE_TAG).fetchSemanticsNodes().singleOrNull()?.boundsInRoot
            ?: throw AssertionError("no nudge on the cover screen")
        val month = compose.onAllNodesWithTag(os.meka.android.calendar.MONTH_TAG, useUnmergedTree = true).fetchSemanticsNodes()
            .single().boundsInRoot
        val next = compose.onAllNodesWithContentDescription("Next week").fetchSemanticsNodes().map { it.boundsInRoot }
            .minByOrNull { it.top } ?: throw AssertionError("no Next week button")
        assertTrue("the pill sits under the month ($pill vs $month)", pill.top >= month.bottom - 1f)
        assertTrue("the pill sits above the buttons ($pill vs $next)", pill.bottom <= next.top + 1f)
        // (9) The folded day is the same column as unfolded: the serif date, the budget pill, the "+ add" at its foot.
        if (compose.onAllNodesWithTag(os.meka.android.calendar.DAY_DATE_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            fail("no serif date on the cover screen's day")
        }
        if (compose.onAllNodesWithTag(BUDGET_TAG).fetchSemanticsNodes().isEmpty()) fail("no budget pill on the cover screen's day")
        if (compose.onAllNodesWithTag(ADD_TAG, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) fail("no + add on the cover screen's day")
    }

    /**
     * Option B slice 5 (the day timeline, refinement 2): unfolded, Timeline in the header opens today's timeline beside
     * the week (on the Fold's 829 dp it starts closed, so the five days keep their width); a task planned at 19:00 for
     * 45 minutes is a block there, read out as the core says.
     */
    @Test @Config(qualifiers = ScreenShots.INNER)
    fun unfoldedTheTimelineOpensBesideTheWeekWithTodaysTimedTask() {
        val (core, spoken) = timedCore()
        show(core)
        assertTrue("no Timeline in the header", compose.onAllNodesWithTag(TIMELINE_TOGGLE_TAG).fetchSemanticsNodes().isNotEmpty())
        assertTrue("open before it was asked", compose.onAllNodesWithTag(TIMELINE_TAG).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag(TIMELINE_TOGGLE_TAG).performClick()
        settle()
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("board", "inner-dark-timeline"))
        ScreenShots.assertSensible(compose, "inner-dark-timeline")
        assertTrue("no timeline beside the week", compose.onAllNodesWithTag(TIMELINE_TAG).fetchSemanticsNodes().isNotEmpty())
        assertTrue("no block for the timed task: $spoken", compose.onAllNodesWithContentDescription(spoken).fetchSemanticsNodes().isNotEmpty())
        // Hide timeline shuts it again.
        compose.onNodeWithTag(TIMELINE_TOGGLE_TAG).performClick()
        settle()
        assertTrue("still open", compose.onAllNodesWithTag(TIMELINE_TAG).fetchSemanticsNodes().isEmpty())
    }

    /** Option B slice 5: folded, the Cards · Timeline tab shows the day in view as a timeline, and back. */
    @Test @Config(qualifiers = ScreenShots.COVER)
    fun foldedTheTimelineTabShowsTheDayInView() {
        val (core, spoken) = timedCore()
        show(core)
        assertTrue("no tabs on the cover screen", compose.onAllNodesWithTag(TIMELINE_TAB_TAG).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText(os.meka.core.domain.BoardTimelineRules.TAB_TIMELINE).performClick()
        settle()
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("board", "cover-dark-timeline"))
        ScreenShots.assertSensible(compose, "cover-dark-timeline")
        assertTrue("no block for the timed task: $spoken", compose.onAllNodesWithContentDescription(spoken).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText(os.meka.core.domain.BoardTimelineRules.TAB_CARDS).performClick()
        settle()
        assertTrue("the timeline stayed", compose.onAllNodesWithTag(TIMELINE_TAG).fetchSemanticsNodes().isEmpty())
        assertTrue("the cards didn't come back", compose.onAllNodesWithText("Send the quote").fetchSemanticsNodes().isNotEmpty())
    }

    /**
     * The Week board made to match Option B (Meka 2026-10-24 14:39, "doesn't look like the mock-up prototype"), slice 1,
     * unfolded on a Wednesday: "October 2026" with W43 in the header; Monday and Tuesday are full columns with their
     * dates (never folded into empty pills); a card with no time says "Any time" on its chip; "+ add" sits at the foot
     * of the day, under its cards; the budget pill says "planned" against what is free.
     */
    @Test @Config(qualifiers = ScreenShots.INNER)
    fun theColumnsMatchTheMockup() {
        ThemeChoice.save(ApplicationProvider.getApplicationContext(), ThemeChoice.DARK)
        val core = core()
        runBlocking {
            core.tick()
            core.addOnBoardSized("Fix the gate latch", core.weekBoard.value.todayEpochDay, 60)
        }
        show(core)
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("board", "inner-dark-option-b"))
        ScreenShots.assertSensible(compose, "inner-dark-option-b")
        ScreenShots.assertShows(compose, "match", "October 2026")
        ScreenShots.assertShows(compose, "match", "W43")
        ScreenShots.assertShows(compose, "match", "Monday 19 October")
        ScreenShots.assertShows(compose, "match", "Tuesday 20 October")
        ScreenShots.assertShows(compose, "match", os.meka.core.domain.BoardMatchRules.ANY_TIME)
        if (compose.onAllNodesWithContentDescription("Tap to open", substring = true).fetchSemanticsNodes().isNotEmpty()) {
            fail("a past day is still folded narrow")
        }
        if (compose.onAllNodesWithText("Nothing done", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) {
            fail("a past day still shows the folded pill's \"Nothing done\"")
        }
        val card = cardBounds("Fix the gate latch")
        val adds = compose.onAllNodesWithTag(ADD_TAG, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        if (adds.isEmpty()) fail("no + add at the foot of the days")
        val todays = adds.firstOrNull { it.left < card.right && it.right > card.left }
            ?: throw AssertionError("no + add in today's column ($card vs $adds)")
        assertTrue("+ add sits under today's card ($todays vs $card)", todays.top >= card.bottom)
        if (compose.onAllNodesWithTag(BUDGET_TAG).fetchSemanticsNodes().isEmpty()) fail("no budget pill")
    }

    /**
     * Option B match, slice 2: unfolded, the lists at the foot are the mockup's card columns: a hairline, then Someday,
     * Side hustle, House and No day yet left to right with their names in serif (no " · 3" in the name), and a Someday
     * card with no time or length is its title and foot only (no "Any time" chip below the hairline).
     */
    @Test @Config(qualifiers = ScreenShots.INNER)
    fun theListsAreTheMockupsCardColumns() {
        ThemeChoice.save(ApplicationProvider.getApplicationContext(), ThemeChoice.DARK)
        val core = core()
        runBlocking {
            core.tick()
            val id = core.addOnBoardSized("Learn to juggle", core.weekBoard.value.todayEpochDay, null)!!
            core.somedayOnBoard(id)
        }
        show(core)
        compose.onNodeWithTag("screen").captureRoboImage(ScreenShots.path("board", "inner-dark-lists"))
        ScreenShots.assertSensible(compose, "inner-dark-lists")
        val titles = compose.onAllNodesWithTag(LIST_TITLE_TAG, useUnmergedTree = true).fetchSemanticsNodes()
        val names = titles.sortedBy { it.boundsInRoot.left }.map { n ->
            n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()
        }
        assertEquals(listOf("Someday", "Side hustle", "House", "No day yet"), names)
        val rule = compose.onAllNodesWithTag(LISTS_RULE_TAG, useUnmergedTree = true).fetchSemanticsNodes().singleOrNull()?.boundsInRoot
            ?: throw AssertionError("no hairline above the lists")
        val card = cardBounds("Learn to juggle")
        assertTrue("the Someday card sits under the hairline ($card vs $rule)", card.top >= rule.bottom)
        val anyTime = compose.onAllNodesWithText(os.meka.core.domain.BoardMatchRules.ANY_TIME, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("a list card wears no \"Any time\" chip", anyTime.none { it.boundsInRoot.top >= rule.bottom })
        if (compose.onAllNodesWithContentDescription("Someday, 1 task").fetchSemanticsNodes().isEmpty()) fail("Someday's heading doesn't say its count")
    }

    /** Option B match: on a Saturday the unfolded board opens on next week; ‹ goes back to this week's Saturday. */
    @Test @Config(qualifiers = ScreenShots.INNER)
    fun onASaturdayTheBoardOpensOnNextWeek() {
        ThemeChoice.save(ApplicationProvider.getApplicationContext(), ThemeChoice.DARK)
        val core = core(now + 3 * 86_400_000L) // Sat 24 Oct 2026, 17:15
        runBlocking {
            core.tick()
            core.addOnBoardSized("Batch cook for the week", core.weekBoard.value.todayEpochDay, 60)
        }
        show(core)
        ScreenShots.assertShows(compose, "saturday", "week 44")
        assertTrue(
            "this week's Saturday card shows on next week",
            compose.onAllNodesWithText("Batch cook for the week", useUnmergedTree = true).fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithContentDescription("Previous week").performClick()
        settle()
        cardBounds("Batch cook for the week")
    }

    /** A core with "Send the quote" planned today at 19:00 for 45 minutes, and its block's spoken label from the core. */
    private fun timedCore(): Pair<MekaCore, String> {
        ThemeChoice.save(ApplicationProvider.getApplicationContext(), ThemeChoice.DARK)
        val core = core()
        runBlocking {
            core.tick()
            val today = core.weekBoard.value.todayEpochDay
            val id = core.addOnBoardSized("Send the quote", today, 45) ?: throw AssertionError("not added")
            core.setWhen(id, today, 19 * 60)
        }
        val board = core.weekBoard.value
        val tl = core.boardTimeline(board, board.todayEpochDay) ?: throw AssertionError("no timeline for today")
        val block = tl.blocks.firstOrNull { it.title == "Send the quote" } ?: throw AssertionError("no block: ${tl.blocks}")
        assertEquals("19:00–19:45", block.label)
        return core to block.spoken
    }

    private fun show(core: MekaCore) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MekaTheme {
                Box(Modifier.fillMaxSize().background(Meka.colors.background).testTag("screen")) {
                    WeekBoardView(core, EventUndo(), onEvent = {}, Modifier.fillMaxSize())
                }
            }
        }
        settle()
    }

    private fun cardBounds(title: String): androidx.compose.ui.geometry.Rect {
        val cards = compose.onAllNodesWithTag(PLANNER_CARD_TAG, useUnmergedTree = true).fetchSemanticsNodes()
        fun holds(n: androidx.compose.ui.semantics.SemanticsNode): Boolean =
            n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == title || n.children.any { holds(it) }
        return cards.firstOrNull { holds(it) }?.boundsInRoot ?: throw AssertionError("no card holds \"$title\"")
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
