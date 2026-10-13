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

    private val weekdays = WorkHours(WorkSchedule.WEEKDAYS) // Mon–Fri 09:00–17:30, in the office (1 h 20 commute)
    /** The same hours from home: no commute bands (the commute counts everywhere, slice 2). */
    private val fromHome = WorkHours(WorkSchedule.WEEKDAYS, homeDays = setOf(thu8))

    private fun ev(id: String, from: Long, to: Long, allDay: Boolean = false) = CalendarEvent(id, id, from, to, allDay, null, "google", null, null)

    private fun today(
        now: Long, events: List<CalendarEvent> = emptyList(), work: WorkHours? = weekdays, day: Long = thu8,
        tasks: List<Task> = emptyList(),
    ) = TodayProjection.project(tasks, now, window(day), events, cal, work = work)

    private fun anytime(id: String) = Task(id, id, null, Lifecycle.ACTIVE, null, null, null, 0, null, null, 0, null, false)

    private fun rows(t: Today) = t.timeline.rows.map {
        when {
            it.kind == TimelineKind.GAP -> "gap:${it.title}"
            it.kind == TimelineKind.NOW && it.detail != null -> "now:${it.detail}"
            else -> it.id
        }
    }

    @Test
    fun aWorkDayShowsWorkAsOneQuietBlockAndFreeTimeBeforeIt() {
        val t = today(at(thu8, 6, 40))
        assertEquals(listOf("now:1 h free before you leave", "c-to", "w-540", "c-home"), rows(t))
        assertEquals(listOf("now:1 h free until Work from home", "w-540"), rows(today(at(thu8, 7, 57), work = fromHome)))
        val work = t.timeline.rows.first { it.kind == TimelineKind.WORK }
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
        val t = today(at(thu8, 11), listOf(training), work = fromHome)
        assertEquals(listOf("w-540", "now", "gap:30 min free after work", "e-Training"), rows(t))
        // In the office the commute home fills that half hour.
        assertEquals(listOf("w-540", "now", "c-home", "e-Training"), rows(today(at(thu8, 11), listOf(training))))
        val work = t.timeline.rows.first()
        assertTrue(work.running)
        assertEquals("Now · until 17:30", work.detail)
    }

    @Test
    fun eventsDuringWorkMakeNoGapAndWorkLeavesOnceOver() {
        val standup = ev("Standup", at(thu8, 10), at(thu8, 10, 15))
        val dinner = ev("Dinner", at(thu8, 20), at(thu8, 21))
        val morning = today(at(thu8, 7), listOf(standup, dinner))
        assertEquals(listOf("now:40 min free before you leave", "c-to", "w-540", "e-Standup", "c-home", "gap:1 h 10 free at home", "e-Dinner"), rows(morning))
        // Home by 18:50, work and both commutes have gone (not folded into "earlier" like an event).
        val evening = today(at(thu8, 19), listOf(standup, dinner))
        assertEquals(listOf("now:1 h free until Dinner", "e-Dinner"), rows(evening))
        assertEquals(listOf("e-Standup"), evening.timeline.earlier.map { it.id })
        // Nothing but finished work and commutes: no now line, nothing on the timeline.
        assertEquals(emptyList(), today(at(thu8, 19)).timeline.rows)
        assertEquals(emptyList(), today(at(thu8, 18), work = fromHome).timeline.rows)
    }

    @Test
    fun weekendsBankHolidaysAndAWorkOffDayHaveNoBlock() {
        assertEquals(listOf(), rows(today(at(sat10, 10), day = sat10)))
        val holiday = WorkHours(WorkSchedule.WEEKDAYS, HolidayCalendar(mapOf(thu8 to "Made-up Day")))
        assertEquals(listOf(), rows(today(at(thu8, 8), work = holiday)))
        val sick = WorkHours(WorkSchedule.WEEKDAYS, offDay = thu8)
        assertEquals(listOf(), rows(today(at(thu8, 10), work = sick)))
        assertEquals(listOf(), rows(today(at(thu8, 8), work = null)))
    }

    @Test
    fun aManualWorkOffDuringTheShiftTakesTodaysBlockAway() {
        val clock = LocalClock(4, 10 * 60)
        val nowMs = at(thu8, 10)
        val off = WorkModeRules.state(WorkSchedule.WEEKDAYS, WorkSwitch(false, nowMs, true), clock, nowMs, thu8)
        assertEquals(thu8, WorkHours.of(off, HolidayCalendar.NONE, thu8).offDay)
        val scheduled = WorkModeRules.state(WorkSchedule.WEEKDAYS, null, clock, nowMs, thu8)
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
        val hours = WorkHours(WorkSchedule.WEEKDAYS, HolidayCalendar(mapOf(thu8 + 1 to "Made-up Day")))
        val v = CalendarAgenda.build(emptyList(), emptyList(), at(thu8, 8), cal, days = 3, work = hours)
        assertEquals("Work 09:00–17:30", v.sections[0].workLine)
        assertNull(v.sections[1].workLine)
        assertEquals("Nothing planned", v.sections[1].emptyLine)
    }

    // ---- Meka's 10:48 screenshots, 2026-10-09 ----

    @Test
    fun atWorkWithNothingElseTheNowLineSaysSo() {
        // 10:48 on a work day, nothing else planned: no bare now line over an empty stretch.
        val t = today(at(thu8, 10, 48), work = fromHome)
        assertEquals(listOf("w-540", "now:Nothing else planned after 17:30"), rows(t))
        // In the office, nothing after he's home (the commute home still shows).
        assertEquals(listOf("w-540", "now:Nothing else planned after 18:50", "c-home"), rows(today(at(thu8, 10, 48))))
        // Something after work: the gap says it, as before.
        val training = ev("Training", at(thu8, 18), at(thu8, 19, 30))
        assertEquals(listOf("w-540", "now", "gap:30 min free after work", "e-Training"), rows(today(at(thu8, 10, 48), listOf(training), work = fromHome)))
        // An event running past midnight: "today".
        val late = ev("Late", at(thu8, 20), at(thu8 + 1, 1))
        assertEquals(listOf("e-Late", "now:Nothing else planned today"), rows(today(at(thu8, 21), listOf(late))))
        // Nothing on at all (only ended events): the now line stays bare; Today's clear line speaks.
        val standup = ev("Standup", at(thu8, 7), at(thu8, 7, 15))
        assertEquals(listOf("now"), rows(today(at(thu8, 8), listOf(standup), work = null)))
    }

    // ---- Fold review 2026-10-09 13:45, item 4 ----

    @Test
    fun atWorkTheNowLineCountsTheAnytimeTasksLeft() {
        val four = (1..4).map { anytime("a$it") }
        // 13:44 at work with four anytime tasks (one of them Up next): what's left to fit in, once (the work row above
        // already says "Now · until 17:30"; Fold review 2026-10-12, polish 6).
        assertEquals(listOf("w-540", "now:4 anytime tasks", "c-home"), rows(today(at(thu8, 13, 44), tasks = four)))
        assertEquals(listOf("w-540", "now:1 anytime task", "c-home"), rows(today(at(thu8, 13, 44), tasks = four.take(1))))
        // A home day too: its row says "Work from home".
        val home = fromHome
        assertEquals("now:4 anytime tasks", rows(today(at(thu8, 13, 44), work = home, tasks = four)).last())
        // An event running past the end of work: free after it, with the tasks.
        val late = ev("Late call", at(thu8, 17), at(thu8, 18, 15))
        assertEquals("now:Free after 18:15 · 4 anytime tasks", rows(today(at(thu8, 17, 10), listOf(late), work = home, tasks = four)).last())
        // Nothing left at all: the old words.
        assertEquals("now:Nothing else planned after 18:15", rows(today(at(thu8, 17, 10), listOf(late), work = home)).last())
        // Running past midnight with tasks left.
        val night = ev("Night", at(thu8, 20), at(thu8 + 1, 1))
        assertEquals("now:Nothing else timed · 2 anytime tasks", rows(today(at(thu8, 21), listOf(night), tasks = four.take(2))).last())
    }

    @Test
    fun theRestLabelReadsNaturally() {
        assertEquals("Nothing else planned after 17:30", TimelineRules.restLabel("17:30", "Work", 0))
        assertEquals("3 anytime tasks", TimelineRules.restLabel("17:30", "Work", 3))
        assertEquals("Free after 15:00 · 1 anytime task", TimelineRules.restLabel("15:00", null, 1))
        assertEquals("Nothing else planned after 15:00", TimelineRules.restLabel("15:00", null, 0))
        assertEquals("Nothing else planned today", TimelineRules.restLabel(null, null, 0))
        assertEquals("Nothing else timed · 2 anytime tasks", TimelineRules.restLabel(null, "Work", 2))
    }

    @Test
    fun theCalendarsWorkBandIsLiveTodayLikeTodays() {
        val v = CalendarAgenda.build(emptyList(), emptyList(), at(thu8, 10, 48), cal, days = 3, work = weekdays)
        val today = v.sections[0]
        assertEquals("Work", today.workTitle)
        assertEquals("Now · until 17:30", today.workDetail)
        assertTrue(today.workRunning)
        assertEquals("Work 09:00–17:30", today.workLine)
        val tomorrow = v.sections[1]
        assertEquals("Work 09:00–17:30", tomorrow.workTitle)
        assertNull(tomorrow.workDetail)
        assertTrue(!tomorrow.workRunning)
        // Before and after work today: the plain band.
        val morning = CalendarAgenda.build(emptyList(), emptyList(), at(thu8, 7, 57), cal, days = 2, work = weekdays).sections[0]
        assertEquals("Work 09:00–17:30", morning.workTitle)
        assertTrue(!morning.workRunning)
        // A home day reads "Work from home" while on.
        val home = CalendarAgenda.build(emptyList(), emptyList(), at(thu8, 11), cal, days = 2, work = weekdays.copy(homeDays = setOf(thu8))).sections[0]
        assertEquals("Work from home", home.workTitle)
        assertEquals("Now · until 17:30", home.workDetail)
    }
}
