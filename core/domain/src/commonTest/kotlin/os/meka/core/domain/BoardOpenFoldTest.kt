package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Week board's alignment pass on the open Fold (Meka 2026-10-29 02:12): rows sized to their days, one pill. */
class BoardOpenFoldTest {
    private val mon26 = CivilDate.toEpochDay(2026, 10, 26)

    private fun task(id: String) = Task(id, id, null, Lifecycle.ACTIVE, null, null, null, 0, null, null, 0, null, false)

    private fun card(id: String, done: Boolean = false) = BoardCard(task(id), "Title $id", null, null, done, false)

    private fun day(past: Boolean, cards: List<BoardCard> = emptyList()) = BoardDay(
        epochDay = mon26, weekday = "Mon", number = "26", isToday = false, isPast = past, place = null, placeLabel = null,
        chips = emptyList(), moreChips = 0, cards = cards, countLine = "", accessibilityLabel = "Monday 26 October",
    )

    @Test
    fun aRowIsAsTallAsItsFullestDayButNeverLessThanItsLeast() {
        // Thursday's cards (b): the row grows to the fullest column, so nothing is clipped.
        assertEquals(612f, BoardOpenFoldRules.rowHeight(listOf(140f, 612f, 300f), BoardOpenFoldRules.LEAST_WEEKDAYS_DP))
        // A quiet week (d): no tall empty row before the weekend.
        assertEquals(BoardOpenFoldRules.LEAST_WEEKDAYS_DP, BoardOpenFoldRules.rowHeight(listOf(90f, 120f), BoardOpenFoldRules.LEAST_WEEKDAYS_DP))
        // Columns not measured yet count as nothing.
        assertEquals(BoardOpenFoldRules.LEAST_WEEKEND_DP, BoardOpenFoldRules.rowHeight(emptyList(), BoardOpenFoldRules.LEAST_WEEKEND_DP))
        assertEquals(200f, BoardOpenFoldRules.rowHeight(listOf(Float.NaN, 200f, -1f), BoardOpenFoldRules.LEAST_WEEKEND_DP))
    }

    @Test
    fun everyDayHeadInARowIsAsTallAsItsTallest() {
        // (9): a day with a done bar and an amber note sets the height; the others start their first card there too.
        assertEquals(96f, BoardOpenFoldRules.headHeight(listOf(52f, 96f, 60f)))
        // Not measured yet: nothing held open.
        assertEquals(0f, BoardOpenFoldRules.headHeight(emptyList()))
        assertEquals(40f, BoardOpenFoldRules.headHeight(listOf(Float.NaN, 40f, -3f, Float.POSITIVE_INFINITY)))
        // The dashed "+ add" line sits inside its edge by half its width.
        assertEquals(0.5f, BoardOpenFoldRules.ADD_STROKE_INSET)
    }

    @Test
    fun everyUnfoldedColumnStacksItsPillTheSameWay() {
        // (c) one style: the weekend's wide columns stack "planned" over the figures like the weekdays.
        assertTrue(BoardOpenFoldRules.pillStacked(dense = true, compact = false))
        assertTrue(BoardOpenFoldRules.pillStacked(dense = true, compact = true))
        assertTrue(BoardOpenFoldRules.pillStacked(dense = false, compact = true))
        assertFalse(BoardOpenFoldRules.pillStacked(dense = false, compact = false))
    }

    @Test
    fun aPastDayWearsTheSamePillSayingWhatWasDone() {
        val p = BoardOpenFoldRules.pastPill(day(past = true, cards = listOf(card("a", done = true), card("b", done = true), card("c"))))!!
        assertEquals("done", p.label)
        assertEquals("2 of 3", p.value)
        assertEquals(2f / 3f, p.fill)
        assertFalse(p.over)
        assertEquals("2 of 3 done", p.spoken)
        val empty = BoardOpenFoldRules.pastPill(day(past = true))!!
        assertEquals("no tasks", empty.value)
        assertEquals(0f, empty.fill)
        assertEquals("Nothing planned", empty.spoken)
        assertNull(BoardOpenFoldRules.pastPill(day(past = false)))
    }

    @Test
    fun aDayWithNoCardsKeepsItsDoneBarsRoomSoThePillsLineUp() {
        // The walk-through's A6 (2026-10-30): Fri (no cards, no bar) had its pill higher than Wed/Thu's.
        assertTrue(BoardOpenFoldRules.barSlot(day(past = false)))
        assertTrue(BoardOpenFoldRules.barSlot(day(past = true)))
        assertFalse(BoardOpenFoldRules.barSlot(day(past = false, cards = listOf(card("a")))))
    }

    @Test
    fun theOpenHeadersMenuHoldsWhatWasFourStylesOfText() {
        val week = BoardWeek(mon26, "This week", "26 Oct–1 Nov", listOf(day(past = false)))
        // A Friday: Review the week is offered on this week.
        val friday = WeekBoard(mon26 + 4, listOf(week, week.copy(startEpochDay = mon26 + 7, title = "Next week")))
        val items = BoardOpenFoldRules.menu(friday, 0, timelineOpen = true, canAddEvent = true, canPlan = true)
        assertEquals(
            listOf(FoldedMenuKind.AGENDA, FoldedMenuKind.TIMELINE, FoldedMenuKind.NEW_EVENT, FoldedMenuKind.PLAN_WEEK, FoldedMenuKind.REVIEW),
            items.map { it.kind },
        )
        assertEquals(listOf("Agenda view", "Hide timeline", "New event", "Plan your week", "Review the week"), items.map { it.label })
        // Shut, the toggle offers the timeline back; the lists and goals are on the page, never in the menu.
        assertEquals("Timeline", BoardOpenFoldRules.menu(friday, 0, false, true, true)[1].label)
        assertTrue(items.none { it.kind in setOf(FoldedMenuKind.GOALS, FoldedMenuKind.NO_DAY, FoldedMenuKind.SOMEDAY) })
        // Next week, no calendar to add to, no planner, no room for the timeline: Agenda view alone.
        assertEquals(listOf(FoldedMenuKind.AGENDA), BoardOpenFoldRules.menu(friday, 1, null, false, false).map { it.kind })
        // Before Friday, no Review.
        val wednesday = WeekBoard(mon26 + 2, listOf(week))
        assertTrue(BoardOpenFoldRules.menu(wednesday, 0, true, true, true).none { it.kind == FoldedMenuKind.REVIEW })
    }

