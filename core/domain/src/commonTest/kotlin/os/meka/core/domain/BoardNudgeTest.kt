package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Week board redesign, Option B, slice 4: Obi's one-line nudge in the header. */
class BoardNudgeTest {
    private val cal = LocalCalendar.UTC
    /** Monday 26 October 2026. */
    private val mon = 20752L

    private fun task(id: String, estimate: Int? = null, due: Long? = null, repeat: Boolean = false, done: Boolean = false) = Task(
        id, "Title $id", null, if (done) Lifecycle.DONE else Lifecycle.ACTIVE, due?.let { it * 86_400_000L + 3_600_000L }, null,
        estimate, 0, null, null, 0, if (done) 1L else null, false, recurrenceRule = if (repeat) "FREQ=DAILY" else null,
    )

    private fun card(t: Task) = BoardCard(t, t.title, null, null, t.lifecycle == Lifecycle.DONE, false)

    private fun day(d: Long, today: Long, planned: Int?, free: Int?, cards: List<Task> = emptyList(), pressure: DayPressure? = null) = BoardDay(
        d, LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(d) - 1], "${d - mon + 26}", d == today, d < today, null, null,
        emptyList(), 0, cards.map(::card), "", "",
        capacity = if (planned != null && free != null && d >= today) BoardCapacity(planned, free, 0) else null, pressure = pressure,
    )

    private fun board(today: Long, days: List<BoardDay>, lane: List<Task> = emptyList()): Pair<WeekBoard, BoardWeek> {
        val week = BoardWeek(mon, "This week", "26 Oct – 1 Nov", days, lane = lane.map(::card))
        val next = BoardWeek(mon + 7, "Next week", "2–8 Nov", (0..6).map { day(mon + 7 + it, today, 0, 420) })
        val b = WeekBoard(today, listOf(week, next))
        return b to week
    }

    private fun quiet(today: Long) = (0..6).map { day(mon + it, today, 0, 420) }

    @Test
    fun anOverDaySaysByHowMuchAndOffersOneCardThatFitsElsewhere() {
        val gym = task("gym", estimate = 60)
        val quote = task("quote", estimate = 45)
        val review = task("review", estimate = 150)
        val days = quiet(mon).toMutableList()
        // Monday (today): 4h 15 planned against 3h 15 free: an hour over. The smallest card that covers the hour is gym.
        days[0] = day(mon, mon, 255, 195, listOf(review, gym, quote))
        days[1] = day(mon + 1, mon, 200, 240)
        days[4] = day(mon + 4, mon, 0, 300)
        val (b, w) = board(mon, days)
        val n = BoardNudgeRules.of(b, w, cal)!!
        assertEquals(BoardNudgeKind.OVER, n.kind)
        assertTrue(n.warns)
        // Wed–Sun all have 7h free; the most room wins, the earlier on a tie (Wed).
        assertEquals("Today is 1h over · move “Title gym” to Wed?", n.line)
        assertEquals("gym", n.fixTaskId)
        assertEquals(mon + 2, n.fixDay)
        assertEquals("Obi says: Today is 1h over, move “Title gym” to Wed?", n.spoken)
    }

    @Test
    fun aLaterOverDayUsesItsShortNameAndAsksPlainlyWhenNothingFits() {
        val days = (0..6).map { day(mon + it, mon, 0, 0) }.toMutableList()
        // Wednesday has 2h 30 planned and 2h free; nowhere else has room, and one card repeats, one is due that day.
        days[2] = day(mon + 2, mon, 150, 120, listOf(task("walk", 30, repeat = true), task("form", 120, due = mon + 2)))
        val (b, w) = board(mon, days)
        val n = BoardNudgeRules.of(b, w, cal)!!
        assertEquals("Wed is 30m over · move something?", n.line)
        assertNull(n.fix)
        assertEquals(-1L, n.fixDay)
    }

    @Test
    fun aDayWithNoFreeTimeSaysSo() {
        val days = quiet(mon).toMutableList()
        days[1] = day(mon + 1, mon, 30, 0, listOf(task("call", 30)))
        val (b, w) = board(mon, days)
        val n = BoardNudgeRules.of(b, w, cal)!!
        // Today has the most room left (the earliest of the 7h days).
        assertEquals("Tomorrow has no free time · move “Title call” to today?", n.line)
    }

    @Test
    fun aFixNeverGoesPastItsDueDayOrOntoAStackingDay() {
        val stacking = DayPressure(mon + 2, "Wednesday", false, emptyList(), "", "", "", emptyList())
        val days = quiet(mon).toMutableList()
        days[0] = day(mon, mon, 90, 60, listOf(task("bins", 30, due = mon + 3)))
        days[2] = day(mon + 2, mon, 0, 600, pressure = stacking)
        days[3] = day(mon + 3, mon, 0, 420)
        days[1] = day(mon + 1, mon, 0, 400)
        val (b, w) = board(mon, days)
        // Wed is stacking up (out), so Thu (7h) beats Tue (6h 40); never Fri, past its due day.
        assertEquals(mon + 3, BoardNudgeRules.of(b, w, cal)!!.fixDay)
    }

    @Test
    fun thenStackingUpThenTasksToPlaceThenItFits() {
        val fix = PressureFix("gym", "Gym", mon + 5, "Move “Gym” to Sat", earlier = false)
        val p = DayPressure(mon + 3, "Thursday", false, emptyList(), "", "", "", listOf(fix))
        val days = quiet(mon).toMutableList()
        days[3] = day(mon + 3, mon, 60, 300, listOf(task("gym", 60)), pressure = p)
        val (b, w) = board(mon, days)
        val stacking = BoardNudgeRules.of(b, w, cal)!!
        assertEquals(BoardNudgeKind.STACKING, stacking.kind)
        assertEquals("Thu is stacking up · move “Gym” to Sat?", stacking.line)
        assertEquals(mon + 5, stacking.fixDay)

        val planned = quiet(mon).toMutableList().also { it[1] = day(mon + 1, mon, 30, 420, listOf(task("a", 30))) }
        val (b2, w2) = board(mon, planned, lane = listOf(task("x"), task("y"), task("z")))
        val toPlace = BoardNudgeRules.of(b2, w2, cal)!!
        assertEquals("3 tasks still need a day", toPlace.line)
        assertFalse(toPlace.warns)
        assertEquals("1 task still needs a day", BoardNudgeRules.of(board(mon, planned, lane = listOf(task("x"))).first, w2.copy(lane = listOf(card(task("x")))), cal)!!.line)

        val (b3, w3) = board(mon, planned)
        assertEquals("Everything fits this week", BoardNudgeRules.of(b3, w3, cal)!!.line)
        assertEquals(BoardNudgeKind.FITS, BoardNudgeRules.of(b3, w3, cal)!!.kind)
        val (b4, w4) = board(mon, quiet(mon))
        assertEquals("A clear week so far", BoardNudgeRules.of(b4, w4, cal)!!.line)
    }

    @Test
    fun nextWeekAndAPastWeek() {
        val (b, _) = board(mon, quiet(mon))
        val next = b.weeks[1].copy(days = b.weeks[1].days.toMutableList().also { it[0] = day(mon + 7, mon, 30, 420, listOf(task("a", 30))) })
        assertEquals("Next week fits so far", BoardNudgeRules.of(b.copy(weeks = listOf(b.weeks[0], next)), next, cal)!!.line)
        // Seen from the Monday after, this week is gone: no line.
        val later = mon + 7
        val (gone, w) = board(later, (0..6).map { day(mon + it, later, null, null) })
        assertNull(BoardNudgeRules.of(gone, w, cal))
        // A day name a week or more ahead carries its date.
        assertEquals("Mon 2 Nov", BoardNudgeRules.dayName(mon + 7, mon))
    }

    @Test
    fun everyLineIsCleanUnderTheNonsenseCheck() {
        val lines = listOf(
            "Today is 1h over · move “Title gym” to Wed?", "Wed is 30m over · move something?", "3 tasks still need a day",
            "Everything fits this week", "Next week fits so far", "A clear week so far",
        )
        lines.forEach { assertTrue(ScreenSenseRules.problemsIn(it).isEmpty(), it) }
    }
}
