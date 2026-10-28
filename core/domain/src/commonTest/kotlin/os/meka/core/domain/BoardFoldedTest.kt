package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Week board on the folded phone, as the approved mockup (`docs/design/week-board/folded.dc.html`). */
class BoardFoldedTest {
    private val mon19 = CivilDate.toEpochDay(2026, 10, 19)
    private val sun25 = mon19 + 6

    private fun task(id: String) = Task(id, id, null, Lifecycle.ACTIVE, null, null, null, 0, null, null, 0, null, false)

    private fun card(id: String, done: Boolean = false) = BoardCard(task(id), "Title $id", null, null, done, false)

    private fun day(
        epoch: Long, weekday: String, number: String, cards: List<BoardCard> = emptyList(), today: Boolean = false,
        past: Boolean = false, capacity: BoardCapacity? = null, countLine: String = "",
    ) = BoardDay(
        epochDay = epoch, weekday = weekday, number = number, isToday = today, isPast = past, place = null, placeLabel = null,
        chips = emptyList(), moreChips = 0, cards = cards, countLine = countLine, accessibilityLabel = "$weekday $number",
        capacity = capacity,
    )

    @Test
    fun theHeaderIsTheMonthAndTheWeekInWords() {
        val h = BoardFoldedRules.head(mon19)
        assertEquals("October", h.month)
        assertEquals("Week 43", h.week)
        assertEquals("October, week 43", h.spoken)
        // A week across two months is named by its Thursday, as the unfolded header.
        assertEquals("October", BoardFoldedRules.head(CivilDate.toEpochDay(2026, 9, 28)).month)
        // No monospace "W43" and no year on the cover screen.
        assertFalse(h.week.startsWith("W4"))
        assertFalse(h.month.contains("2026"))
    }

    @Test
    fun theDaysLineSaysTodayAndThePlanAgainstFreeTime() {
        val today = day(sun25, "Sun", "25", listOf(card("a"), card("b")), today = true, capacity = BoardCapacity(210, 570, 1))
        val line = BoardFoldedRules.dayLine(today)
        assertEquals("Sun 25 October · today", line.title)
        assertEquals("Sun", line.short)
        assertEquals("3h 30 of 9h 30", line.plan)
        assertEquals(210f / 570f, line.fill)
        assertFalse(line.over)
        assertEquals("Sun 25 October, today, 3h 30 of 9h 30", line.spoken)
        assertEquals("+ Add to Sun", BoardFoldedRules.addLabel(today))
    }

    @Test
    fun anEmptyDayAnOverDayAndAPastDay() {
        assertEquals("Nothing yet", BoardFoldedRules.dayLine(day(mon19 + 4, "Fri", "23", capacity = BoardCapacity(0, 300, 0))).plan)
        assertEquals(0f, BoardFoldedRules.dayLine(day(mon19 + 4, "Fri", "23", capacity = BoardCapacity(0, 300, 0))).fill)
        val over = BoardFoldedRules.dayLine(day(mon19 + 3, "Thu", "22", capacity = BoardCapacity(90, 60, 0)))
        assertEquals("1h 30 · 30m over", over.plan)
        assertTrue(over.over)
        assertEquals(1f, over.fill)
        assertEquals("2h planned", BoardFoldedRules.plan(BoardCapacity(120, 0, 0)))
        val past = BoardFoldedRules.dayLine(day(mon19, "Mon", "19", past = true, countLine = "3 done", capacity = BoardCapacity(60, 0, 0)))
        assertEquals("Mon 19 October", past.title)
        assertEquals("3 done", past.plan)
        assertFalse(past.over)
        assertEquals("", BoardFoldedRules.dayLine(day(mon19 + 1, "Tue", "20")).plan)
    }

    @Test
    fun aPillShowsOneShortBarAnOpenTaskUpToThree() {
        assertEquals(0, BoardFoldedRules.dots(day(mon19, "Mon", "19")))
        assertEquals(1, BoardFoldedRules.dots(day(mon19, "Mon", "19", listOf(card("a"), card("b", done = true)))))
        assertEquals(3, BoardFoldedRules.dots(day(mon19, "Mon", "19", (1..5).map { card("c$it") })))
        assertEquals(2, BoardFoldRules.pill(day(mon19, "Mon", "19", listOf(card("a"), card("b")))).dots)
    }

    @Test
    fun theMenuHoldsWhatLeftTheHeader() {
        val week = BoardWeek(mon19, "This week", "19–25 Oct", (0..6).map { day(mon19 + it, "D", "${19 + it}") }, lane = listOf(card("n")))
        val board = WeekBoard(sun25, listOf(week), someday = listOf(card("s1"), card("s2")))
        val items = BoardFoldedRules.menu(board, week, 0, timelineOpen = false, canAddEvent = true, canPlan = true)
        assertEquals(
            listOf(
                FoldedMenuKind.AGENDA, FoldedMenuKind.TIMELINE, FoldedMenuKind.NEW_EVENT, FoldedMenuKind.PLAN_WEEK,
                FoldedMenuKind.REVIEW, FoldedMenuKind.GOALS, FoldedMenuKind.NO_DAY, FoldedMenuKind.SOMEDAY,
            ),
            items.map { it.kind },
        )
        assertEquals("Timeline", items[1].label)
        assertEquals("Obi helps", items[3].detail)
        assertEquals("up to 3", items.first { it.kind == FoldedMenuKind.GOALS }.detail)
        assertEquals("1", items.first { it.kind == FoldedMenuKind.NO_DAY }.detail)
        assertEquals("2", items.first { it.kind == FoldedMenuKind.SOMEDAY }.detail)
        // With the timeline open it offers the cards back; no calendar to add to, no planner, next week: no Review.
        val other = BoardFoldedRules.menu(board, week, 1, timelineOpen = true, canAddEvent = false, canPlan = false)
        assertEquals("Cards", other.first { it.kind == FoldedMenuKind.TIMELINE }.label)
        assertTrue(other.none { it.kind in setOf(FoldedMenuKind.NEW_EVENT, FoldedMenuKind.PLAN_WEEK, FoldedMenuKind.REVIEW) })
        val empty = WeekBoard(sun25, listOf(week.copy(lane = emptyList())))
        assertEquals("Nothing in Someday yet · a task's menu puts one there", BoardFoldedRules.emptyList("Someday"))
        assertEquals("empty", BoardFoldedRules.menu(empty, empty.weeks[0], 0, false, false, false).first { it.kind == FoldedMenuKind.SOMEDAY }.detail)
    }
}
