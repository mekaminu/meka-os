package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** School rhythm, slice 1: one typed line per day off, date or weekly thing, and the week-ahead cover question. */
class SchoolTest {
    private val hour = 3_600_000L
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(hour)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val sat = d(10, 10)
    private fun at(day: Long, h: Int) = cal.toEpochMs(day, h * 60)
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private fun school(dev: Device) = School(dev.replica, { "sch${n++}" }, { world.clock.nowMs }, cal)
    private val sFold = school(fold)
    private val sMac = school(mac)
    private val work = WorkHours(WorkSchedule.DEFAULT)

    init { world.clock.nowMs = at(sat, 10) }

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    private fun read(text: String, today: Long = sat) = SchoolRules.read(text, today)

    @Test
    fun daysOffAreReadFromOneLineWithTheNextYearWhenNoneIsTyped() {
        assertEquals(SchoolEntry(SchoolKind.OFF, "INSET day", "", d(10, 27), d(10, 27)), read("INSET 27 Oct"))
        assertEquals(SchoolEntry(SchoolKind.OFF, "Half term", "", d(10, 26), d(10, 30)), read("Half term 26–30 Oct"))
        assertEquals(SchoolEntry(SchoolKind.OFF, "Half term", "", d(10, 26), d(10, 30)), read("half term Mon 26 Oct - Fri 30 Oct"))
        // Across new year; the start's year follows the end's.
        assertEquals(SchoolEntry(SchoolKind.OFF, "Christmas holidays", "", d(12, 18), d(1, 4, 2027)), read("Christmas holidays 18 Dec – 4 Jan"))
        // One boy named: his. "off" alone is "No school".
        assertEquals(SchoolEntry(SchoolKind.OFF, "No school", "Rex", d(11, 3), d(11, 3)), read("Rex off 3 Nov"))
        assertEquals(SchoolEntry(SchoolKind.OFF, "INSET day", "", d(10, 27), d(10, 27)), read("Rex and Logan INSET 27/10"))
        assertEquals(SchoolEntry(SchoolKind.OFF, "Training day", "", d(1, 4, 2027), d(1, 4, 2027)), read("Training day 4th January"))
        // A date already gone this year is next year's; one with a past year is refused.
        assertEquals(d(9, 1, 2027), read("Holiday 1/9")?.startDay)
        assertNull(read("Trip 3 Feb 2024"))
        // Typed while it's under way, a day off is this year's.
        assertEquals(d(10, 26), read("Half term 26–30 Oct", today = d(10, 28))?.startDay)
        // Too long to be a holiday, or no date at all.
        assertNull(read("Summer holidays 22 Jul – 31 Dec"))
        assertNull(read("Half term"))
        assertNull(read("  "))
    }

    @Test
    fun aDateWithATitleIsADayToRememberAndAWeekdayAloneIsWeekly() {
        assertEquals(SchoolEntry(SchoolKind.DAY, "Trip to the zoo", "Logan", d(11, 20), d(11, 20)), read("Logan's trip to the zoo 20 Nov"))
        assertEquals(SchoolEntry(SchoolKind.DAY, "Non-uniform day", "", d(11, 13), d(11, 13)), read("Non-uniform day Fri 13 Nov"))
        assertEquals(SchoolEntry(SchoolKind.WEEKLY, "PE", "Rex", sat, sat, 2), read("Rex PE Tue"))
        assertEquals(SchoolEntry(SchoolKind.WEEKLY, "Swimming", "Logan", sat, sat, 4), read("Logan swimming every Thursday"))
        assertEquals(SchoolEntry(SchoolKind.WEEKLY, "PE", "", sat, sat, 5), read("PE on Fridays"))
        // No school at weekends; a weekday with nothing else said isn't an item.
        assertNull(read("PE Saturday"))
        assertNull(read("Rex Tuesday"))
        assertEquals("Added PE · every Tuesday · Rex", SchoolRules.addedLine(read("Rex PE Tue")!!))
        assertEquals("Added Half term · Mon 26 – Fri 30 Oct · Rex and Logan", SchoolRules.addedLine(read("Half term 26–30 Oct")!!))
    }

