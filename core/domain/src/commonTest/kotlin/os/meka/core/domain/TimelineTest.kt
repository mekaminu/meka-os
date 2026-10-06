package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineTest {
    private val hour = 3_600_000L
    private val min = 60_000L
    // Tue 6 Oct 2026 in London (BST, +1 h): local midnight = 5 Oct 23:00 UTC.
    private val oct6Utc = 1_791_244_800_000L // 2026-10-06T00:00:00Z
    private val start = oct6Utc - hour
    private val day = DayWindow(startMs = start, endMs = start + 24 * hour, utcOffsetMs = hour)
    private fun at(h: Int, m: Int = 0) = start + h * hour + m * min

    private fun ev(id: String, from: Long, to: Long, allDay: Boolean = false, location: String? = null, provider: String = "google") =
        CalendarEvent(id, id, from, to, allDay, location, provider, null, null)

    private fun task(id: String, scheduled: Long? = null, estimate: Int? = null, priority: Int = 0) =
        Task(id, id, null, Lifecycle.ACTIVE, null, scheduled, estimate, priority, null, null, 0, null, false)

    private fun project(tasks: List<Task>, events: List<CalendarEvent>, now: Long) =
        TodayProjection.project(tasks, now, day, events).timeline

    @Test
    fun dateLabelIsTheLocalDayInFull() {
        assertEquals("Tuesday 6 October", project(emptyList(), emptyList(), at(10)).dateLabel)
        assertEquals("Tuesday 6 October", CivilDate.longLabel(CivilDate.toEpochDay(2026, 10, 6)))
    }

    @Test
    fun eventsAndPlannedTasksMergeInTimeOrderWithNowLineAndGaps() {
        val tl = project(
            tasks = listOf(task("write", scheduled = at(14), estimate = 60), task("loose")),
            events = listOf(ev("standup", at(9, 30), at(9, 45)), ev("lunch", at(12), at(13), location = "Canteen"), ev("call", at(16), at(16, 30))),
            now = at(10, 2),
        )
        // standup has ended: folded away.
        assertEquals(listOf("e-standup"), tl.earlier.map { it.id })
        assertEquals("1 earlier", tl.earlierLabel)
        // write (14:00, 60 min) ends 15:00; call at 16:00 leaves a 1 h gap. From now (10:02) to lunch: 1 h 58 → 1 h 55.
        assertEquals(
            listOf("now", "gap", "e-lunch", "gap", "t-write", "gap", "e-call"),
            tl.rows.map { if (it.kind == TimelineKind.GAP) "gap" else it.id },
        )
        assertEquals(listOf("1 h 55 free", "1 h free", "1 h free"), tl.rows.filter { it.kind == TimelineKind.GAP }.map { it.title })
        assertEquals("10:02", tl.rows.first().time)
        assertEquals("gap-e-lunch", tl.rows[1].id) // stable while now moves
        val lunch = tl.rows.first { it.id == "e-lunch" }
        assertEquals("12:00–13:00", lunch.time)
        assertEquals("Canteen", lunch.detail)
        assertEquals("14:00", tl.rows.first { it.id == "t-write" }.time)
        assertEquals("60 min", tl.rows.first { it.id == "t-write" }.detail)
        assertEquals(listOf("loose"), tl.anytime.map { it.id })
    }

    @Test
    fun runningEventsSitAboveTheNowLineAndFreeTimeCountsFromTheirEnd() {
        val tl = project(emptyList(), listOf(ev("workshop", at(9), at(11)), ev("review", at(11, 20), at(12))), now = at(10))
        assertEquals(listOf("e-workshop", "now", "e-review"), tl.rows.map { it.id })
        assertTrue(tl.rows.first().running)
    }

    @Test
    fun overdueTimeOnAPlannedTaskKeepsItVisibleAboveNow() {
        // A planned task whose time has passed isn't folded: it still needs doing. (Up next takes the next one.)
        val t = TodayProjection.project(listOf(task("morning", scheduled = at(8)), task("later", scheduled = at(15))), at(10), day)
        assertEquals("later", t.upNext!!.id)
        assertEquals(listOf("t-morning", "now", "gap", "t-later"), t.timeline.rows.map { if (it.kind == TimelineKind.GAP) "gap" else it.id })
    }

    @Test
    fun shortGapsAreNotShownAndRoundDownToFiveMinutes() {
        val tl = project(emptyList(), listOf(ev("a", at(10, 20), at(10, 30)), ev("b", at(10, 50), at(11))), now = at(10))
        assertTrue(tl.rows.none { it.kind == TimelineKind.GAP })
        assertEquals("45 min free", TimelineRules.freeLabel(47))
        assertEquals("2 h free", TimelineRules.freeLabel(122))
        assertEquals("1 h 05 free", TimelineRules.freeLabel(65))
    }

    @Test
    fun anEventFromLastNightSaysUntilAndAllDayEventsAreChips() {
        val tl = project(
            emptyList(),
            listOf(
                ev("late shift", start - 2 * hour, at(1)),
                ev("Holiday", oct6Utc, oct6Utc + 24 * hour, allDay = true),
            ),
            now = at(0, 30),
        )
        assertEquals(listOf("Holiday"), tl.allDay.map { it.title })
        val shift = tl.rows.first { it.id == "e-late shift" }
        assertEquals("Until 01:00", shift.time)
        assertTrue(shift.running)
    }

    @Test
    fun nextEventWithinTheHourGoesToUpNext() {
        val events = listOf(ev("Call with Tunde", at(10, 25), at(11), location = "Meet"), ev("Gym", at(18), at(19)))
        val next = project(emptyList(), events, now = at(10)).nextEvent!!
        assertEquals("Call with Tunde in 25 min", next.line)
        assertEquals("10:25–11:00 · Meet", next.detail)
        assertEquals("Standup in 1 h", project(emptyList(), listOf(ev("Standup", at(11), at(11, 15))), at(10)).nextEvent!!.line)
        assertNull(project(emptyList(), events, now = at(9, 20)).nextEvent) // 65 min away
        // Partly through a minute rounds up: 10:00:30 → 10:25 is "25 min".
        assertEquals(25, project(emptyList(), events, now = at(10) + 30_000).nextEvent!!.minutes)
    }

    @Test
    fun noTimedThingsMeansNoNowLine() {
        val tl = project(listOf(task("first", priority = 1), task("loose")), emptyList(), at(10))
        assertTrue(tl.rows.isEmpty())
        assertTrue(!tl.hasTimedOrAllDay)
        assertEquals(1, tl.anytime.size)
    }

    @Test
    fun needsYouIsNotRepeatedButAPlannedUpNextStaysInTheTimeline() {
        val overdue = Task("od", "od", null, Lifecycle.ACTIVE, at(9), at(8), null, 0, null, null, 0, null, false)
        val t = TodayProjection.project(listOf(overdue, task("next", scheduled = at(11)), task("loose")), at(10), day)
        assertEquals("next", t.upNext!!.id)
        assertTrue(t.timeline.rows.none { it.task?.id == "od" })
        assertTrue(t.timeline.rows.any { it.task?.id == "next" })
        // An unscheduled Up next isn't repeated under Anytime today.
        val u = TodayProjection.project(listOf(task("a", priority = 2), task("b")), at(10), day)
        assertEquals("a", u.upNext!!.id)
        assertEquals(listOf("b"), u.timeline.anytime.map { it.id })
    }
}