    @Test
    fun plusGoalMovesIntoTheMenuSoObisLineKeepsOneLine() {
        val week = BoardWeek(mon26, "This week", "26 Oct–1 Nov", listOf(day(past = false)))
        val board = WeekBoard(mon26 + 2, listOf(week))
        // No goals picked: "Goals · up to 3" last in ⋯, after what the menu already held.
        val none = BoardOpenFoldRules.menuWithGoals(board, week, 0, true, true, true)
        assertEquals(BoardOpenFoldRules.menu(board, 0, true, true, true) + FoldedMenuItem(FoldedMenuKind.GOALS, "Goals", "up to 3"), none)
        // Two picked: "2 this week"; three (no room for another) still opens them to turn one off.
        val g = { id: String -> BoardGoal(id, "Goal $id", 0, 1, finished = false, picked = true) }
        assertEquals("2 this week", BoardOpenFoldRules.menuWithGoals(board, week.copy(goals = listOf(g("a"), g("b"))), 0, true, true, true).last().detail)
        val full = week.copy(goals = listOf(g("a"), g("b"), g("c")))
        assertEquals(FoldedMenuKind.GOALS, BoardOpenFoldRules.menuWithGoals(board, full, 0, true, true, true).last().kind)
        // No week: the plain menu.
        assertEquals(BoardOpenFoldRules.menu(board, 0, null, false, false), BoardOpenFoldRules.menuWithGoals(board, null, 0, null, false, false))
        // "+ Goal" leaves the header's row only when it is in ⋯.
        assertFalse(BoardOpenFoldRules.addInHeader(goalsInMenu = true))
        assertTrue(BoardOpenFoldRules.addInHeader(goalsInMenu = false))
        // Obi's words: one line beside Today, two under the title.
        assertEquals(1, BoardMatchRules.nudgeLines(BoardNudgePlace.BESIDE_TODAY))
        assertEquals(2, BoardMatchRules.nudgeLines(BoardNudgePlace.UNDER_TITLE))
    }

    @Test
    fun theWeekendAndTheListsSitOnTheWeekdaysGrid() {
        // The Fold's 580 dp row with no gap (each column pads itself): five weekdays of 116, the weekend two of them.
        assertEquals(116f, BoardOpenFoldRules.gridColumn(580f))
        assertEquals(listOf(116f, 116f), BoardOpenFoldRules.gridWidths(2, 580f))
        // The four lists the same width as a weekday, never stretched across the row.
        assertEquals(List(4) { 116f }, BoardOpenFoldRules.gridWidths(4, 580f))
        // The Mac's row with an 8 pt gap: (600 - 4 × 8) / 5.
        assertTrue(kotlin.math.abs(BoardOpenFoldRules.gridColumn(600f, 8f) - 113.6f) < 0.001f)
        BoardOpenFoldRules.gridWidths(2, 600f, 8f).forEach { assertTrue(kotlin.math.abs(it - 113.6f) < 0.001f) }
        // More than five share the row; none and a squeezed row give nothing below zero.
        assertEquals(List(6) { 100f }, BoardOpenFoldRules.gridWidths(6, 600f))
        assertEquals(emptyList<Float>(), BoardOpenFoldRules.gridWidths(0, 600f))
        assertEquals(0f, BoardOpenFoldRules.gridColumn(10f, 8f))
    }

    @Test
    fun aScrolledWeekFadesUnderItsHeaderAndARestingOneDoesNot() {
        // At rest (and within the bounce slop) nothing fades, so the first card is whole.
        assertFalse(BoardOpenFoldRules.headFade(0f))
        assertFalse(BoardOpenFoldRules.headFade(1f))
        assertFalse(BoardOpenFoldRules.headFade(Float.NaN))
        // Scrolled, the top fades: a dashed sliver of "+ add" or a row under the strip's divider is never hard-cut.
        assertTrue(BoardOpenFoldRules.headFade(2f))
        assertTrue(BoardOpenFoldRules.headFade(400f))
        // Shown in full: gone at the edge, half way at 8 dp, whole from 16 dp down.
        assertEquals(16f, BoardOpenFoldRules.HEAD_FADE_DP)
        assertEquals(0f, BoardOpenFoldRules.headAlpha(0f))
        assertEquals(0.5f, BoardOpenFoldRules.headAlpha(8f))
        assertEquals(1f, BoardOpenFoldRules.headAlpha(16f))
        assertEquals(1f, BoardOpenFoldRules.headAlpha(200f))
        // Not shown, everything whole; half faded in, the edge is half shown.
        assertEquals(1f, BoardOpenFoldRules.headAlpha(0f, shown = 0f))
        assertEquals(0.5f, BoardOpenFoldRules.headAlpha(0f, shown = 0.5f))
        assertEquals(1f, BoardOpenFoldRules.headAlpha(-4f, shown = 0f))
        assertEquals(0f, BoardOpenFoldRules.headAlpha(-4f, shown = 2f))
    }
}
