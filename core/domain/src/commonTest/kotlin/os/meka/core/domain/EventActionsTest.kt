package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventActionsTest {
    private val hour = 3_600_000L
    private val min = 60_000L
    private val world = SyncWorld()
    // Fixed +1 h (London in October before the clocks go back).
    private val cal = LocalCalendar.fixedOffset(hour)
    private val tue6 = CivilDate.toEpochDay(2026, 10, 6)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)

    private val a = world.device("android")
    private val m = world.device("mac")
    private fun actions(d: Device) = EventActions(d.replica, d.tasks, { world.clock.nowMs }, cal)
    private val ea = actions(a)
    private val em = actions(m)

    init { world.clock.nowMs = at(tue6, 10) }

    private fun ev(id: String = "ev1", title: String = "Call with Tunde", start: Long = at(tue6, 14), end: Long = at(tue6, 15), allDay: Boolean = false) =
        CalendarEvent(id, title, start, end, allDay, null, "google", "meka@gmail.com", "Personal")

    private fun syncBoth() { a.sync(); m.sync(); a.sync() }

    @Test
    fun prepTaskIsPlannedBeforeTheEventAndDueAtItsStart() {
        val e = ev()
        val id = ea.addPrep(e)
        assertEquals("pev1", id)
        val t = a.tasks.get(id)!!
        assertEquals("Prepare for Call with Tunde", t.title)
        assertEquals(at(tue6, 13, 30), t.scheduledAtMs)
        assertEquals(at(tue6, 14), t.dueAtMs)
        assertEquals(15, t.estimateMinutes)
        assertEquals("ev1", t.eventId)
        assertEquals(Lifecycle.ACTIVE, t.lifecycle)
        assertEquals(t, ea.marks().prepTasks["ev1"])
    }

    @Test
    fun prepTaskTooCloseToTheEventIsDueButNotPlanned() {
        world.clock.nowMs = at(tue6, 13, 45)
        val t = a.tasks.get(ea.addPrep(ev()))!!
        assertNull(t.scheduledAtMs)
        assertEquals(at(tue6, 14), t.dueAtMs)
    }

    @Test
    fun allDayEventsArePreparedBy9OnTheirDay() {
        val e = ev(start = (tue6 + 2) * CivilDate.DAY_MS, end = (tue6 + 3) * CivilDate.DAY_MS, allDay = true, title = "Bank holiday")
        val t = a.tasks.get(ea.addPrep(e))!!
        assertNull(t.scheduledAtMs)
        assertEquals(at(tue6 + 2, 9), t.dueAtMs)
    }

    @Test
    fun longTitlesAreCutAtAWord() {
        val long = (1..80).joinToString(" ") { "word$it" }
        val p = PrepRules.plan(ev(title = long), world.clock.nowMs, cal)
        assertTrue(p.title.length <= PrepRules.MAX_TITLE)
        assertTrue(p.title.endsWith("…"))
        assertFalse(p.title.dropLast(1).endsWith(" "))
    }

    @Test
    fun addingTwiceOrOnBothDevicesOfflineMakesOneTask() {
        val e = ev()
        ea.addPrep(e)
        ea.addPrep(e)
        em.addPrep(e)
        syncBoth()
        listOf(a, m).forEach { d ->
            val preps = d.tasks.all().filter { it.eventId == "ev1" }
            assertEquals(1, preps.size)
            assertFalse(preps[0].hasConflict)
        }
    }

    @Test
    fun anOpenPrepTaskIsLeftAsItIs() {
        val id = ea.addPrep(ev())
        a.tasks.edit(id, TaskEdit(title = "Read Tunde's deck"))
        ea.addPrep(ev())
        assertEquals("Read Tunde's deck", a.tasks.get(id)!!.title)
    }

    @Test
    fun aDeletedOrFinishedPrepTaskComesBack() {
        val id = ea.addPrep(ev())
        a.tasks.delete(id)
        assertNull(ea.marks().prepTasks["ev1"])
        assertEquals(id, ea.addPrep(ev()))
        assertEquals(Lifecycle.ACTIVE, a.tasks.get(id)!!.lifecycle)

        a.tasks.complete(id)
        assertTrue(ea.marks().prepTasks["ev1"]!!.isDone)
        ea.addPrep(ev())
        val back = a.tasks.get(id)!!
        assertEquals(Lifecycle.ACTIVE, back.lifecycle)
        assertNull(back.completedAtMs)
    }

    @Test
    fun hideAndShowSyncAndTheLastTapWins() {
        ea.hide("ev1")
        assertTrue(ea.marks().isHidden("ev1"))
        syncBoth()
        assertTrue(em.marks().isHidden("ev1"))
        assertEquals(listOf("ev2"), em.marks().visible(listOf(ev(), ev("ev2"))).map { it.id })

        // Offline on both: Android shows it, then the Mac hides it again later; the later tap wins everywhere.
        ea.show("ev1")
        world.clock.nowMs += min
        em.hide("ev1")
        em.show("ev1")
        world.clock.nowMs += min
        em.hide("ev1")
        syncBoth()
        assertTrue(ea.marks().isHidden("ev1"))
        assertTrue(em.marks().isHidden("ev1"))
        em.show("ev1")
        syncBoth()
        assertFalse(ea.marks().isHidden("ev1"))
    }

    @Test
    fun hidingTwiceWritesNothingNew() {
        ea.hide("ev1")
        val ops = a.replica.pendingPushCount()
        ea.hide("ev1")
        assertEquals(ops, a.replica.pendingPushCount())
        ea.show("ev2") // never hidden
        assertEquals(ops, a.replica.pendingPushCount())
    }

    @Test
    fun hiddenEventsLeaveTheDayButStayListedInTheAgenda() {
        val call = ev()
        val standup = ev("ev2", "Standup", at(tue6, 9, 30), at(tue6, 9, 45))
        val later = ev("ev3", "Dentist", at(tue6 + 4, 9), at(tue6 + 4, 10))
        val v = CalendarAgenda.build(emptyList(), listOf(call, standup, later), at(tue6, 10), cal, hidden = setOf("ev1", "ev3"))
        val today = v.sections[0]
        assertEquals(listOf("ev2"), (today.ended + today.rows).mapNotNull { it.event?.id })
        assertEquals(listOf("ev1"), today.hidden.map { it.id })
        assertEquals("1 hidden from your day", today.hiddenLabel)
        // A day with only a hidden event still shows (so it can be shown again), quietly.
        val sat = v.sections.first { it.firstDay == tue6 + 4 }
        assertEquals(AgendaKind.DAY, sat.kind)
        assertEquals("Nothing planned", sat.subtitle)
        assertNull(sat.emptyLine)
        assertEquals(listOf("ev3"), sat.hidden.map { it.id })
        assertEquals(0, v.weeks[0].days.first { it.epochDay == tue6 + 4 }.dots)
        assertEquals("1 event in the next 30 days", v.summary)
    }

    @Test
    fun theTimelineLeavesOutHiddenEvents() {
        ea.hide("ev1")
        val all = listOf(ev(), ev("ev2", "Standup", at(tue6, 11), at(tue6, 11, 15)))
        val today = TodayProjection.project(emptyList(), at(tue6, 10), CalendarAgenda.window(tue6, cal), ea.marks().visible(all), cal)
        assertEquals(listOf("ev2"), today.events.map { it.id })
    }

    @Test
    fun theDetailSaysWhatIsSet() {
        val e = ev()
        var d = EventDetails.build(e, at(tue6, 10), cal, ea.marks())
        assertFalse(d.hidden)
        assertNull(d.prepLine)
        assertTrue(d.canPrep)

        ea.addPrep(e)
        ea.hide("ev1")
        d = EventDetails.build(e, at(tue6, 10), cal, ea.marks())
        assertTrue(d.hidden)
        assertEquals("pev1", d.prepTaskId)
        assertEquals("Prep task at 13:30", d.prepLine)
        assertFalse(d.canPrep)

        // Tomorrow's event: the day is named.
        val tomorrow = ev("ev9", start = at(tue6 + 1, 9), end = at(tue6 + 1, 10))
        ea.addPrep(tomorrow)
        assertEquals("Prep task at Wed 7 Oct 08:30", EventDetails.build(tomorrow, at(tue6, 10), cal, ea.marks()).prepLine)

        a.tasks.complete("pev1")
        d = EventDetails.build(e, at(tue6, 10), cal, ea.marks())
        assertEquals("Prep task done", d.prepLine)
        assertTrue(d.canPrep)
        // An ended event can't be prepared for.
        assertFalse(EventDetails.build(e, at(tue6, 16), cal, ea.marks()).canPrep)
    }
}
