package os.meka.core.domain

import os.meka.core.testing.InventedRoute
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The companion, slice 8: trains on another day carry the office day and TfL's plans for that morning. */
class TrainDayAskTest {
    // Rule 13: the route comes from About you (an invented one here), never the code.
    @BeforeTest
    fun route() { Household.useRoute(InventedRoute.TYPED) }

    @AfterTest
    fun noHousehold() { Household.set(HouseholdNames.EMPTY) }

    private val day = CivilDate.DAY_MS
    private val min = 60_000L
    private val cal = LocalCalendar.UTC
    private val thu = 20734L // Thursday 8 October 2026
    private val fri = thu + 1
    private val sat = thu + 2
    private val work = WorkHours(WorkSchedule.DEFAULT)

    private fun ms(d: Long, minute: Int) = d * day + minute * min
    private fun planned(text: String, forDay: Long, read: Long) =
        LineStatusSnapshot(emptyList(), null, LineStatusCodec.decode(text), forDay, read)

    @Test
    fun theQuestionsItAnswers() {
        listOf("train or drive to the match on Saturday?", "are the trains OK tomorrow?", "any engineering works on Thursday?",
            "is the tube running next weekend", "rail strike on Friday?", "which station for the match?")
            .forEach { assertTrue(TrainDayAskRules.isAbout(it), it) }
        listOf("what's on Thursday?", "am I free this weekend?", "training on Saturday?", "explain how a heat pump works")
            .forEach { assertFalse(TrainDayAskRules.isAbout(it), it) }
    }

    @Test
    fun tomorrowsPlansAreCarriedOnceReadAndNoTimesAreInvented() {
        val now = ms(thu, 19 * 60)
        val s = planned("metro=10;north-rail=10;south-rail=4", fri, ms(thu, 18 * 60 + 30))
        val lines = TrainDayAskRules.askLines(AskDays(fri, sat), s, work, now, cal)
        assertEquals(listOf(
            "Trains on the days asked about · live times are only known for the next couple of hours",
            "Trains · his route ${InventedRoute.TYPED}",
            "Trains Fri 9 Oct · office day · leave 07:40 · work 09:00–17:30 · home about 18:50",
            "Trains Fri 9 Oct · TfL's plans for the morning (checked 18:30): South Rail planned closure; North Rail good service; Metro line good service",
            "Trains Sat 10 Oct · not a work day · no commute",
            "Trains Sat 10 Oct · TfL's plans for the morning not read yet (read from 17:00 the evening before)",
        ), lines)
        lines.forEach { assertTrue(it.length <= AskRules.MAX_LINE, it) }
    }

    @Test
    fun stalePlansTodayAndNoRouteCarryNothingOld() {
        // Plans read seven hours ago are too old to repeat.
        val stale = planned("south-rail=4", fri, ms(thu, 12 * 60))
        val lines = TrainDayAskRules.askLines(AskDays(fri, fri), stale, work, ms(thu, 19 * 60), cal)
        assertEquals("Trains Fri 9 Oct · TfL's plans for the morning not read yet (read from 17:00 the evening before)", lines.last())
        // Today and yesterday are left to today's live lines; a question about them alone carries none.
        assertTrue(TrainDayAskRules.askLines(AskDays(thu - 1, thu), stale, work, ms(thu, 9 * 60), cal).isEmpty())
        // At most three coming days.
        assertEquals(2 + 2 * TrainDayAskRules.MAX_DAYS, TrainDayAskRules.askLines(AskDays(fri, fri + 6), stale, work, ms(thu, 9 * 60), cal).size)
        // Without a route in About you there is nothing to say about his trains.
        Household.set(HouseholdNames.EMPTY)
        assertTrue(TrainDayAskRules.askLines(AskDays(fri, fri), stale, work, ms(thu, 9 * 60), cal).isEmpty())
    }
}
