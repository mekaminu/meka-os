package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fold review 2026-10-09 07:26, item 6: "TODAY" sat alone over the capture bar on a work day. The section's rows were
 * there (the work band, the now line, anytime tasks); it began at the foot of the closed Fold's screen. Its label now
 * says what it holds and rides with the first thing under it.
 */
class TodaySectionTest {
    private val hour = 3_600_000L
    private val min = 60_000L
    // Fri 9 Oct 2026 in London (BST, +1 h): local midnight = 8 Oct 23:00 UTC.
    private val start = 1_791_504_000_000L - hour
    private val day = DayWindow(startMs = start, endMs = start + 24 * hour, utcOffsetMs = hour)
    private val calendar = LocalCalendar.fixedOffset(hour)
    private fun at(h: Int, m: Int = 0) = start + h * hour + m * min
    private val work = listOf(WorkBlock(at(9), at(17, 30), 9 * 60, 17 * 60 + 30))

    private fun ev(id: String, from: Long, to: Long, allDay: Boolean = false) =
        CalendarEvent(id, id, from, to, allDay, null, "google", null, null)

    private fun task(id: String, scheduled: Long? = null) =
        Task(id, id, null, Lifecycle.ACTIVE, null, scheduled, null, 0, null, null, 0, null, false)

    private fun build(
        now: Long, events: List<CalendarEvent> = emptyList(), planned: List<Task> = emptyList(),
        anytime: List<Task> = emptyList(), sessions: List<BookedSession> = emptyList(), work: List<WorkBlock> = this.work,
    ) = TimelineRules.build(planned, anytime, events, now, day, calendar, sessions, work)

    @Test
    fun aWorkDayMorningShowsTheNowLineAndTheWorkBandUnderASayingLabel() {
        // 07:26 on a work day with nothing else booked: the section is the now line and the work band.
        val tl = build(at(7, 26), anytime = listOf(task("Call the garage")))
        assertEquals(listOf(TimelineKind.NOW, TimelineKind.WORK), tl.rows.map { it.kind })
        assertEquals("1 h 30 free until Work", tl.rows.first().detail)
        assertEquals(listOf("Call the garage"), tl.anytime.map { it.title })
        assertTrue(tl.hasTimedOrAllDay)
        assertEquals("Work 09:00–17:30", tl.summary)
        assertEquals("Today · Work 09:00–17:30", tl.sectionLabel)
        assertEquals(DayHead.ROW, tl.head)
    }

    @Test
    fun atWorkTheLabelSaysUntilWhenAndCountsWhatIsAhead() {
        val tl = build(
            at(11), events = listOf(ev("Standup", at(12), at(12, 15)), ev("1:1", at(15), at(15, 30))),
            planned = listOf(task("Send invoice", at(16))),
        )
        assertEquals("At work until 17:30 · 2 events · 1 planned", tl.summary)
    }

    @Test
    fun allDayAndSessionsLeadAndOnlyThreePartsAreSaid() {
        val gym = BookedSession("gym", "Gym", "Push", day.epochDay, at(18), at(19), "Today 18:00")
        val tl = build(
            at(8), events = listOf(ev("Bins", start + hour, start + 25 * hour, allDay = true), ev("Standup", at(9, 30), at(9, 45))),
            sessions = listOf(gym), planned = listOf(task("Pay rent", at(20))),
        )
        assertEquals("1 all day · Work 09:00–17:30 · 1 event", tl.summary)
        assertEquals(DayHead.ALL_DAY, tl.head)
        val noWork = build(at(8), sessions = listOf(gym), work = emptyList())
        assertEquals("Gym 18:00", noWork.summary)
    }

    @Test
    fun afterWorkTheBandLeavesAndFinishedEventsHeadTheSection() {
        val tl = build(at(18), events = listOf(ev("Standup", at(9, 30), at(9, 45))))
        assertTrue(tl.rows.none { it.kind == TimelineKind.WORK })
        assertNull(tl.summary)
        assertEquals("Today", tl.sectionLabel)
        assertEquals(DayHead.EARLIER, tl.head)
    }

    @Test
    fun nothingAtAllHasNoHead() {
        val tl = build(at(10), work = emptyList())
        assertNull(tl.head)
        assertNull(tl.summary)
    }
}
