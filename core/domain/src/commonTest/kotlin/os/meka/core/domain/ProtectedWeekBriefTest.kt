package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Protected time, slice 5: the morning brief and the week ahead show protected time. */
class ProtectedWeekBriefTest {
    private val cal = LocalCalendar.UTC
    private fun d(m: Int, day: Int) = CivilDate.toEpochDay(2026, m, day)
    private val sat = d(10, 17)
    private val sun = d(10, 18)
    private val mon = d(10, 19)
    private val tue = d(10, 20)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private fun window(day: Long) = DayWindow(at(day, 0), at(day + 1, 0), 0)
    /** Mon–Fri 09:00–17:30, but Tuesday 12:00–20:00 (a late shift). */
    private val late = WorkSchedule(setOf(1, 2, 3, 4, 5), 9 * 60, 17 * 60 + 30, true, mapOf(2 to DayHours(12 * 60, 20 * 60)))
    private val both = ProtectedSetting(evenings = true, fromMin = 19 * 60, weekends = true)
    private val none = HolidayCalendar.NONE

    @Test
    fun eachDayHasItsMarkAndTheWeekItsLine() {
        assertEquals("Protected all day", ProtectedRules.dayMark(both, sat, late, none))
        assertEquals("Protected from 19:00", ProtectedRules.dayMark(both, mon, late, none))
        assertEquals("Protected from 20:00 · after the shift", ProtectedRules.dayMark(both, tue, late, none))
        assertEquals("Not protected · you opened it", ProtectedRules.dayMark(both.copy(openDay = mon), mon, late, none))
        assertNull(ProtectedRules.dayMark(both.copy(evenings = false), mon, late, none))
        assertNull(ProtectedRules.dayMark(null, sat, late, none))

        val week = (mon..mon + 6).toList()
        val line = ProtectedRules.weekLine(both, week, late, none)
        assertEquals("Protected · Sat, Sun all day · 5 evenings from 19:00", line)
        assertEquals("Protected time: Sat, Sun all day, and 5 evenings from 7 o'clock.", ProtectedRules.weekSpoken(line, both))
        assertEquals("Protected · Sat, Sun all day", ProtectedRules.weekLine(both.copy(evenings = false), week, late, none))
        assertNull(ProtectedRules.weekLine(both.copy(evenings = false, weekends = false), week, late, none))
    }

    @Test
    fun theWeekAheadCarriesThem() {
        val i = WeekAheadInputs(emptyList(), WorkHours(late), none, protectedSetting = both)
        val v = WeekAheadRules.view(i, at(sun, 18), cal, ::window)
        assertEquals(mon, v.days.first().epochDay)
        assertEquals("Protected from 19:00", v.days[0].protectedLine)
        assertEquals("Protected from 20:00 · after the shift", v.days[1].protectedLine)
        assertEquals("Protected all day", v.days[5].protectedLine)
        assertEquals("Protected · Sat, Sun all day · 5 evenings from 19:00", v.protectedLine)
        assertTrue("Protected time: Sat, Sun all day, and 5 evenings from 7 o'clock." in v.spoken, v.spoken)
        // Off: nothing changes.
        val off = WeekAheadRules.view(i.copy(protectedSetting = null), at(sun, 18), cal, ::window)
        assertNull(off.protectedLine)
        assertTrue(off.days.all { it.protectedLine == null })
        assertTrue("Protected" !in off.spoken)
    }

    @Test
    fun theBriefSaysTodaysProtectedTime() {
        assertEquals("Protected from 19:00 tonight", ProtectedRules.briefLine(both, mon, late, none))
        assertEquals("Your evening is protected from 7 o'clock.", ProtectedRules.briefSentence(both, mon, late, none))
        assertEquals("Your evening is protected from 8 o'clock.", ProtectedRules.briefSentence(both, tue, late, none))
        assertEquals("Protected all day · tasks wait for Mon 19 Oct", ProtectedRules.briefLine(both, sat, late, none))
        assertEquals("Protected all day · tasks wait for tomorrow", ProtectedRules.briefLine(both, sun, late, none))
        assertEquals("Today is protected, so no tasks are planned.", ProtectedRules.briefSentence(both, sat, late, none))
        assertEquals("Not protected tonight · you opened it", ProtectedRules.briefLine(both.copy(openDay = mon), mon, late, none))
        assertEquals("You opened today, so it isn't protected.", ProtectedRules.briefSentence(both.copy(openDay = sat), sat, late, none))
        assertNull(ProtectedRules.briefLine(null, mon, late, none))
        assertNull(ProtectedRules.briefSentence(both.copy(evenings = false), mon, late, none))

        // The spoken brief says it straight after the kind of day.
        val v = MorningBriefView.EMPTY.copy(dateLabel = "Sat 17 Oct",
            story = BriefStory(epochDay = sat, weekend = true, protectedTime = ProtectedRules.briefSentence(both, sat, late, none)))
        val s = BriefScriptRules.script(v)
        assertTrue(s.startsWith("Good morning, Meka. It's Saturday 17 October, no work today. Today is protected, so no tasks are planned."), s)
    }
}
