package os.meka.core.domain

import os.meka.core.testing.InventedRoute
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Live trains, slice 5: this morning's wake alarm anchored on the train. */
class TrainWakeTest {
    @BeforeTest
    fun route() {
        Household.set(Household.names.copy(route = RouteRules.parse(InventedRoute.TYPED), homeStation = "Oakfield",
            walkHomeMin = 10, walkWorkMin = 15))
    }

    @AfterTest
    fun noHousehold() { Household.set(HouseholdNames.EMPTY) }

    // Friday 9 October 2026, British Summer Time (UTC+1). Work 09:00 with a 1 h 20 commute: he usually leaves 07:40
    // and is at the train by 07:50.
    private val cal = LocalCalendar.fixedOffset(3_600_000L)
    private val fri = CivilDate.toEpochDay(2026, 10, 9)
    private fun at(h: Int, m: Int = 0) = cal.toEpochMs(fri, h * 60 + m)
    private val office = PlacesRules.officeWindow(WorkHours(WorkSchedule.DEFAULT), fri, cal)
    private val noQuiet = QuietHours(false, 22 * 60, 7 * 60)

    private fun alarm(minute: Int = 6 * 60 + 40, day: Long = fri, dismissed: Long? = null, snoozed: Long? = null) =
        Alarm(AlarmRules.wakeId(day), AlarmKind.WAKE, day, minute, cal.toEpochMs(day, minute), false, snoozed, dismissed, "Work at 09:00")

    private val good = listOf(
        TrainDeparture(7 * 60 + 30, 7 * 60 + 30),
        TrainDeparture(7 * 60 + 42, 7 * 60 + 42, platform = "3"),
        TrainDeparture(7 * 60 + 54, 7 * 60 + 54),
    )
    private val cancelled = good.toMutableList().also { it[1] = TrainDeparture(7 * 60 + 42, null, cancelled = true) }

    private fun snap(list: List<TrainDeparture>, checked: Long = at(6, 5), dir: String = TrainBoard.AM) =
        LineStatusSnapshot(emptyList(), null, board = TrainBoard(list, dir, checked, TrainBoard.OK, checked))

    @Test
    fun theRingingAlarmSaysTheTrainToLeaveFor() {
        val tw = TrainWakeRules.view(snap(good), alarm(), office, 60, at(6, 5), cal)!!
        assertEquals("Leave by 07:32 for the 07:42", tw.ringLine)
        assertNull(tw.trouble)
        assertNull(tw.wakeMinute) // 06:40 already gives an hour before 07:32
        val ring = AlarmRules.ring(alarm())
        assertEquals("Leave by 07:32 for the 07:42", TrainWakeRules.ring(ring, tw)!!.line)
        // Another alarm (a quick one) keeps its own line.
        val quick = ring.copy(id = "quick.1")
        assertEquals("Work at 09:00", TrainWakeRules.ring(quick, tw)!!.line)
        assertTrue(TrainWakeRules.notices(tw, snap(good), 6 * 60 + 40, noQuiet, at(6, 5), cal).isEmpty())
    }

    @Test
    fun hisTrainCancelledOffersAnEarlierWakeOnlyOnATap() {
        val tw = TrainWakeRules.view(snap(cancelled), alarm(), office, 60, at(6, 5), cal)!!
        assertEquals("07:42 cancelled", tw.trouble)
        assertEquals("Leave by 07:20 for the 07:30 · 07:42 cancelled", tw.ringLine)
        assertEquals(6 * 60 + 20, tw.wakeMinute)
        assertEquals("Wake alarm 06:20 · for the 07:30", TrainWakeRules.movedLine(tw))
        val n = TrainWakeRules.notices(tw, snap(cancelled), 6 * 60 + 40, noQuiet, at(6, 5), cal).single()
        assertEquals(TrainWakeRules.key(fri, 7 * 60 + 30), n.key)
        assertEquals("07:42 cancelled · leave by 07:20 for the 07:30", n.title)
        assertEquals("Wake at 06:20 instead of 06:40?", n.text)
        assertEquals(listOf(NoticeAction.WAKE_FOR_TRAIN), n.actions)
        assertEquals(at(6, 20), n.expiresAtMs)
        // Quiet hours to 07:00 run past 06:20: no heads-up (the ringing alarm still says it).
        assertTrue(TrainWakeRules.notices(tw, snap(cancelled), 6 * 60 + 40, QuietHours.DEFAULT, at(6, 5), cal).isEmpty())
        // An alarm already at 06:15: nothing to offer.
        assertNull(TrainWakeRules.view(snap(cancelled), alarm(6 * 60 + 15), office, 60, at(6, 5), cal)!!.wakeMinute)
        // Past 06:20 already: too late to offer it.
        assertNull(TrainWakeRules.view(snap(cancelled, checked = at(6, 20)), alarm(), office, 60, at(6, 22), cal)!!.wakeMinute)
        // A late (5+ min) train counts as trouble too.
        val late = good.toMutableList().also { it[1] = TrainDeparture(7 * 60 + 42, 7 * 60 + 50) }
        assertEquals("07:42 8 min late", TrainWakeRules.view(snap(late), alarm(), office, 60, at(6, 5), cal)!!.trouble)
    }

    @Test
    fun onlyThisMorningsWakeAlarmOnAnOfficeDayWithAFreshMorningBoard() {
        assertNull(TrainWakeRules.view(snap(good), null, office, 60, at(6, 5), cal))
        assertNull(TrainWakeRules.view(snap(good), alarm(), null, 60, at(6, 5), cal))
        assertNull(TrainWakeRules.view(snap(good), alarm(dismissed = at(6, 41)), office, 60, at(6, 45), cal))
        assertNull(TrainWakeRules.view(snap(good), alarm(day = fri + 1), office, 60, at(6, 5), cal))
        assertNull(TrainWakeRules.view(snap(good, dir = TrainBoard.PM), alarm(), office, 60, at(6, 5), cal))
        assertNull(TrainWakeRules.view(snap(good, checked = at(5, 0)), alarm(), office, 60, at(6, 5), cal))
        // Rung out unanswered: nothing.
        assertNull(TrainWakeRules.view(snap(good, checked = at(6, 50)), alarm(), office, 60, at(6, 51), cal))
        // Without the time to the train: nothing (never guessed).
        Household.set(Household.names.copy(walkHomeMin = null, walkWorkMin = null))
        assertNull(TrainWakeRules.view(snap(good), alarm(), office, 60, at(6, 5), cal))
    }
}
