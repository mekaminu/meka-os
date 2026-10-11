package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Appointment nudges, slice 2a: Ask and Talk hear the appointments, Search finds them. */
class AskAppointmentsTest {
    private val hour = 3_600_000L
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(hour)
    private val sun = CivilDate.toEpochDay(2026, 10, 11) // Sun 11 Oct 2026
    private fun at(day: Long, h: Int) = cal.toEpochMs(day, h * 60)
    private var n = 0
    private val fold = world.device("android")
    private val appointments = Appointments(fold.replica, { "a${n++}" }, { world.clock.nowMs }, cal)

    init { world.clock.nowMs = at(sun, 10) }

    @Test
    fun askHearsEachAppointmentWithItsLastVisitAndWhenItIsDue() {
        assertEquals(emptyList(), AppointmentAskRules.askLines(appointments.view()))
        appointments.type("Dentist every 6 months, last 1 Apr")
        world.clock.nowMs += 1_000
        appointments.type("Haircut every 6 weeks, last 26 Sep")
        world.clock.nowMs += 1_000
        appointments.type("Optician every 2 years, last 10 Mar 2025, booked Tue 20 Oct 9:30")
        val lines = AppointmentAskRules.askLines(appointments.view())
        assertEquals(
            listOf(
                "Appointments · 3 appointments · 1 due",
                "Dentist · every 6 months · last went Wed 1 Apr · Overdue · was due Thu 1 Oct",
                "Optician · every 2 years · last went 10 Mar 2025 · Booked · Tue 20 Oct 09:30",
                "Haircut · every 6 weeks · last went Sat 26 Sep · Next due Sat 7 Nov",
            ),
            lines,
        )
        // They go with a question as `appointments` lines, Meka's own words, so never untrusted.
        val today = TodayProjection.project(emptyList(), world.clock.nowMs, DayWindow(at(sun, 0), at(sun + 1, 0)))
        val ctx = AskRules.context(today, world.clock.nowMs, cal, appointments = lines)
        assertEquals(lines, ctx.items.filter { it.kind == AskItemKind.APPOINTMENTS }.map { it.line })
        assertFalse(ctx.untrusted)
        assertEquals("appointments", AskItemKind.APPOINTMENTS.wire)
    }

    @Test
    fun aLongListSaysOnlyTheFirstAreBelow() {
        (1..AppointmentAskRules.ASK_ITEMS + 2).forEach {
            world.clock.nowMs += 1_000
            appointments.type("Physio $it every 3 months, last 1 Sep")
        }
        val lines = AppointmentAskRules.askLines(appointments.view())
        assertEquals(AppointmentAskRules.ASK_ITEMS + 1, lines.size)
        assertTrue(lines.first().endsWith(" · the first ${AppointmentAskRules.ASK_ITEMS} below"))
    }

    @Test
    fun searchFindsAppointmentsWithTheirStatusAndOpensTheList() {
        appointments.type("Dentist every 6 months, last 1 Apr")
        appointments.type("Hygienist every 6 months")
        val sources = SearchSources(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            appointments = appointments.items())
        val g = Search.run("dent", sources, world.clock.nowMs, cal).groups.single()
        assertEquals(SearchKind.APPOINTMENT, g.kind)
        assertEquals("Appointments", g.label)
        val hit = g.hits.single()
        assertEquals("Dentist", hit.title)
        assertEquals("Overdue · was due Thu 1 Oct", hit.detail)
        assertEquals(SearchTarget.APPOINTMENTS, hit.target)
        assertEquals("Appointment · Overdue · was due Thu 1 Oct", AskFieldRules.row(hit).line)
        // A removed one is gone from Search, as from the pane.
        appointments.remove(hit.id)
        val after = sources.copy(appointments = appointments.items())
        assertTrue(Search.run("dent", after, world.clock.nowMs, cal).groups.isEmpty())
    }
}
