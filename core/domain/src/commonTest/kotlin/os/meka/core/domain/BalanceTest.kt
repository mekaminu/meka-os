package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Balance view, slice 1: a week's time across work, family, health and self against his targets. */
class BalanceTest {
    @BeforeTest
    fun household() { Household.useTyped("Max, U7\nFelix, U10", "", "Jordan") }

    @AfterTest
    fun noHousehold() { Household.set(HouseholdNames.EMPTY) }

    private val cal = LocalCalendar.fixedOffset(3_600_000L)
    private val mon = CivilDate.toEpochDay(2026, 10, 12)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private var n = 0
    private fun ev(title: String, day: Long, h: Int, m: Int, endH: Int, endM: Int = 0, allDay: Boolean = false, provider: String = "google") =
        CalendarEvent("e${n++}", title, at(day, h, m), at(day, endH, endM), allDay, null, provider, "a@example.com", "Personal")
    private val work = WorkHours(WorkSchedule.DEFAULT, HolidayCalendar.NONE)
    private val childOf: (CalendarEvent) -> String? = { if (it.title.contains("U7")) "Max" else null }

    private val week = listOf(
        ev("Client call", mon, 18, 0, 18, 30),
        ev("Pick up parcel", mon, 12, 0, 13),
        ev("Dentist", mon + 1, 14, 0, 15),
        ev("Gym", mon + 2, 7, 0, 8),
        ev("Gym class", mon + 2, 7, 30, 8, 15),
        ev("Date night", mon + 4, 19, 0, 22),
        ev("Max U7s v Hilltown", mon + 5, 10, 0, 11),
        ev("Jordan's dinner", mon + 5, 18, 0, 20),
        ev("Cinema", mon + 6, 15, 0, 17, 30),
        ev("Bank holiday", mon + 6, 0, 0, 23, allDay = true),
        ev("Barça v Getafe", mon + 6, 20, 0, 22, provider = "fixtures"),
    )
    private val habits = listOf(
        "Gym" to listOf(false, false, true, false, true, false, false),
        "Read 20 pages" to listOf(true, true, false, false, false, false, false),
        "Floss" to listOf(true, true, true, true, true, true, true),
    )

    private fun row(v: BalanceView, a: BalanceArea) = v.rows.first { it.area == a }

    @Test
    fun aFinishedWeekIsCountedFromTheCalendarTheHoursAndTheHabits() {
        val v = BalanceRules.view(mon, at(mon + 7, 9), cal, work, week, habits, emptyMap(), childOf)
        assertFalse(v.isCurrent)
        // Work: 40 h 30 of hours, less the dentist inside them, plus the client call after them.
        val w = row(v, BalanceArea.WORK)
        assertEquals(40 * 60, w.minutes)
        assertEquals("40 h of your 40 h 30", w.line)
        assertEquals("Within your hours", w.status)
        assertFalse(w.lit)
        // Family: the U7 match, date night and dinner with his partner.
        val f = row(v, BalanceArea.FAMILY)
        assertEquals(6 * 60, f.minutes)
        assertEquals("6 h of 8 h", f.line)
        assertEquals("2 h short", f.status)
        assertEquals("Date night · Fri · 3 h", f.items.first())
        // Health: the two overlapping gym events once, the dentist, and Friday's gym tick (Wednesday's had an event).
        val h = row(v, BalanceArea.HEALTH)
        assertEquals(75 + 60 + 45, h.minutes)
        assertEquals("Met", h.status)
        assertTrue(h.lit)
        assertEquals(100, h.pct)
        // Self: the cinema and two reading ticks; Floss names no area.
        val s = row(v, BalanceArea.SELF)
        assertEquals(150 + 60, s.minutes)
        assertEquals("3 h 30 of 2 h", s.line)
        assertEquals("2 of 3 targets met", v.line)
        assertEquals("1 event not counted · no clear area", v.uncounted)
    }

    @Test
    fun theWeekInProgressCountsOnlyWhatHasHappened() {
        val v = BalanceRules.view(mon, at(mon + 2, 12), cal, work, week, habits, emptyMap(), childOf)
        assertTrue(v.isCurrent)
        // Mon and Tue 8 h 30 each, Wednesday's first 3 h; the dentist out, the client call in.
        assertEquals(17 * 60 + 3 * 60 - 60 + 30, row(v, BalanceArea.WORK).minutes)
        assertEquals("Within your hours so far", row(v, BalanceArea.WORK).status)
        assertEquals(0, row(v, BalanceArea.FAMILY).minutes)
        assertEquals("8 h to go", row(v, BalanceArea.FAMILY).status)
        assertEquals("0 of 3 targets met so far", v.line)
        // Nothing yet in a week that hasn't started.
        assertTrue(BalanceRules.view(mon + 7, at(mon + 2, 12), cal, work, week, habits, emptyMap(), childOf).rows.all { it.minutes == 0 })
    }

    @Test
    fun targetsAreHisAndSynced() {
        val world = SyncWorld()
        val fold = world.device("android")
        val mac = world.device("mac")
        val t = BalanceTargets(fold.replica)
        assertEquals("Work: 35 h a week · on both devices", t.set(BalanceArea.WORK, 35 * 60))
        assertNull(t.set(BalanceArea.WORK, 35 * 60))
        assertNull(t.set(BalanceArea.FAMILY, 9 * 60)) // not a choice
        assertEquals("Family: 10 h a week · on both devices", t.set(BalanceArea.FAMILY, 10 * 60))
        fold.sync(); mac.sync()
        val targets = BalanceTargets(mac.replica).targets()
        assertEquals(mapOf(BalanceArea.WORK to 35 * 60, BalanceArea.FAMILY to 10 * 60), targets)
        val v = BalanceRules.view(mon, at(mon + 7, 9), cal, work, week, habits, targets, childOf)
        val w = row(v, BalanceArea.WORK)
        assertEquals("40 h of 35 h", w.line)
        assertEquals("5 h over", w.status)
        assertTrue(w.lit)
        assertFalse(w.defaultTarget)
        assertEquals("2 of 3 targets met · work 5 h over", v.line)
        assertEquals("6 h of 10 h", row(v, BalanceArea.FAMILY).line)
        // Your own hours again.
        assertEquals("Work: your own hours · on both devices", BalanceTargets(mac.replica).set(BalanceArea.WORK, BalanceRules.YOUR_HOURS))
        assertNull(BalanceTargets(mac.replica).targets()[BalanceArea.WORK])
    }

    @Test
    fun areasAreReadFromWholeWords() {
        assertEquals(BalanceArea.HEALTH, BalanceRules.area(ev("Parkrun", mon, 9, 0, 10), childOf))
        assertEquals(BalanceArea.SELF, BalanceRules.area(ev("Drinks with the lads", mon, 19, 0, 21), childOf))
        assertEquals(BalanceArea.FAMILY, BalanceRules.area(ev("Swimming · Felix", mon, 17, 0, 18), childOf))
        assertEquals(BalanceArea.WORK, BalanceRules.area(ev("1:1 with Sam", mon, 9, 0, 10), childOf))
        assertNull(BalanceRules.area(ev("Runway planning", mon, 9, 0, 10), childOf))
        assertEquals(BalanceArea.HEALTH, BalanceRules.habitArea("Morning walk"))
        assertNull(BalanceRules.habitArea("Floss"))
        assertEquals("6 h 05", BalanceRules.hours(365))
        assertEquals("45 min", BalanceRules.hours(45))
    }
}
