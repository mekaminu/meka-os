package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Work hours on Today and the Calendar tab (Fold review 2026-10-08, item 1). */
class WorkHoursTest {
    private val hour = 3_600_000L
    private val min = 60_000L
    private val cal = LocalCalendar.fixedOffset(hour) // London in early October (BST)
    private val thu8 = CivilDate.toEpochDay(2026, 10, 8) // a Thursday, a work day
    private val sat10 = thu8 + 2
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private fun window(day: Long) = CalendarAgenda.window(day, cal)

    private val weekdays = WorkHours(WorkSchedule.DEFAULT) // Mon–Fri 09:00–17:30

    private fun ev(id: String, from: Long, to: Long, allDay: Boolean = false) = CalendarEvent(id, id, from, to, allDay, null, "google", null, null)

    private fun today(now: Long, events: List<CalendarEvent> = emptyList(), work: WorkHours? = weekdays, day: Long = thu8) =
        TodayProjection.project(emptyList(), now, window(day), events, cal, work = work)

    private fun rows(t: Today) = t.timeline.rows.map { if (it.kind == TimelineKind.GAP) "gap:${it.title}" else it.id }

    @Test
    fun aWorkDayShowsWorkAsOneQuietBlockAndFreeTimeBeforeIt() {
        val t = today(at(thu8, 7, 57))
        assertEquals(listOf("now", "gap:1 h free before work", "w-540"), rows(t))
        val work = t.timeline.rows.last()
        assertEquals(TimelineKind.WORK, work.kind)
        assertEquals("09:00–17:30", work.time)
        assertEquals("Work", work.title)
        assertNull(work.detail)
        // Work isn't an event ahead or offered to Up next.
        assertTrue(!t.timeline.hasEventsAhead)
        assertNull(t.timeline.nextEvent)
    }

    @Test
    fun atWorkTheBlockSitsAboveNowAndTheGapAfterSaysAfterWork() {
        val training = ev("Training", at(thu8, 18), at(thu8, 19, 30))
        val t = today(at(thu8, 11), listOf(training))
        assertEquals(listOf("w-540", "now", "gap:30 min free after work", "e-Training"), rows(t))
        val work = t.timeline.rows.first()
        assertTrue(work.running)
        assertEquals("Now · until 17:30", work.detail)
    }

    @Test
    fun eventsDuringWorkMakeNoGapAndWorkLeavesOnceOver() {
        val standup = ev("Standup", at(thu8, 10), at(thu8, 10, 15))
        val dinner = ev("Dinner", at(thu8, 19), at(thu8, 20))
        val morning = today(at(thu8, 8), listOf(standup, dinner))
        assertEquals(listOf("now", "gap:1 h free before work", "w-540", "e-Standup", "gap:1 h 30 free after work", "e-Dinner"), rows(morning))
        // After 17:30 work has gone (not folded into "earlier" like an event).
        val evening = today(at(thu8, 18), listOf(standup, dinner))
        assertEquals(listOf("now", "gap:1 h free", "e-Dinner"), rows(evening))
        assertEquals(listOf("e-Standup"), evening.timeline.earlier.map { it.id })
        // Nothing but finished work: no now line, nothing on the timeline.
        assertEquals(emptyList(), today(at(thu8, 18)).timeline.rows)
    }

    @Test
    fun weekendsBankHolidaysAndAWorkOffDayHaveNoBlock() {
        assertEquals(listOf(), rows(today(at(sat10, 10), day = sat10)))
        val holiday = WorkHours(WorkSchedule.DEFAULT, HolidayCalendar(mapOf(thu8 to "Made-up Day")))
        assertEquals(listOf(), rows(today(at(thu8, 8), work = holiday)))
        val sick = WorkHours(WorkSchedule.DEFAULT, offDay = thu8)
        assertEquals(listOf(), rows(today(at(thu8, 10), work = sick)))
        assertEquals(listOf(), rows(today(at(thu8, 8), work = null)))
    }

