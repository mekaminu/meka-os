package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Fold review 2026-10-12, polish 5 and 6: the Calendar's work label, Today's now line once, Up next's why, titles. */
class FoldPolishTest {
    private val hour = 3_600_000L
    private val min = 60_000L
    private val cal = LocalCalendar.fixedOffset(hour)
    private val thu8 = CivilDate.toEpochDay(2026, 10, 8)
    private fun at(day: Long, h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)
    private val day = CalendarAgenda.window(thu8, cal)

    private fun task(
        id: String, title: String = id, due: Long? = null, goal: String? = null, created: Long = at(thu8, 7),
        repeat: String? = null,
    ) = Task(id, title, null, Lifecycle.ACTIVE, due, null, null, 0, goal, null, created, null, false, recurrenceRule = repeat)

    private fun why(t: Task, now: Long = at(thu8, 10), goals: Map<String, String> = emptyMap()) =
        UpNextWhyRules.why(t, day, now, goals, cal::minuteOfDay, cal::epochDayOf)

    @Test
    fun titlesShowWithACapitalFirstLetter() {
        assertEquals("Renew passport", TaskTitleRules.display("renew passport"))
        assertEquals("Renew passport", TaskTitleRules.display("Renew passport"))
        // A brand written that way stays ("iPhone", "eBay").
        assertEquals("iPhone case", TaskTitleRules.display("iPhone case"))
        assertEquals("eBay return", TaskTitleRules.display("eBay return"))
        assertEquals("", TaskTitleRules.display(""))
        assertEquals("2 forms", TaskTitleRules.display("2 forms"))
        assertEquals("Éclair", TaskTitleRules.display("éclair"))
    }

    @Test
    fun upNextSaysWhyItIsNext() {
        // Made today, nothing pressing: no reason, the card says "Anytime today".
        assertNull(why(task("a")))
        assertEquals("Carried over from yesterday", why(task("a", created = at(thu8 - 1, 20))))
        assertEquals("Carried over 2 days", why(task("a", created = at(thu8 - 2, 9))))
        assertEquals("Open over a month", why(task("a", created = at(thu8 - 45, 9))))
        // A repeating chore isn't "carried over".
        assertNull(why(task("a", created = at(thu8 - 5, 9), repeat = "FREQ=DAILY")))
        assertEquals("On this week's goal · Carried over 2 days",
            why(task("a", goal = "g1", created = at(thu8 - 2, 9)), goals = mapOf("g1" to "Q4 plan")))
        // A goal not picked for this week doesn't count.
        assertNull(why(task("a", goal = "g2"), goals = mapOf("g1" to "Q4 plan")))
        assertEquals("Due 15:00", why(task("a", due = at(thu8, 15))))
        // At most two reasons, the most pressing first.
        assertEquals("Overdue · On this week's goal",
            why(task("a", due = at(thu8, 9), goal = "g1", created = at(thu8 - 3, 9)), goals = mapOf("g1" to "Q4 plan")))
    }

    @Test
    fun theUpNextCardCarriesTheWhyAndTheCapital() {
        val t = task("inv", "send the invoice", created = at(thu8 - 2, 9), goal = "g1")
        val today = TodayProjection.project(listOf(t), at(thu8, 10), day, emptyList(), cal)
        val v = CoverNowRules.upNext(today, at(thu8, 10), cal, mapOf("g1" to "Q4 plan"))!!
        assertEquals("Send the invoice", v.title)
        assertEquals("On this week's goal · Carried over 2 days", v.line)
        // The cover screen's card says the same.
        val cover = CoverNowRules.now(today, at(thu8, 10), cal, weekGoals = mapOf("g1" to "Q4 plan"))
        assertEquals("On this week's goal · Carried over 2 days", cover.line)
        // Made today: as before.
        val fresh = TodayProjection.project(listOf(task("x", "call mum")), at(thu8, 10), day, emptyList(), cal)
        assertEquals("Anytime today", CoverNowRules.upNext(fresh, at(thu8, 10), cal)!!.line)
    }

    @Test
    fun theCalendarSaysWorkBesideTheDayHeading() {
        val v = CalendarAgenda.build(emptyList(), emptyList(), at(thu8, 7, 57), cal, days = 3, work = WorkHours(WorkSchedule.WEEKDAYS))
        assertEquals("Work 09:00–17:30", v.sections[0].workLabel)
        assertEquals("Work 09:00–17:30", v.sections[1].workLabel)
        // At work today: lit, "until".
        val on = CalendarAgenda.build(emptyList(), emptyList(), at(thu8, 11), cal, days = 3, work = WorkHours(WorkSchedule.WEEKDAYS))
        assertEquals(true, on.sections[0].workRunning)
        assertEquals("Work until 17:30", on.sections[0].workLabel)
        assertEquals("Work 09:00–17:30", on.sections[1].workLabel)
        // No work hours: nothing.
        assertNull(CalendarAgenda.build(emptyList(), emptyList(), at(thu8, 11), cal, days = 3).sections[0].workLabel)
    }
}
