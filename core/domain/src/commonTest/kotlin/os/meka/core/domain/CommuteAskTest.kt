package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The commute counts everywhere, slice 5: Ask and Talk know the commute and the wake alarm. */
class CommuteAskTest {
    private val day = CivilDate.DAY_MS
    private val min = 60_000L
    private val cal = LocalCalendar.UTC
    private val thu = 20734L // Thursday 8 October 2026 (the short day, 09:00–15:30)
    private val fri = thu + 1
    private val sat = thu + 2
    private val mon = thu + 4
    private val work = WorkHours(WorkSchedule.DEFAULT)

    private fun ms(d: Long, minute: Int) = d * day + minute * min
    private fun wake(d: Long, hours: WorkHours, set: Int? = null): WakeView {
        val alarm = set?.let { Alarm(AlarmRules.wakeId(d), AlarmKind.WAKE, d, it, ms(d, it), false, null, null, null) }
        return AlarmRules.view(ms(d - 1, 20 * 60), cal, d, alarm, emptyList(), hours, 60)
    }

    @Test
    fun theQuestionsItAnswers() {
        listOf(
            "When do I need to leave?", "what time should I leave tomorrow", "What time should I get up?",
            "when do I have to set off", "wake me up when?", "how long is my commute?", "When will I be home?",
            "what time do I get home tonight", "leave by when?",
        ).forEach { assertTrue(CommuteAskRules.isAbout(it), it) }
        listOf("How much leave have I got left?", "When's my next leave?", "What's on today?", "home insurance renewal")
            .forEach { assertFalse(CommuteAskRules.isAbout(it), it) }
    }

    @Test
    fun theDayLinesSayWhenToLeaveOrWhyThereIsNoCommute() {
        assertEquals("office day · leave 07:40 · work 09:00–17:30 · home about 18:50", CommuteAskRules.dayLine(work, fri, cal))
        assertEquals("office day · leave 07:40 · work 09:00–15:30 · home about 16:50", CommuteAskRules.dayLine(work, thu, cal))
        assertEquals("not a work day · no commute", CommuteAskRules.dayLine(work, sat, cal))
        assertEquals("work from home 09:00–17:30 · no commute", CommuteAskRules.dayLine(work.copy(homeDays = setOf(fri)), fri, cal))
        assertEquals("annual leave · no commute", CommuteAskRules.dayLine(work.copy(leaveDays = setOf(fri)), fri, cal))
        assertEquals("bank holiday (Holiday) · no commute",
            CommuteAskRules.dayLine(work.copy(holidays = HolidayCalendar(mapOf(fri to "Holiday"))), fri, cal))
    }

    @Test
    fun theAskLinesCarryTheSettingTodayTomorrowAndTheWakeAlarm() {
        val own = work.copy(commute = CommuteSetting(75, 90, chosen = true))
        val lines = CommuteAskRules.askLines(own, cal, ms(thu, 8 * 60), wake(fri, own))
        assertEquals(
            listOf(
                "Commute · 1 h 15 to work · 1 h 30 home · door to door, office days only",
                "Today · Thu 8 Oct · office day · leave 07:45 · work 09:00–15:30 · home about 17:00 · on the way in now",
                "Tomorrow · Fri 9 Oct · office day · leave 07:45 · work 09:00–17:30 · home about 19:00",
                "Wake alarm · Tomorrow · Fri 9 Oct · not set · suggested 06:45 (Work at 09:00 · 1 h 15 commute · 1 h to get ready)",
            ),
            lines,
        )
        // The default says so; Saturday morning looks on to Monday, the next office day.
        val weekend = CommuteAskRules.askLines(work, cal, ms(sat, 9 * 60), null)
        assertEquals("Commute · about 1 h 20 each way (the default; Meka hasn't set his own) · door to door, office days only", weekend[0])
        assertEquals("Today · Sat 10 Oct · not a work day · no commute", weekend[1])
        assertEquals("Next office day · Mon 12 Oct · office day · leave 07:40 · work 09:00–17:30 · home about 18:50", weekend[3])
        // A week of leave: none ahead.
        val away = work.copy(leaveDays = (thu..thu + 10).toSet())
        assertEquals("Next office day · none in the next 7 days", CommuteAskRules.askLines(away, cal, ms(thu, 9 * 60), null)[3])
        // Where today has got to.
        assertTrue(CommuteAskRules.askLines(work, cal, ms(fri, 7 * 60), null)[1].endsWith("· not left yet"))
        assertTrue(CommuteAskRules.askLines(work, cal, ms(fri, 12 * 60), null)[1].endsWith("· at work now"))
        assertTrue(CommuteAskRules.askLines(work, cal, ms(fri, 18 * 60), null)[1].endsWith("· on the way home now"))
        assertTrue(CommuteAskRules.askLines(work, cal, ms(fri, 21 * 60), null)[1].endsWith("· home now"))
    }

