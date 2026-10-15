package os.meka.core.domain

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fold review 2026-10-12, bug 4: an event during work hours is flagged on Today, the Calendar and in the brief. */
class WorkClashTest {
    @BeforeTest
    fun household() {
        // Rule 13, release 2: the children and clubs come from About you; these are invented.
        Household.useTyped("Max, U7\nFelix, U10", "OTFC Lindenbrook United, HRFC")
    }

    @AfterTest
    fun noHousehold() { Household.set(HouseholdNames.EMPTY) }

    // Wednesday 14 October 2026, British Summer Time (UTC+1); work 09:00–17:30.
    private val cal = LocalCalendar.fixedOffset(3_600_000L)
    private val wed = CivilDate.toEpochDay(2026, 10, 14)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private val hours = WorkHours(WorkSchedule.DEFAULT)
    private val blocks = hours.blocks(wed, cal)

    private fun ev(
        id: String, title: String, from: Long, to: Long, provider: String = "google", allDay: Boolean = false,
        calendarTitle: String? = null,
    ) = CalendarEvent(id, title, from, to, allDay, null, provider, null, null, calendarTitle = calendarTitle)

    private val training = ev("t", "Training", at(wed, 16, 55), at(wed, 17, 55))

    @Test
    fun trainingDuringWorkAsksWhosTaking() {
        val c = WorkClashRules.clash(training, blocks, cal)!!
        assertTrue(c.needsCover)
        assertNull(c.child)
        assertEquals("Clashes with work · who's taking?", c.mark)
        assertEquals("Training at 16:55 while you're at work — who's taking?", c.line)
    }

    @Test
    fun theChildIsNamedFromTheTitleOrTheCalendar() {
        val u7 = WorkClashRules.clash(ev("a", "OTFC U7s training", at(wed, 16, 55), at(wed, 17, 55)), blocks, cal)!!
        assertEquals("Max", u7.child)
        assertEquals("Clashes with work · who's taking Max?", u7.mark)
        val byCalendar = WorkClashRules.clash(training.copy(calendarTitle = "HRFC U10s"), blocks, cal)!!
        assertEquals("Felix", byCalendar.child)
        assertEquals("Training at 16:55 while you're at work — who's taking Felix?", byCalendar.line)
    }

    @Test
    fun anOrdinaryEventSaysWhenWorkEnds() {
        val c = WorkClashRules.clash(ev("d", "Dentist", at(wed, 10), at(wed, 10, 30)), blocks, cal)!!
        assertEquals("Clashes with work until 17:30", c.mark)
        assertEquals("Dentist at 10:00 while you're at work until 17:30", c.line)
        assertEquals("Heads up: Dentist at 10 o'clock, while you're at work until half past 5.", c.spoken("Dentist") { BriefScriptRules.clock(it) })
    }

    @Test
    fun nothingOutsideWorkOrFromTheWorkCalendarOrFixturesOrAllDay() {
        assertNull(WorkClashRules.clash(ev("e", "Training", at(wed, 17, 30), at(wed, 18, 30)), blocks, cal)) // starts as work ends
        assertNull(WorkClashRules.clash(ev("o", "Standup", at(wed, 9, 30), at(wed, 9, 45), provider = "microsoft"), blocks, cal))
        assertNull(WorkClashRules.clash(ev("f", "Barça v Getafe", at(wed, 15), at(wed, 17), provider = "fixtures"), blocks, cal))
        assertNull(WorkClashRules.clash(ev("x", "Holiday", at(wed, 0), at(wed + 1, 0), allDay = true), blocks, cal))
        assertNull(WorkClashRules.clash(training, hours.blocks(wed + 3, cal), cal)) // Saturday: no work
    }

    @Test
    fun aHomeDaySaysWorkingFromHome() {
        val home = WorkHours(WorkSchedule.DEFAULT, homeDays = setOf(wed)).blocks(wed, cal)
        val c = WorkClashRules.clash(training, home, cal)!!
        assertEquals("Clashes with work from home · who's taking?", c.mark)
        assertEquals("Training at 16:55 while you're working from home — who's taking?", c.line)
    }

    @Test
    fun todayMarksTheRowAndSaysTheFirstClashStillAhead() {
        val dentist = ev("d", "Dentist", at(wed, 10), at(wed, 10, 30))
        val day = CalendarAgenda.window(wed, cal)
        val morning = TimelineRules.build(emptyList(), emptyList(), listOf(dentist, training), at(wed, 8), day, cal, work = blocks)
        assertEquals("Clashes with work · who's taking?", morning.rows.first { it.id == "e-t" }.clash)
        assertEquals("Dentist at 10:00 while you're at work until 17:30", morning.clashLine)
        // Once the dentist is over, the training is the one said.
        val noon = TimelineRules.build(emptyList(), emptyList(), listOf(dentist, training), at(wed, 12), day, cal, work = blocks)
        assertEquals("Training at 16:55 while you're at work — who's taking?", noon.clashLine)
        // Without work (a day off), nothing is marked.
        val off = TimelineRules.build(emptyList(), emptyList(), listOf(training), at(wed, 8), day, cal)
        assertNull(off.clashLine)
        assertNull(off.rows.first { it.id == "e-t" }.clash)
    }

    @Test
    fun theCalendarMarksTheEventOnItsDay() {
        val v = CalendarAgenda.build(emptyList(), listOf(training), at(wed - 2, 8), cal, work = hours)
        val row = v.sections.first { it.firstDay == wed }.rows.first { it.id == "e-t" }
        assertEquals("Clashes with work · who's taking?", row.clash)
    }

    @Test
    fun theBriefSaysItAfterTheCalendar() {
        val c = WorkClashRules.clash(ev("a", "OTFC U7s training", at(wed, 16, 55), at(wed, 17, 55)), blocks, cal)!!
        val said = c.spoken(FootballRules.spokenFixture(c.title) ?: c.title) { BriefScriptRules.clock(it) }
        assertEquals("Heads up: Max's under-sevens training at 4:55, while you're at work. Who's taking Max?", said)
        val story = BriefStory(events = listOf(BriefEvent(c.title, 16 * 60 + 55, 17 * 60 + 55)), workEndMin = 17 * 60 + 30, workClashes = listOf(said))
        val lines = BriefScriptRules.calendar(MorningBriefView.EMPTY.copy(story = story), story)
        assertEquals(said, lines.last())
    }
}