    @Test
    fun aManualWorkOffDuringTheShiftTakesTodaysBlockAway() {
        val clock = LocalClock(4, 10 * 60)
        val nowMs = at(thu8, 10)
        val off = WorkModeRules.state(WorkSchedule.DEFAULT, WorkSwitch(false, nowMs, true), clock, nowMs, thu8)
        assertEquals(thu8, WorkHours.of(off, HolidayCalendar.NONE, thu8).offDay)
        val scheduled = WorkModeRules.state(WorkSchedule.DEFAULT, null, clock, nowMs, thu8)
        assertNull(WorkHours.of(scheduled, HolidayCalendar.NONE, thu8).offDay)
    }

    @Test
    fun theDayRingCountsFreeTimeOutsideWork() {
        // 07:57 → 22:00 is 14 h 03; work takes 8 h 30 → 5 h 33 free.
        val ring = today(at(thu8, 7, 57)).dayRing
        assertEquals(5 * 60 + 33, ring.freeMinutes)
        assertTrue(ring.arcs.isEmpty()) // work isn't drawn as an arc
        // A day off: the whole waking day.
        assertEquals(14 * 60 + 3, today(at(thu8, 7, 57), work = null).dayRing.freeMinutes)
        // An event during work isn't counted twice.
        val standup = ev("Standup", at(thu8, 10), at(thu8, 10, 15))
        assertEquals(5 * 60 + 33, today(at(thu8, 7, 57), listOf(standup)).dayRing.freeMinutes)
    }

    @Test
    fun aNightShiftShowsOnTheDayItStartsAndItsTailNextMorning() {
        val nights = WorkHours(WorkSchedule(setOf(4), 22 * 60, 6 * 60)) // Thursday nights
        val thu = nights.blocks(thu8, cal)
        assertEquals(1, thu.size)
        assertEquals(at(thu8, 22), thu[0].startMs)
        assertEquals(at(thu8 + 1, 0), thu[0].endMs)
        assertEquals("22:00–06:00", thu[0].label)
        val fri = nights.blocks(thu8 + 1, cal)
        assertEquals(listOf(at(thu8 + 1, 0) to at(thu8 + 1, 6)), fri.map { it.startMs to it.endMs })
        assertEquals(emptyList(), nights.blocks(sat10, cal))
    }

    @Test
    fun theCalendarTabSaysWorkOnEachWorkDayAndNeverFoldsOne() {
        val v = CalendarAgenda.build(emptyList(), emptyList(), at(thu8, 7, 57), cal, days = 7, work = weekdays)
        // Thu (today), Fri (tomorrow), Sat–Sun folded, Mon–Wed each a work day.
        assertEquals(listOf("Today", "Tomorrow", "Sat 10 – Sun 11 Oct", "Mon 12 Oct", "Tue 13 Oct", "Wed 14 Oct"), v.sections.map { it.title })
        val tomorrow = v.sections[1]
        assertEquals("Work 09:00–17:30", tomorrow.workLine)
        assertNull(tomorrow.emptyLine) // not "Nothing planned" on a work day
        assertNull(v.sections[2].workLine)
        assertEquals("Nothing planned", v.sections[2].subtitle)
        assertEquals("Work 09:00–17:30", v.sections[3].workLine)
        assertNull(v.sections[3].subtitle)
        // Work isn't counted as busy.
        assertEquals(0, v.weeks[0].days.first { it.epochDay == thu8 + 1 }.dots)
        assertEquals("Friday 9 October, work 09:00–17:30", v.weeks[0].days.first { it.epochDay == thu8 + 1 }.accessibilityLabel)
        // Without work hours the old behaviour stands.
        val plain = CalendarAgenda.build(emptyList(), emptyList(), at(thu8, 7, 57), cal, days = 7)
        assertEquals("Nothing planned", plain.sections[1].emptyLine)
        assertNull(plain.sections[1].workLine)
    }

    @Test
    fun aBankHolidayInTheCalendarHasNoWorkLine() {
        val hours = WorkHours(WorkSchedule.DEFAULT, HolidayCalendar(mapOf(thu8 + 1 to "Made-up Day")))
        val v = CalendarAgenda.build(emptyList(), emptyList(), at(thu8, 8), cal, days = 3, work = hours)
        assertEquals("Work 09:00–17:30", v.sections[0].workLine)
        assertNull(v.sections[1].workLine)
        assertEquals("Nothing planned", v.sections[1].emptyLine)
    }
}
