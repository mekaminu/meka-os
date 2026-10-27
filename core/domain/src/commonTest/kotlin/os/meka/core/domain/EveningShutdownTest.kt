package os.meka.core.domain

import os.meka.core.sync.fv
import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
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
    fun theEveningStartsWhenWorkEndsOnAWorkDayAndAtEightOtherwise() {
        val tue = today()
        assertEquals(17 * 60 + 30, ShutdownRules.startMinute(schedule, tue))
        val sat = tue + 4
        assertEquals(20 * 60, ShutdownRules.startMinute(schedule, sat))
        // A night shift or a very early finish doesn't define the evening.
        assertEquals(20 * 60, ShutdownRules.startMinute(WorkSchedule(setOf(2), 22 * 60, 6 * 60), tue))
        assertEquals(20 * 60, ShutdownRules.startMinute(WorkSchedule(setOf(2), 7 * 60, 13 * 60), tue))
        assertEquals(20 * 60, ShutdownRules.startMinute(schedule.copy(enabled = false), tue))
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
        assertEquals("Nothing left · 2 planned for tomorrow · first at 14:00", v.cardLine)

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
        // Saturday: the evening starts at 20:00.
        world.clock.nowMs = at(today() + 4, 19, 45)
        assertFalse(view().evening)
        world.clock.nowMs = at(today(), 20, 0)
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
        assertEquals(ShutdownRules.NOTHING_DONE, v.doneCountLine)
    }

    @Test
    fun laterPutsTheCardOffAnHourOnBothDevicesNeverPast23() {
        assertEquals(19 * 60 + 30, ShutdownRules.laterMinute(18 * 60 + 30))
        assertEquals(20 * 60 + 15, ShutdownRules.laterMinute(19 * 60 + 7)) // rounded up to the quarter
        assertEquals(23 * 60, ShutdownRules.laterMinute(22 * 60 + 20)) // capped at 23:00
        assertNull(ShutdownRules.laterMinute(22 * 60 + 50)) // under a quarter of an hour away
        assertEquals("Shut down the day comes back at 19:30", ShutdownRules.laterSaid(19 * 60 + 30))

        assertTrue(view().laterOffered)
        sa.putOff(today(), at(today(), 19, 30))
        a.sync(); m.sync()
        val sm = shutdown(m)
        val tm = tasks(m)
        assertFalse(view().offered)
        assertFalse(view(sm, tm).offered) // the Mac's card goes too
        assertTrue(view().evening) // the quiet glance still shows
        assertEquals(at(today(), 19, 30), view(sm, tm).laterUntilMs)
        world.clock.nowMs = at(today(), 19, 30)
        assertTrue(view().offered)
        // Tomorrow's evening isn't put off by tonight's Later.
        world.clock.nowMs = at(today() + 1, 18, 30)
        assertTrue(view().offered)
        assertEquals(0, view().laterUntilMs)
    }

    @Test
    fun theCardSwitchedOffOrAnnualLeaveShowsNoCardButThePaneStillWorks() {
        sa.setCard(on = false)
        a.sync(); m.sync()
        val sm = shutdown(m)
        val off = view(sm, tasks(m))
        assertFalse(off.offered)
        assertTrue(off.cardOff)
        assertFalse(off.laterOffered)
        assertTrue(off.evening)
        assertEquals("Evening card · off · open it here whenever you like", ShutdownRules.cardLine(off.cardOff, off.onLeave))
        sm.setCard(on = true)
        m.sync(); a.sync()
        assertTrue(view().offered)

        val leave = sa.view(ta.all(), emptyList(), schedule, false, window(), window(today() + 1), onLeave = true)
        assertFalse(leave.offered)
        assertTrue(leave.onLeave)
        assertEquals("Evening card · on · not on annual leave days", ShutdownRules.cardLine(false, true))
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

    @Test
    fun doneForTheDayListsWhatGotDoneNewestFirstWithTheRestCounted() {
        val day = today()
        val titles = (1..8).map { "Job $it" }
        titles.forEachIndexed { k, t ->
            world.clock.nowMs = at(day, 9 + k)
            ta.complete(ta.create(NewTask(t)))
        }
        // Finished yesterday: not today's.
        world.clock.nowMs = at(day - 1, 20)
        ta.complete(ta.create(NewTask("Yesterday's")))
        world.clock.nowMs = at(day, 18, 30)

        val v = view()
        assertEquals(8, v.doneCount)
        assertEquals(ShutdownRules.DONE_SHOWN, v.done.size)
        assertEquals(listOf("Job 8", "Job 7", "Job 6", "Job 5", "Job 4", "Job 3"), v.done.map { it.title })
        assertEquals("16:00", v.done.first().time)
        assertEquals("+ 2 more", v.doneMore)
        assertNull(ShutdownRules.doneMore(ShutdownRules.DONE_SHOWN))
    }

    @Test
    fun doneForTheDaySaysWhatTomorrowStartsWith() {
        val day = today()
        assertNull(view().firstLine)
        val events = listOf(
            event("hol", "Bank holiday", at(day + 1, 0), at(day + 2, 0), allDay = true),
            event("su", "Standup with the whole platform team and guests", at(day + 1, 9, 30), at(day + 1, 9, 45)),
        )
        assertEquals("Tomorrow starts at 09:30 · Standup with the whole…", view(events = events).firstLine)
    }

    @Test
    fun shuttingDownTurnsTheWatchFaceToNightUntilTomorrow() {
        assertFalse(view().night)
        sa.shutDown()
        assertTrue(view().night)
        world.clock.advance(CivilDate.DAY_MS)
        assertFalse(view().night)
        assertEquals(WatchFaceRules.NIGHT_ALPHA, WatchFaceRules.nightAlpha(true))
        assertEquals(1f, WatchFaceRules.nightAlpha(false))
        assertEquals(DayRingLiveMode.MINUTE, WatchFaceRules.liveMode(reduced = false, powerSave = false, bedside = false, quiet = false, night = true))
        assertEquals(DayRingLiveMode.SWEEP, WatchFaceRules.liveMode(reduced = false, powerSave = false, bedside = false, quiet = false, night = false))
        assertEquals(DayRingLiveMode.STILL, WatchFaceRules.liveMode(reduced = true, powerSave = false, bedside = false, quiet = false, night = true))
    }

    @Test
    fun aTaskDatedTomorrowIsCountedOnTheCardAndNotLeftFromToday() {
        // Meka 2026-10-26: Ask added a task for tomorrow, the card said "5 left · nothing planned for tomorrow yet".
        val day = today()
        repeat(2) { ta.create(NewTask("Left $it")) }
        val asked = ta.create(NewTask("Ring the school"))
        ta.setWhen(asked, day + 1, null) // how Ask, Live and the Board date a task
        val timed = ta.create(NewTask("Gym"))
        ta.setWhen(timed, day + 1, 7 * 60 + 40)
        val v = view()
        assertEquals(listOf("Left 0", "Left 1"), v.left.map { it.task.title })
        assertEquals(listOf("Gym", "Ring the school"), v.tomorrow.rows.map { it.title })
        assertEquals("2 left · 2 planned for tomorrow · first at 07:40", v.cardLine)
        assertEquals(listOf(timed, asked), v.tomorrow.rows.map { ShutdownRules.taskIdOf(it) })
        assertNull(ShutdownRules.taskIdOf(TomorrowRow("e-1", "09:00", "Standup", true, null)))
        // One task: "1 planned for tomorrow".
        ta.complete(timed)
        assertEquals("2 left · 1 planned for tomorrow", view().cardLine)
    }

    @Test
    fun theTomorrowPeekShowsFromFivePmOrOnceShutDown() {
        val day = today()
        world.clock.nowMs = at(day, 16, 59)
        assertNull(view().peek)
        world.clock.nowMs = at(day, 17, 0)
        val label = CivilDate.shortLabel(day + 1)
        assertEquals("Tomorrow · $label · nothing planned yet", view().peek?.line)
        val gym = ta.create(NewTask("Gym"))
        ta.setWhen(gym, day + 1, 7 * 60 + 40)
        ta.create(NewTask("Pay rent", dueAtMs = at(day + 1, 12)))
        val events = listOf(event("s", "Standup", at(day + 1, 9, 30), at(day + 1, 9, 45)))
        val p = view(events = events).peek!!
        assertEquals("Tomorrow · $label · 1 event · 2 tasks · first 07:40 Gym", p.line)
        assertEquals("Tomorrow, $label: 1 event, 2 tasks, first 07:40 Gym", p.spoken)
        // Shown beside the shutdown card (work ends 17:30), and after shutting down.
        world.clock.nowMs = at(day, 17, 30)
        assertTrue(view().offered)
        assertNotNull(view().peek)
        sa.shutDown()
        assertNotNull(view().peek)
        // In the morning, after a shutdown the night before: gone until 17:00.
        world.clock.nowMs = at(day + 1, 8)
        assertNull(view().peek)
        // A work day ending at 16:00 brings it at 16:00.
        assertTrue(ShutdownRules.peekShown(16 * 60, 16 * 60, doneToday = false))
        assertFalse(ShutdownRules.peekShown(15 * 60 + 59, 16 * 60, doneToday = false))
    }

    @Test
    fun theTomorrowSheetMovesKeepTheTimeAndTicksLeaveAtOnce() {
        val day = today() // Tuesday 18:30 UTC
        val gym = ta.get(ta.create(NewTask("Gym", scheduledAtMs = at(day + 1, 7, 40))))!!
        val loose = ta.get(ta.create(NewTask("Loose end")))!!
        val cal = LocalCalendar.UTC
        // Today: 07:40 has gone, so no time; 19:00 is still ahead, so it stays.
        assertNull(ShutdownRules.movedMinute(gym, day, day, 18 * 60 + 30, cal))
        val late = ta.get(ta.create(NewTask("Call Sam", scheduledAtMs = at(day + 1, 19))))!!
        assertEquals(19 * 60, ShutdownRules.movedMinute(late, day, day, 18 * 60 + 30, cal))
        // The day after keeps it; a task with no time has none.
        assertEquals(7 * 60 + 40, ShutdownRules.movedMinute(gym, day + 2, day, 18 * 60 + 30, cal))
        assertNull(ShutdownRules.movedMinute(loose, day + 2, day, 18 * 60 + 30, cal))
        assertEquals("Moved “Gym” to Today", ShutdownRules.movedLine("Gym ", day, day))
        assertEquals("Moved “Gym” to ${CivilDate.shortLabel(day + 2)}", ShutdownRules.movedLine("Gym", day + 2, day))

        val rows = view().tomorrow.rows
        assertEquals(listOf("Gym", "Call Sam"), rows.map { it.title })
        val event = TomorrowRow("e-1", "09:00", "Standup", true, null)
        val shown = InstantTaskRules.tomorrow(rows + event, mapOf(gym.id to TaskMove.DONE))
        assertEquals(listOf("Call Sam", "Standup"), shown.map { it.title })
        assertEquals("Today", InstantTaskRules.what(TaskMove.TODAY))
        assertEquals("Day after", InstantTaskRules.what(TaskMove.DAY_AFTER))
    }
}
