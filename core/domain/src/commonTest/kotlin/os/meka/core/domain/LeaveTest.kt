package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Annual leave planner, slice 1: stretches typed once, the allowance, the school holidays and leave as days off. */
class LeaveTest {
    private val hour = 3_600_000L
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(hour)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val sat = d(10, 10)
    private fun at(day: Long, h: Int) = cal.toEpochMs(day, h * 60)
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private fun leave(dev: Device) = Leave(dev.replica, { "lv${n++}" }, { world.clock.nowMs }, cal)
    private fun school(dev: Device) = School(dev.replica, { "sch${n++}" }, { world.clock.nowMs }, cal)
    private val holidays = HolidayCalendar(mapOf(d(12, 25) to "Christmas Day", d(12, 28) to "Boxing Day (substitute day)", d(1, 1, 2027) to "New Year’s Day"))
    private val work = WorkHours(WorkSchedule.DEFAULT, holidays)

    init { world.clock.nowMs = at(sat, 10) }

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    @Test
    fun aStretchIsReadFromOneLineAndLeaveAlreadyTakenNeedsItsYear() {
        val jan1 = d(1, 1)
        assertEquals(LeaveEntry("Annual leave", d(10, 27), d(10, 30)), LeaveRules.read("27–30 Oct", sat, jan1))
        assertEquals(LeaveEntry("Annual leave", d(11, 13), d(11, 13)), LeaveRules.read("Leave Fri 13 Nov", sat, jan1))
        assertEquals(LeaveEntry("Spain", d(12, 18), d(1, 4, 2027)), LeaveRules.read("Spain 18 Dec – 4 Jan", sat, jan1))
        // With no year a gone date is next year's; with this year's it is leave already taken.
        assertEquals(d(8, 3, 2027), LeaveRules.read("3–7 Aug", sat, jan1)?.startDay)
        assertEquals(LeaveEntry("Annual leave", d(8, 3), d(8, 7)), LeaveRules.read("3–7 Aug 2026", sat, jan1))
        // Before the leave year, no date, or a span too long to be right: refused.
        assertNull(LeaveRules.read("3–7 Aug 2025", sat, jan1))
        assertNull(LeaveRules.read("Spain", sat, jan1))
        assertNull(LeaveRules.read("1 Jul – 30 Sep", sat, jan1))
        // The school's own reading is unchanged by the shared date reader.
        assertEquals(SchoolEntry(SchoolKind.OFF, "Half term", "", d(10, 26), d(10, 30)), SchoolRules.read("Half term 26–30 Oct", sat))
        assertNull(SchoolRules.read("Trip 3 Feb 2026", sat))
    }

    @Test
    fun onlyWorkDaysCountAndTheSummaryKeepsTheYearsTally() {
        val lv = leave(fold)
        assertEquals(LeaveRules.EMPTY_LINE, lv.view(work, emptyList()).summary)
        assertEquals("Booked Annual leave · Mon 3 – Fri 7 Aug · 5 days", lv.add("3–7 Aug 2026", work)?.line)
        assertEquals("Booked Annual leave · Tue 27 – Fri 30 Oct · 4 days", lv.add("27–30 Oct", work)?.line)
        // Christmas Day, the substitute Boxing Day and New Year's Day cost nothing.
        assertEquals("Booked Spain · Fri 18 Dec – Mon 4 Jan · 9 days", lv.add("Spain 18 Dec – 4 Jan", work)?.line)
        // A weekend, or days already booked, write nothing.
        assertEquals(LeaveAdded("No work days in Sat 17 Oct, so no leave is needed", refused = true), lv.add("Sat 17 Oct", work))
        assertEquals(LeaveAdded("Already booked: Wed 28 Oct", refused = true), lv.add("28 Oct", work))
        assertNull(lv.add("whenever", work))
        assertEquals(3, lv.items().size)

        val v = lv.view(work, emptyList())
        // In the leave year Jan–Dec 2026: Aug 5 + Oct 4 + Dec 8 (Jan 4 is next year's).
        assertEquals("17 of 25 days booked · 8 days left", v.summary)
        assertEquals("Leave year Jan – Dec 2026 · 5 taken · 12 to come", v.yearLine)
        assertEquals(listOf("Annual leave", "Spain"), v.booked.map { it.title })
        assertEquals("Fri 18 Dec – Mon 4 Jan · 9 days", v.booked[1].line)
        assertEquals(listOf("Mon 3 – Fri 7 Aug · 5 days"), v.taken.map { it.line })
        assertEquals(17, v.daysBooked)

        // A smaller allowance goes over; a leave year from April counts Jan 4 too.
        assertTrue(lv.setSettings(15, 1))
        assertEquals("17 days booked · 2 over your 15", lv.view(work, emptyList()).summary)
        assertTrue(lv.setSettings(25, 4))
        val april = lv.view(work, emptyList())
        assertEquals("18 of 25 days booked · 7 days left", april.summary)
        assertEquals("Leave year Apr 2026 – Mar 2027 · 5 taken · 13 to come", april.yearLine)
        assertEquals("25 days a year · the leave year starts 1 Apr", april.allowanceLine)
        assertFalse(lv.setSettings(25, 4))
        assertEquals(LeaveSettings(60, 1), LeaveRules.settings(mapOf(LeaveSettingFields.ALLOWANCE to os.meka.core.sync.FieldValue.Int64(60))).copy())
    }