    @Test
    fun theCoverQuestionShowsAWeekAheadOfAnOfficeDayOffOnly() {
        sFold.add("INSET 12 Oct")           // Monday, in 2 days: asked
        sFold.add("Half term 26–30 Oct")    // more than a week away: not yet
        sFold.add("Rex off 17 Oct")         // a Saturday: nothing to cover
        sFold.add("Logan off 14–16 Oct")    // Wed–Fri, Thursday worked from home
        val hours = work.copy(homeDays = setOf(d(10, 15)))
        val v = sFold.view(hours)
        assertEquals(listOf("INSET day", "No school"), v.covers.map { it.line.substringBefore(" ·") })
        val inset = v.covers[0]
        assertEquals("Rex and Logan are off Mon 12 Oct", inset.title)
        assertEquals("INSET day · in 2 days", inset.line)
        assertEquals("You're in the office that day. Who's covering?", inset.question)
        assertEquals("Mon 12 Oct shows as work from home on both apps", inset.detail)
        assertEquals(listOf(d(10, 12)), inset.days)
        val logan = v.covers[1]
        assertEquals("Logan is off Wed 14 – Fri 16 Oct", logan.title)
        assertEquals("You're in the office on Wed and Fri. Who's covering?", logan.question)
        assertEquals(listOf(d(10, 14), d(10, 16)), logan.days)
        assertEquals("Next day off: INSET day · Mon 12 Oct", v.summary)

        // A week before half term's first office day, it's asked too; bank holidays aren't office days.
        world.clock.nowMs = at(d(10, 19), 9)
        val later = sFold.view(work)
        assertEquals(listOf("Half term"), later.covers.map { it.line.substringBefore(" ·") })
        assertEquals("You're in the office on 5 of those days. Who's covering?", later.covers.single().question)
        assertTrue(sFold.view(WorkHours(WorkSchedule.DEFAULT, HolidayCalendar((26L..30L).associate { d(10, it.toInt()) to "x" })))
            .covers.isEmpty())
    }

    @Test
    fun answeringOnOneDeviceClearsTheQuestionOnBothAndUndoBringsItBack() {
        val (id, _) = sFold.add("INSET 12 Oct")!!
        sync()
        val cover = sMac.view(work).covers.single()
        assertTrue(sMac.answer(id, home = true, days = cover.days))
        assertEquals("Working from home Mon 12 Oct", SchoolRules.coverLine(cover, home = true, item = "INSET day"))
        sync()
        assertTrue(sFold.view(work).covers.isEmpty())
        assertEquals("You're working from home Mon 12 Oct", sFold.view(work).off.single().note)
        assertTrue(sFold.reopen(id))
        sync()
        assertEquals(1, sMac.view(work).covers.size)
        assertTrue(sMac.answer(id, home = false, days = cover.days))
        assertEquals("Covered", sMac.view(work).off.single().note)
        assertEquals("Covered · INSET day", SchoolRules.coverLine(cover, home = false, item = "INSET day"))
        // Removed on one device, gone on both.
        assertTrue(sMac.remove(id))
        assertFalse(sMac.remove(id))
        sync()
        assertTrue(sFold.view(work).isEmpty)
        assertEquals(SchoolRules.EMPTY_LINE, sFold.view(work).summary)
    }

    @Test
    fun weeklyThingsSayTheirNextSchoolDaySkippingDaysOff() {
        sFold.add("Rex PE Tue")
        sFold.add("Logan swimming Thursdays")
        sFold.add("Rex off 13 Oct")
        sFold.add("Non-uniform day 16 Oct")
        val v = sFold.view(work)
        assertEquals(listOf("PE", "Swimming"), v.weekly.map { it.title })
        assertEquals("Every Tuesday · Rex", v.weekly[0].line)
        assertEquals("Next: Tue 20 Oct", v.weekly[0].note)   // Rex is off on the 13th
        assertEquals("Next: Thu 15 Oct", v.weekly[1].note)
        assertEquals(listOf("Fri 16 Oct · Rex and Logan"), v.dates.map { it.line })
        assertEquals(listOf("Tue 13 Oct · Rex"), v.off.map { it.line })
        val items = sFold.items()
        assertTrue(SchoolRules.isSchoolDay(items, d(10, 13), "", HolidayCalendar.NONE)) // Logan still goes
        assertFalse(SchoolRules.isSchoolDay(items, d(10, 13), "Rex", HolidayCalendar.NONE))
        assertFalse(SchoolRules.isSchoolDay(items, d(10, 12), "Rex", HolidayCalendar(mapOf(d(10, 12) to "Bank holiday"))))
        // Past one-off dates and days off leave the pane.
        world.clock.nowMs = at(d(10, 17), 9)
        val later = sFold.view(work)
        assertTrue(later.dates.isEmpty() && later.off.isEmpty())
        assertEquals(SchoolRules.NOTHING_OFF, later.summary)
        assertEquals("Next: Tue 20 Oct", later.weekly[0].note)
    }
}