    @Test
    fun theWakeLineSaysSetOrSuggested() {
        assertEquals(
            "Wake alarm · Tomorrow · Fri 9 Oct · set for 06:30 · suggested 06:40 (Work at 09:00 · 1 h 20 commute · 1 h to get ready)",
            CommuteAskRules.wakeLine(wake(fri, work, set = 6 * 60 + 30)),
        )
        assertEquals("Wake alarm · Tomorrow · Sat 10 Oct · not set · nothing early, so no suggestion", CommuteAskRules.wakeLine(wake(sat, work)))
    }

    @Test
    fun theDeviceAnswersWithoutTheAi() {
        fun ans(q: String, at: Long, hours: WorkHours = work, w: WakeView? = null) = CommuteAskRules.localAnswer(q, hours, cal, at, w)
        assertNull(ans("What's on today?", ms(fri, 7 * 60)))
        assertEquals("Leave about 07:40 for work at 09:00; home about 18:50.", ans("When do I need to leave?", ms(fri, 7 * 60)))
        assertEquals("Work ends at 17:30, so you're home about 18:50.", ans("When will I be home?", ms(fri, 12 * 60)))
        assertEquals("You're home about 18:50.", ans("When will I be home?", ms(fri, 18 * 60)))
        // Thursday evening: tomorrow's.
        assertEquals("Leave about 07:40 tomorrow for work at 09:00; home about 18:50.", ans("when do I need to leave?", ms(thu, 20 * 60)))
        // Friday evening: Monday's.
        assertEquals("Leave about 07:40 on Mon 12 Oct for work at 09:00; home about 18:50.", ans("when do I need to leave?", ms(fri, 20 * 60)))
        assertEquals("No office days in the next 7 days, so no commute.",
            ans("when do I need to leave?", ms(fri, 20 * 60), work.copy(leaveDays = (sat..sat + 10).toSet())))
        // Getting up: the wake alarm.
        assertEquals("Get up at about 06:40 tomorrow: Work at 09:00 · 1 h 20 commute · 1 h to get ready.",
            ans("What time should I get up?", ms(thu, 20 * 60), w = wake(fri, work)))
        assertEquals("Your alarm is set for 06:30 tomorrow: Work at 09:00 · 1 h 20 commute · 1 h to get ready.",
            ans("what time should I get up", ms(thu, 20 * 60), w = wake(fri, work, set = 6 * 60 + 30)))
        assertEquals("Your alarm is set for 07:00 tomorrow, but 06:40 would give you time: Work at 09:00 · 1 h 20 commute · 1 h to get ready.",
            ans("wake up time?", ms(thu, 20 * 60), w = wake(fri, work, set = 7 * 60)))
        assertEquals("Nothing early tomorrow, so get up when you like.", ans("What time should I get up?", ms(fri, 20 * 60), w = wake(sat, work)))
    }
}
