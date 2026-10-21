package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Week board, slice 6: Block time — free starts in a card's day and what his tap does. */
class BoardBlockTest {
    private val dayMs = CivilDate.DAY_MS
    private val cal = LocalCalendar.UTC
    // Wed 21 Oct 2026, 10:00 UTC.
    private val today = CivilDate.toEpochDay(2026, 10, 21)
    private val now = today * dayMs + 10 * 3_600_000L
    private val sat = today + 3

    private fun task(id: String, title: String = id, estimate: Int? = null, scheduled: Long? = null, lifecycle: Lifecycle = Lifecycle.ACTIVE, completed: Long? = null) =
        Task(id, title, null, lifecycle, null, scheduled, estimate, 0, null, null, 0, completed, false)

    private fun at(day: Long, hour: Int, minute: Int = 0) = day * dayMs + hour * 3_600_000L + minute * 60_000L

    private fun event(id: String, start: Long, end: Long) = CalendarEvent(id, id, start, end, false, null, "google", "a", "Personal")

    private val work = WorkHours(WorkSchedule(setOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60), commute = CommuteSetting(60, 60))

    @Test
    fun startsComeFromTheFreeTimeTheDayShows() {
        // Today at the office: free 18:00–21:00 after work and the commute; an hour apart for a 30m block.
        assertEquals(
            listOf("18:00–18:30", "19:00–19:30", "20:00–20:30"),
            BoardBlockRules.slots(today, today, now, emptyList(), cal, work, 30).map { it.label },
        )
        // Saturday with lunch 13:00–14:30: each free stretch's first start comes first, then later ones.
        val lunch = listOf(event("lunch", at(sat, 13), at(sat, 14, 30)))
        assertEquals(
            listOf("09:00–10:00", "10:00–11:00", "11:00–12:00", "14:30–15:30"),
            BoardBlockRules.slots(sat, today, now, lunch, cal, work, 60).map { it.label },
        )
        // From now on today, on the quarter hour.
        val late = now + 7 * 60_000L
        assertEquals("10:15–10:45", BoardBlockRules.slots(today, today, late, emptyList(), cal, null, 30).first().label)
        assertEquals("10:15 to 10:45", BoardBlockRules.slots(today, today, late, emptyList(), cal, null, 30).first().spoken)
        // A 2h block doesn't fit a 1h gap.
        val busy = listOf(event("all", at(sat, 9), at(sat, 20)))
        assertTrue(BoardBlockRules.slots(sat, today, now, busy, cal, work, 120).isEmpty())
    }

    @Test
    fun theOfferUsesTheEstimateThenTheGuessThenHalfAnHour() {
        val tasks = listOf(
            task("mot", "Book MOT", estimate = 60, scheduled = at(sat, 12)),
            task("gate", "Fix the gate", scheduled = at(sat, 12)),
            task("old", "Fix the fence", estimate = 120, lifecycle = Lifecycle.DONE, completed = at(today - 30, 9)),
        )
        val board = WeekBoardRules.build(tasks, emptyList(), now, cal, work)
        val mot = BoardBlockRules.offer(board, "mot", now, emptyList(), cal, work, "google", hasBlock = false)!!
        assertEquals(60, mot.lengthMin)
        assertEquals("Block time · 1h", mot.heading)
        assertEquals(sat, mot.epochDay)
        assertEquals("09:00–10:00", mot.slots.first().label)
        assertTrue(mot.canBlock)
        assertEquals("Adds it to Google as busy time when you tap a start", mot.line)
        // "Fix the gate" shares "fix" with the 2h fence: Navilife's guess is the length.
        assertEquals(120, BoardBlockRules.offer(board, "gate", now, emptyList(), cal, work, "google", false)!!.lengthMin)
        val plain = WeekBoardRules.build(listOf(task("x", "Read", scheduled = at(sat, 12))), emptyList(), now, cal, work)
        assertEquals(30, BoardBlockRules.offer(plain, "x", now, emptyList(), cal, work, "google", false)!!.lengthMin)
    }

    @Test
    fun noCalendarNoRoomAndAnExistingBlockSaySo() {
        val board = WeekBoardRules.build(listOf(task("mot", "Book MOT", scheduled = at(sat, 12))), emptyList(), now, cal, work)
        val none = BoardBlockRules.offer(board, "mot", now, emptyList(), cal, work, null, false)!!
        assertFalse(none.canBlock)
        assertEquals(BoardBlockRules.NO_ACCOUNT, none.line)
        val full = BoardBlockRules.offer(board, "mot", now, listOf(event("all", at(sat, 8), at(sat, 22))), cal, work, "google", false)!!
        assertFalse(full.canBlock)
        assertEquals(BoardBlockRules.NO_ROOM, full.line)
        val moving = BoardBlockRules.offer(board, "mot", now, emptyList(), cal, work, "microsoft", hasBlock = true)!!
        assertEquals("Moves its block in Outlook", moving.line)
    }

    @Test
    fun doneOrMissingCardsOfferNothing() {
        val tasks = listOf(task("d", "Done one", lifecycle = Lifecycle.DONE, completed = now - 3_600_000L))
        val board = WeekBoardRules.build(tasks, emptyList(), now, cal, work)
        assertNull(BoardBlockRules.offer(board, "d", now, emptyList(), cal, work, "google", false))
        assertNull(BoardBlockRules.offer(board, "nope", now, emptyList(), cal, work, "google", false))
    }

    @Test
    fun theBlockCarriesTheTasksTitleAndTheLineSaysWhen() {
        val t = task("mot", "  Book MOT ")
        val d = BoardBlockRules.draft(t, at(sat, 9), 30)
        assertEquals("Book MOT", d.title)
        assertEquals(at(sat, 9, 30), d.endAtMs)
        assertEquals(PlanCalendarRules.NOTE, d.notes)
        val slot = BoardBlockRules.slots(sat, today, now, emptyList(), cal, work, 30).first()
        assertEquals("Blocked “Book MOT” · Sat 24 Oct 09:00–09:30 in Google", BoardBlockRules.line("Book MOT", slot, sat, today, "google", false))
        assertEquals("Moved “Book MOT”'s block to Tomorrow 09:00–09:30 in Google", BoardBlockRules.line("Book MOT", slot, today + 1, today, "google", true))
    }
}
