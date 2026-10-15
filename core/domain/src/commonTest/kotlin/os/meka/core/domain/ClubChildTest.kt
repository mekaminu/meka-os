package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fold review 2026-10-12 17:56, item 4: club training names the child, set once per repeating series. */
class ClubChildTest {
    @BeforeTest
    fun household() {
        // Rule 13, release 2: the children and clubs come from About you; these are invented.
        Household.useTyped("Max, U7\nFelix, U10", "OTFC Lindenbrook United, HRFC")
    }

    @AfterTest
    fun noHousehold() { Household.set(HouseholdNames.EMPTY) }

    private val world = SyncWorld()
    // Wednesday 14 October 2026, British Summer Time (UTC+1).
    private val cal = LocalCalendar.fixedOffset(3_600_000L)
    private val wed = CivilDate.toEpochDay(2026, 10, 14)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private fun actions(d: Device) = EventActions(d.replica, d.tasks, { world.clock.nowMs }, cal)

    init { world.clock.nowMs = at(wed, 8) }

    private fun ev(
        id: String, title: String, day: Long = wed, h: Int = 16, m: Int = 55, provider: String = "google",
        calendarName: String? = "Family", calendarTitle: String? = null, allDay: Boolean = false,
    ) = CalendarEvent(id, title, at(day, h, m), at(day, h + 1, m), allDay, null, provider, "meka@gmail.com", calendarName, calendarTitle = calendarTitle)

    private val training = ev("t1", "Training")
    private val nextWeek = ev("t2", "Training", day = wed + 7)

    @Test
    fun trainingWithNoAgeGroupAsksWhoseItIs() {
        assertTrue(ClubChildRules.asks(training))
        assertEquals("Max or Felix?", ClubChildRules.line(training, emptyMap(), cal))
        assertTrue(ClubChildRules.canSet(training))
        assertNull(ClubChildRules.setLine(training, emptyMap(), cal))
    }

    @Test
    fun theAgeGroupOrTheCalendarSaysItWithoutAsking() {
        val u7 = ev("a", "OTFC U7s training")
        assertEquals("Max", ClubChildRules.line(u7, emptyMap(), cal))
        assertFalse(ClubChildRules.canSet(u7))
        val byCalendar = ev("b", "Training", calendarName = "HRFC U10s")
        assertEquals("Felix", ClubChildRules.line(byCalendar, emptyMap(), cal))
        assertFalse(ClubChildRules.canSet(byCalendar))
    }

    @Test
    fun ordinaryEventsTheWorkCalendarAndAllDayDontAsk() {
        assertNull(ClubChildRules.line(ev("d", "Dentist"), emptyMap(), cal))
        assertNull(ClubChildRules.line(ev("o", "Compliance training", provider = "microsoft"), emptyMap(), cal))
        assertNull(ClubChildRules.line(ev("x", "Training", allDay = true), emptyMap(), cal))
        assertNull(ClubChildRules.line(ev("f", "Barça training", provider = "fixtures"), emptyMap(), cal))
    }

    @Test
    fun aSeriesIsCalendarTitleWeekdayAndTime() {
        assertEquals(ClubChildRules.seriesId(training, cal), ClubChildRules.seriesId(nextWeek, cal))
        assertEquals(ClubChildRules.seriesId(training, cal), ClubChildRules.seriesId(ev("t3", " training! "), cal))
        assertTrue(ClubChildRules.seriesId(training, cal) != ClubChildRules.seriesId(ev("t4", "Training", day = wed + 1), cal))
        assertTrue(ClubChildRules.seriesId(training, cal) != ClubChildRules.seriesId(ev("t5", "Training", h = 17), cal))
        assertTrue(ClubChildRules.seriesId(training, cal) != ClubChildRules.seriesId(ev("t6", "Training", calendarName = "Other"), cal))
    }

    @Test
    fun setOnceForTheSeriesSyncsAndEverySessionNamesTheChild() {
        assertNull(actions(fold).setClubChild(training, "Max"))
        fold.sync(); mac.sync()
        val marks = actions(mac).marks()
        assertEquals("Max", ClubChildRules.line(nextWeek, marks.clubChildren, cal))
        assertEquals("For Max · every Wed 16:55", ClubChildRules.setLine(nextWeek, marks.clubChildren, cal))
        assertEquals("Max or Felix?", ClubChildRules.line(ev("t9", "Training", day = wed + 1), marks.clubChildren, cal))
        // The pane offers the choice with Max picked; the clash asks for him by name.
        val d = EventDetails.build(nextWeek, world.clock.nowMs, cal, marks)
        assertEquals(listOf("Max", "Felix"), d.clubChildChoices)
        assertEquals("Max", d.clubChild)
        val clash = WorkClashRules.clash(nextWeek, WorkHours(WorkSchedule.DEFAULT).blocks(wed + 7, cal), cal, marks.clubChildren)!!
        assertEquals("Clashes with work · who's taking Max?", clash.mark)
    }

    @Test
    fun changingClearingAndRefusals() {
        val ea = actions(fold)
        ea.setClubChild(training, "Max")
        assertEquals("Max", ea.setClubChild(training, "Felix"))
        assertEquals("Felix", ea.marks().clubChildren.values.single())
        assertEquals("Felix", ea.setClubChild(training, null)) // Undo back to nobody
        assertTrue(ea.marks().clubChildren.isEmpty())
        assertNull(ea.setClubChild(training, "Jeanette")) // not a child: nothing written
        assertTrue(ea.marks().clubChildren.isEmpty())
        ea.setClubChild(ev("a", "OTFC U7s training"), "Felix") // the title says Max: nothing written
        assertTrue(ea.marks().clubChildren.isEmpty())
        assertEquals("Max · “Training” every Wed", ClubChildRules.savedLine(training, "Max", cal))
        assertEquals("Cleared who “Training” is for", ClubChildRules.savedLine(training, null, cal))
    }

    @Test
    fun theCalendarAgendaNamesTheChildFirstOnTheRow() {
        val ea = actions(fold)
        ea.setClubChild(training, "Felix")
        val view = CalendarAgenda.build(emptyList(), listOf(training.copy(location = "Lindenbrook Park")), world.clock.nowMs, cal, children = ea.marks().clubChildren)
        val row = view.sections.flatMap { it.rows }.single { it.event?.id == "t1" }
        assertEquals("Felix · Lindenbrook Park", row.detail)
        val unset = CalendarAgenda.build(emptyList(), listOf(training), world.clock.nowMs, cal)
        assertEquals("Max or Felix?", unset.sections.flatMap { it.rows }.single { it.event?.id == "t1" }.detail)
    }
}
