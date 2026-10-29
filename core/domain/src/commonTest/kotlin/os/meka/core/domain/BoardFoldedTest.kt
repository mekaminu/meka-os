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

    private fun event(title: String, calendar: String?, account: String? = "alex@example.com", provider: String = "google", startH: Int = 18, mins: Int = 90) =
        CalendarEvent(
            id = title, title = title, startAtMs = startH * 3_600_000L, endAtMs = startH * 3_600_000L + mins * 60_000L,
            allDay = false, location = null, provider = provider, account = account, calendarName = calendar,
        )

    private fun chip(e: CalendarEvent, time: String = "18:00") = BoardChip(e.id, time, e.title, 1, e)

    @Test
    fun aPillCountsEventsTooSoABusyDayOfEventsShowsDots() {
        val e = event("Training", "Football")
        val busy = day(mon19, "Mon", "19").copy(chips = listOf(chip(e), chip(e.copy(id = "b"))))
        assertEquals(2, BoardFoldedRules.dots(busy))
        assertEquals(3, BoardFoldedRules.dots(busy.copy(cards = listOf(card("a"), card("b")))))
    }

    @Test
    fun aFoldedTaskCardHasItsTimeOrLengthInWordsNeverMono() {
        val timed = BoardCard(task("t"), "Review plan", "18:00", null, false, false)
        assertEquals("18:00", BoardFoldedRules.card(timed).right)
        val sized = BoardCard(task("s").copy(estimateMinutes = 90), "Do feedback", null, null, false, false)
        assertEquals("1h 30", BoardFoldedRules.card(sized).right)
        assertFalse(BoardFoldedRules.card(sized).right.contains(":"))
        val plain = card("p")
        assertEquals("", BoardFoldedRules.card(plain).right)
        assertEquals("", BoardFoldedRules.card(plain).meta)
        assertEquals("Title p", BoardFoldedRules.card(plain).spoken)
        assertEquals("Do feedback, 1h 30", BoardFoldedRules.card(sized).spoken)
    }

    @Test
    fun aFoldedEventCardNamesItsCalendarAndLengthNeverAnAddress() {
        val e = event("Training", "alex@example.com")
        val card = BoardMatchRules.eventCards(day(mon19, "Mon", "19").copy(chips = listOf(chip(e)))).single()
        assertEquals("Personal", card.calendar)
        val f = BoardFoldedRules.event(card)
        assertEquals("Personal · 1h 30", f.meta)
        assertEquals("18:00", f.right)
        assertFalse(f.meta.contains("@"))
        assertFalse(f.spoken.contains("@"))
        // The phone's own calendar app (not "google") names the main calendar after the address too.
        val phone = event("Training", "alex@example.com", provider = "device")
        assertEquals("Personal", CalendarRules.defaultName(phone))
        // Someone else's shared calendar is "Shared", never their address; a real name stays.
        assertEquals("Shared", CalendarRules.defaultName(event("Swim", "sam@example.com")))
        assertEquals("Football", CalendarRules.defaultName(event("Swim", "Football")))
        assertTrue(CalendarRules.looksLikeAddress(" alex.b@mail.example.co.uk "))
        assertFalse(CalendarRules.looksLikeAddress("Family @ home"))
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