    @Test
    fun schoolHolidaysShowWhatIsBookedAndNudgeBeforeTheGoodWeeksGo() {
        val sc = school(fold)
        sc.add("Half term 26–30 Oct")
        sc.add("Christmas holidays 18 Dec – 4 Jan")
        sc.add("INSET Sat 24 Oct") // a Saturday: no work days, not shown
        val lv = leave(fold)
        val hs = lv.view(work, sc.items()).holidays
        assertEquals(listOf("Half term", "Christmas holidays"), hs.map { it.title })
        assertEquals("Mon 26 – Fri 30 Oct · 5 work days", hs[0].line)
        assertEquals("In 2 weeks · book before the good weeks go", hs[0].note)
        assertTrue(hs[0].nudge)
        assertEquals("Book 5 days", hs[0].bookLabel)
        // Ten weeks off: not lit yet.
        assertEquals("Not booked", hs[1].note)
        assertFalse(hs[1].nudge)

        lv.add("27 Oct", work)
        assertEquals("1 of 5 booked", lv.view(work, sc.items()).holidays[0].note)
        val booked = lv.bookHoliday(hs[0].id, sc.items(), work)
        assertEquals("Booked Half term · Mon 26 – Fri 30 Oct · 4 days", booked?.line)
        val after = lv.view(work, sc.items()).holidays[0]
        assertEquals("Booked", after.note)
        assertNull(after.bookLabel)
        assertNull(lv.bookHoliday(hs[0].id, sc.items(), work))
        assertEquals("5 of 25 days booked · 20 days left", lv.view(work, sc.items()).summary)

        // Both apps see it; a stretch removed on the Mac is gone on the Fold.
        sync()
        val onMac = leave(mac)
        assertEquals("5 of 25 days booked · 20 days left", onMac.view(work, school(mac).items()).summary)
        // Booking the half term took Mon 26 – Fri 30 Oct in one stretch; the 27th alone stays when it goes.
        assertTrue(onMac.remove(onMac.items().first { it.title == "Half term" }.id))
        sync()
        assertEquals("1 of 25 days booked · 24 days left", lv.view(work, sc.items()).summary)
        assertEquals("1 of 5 booked", lv.view(work, sc.items()).holidays[0].note)
    }

    @Test
    fun bookedLeaveIsADayOffForWorkModeButNotForTheSchool() {
        val lv = leave(fold)
        lv.add("27–30 Oct", work)
        val wm = WorkMode(fold.replica, { holidays }, leave = { lv.days() }) { world.clock.nowMs }
        val tue = d(10, 27)
        world.clock.nowMs = at(tue, 10)
        val state = wm.state(LocalClock(2, 10 * 60), tue)
        assertFalse(state.atWork)
        assertEquals("Off work · Annual leave · next shift Mon 09:00", state.line)
        val hours = wm.hours(LocalClock(2, 10 * 60), tue)
        assertFalse(hours.isWorkDay(tue))
        assertNull(hours.line(tue))
        assertTrue(hours.isWorkDay(d(11, 2)))
        // The school still has the boys in on Meka's leave, and asks no cover question for those days.
        assertFalse(hours.holidays.isHoliday(tue))
        val sc = school(fold)
        sc.add("Rex off 28 Oct")
        assertTrue(SchoolRules.isSchoolDay(sc.items(), tue, "Rex", hours.holidays))
        assertTrue(SchoolRules.covers(sc.items(), tue, hours).isEmpty())
        // A bank holiday keeps its own name under leave.
        assertEquals("Christmas Day", LeaveRules.withLeave(holidays, setOf(d(12, 25), d(12, 24))).title(d(12, 25)))
        assertEquals("Annual leave", LeaveRules.withLeave(holidays, setOf(d(12, 24))).title(d(12, 24)))
    }
}
