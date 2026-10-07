package os.meka.core.domain

import os.meka.core.sync.fv
import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EveningShutdownTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private val hourMs = 3_600_000L
    private val minMs = 60_000L
    private var n = 0
    private fun ids(): String = "S${n++}"

    private fun tasks(d: Device) = Tasks(d.replica, ::ids, { world.clock.nowMs })
    private fun shutdown(d: Device) = EveningShutdown(d.replica, tasks(d), { world.clock.nowMs })

    private val a = world.device("android")
    private val m = world.device("mac")
    private val ta = tasks(a)
    private val sa = shutdown(a)
    private val schedule = WorkSchedule.DEFAULT // Mon–Fri 09:00–17:30

    init {
        // Start every test on a Tuesday at 18:30 UTC.
        val today = world.clock.nowMs.floorDiv(dayMs)
        val toTuesday = (9 - CivilDate.isoDayOfWeek(today)) % 7
        world.clock.nowMs = (today + toTuesday) * dayMs + 18 * hourMs + 30 * minMs
    }

    private fun today() = world.clock.nowMs.floorDiv(dayMs)
    private fun window(day: Long = today()) = DayWindow(day * dayMs, (day + 1) * dayMs)
    private fun at(day: Long, h: Int, min: Int = 0) = day * dayMs + h * hourMs + min * minMs
    private fun view(s: EveningShutdown = sa, t: Tasks = ta, events: List<CalendarEvent> = emptyList(), atWork: Boolean = false) =
        s.view(t.all(), events, schedule, atWork, window(), window(today() + 1))

    private fun event(id: String, title: String, start: Long, end: Long, allDay: Boolean = false, provider: String = "google") =
        CalendarEvent(id, title, start, end, allDay, null, provider, null, null)

    @Test
    fun theEveningStartsWhenWorkEndsOnAWorkDayAndAtSixOtherwise() {
        val tue = today()
        assertEquals(17 * 60 + 30, ShutdownRules.startMinute(schedule, tue))
        val sat = tue + 4
        assertEquals(18 * 60, ShutdownRules.startMinute(schedule, sat))
        // A night shift or a very early finish doesn't define the evening.
        assertEquals(18 * 60, ShutdownRules.startMinute(WorkSchedule(setOf(2), 22 * 60, 6 * 60), tue))
        assertEquals(18 * 60, ShutdownRules.startMinute(WorkSchedule(setOf(2), 7 * 60, 13 * 60), tue))
        assertEquals(18 * 60, ShutdownRules.startMinute(schedule.copy(enabled = false), tue))
    }

    @Test
    fun theCardIsOfferedInTheEveningOnlyWhenNotAtWorkAndNotYetShutDown() {
        assertTrue(view().offered)
        assertFalse(view(atWork = true).offered) // working late: wait until work ends
        world.clock.nowMs = at(today(), 17, 0)
        assertFalse(view().offered)
        world.clock.nowMs = at(today(), 17, 30)
        assertTrue(view().offered)

        sa.shutDown()
        val v = view()
        assertFalse(v.offered)
        assertTrue(v.doneToday)
        assertEquals("Day shut down at 17:30", v.doneLine)

        // Tomorrow evening it is offered again.
        world.clock.advance(dayMs)
        assertFalse(view().doneToday)
        assertTrue(view().offered)
        assertNull(view().doneLine)
    }

    @Test
    fun leftFromTodayIsWhatTodayShowsPlusMissedPlansInTheOrderYoudMeetThem() {
        val day = today()
        val loose = ta.create(NewTask("Loose end"))
        val planned = ta.create(NewTask("Planned", scheduledAtMs = at(day, 14)))
        val missed = ta.create(NewTask("Missed yesterday", scheduledAtMs = at(day - 1, 10)))
        val overdue = ta.create(NewTask("Overdue", dueAtMs = at(day - 2, 9)))
        ta.create(NewTask("Due Friday", dueAtMs = at(day + 3, 9)))
        ta.create(NewTask("Planned tomorrow", scheduledAtMs = at(day + 1, 9)))
        ta.create(NewTask("Idea", lifecycle = Lifecycle.SOMEDAY))
        val done = ta.create(NewTask("Finished"))
        ta.complete(done)

        val v = view()
        assertEquals(listOf(missed, planned, overdue, loose), v.left.map { it.task.id })
        assertEquals(listOf("Planned ${CivilDate.shortLabel(day - 1)}", "Planned 14:00", "Overdue", null), v.left.map { it.line })
        assertTrue(v.left[2].overdue)
        assertEquals("4 left from today", v.leftLine)
        assertEquals(1, v.doneCount)
        assertEquals("1 done today", v.doneCountLine)
    }

    @Test
    fun aRepeatingTaskCanBeSkippedAndAOneOffCanGoToSomeday() {
        val oneOff = ta.create(NewTask("Post the letter"))
        val routine = ta.create(NewTask("Stretch"))
        ta.setRepeat(routine, Recurrence.Daily(1))
        val v = view()
        val byId = v.left.associateBy { it.task.id }
        assertTrue(byId.getValue(oneOff).canSomeday)
        assertFalse(byId.getValue(oneOff).canSkip)
        assertTrue(byId.getValue(routine).canSkip)
        assertFalse(byId.getValue(routine).canSomeday)
        assertEquals("↻ Every day", byId.getValue(routine).line)

        ta.skipOccurrence(routine)
        val after = view()
        assertEquals(listOf(oneOff), after.left.map { it.task.id })
        // The next occurrence is tomorrow's.
        assertEquals(listOf("Stretch"), after.tomorrow.rows.map { it.title })
    }

    @Test
    fun movingTheRestToTomorrowEmptiesTodayAndFillsTomorrow() {
        val day = today()
        ta.create(NewTask("Loose end"))
        ta.create(NewTask("Planned", scheduledAtMs = at(day, 14)))
        assertEquals(2, sa.carryAllToTomorrow(window()))

        val v = view()
        assertTrue(v.left.isEmpty())
        assertEquals("Nothing left from today", v.leftLine)
        // Planned keeps its time of day; the loose end comes with no time.
        assertEquals(listOf("14:00" to "Planned", null to "Loose end"), v.tomorrow.rows.map { it.time to it.title })
        assertEquals("2 tasks · first at 14:00", v.tomorrow.summary)
        assertEquals("Nothing left · tomorrow: 2 tasks, first at 14:00", v.cardLine)

        // Tomorrow they are Today's again.
        world.clock.advance(dayMs)
        val todayNext = TodayProjection.project(ta.all(), world.clock.nowMs, window())
        assertEquals(2, (listOfNotNull(todayNext.upNext) + todayNext.yourDay + todayNext.needsYou.map { it.task }).size)
    }

    @Test
    fun tomorrowShowsWorkEventsAndTasksInTimeOrder() {
        val tomorrow = today() + 1
        val events = listOf(
            event("x", "Barça v Sevilla", at(tomorrow, 20), at(tomorrow, 22), provider = "fixtures"),
            event("s", "Standup", at(tomorrow, 9, 30), at(tomorrow, 9, 45)),
            event("h", "Bank holiday", tomorrow * dayMs, (tomorrow + 1) * dayMs, allDay = true),
            event("late", "Late film", at(today(), 23), at(tomorrow, 1)),
            event("next", "Day after", at(tomorrow + 1, 9), at(tomorrow + 1, 10)),
        )
        ta.create(NewTask("Gym", scheduledAtMs = at(tomorrow, 7)))
        ta.create(NewTask("Pay rent", dueAtMs = at(tomorrow, 12)))
        val p = view(events = events).tomorrow

        assertEquals("Tomorrow · ${CivilDate.shortLabel(tomorrow)}", p.label)
        assertEquals("Work 09:00–17:30", p.workLine)
        assertEquals(
            listOf("All day" to "Bank holiday", "Until 01:00" to "Late film", "07:00" to "Gym", "09:30" to "Standup", "20:00" to "Barça v Sevilla", null to "Pay rent"),
            p.rows.map { it.time to it.title },
        )
        assertEquals("Fixtures", p.rows[4].detail)
        assertEquals("Due 12:00", p.rows[5].detail)
        assertEquals("4 events · 2 tasks · first at 07:00", p.summary)
        assertEquals(4, p.eventCount)
        assertEquals(2, p.taskCount)
        assertEquals("Gym", p.first?.title)
        assertEquals("Tomorrow: first thing 07:00 Gym · 4 events · 2 tasks", p.glance)
    }

    @Test
    fun tomorrowAtAGlanceLeadsWithTheFirstThingThenAnAllDayEvent() {
        val tomorrow = today() + 1
        val standup = event("s", "Standup", at(tomorrow, 9), at(tomorrow, 9, 15))
        // The only thing tomorrow: no counts after it.
        assertEquals("Tomorrow: first thing 09:00 Standup", view(events = listOf(standup)).tomorrow.glance)
        // Something still running from tonight isn't the first thing tomorrow.
        val late = event("late", "Late film", at(today(), 23), at(tomorrow, 1))
        assertEquals("Tomorrow: first thing 09:00 Standup · 2 events", view(events = listOf(late, standup)).tomorrow.glance)
        // Nothing timed: an all-day event leads.
        val holiday = event("h", "Bank holiday", tomorrow * dayMs, (tomorrow + 1) * dayMs, allDay = true)
        ta.create(NewTask("Pay rent", dueAtMs = at(tomorrow, 12)))
        assertEquals("Tomorrow: Bank holiday all day · 1 event · 1 task", view(events = listOf(holiday)).tomorrow.glance)
        // Only untimed tasks: just the count.
        assertEquals("Tomorrow: 1 task", view().tomorrow.glance)
        assertNull(view().tomorrow.first)
        // A long title is cut at a word.
        assertEquals(
            "Tomorrow: first thing 08:00 Quarterly planning with the…",
            view(events = listOf(event("q", "Quarterly planning with the whole regional team", at(tomorrow, 8), at(tomorrow, 10)))).tomorrow.glance
                .substringBefore(" · "),
        )
        assertEquals("Standup", ShutdownRules.shorten("  Standup "))
        assertEquals(ShutdownRules.GLANCE_TITLE_MAX, ShutdownRules.shorten("x".repeat(50)).length)
    }

    @Test
    fun theGlanceIsForTheEveningOnTodayAfterShuttingDownOrWhileStillAtWork() {
        world.clock.nowMs = at(today(), 17, 0)
        assertFalse(view().evening)
        world.clock.nowMs = at(today(), 17, 30) // work ends: the evening starts
        assertTrue(view().evening)
        assertTrue(view(atWork = true).evening) // working late: no card, but the glance shows
        assertFalse(view(atWork = true).offered)
        sa.shutDown()
        assertTrue(view().evening)
        assertFalse(view().offered)
        assertEquals("Tomorrow: nothing planned yet", view().tomorrow.glance)
        // Saturday: the evening starts at 18:00.
        world.clock.nowMs = at(today() + 4, 17, 45)
        assertFalse(view().evening)
        world.clock.nowMs = at(today(), 18, 0)
        assertTrue(view().evening)
    }

    @Test
    fun aFridayShutdownShowsNoWorkTomorrowAndAnEmptyDaySaysSo() {
        world.clock.advance(3 * dayMs) // Friday
        val v = view()
        assertNull(v.tomorrow.workLine)
        assertEquals("Nothing planned yet", v.tomorrow.summary)
        assertEquals("Tomorrow: nothing planned yet", v.tomorrow.glance)
        assertEquals("Nothing left · nothing planned for tomorrow yet", v.cardLine)
        assertEquals("No tasks finished today", v.doneCountLine)
    }

    @Test
    fun shuttingDownOnOneDevicePutsTheCardAwayOnTheOther() {
        val sm = shutdown(m)
        val tm = tasks(m)
        assertTrue(view(sm, tm).offered)
        sa.shutDown()
        a.sync(); m.sync()
        assertFalse(view(sm, tm).offered)
        assertTrue(view(sm, tm).doneToday)
    }

    @Test
    fun aDeviceThatIsNotOnTheSameDayDoesNotCountAnOldShutdown() {
        // A shutdown recorded for yesterday (e.g. a device that was offline) doesn't hide today's card.
        a.replica.commitLocal(EntityTypes.CONTEXT_MODE, EveningShutdown.ENTITY_ID, mapOf(ShutdownFields.DONE_DAY to (today() - 1).fv()))
        assertTrue(view().offered)
        assertFalse(view().doneToday)
    }
}
