package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Date night, slice 1: the fortnightly evening, kept clear by the planner, skippable, and Today's line on the day. */
class DateNightTest {
    private val hour = 3_600_000L
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(hour)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val sat = d(10, 10)
    private val fri16 = d(10, 16)
    private fun at(day: Long, h: Int, min: Int = 0) = cal.toEpochMs(day, h * 60 + min)
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private fun nights(dev: Device) = DateNight(dev.replica, { world.clock.nowMs }, cal)
    private val nFold = nights(fold)
    private val nMac = nights(mac)

    init { world.clock.nowMs = at(sat, 10) }

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    @Test
    fun everyOtherFridayIsChosenOnceAndItsFortnightCanMove() {
        assertEquals(DateNightRules.OFF_LINE, DateNightRules.view(nFold.setting(), sat).summary)
        assertEquals(fri16, DateNightRules.firstNight(5, sat))
        assertEquals(fri16 + 7, DateNightRules.firstNight(5, sat, nextWeek = true))
        assertFalse(nFold.set(5, 19 * 60, sat)) // not a Friday
        assertFalse(nFold.set(5, 19 * 60, fri16 - 7)) // gone by
        assertTrue(nFold.set(5, 19 * 60, fri16))
        assertFalse(nFold.set(5, 19 * 60, fri16 + 14)) // the same nights

        val v = DateNightRules.view(nFold.setting(), sat)
        assertTrue(v.on)
        assertEquals("Every other Friday from 19:00 · next Fri 16 Oct", v.summary)
        assertEquals(listOf(fri16, fri16 + 14, fri16 + 28, fri16 + 42), v.nights.map { it.day })
        assertEquals("19:00 · kept clear", v.nights.first().line)
        assertEquals(listOf("Fri 16 Oct" to true, "Fri 23 Oct" to false), v.starts.map { it.label to it.chosen })

        // The other fortnight, and a later start, from the Mac.
        sync()
        assertTrue(nMac.set(5, 19 * 60 + 30, fri16 + 7))
        sync()
        assertEquals("Every other Friday from 19:30 · next Fri 23 Oct", DateNightRules.view(nFold.setting(), sat).summary)
        assertFalse(DateNightRules.kept(nFold.setting(), fri16))
        assertTrue(DateNightRules.kept(nFold.setting(), fri16 + 21))

        // On the day it says tonight; turned off, the evenings are free and the choice is remembered for the chips.
        assertEquals("Every other Friday from 19:30 · tonight", DateNightRules.view(nFold.setting(), fri16 + 7).summary)
        assertTrue(nFold.off())
        assertFalse(nFold.off())
        val off = DateNightRules.view(nFold.setting(), sat)
        assertEquals(DateNightRules.OFF_LINE, off.summary)
        assertEquals(5, off.weekday)
        assertEquals(19 * 60 + 30, off.startMin)
        assertNull(DateNightRules.slot(nFold.setting(), fri16 + 7, cal))
    }

    @Test
    fun aSkippedNightIsFreeToPlanOnBothDevicesAndCanBeKeptAgain() {
        nFold.set(5, 19 * 60, fri16)
        sync()
        assertTrue(nMac.skip(fri16, true))
        assertFalse(nMac.skip(fri16, true))
        assertFalse(nMac.skip(fri16 + 7, true)) // not a date night
        assertEquals("Skipped Fri 16 Oct · the evening is free to plan", DateNightRules.skipLine(fri16, sat, true))
        sync()
        val v = DateNightRules.view(nFold.setting(), sat)
        assertEquals("Every other Friday from 19:00 · next Fri 30 Oct", v.summary)
        assertTrue(v.nights.first().skipped)
        assertEquals("Skipped · the evening is free to plan", v.nights.first().line)
        assertNull(DateNightRules.slot(nFold.setting(), fri16, cal))
        assertNull(DateNightRules.todayLine(nFold.setting(), fri16, 13 * 60))

        assertTrue(nFold.skip(fri16, false))
        assertEquals("Fri 16 Oct is kept clear again", DateNightRules.skipLine(fri16, sat, false))
        assertEquals(DayPlanner.Slot(at(fri16, 19), at(fri16, 23)), DateNightRules.slot(nFold.setting(), fri16, cal))
    }

    @Test
    fun theEveningIsKeptClearInThePlannerAndTodaySaysSo() {
        nFold.set(5, 21 * 60, fri16)
        // A late start is kept until midnight, not past it.
        assertEquals(DayPlanner.Slot(at(fri16, 21), at(fri16 + 1, 0)), DateNightRules.slot(nFold.setting(), fri16, cal))
        nFold.set(5, 19 * 60, fri16)
        val s = nFold.setting()

        val now = at(fri16, 17)
        val day = DayWindow(at(fri16, 0), at(fri16 + 1, 0), hour)
        fun task(id: String, minutes: Int) = Task(id, id, null, Lifecycle.ACTIVE, null, null, minutes, 0, null, null, 0, null, false)
        val plan = DayPlanner.plan(
            listOf(task("Short", 60), task("Long", 180)), emptyList(), now, day,
            meals = listOfNotNull(DateNightRules.block(s, fri16, cal)),
        )
        assertEquals(listOf("Short"), plan.placements.map { it.task.title })
        assertEquals(at(fri16, 17), plan.placements.single().startMs)
        assertEquals(listOf("Long"), plan.unplaced.map { it.title })
        assertEquals(listOf(DayPlanner.MealBlock("Date night", at(fri16, 19), at(fri16, 23))), plan.meals)
        assertEquals("◐ Date night is kept clear. Nothing is planned over it.", plan.keptLine)
        assertEquals(
            "◐ Kept free for your fast's meals and date night. Nothing is planned over them.",
            plan.copy(meals = plan.meals + DayPlanner.MealBlock("Break your fast", 0, 1)).keptLine,
        )
        assertNull(plan.copy(meals = emptyList()).keptLine)

        assertNull(DateNightRules.todayLine(s, fri16, 11 * 60 + 59))
        assertEquals("Date night tonight from 19:00", DateNightRules.todayLine(s, fri16, 12 * 60)?.text)
        assertEquals("Date night tonight", DateNightRules.todayLine(s, fri16, 20 * 60)?.text)
        assertNull(DateNightRules.todayLine(s, fri16, 23 * 60))
        assertNull(DateNightRules.todayLine(s, fri16 + 7, 13 * 60))
    }
}
