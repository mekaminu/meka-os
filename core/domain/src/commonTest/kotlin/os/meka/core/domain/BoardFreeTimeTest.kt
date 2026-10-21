package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Week board, slice 9 (item (3)): a day's free times on the Board and where a card dropped on one is blocked. */
class BoardFreeTimeTest {
    private val dayMs = CivilDate.DAY_MS
    private val cal = LocalCalendar.UTC
    // Wed 21 Oct 2026, 10:00 UTC.
    private val today = CivilDate.toEpochDay(2026, 10, 21)
    private val now = today * dayMs + 10 * 3_600_000L
    private val sat = today + 3

    private fun at(day: Long, hour: Int, minute: Int = 0) = day * dayMs + hour * 3_600_000L + minute * 60_000L
    private fun event(id: String, start: Long, end: Long) = CalendarEvent(id, id, start, end, false, null, "google", "a", "Personal")
    private val work = WorkHours(WorkSchedule(setOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60), commute = CommuteSetting(60, 60))

    @Test
    fun freeTimesAreTheDaysFreeStretchesOnTheQuarterHour() {
        // Saturday with lunch 13:10–14:20: 09:00–13:00 (rounded down) and 14:30–21:00 (rounded up).
        val lunch = listOf(event("lunch", at(sat, 13, 10), at(sat, 14, 20)))
        val free = BoardBlockRules.freeTimes(sat, today, now, lunch, cal, work)
        assertEquals(listOf("09:00–13:00", "14:30–21:00"), free.map { it.label })
        assertEquals("4h free", free[0].lengthLabel)
        assertEquals(1f, free[1].weight)
        assertEquals("Free from 09:00 to 13:00, drop a task here to block it", free[0].spoken)
        // Today at the office: only the evening after the commute.
        assertEquals(listOf("18:00–21:00"), BoardBlockRules.freeTimes(today, today, now, emptyList(), cal, work).map { it.label })
        // Nothing before today; slivers under 15 minutes are left out.
        assertTrue(BoardBlockRules.freeTimes(today - 1, today, now, emptyList(), cal, work).isEmpty())
        val tight = listOf(event("a", at(sat, 9), at(sat, 12, 50)), event("b", at(sat, 13), at(sat, 21)))
        assertTrue(BoardBlockRules.freeTimes(sat, today, now, tight, cal, work).isEmpty())
    }

    @Test
    fun aDropLandsWhereHeLetGoInsideTheStretch() {
        val free = BoardBlockRules.freeTimes(sat, today, now, emptyList(), cal, work).single() // 09:00–21:00
        assertEquals("09:00–09:30", BoardBlockRules.dropSlot(free, 30, 0f, sat, cal)!!.label)
        // Half way down a 12h stretch for an hour's card: 11h of room, 5h30 in → 14:30.
        assertEquals("14:30–15:30", BoardBlockRules.dropSlot(free, 60, 0.5f, sat, cal)!!.label)
        // At the very bottom it still ends inside the stretch.
        val last = BoardBlockRules.dropSlot(free, 60, 1f, sat, cal)!!
        assertEquals("20:00–21:00", last.label)
        assertEquals(at(sat, 20), last.startMs)
        assertEquals(20 * 60, last.minute)
    }

    @Test
    fun aStretchTooShortSaysSo() {
        val busy = listOf(event("a", at(sat, 9, 45), at(sat, 21)))
        val free = BoardBlockRules.freeTimes(sat, today, now, busy, cal, work).single()
        assertEquals("09:00–09:45", free.label)
        assertNull(BoardBlockRules.dropSlot(free, 60, 0f, sat, cal))
        assertEquals("“Book MOT” needs 1h · only 45m free there", BoardBlockRules.tooShort("Book MOT", 60, free))
    }

    @Test
    fun theBoardCarriesEachDaysFreeTimes() {
        val board = WeekBoardRules.build(emptyList(), listOf(event("lunch", at(sat, 13), at(sat, 14))), now, cal, work)
        assertEquals(listOf("09:00–13:00", "14:00–21:00"), board.day(sat)!!.freeTimes.map { it.label })
        assertTrue(board.day(today - 1)?.freeTimes?.isEmpty() ?: true)
    }
}
