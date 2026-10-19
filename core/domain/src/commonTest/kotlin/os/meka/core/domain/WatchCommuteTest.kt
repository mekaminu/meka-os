package os.meka.core.domain

import os.meka.core.testing.InventedRoute
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The commute counts everywhere, completeness: when to leave on the Galaxy Watch. Invented route and stations (rule 13). */
class WatchCommuteTest {
    @BeforeTest
    fun route() {
        Household.set(Household.names.copy(route = RouteRules.parse(InventedRoute.TYPED), homeStation = "Oakfield",
            walkHomeMin = 10, walkWorkMin = 15))
    }

    @AfterTest
    fun noHousehold() { Household.set(HouseholdNames.EMPTY) }

    // Friday 9 October 2026 (BST). Work 09:00–17:30 with a 1 h 20 commute each way: he usually leaves home at 07:40.
    private val cal = LocalCalendar.fixedOffset(3_600_000L)
    private val fri = CivilDate.toEpochDay(2026, 10, 9)
    private fun at(h: Int, m: Int = 0) = cal.toEpochMs(fri, h * 60 + m)
    private val hours = WorkHours(WorkSchedule.DEFAULT)
    private val office = PlacesRules.officeWindow(hours, fri, cal)
    private val bands = CommuteRules.bands(hours, fri, cal)

    private val morning = listOf(
        TrainDeparture(7 * 60 + 30, null, cancelled = true, arrMin = 8 * 60 + 2),
        TrainDeparture(7 * 60 + 42, 7 * 60 + 42, platform = "3", arrMin = 8 * 60 + 14),
        TrainDeparture(7 * 60 + 54, 7 * 60 + 54, arrMin = 8 * 60 + 26),
    )

    private fun snap(nowMs: Long, list: List<TrainDeparture> = morning) =
        LineStatusSnapshot(emptyList(), null, board = TrainBoard(list, TrainBoard.AM, nowMs, TrainBoard.OK, nowMs))

    private fun view(nowMs: Long, live: Boolean = false, ride: TrainRide? = null): WatchCommute? {
        val s = snap(nowMs)
        val commute = RouteRules.commute(office, nowMs, cal)
        val leave = if (live && commute != null) RouteRules.liveBoard(s, commute, nowMs)?.let { RouteRules.leaveBy(it, commute, office!!, nowMs, cal) } else null
        val route = if (live) RouteRules.todayLine(s, office, nowMs, cal) else null
        return WatchCommuteRules.view(office, bands, leave, route, ride, nowMs, cal)
    }

    @Test
    fun withTheLiveBoardItSaysTheTrainToLeaveForAndWhatsWrong() {
        val v = view(at(7, 20), live = true)!!
        assertEquals("LEAVE BY 07:32 · IN 12 MIN", v.label)
        assertEquals("For the 07:42 · on time", v.line)
        // The cancelled 07:30 lights it; the line's name and the leave-by (already in the row) are left out.
        assertTrue(v.lit)
        assertTrue(v.note!!.startsWith("07:30 cancelled"), v.note)
        assertFalse(v.note!!.contains("leave by"))
        assertEquals("Leave by 07:32 · the 07:42", v.short)
        assertTrue(v.spoken.startsWith("Leave by 07:32 for the 07:42, on time."), v.spoken)
    }

    @Test
    fun withoutLiveTrainsItSaysWhenTheCommuteStarts() {
        val early = view(at(6, 30))!!
        assertEquals("LEAVE ABOUT 07:40", early.label)
        assertEquals("Work at 09:00 · 1 h 20 commute", early.line)
        assertFalse(early.lit)
        assertNull(early.note)
        assertEquals("Leave about 07:40", early.short)
        assertEquals("LEAVE ABOUT 07:40 · IN 40 MIN", view(at(7))!!.label)
        val soon = view(at(7, 35))!!
        assertEquals("LEAVE ABOUT 07:40 · IN 5 MIN", soon.label)
        assertTrue(soon.lit)
        val onWay = view(at(8))!!
        assertEquals("AT WORK ABOUT 09:00", onWay.label)
        assertEquals("On your way · 1 h 20 commute", onWay.line)
        assertFalse(onWay.lit)
    }

    @Test
    fun onTheTrainItCountsDownToTheChange() {
        val v = view(at(8), ride = TrainRide(7 * 60 + 42, 14, "Oakfield Junction", true))!!
        assertEquals("ON THE 07:42", v.label)
        assertEquals("14 min to Oakfield Junction", v.line)
        assertEquals("On the 07:42 · 14 min to Oakfield Junction", v.short)
    }

    @Test
    fun theEveningSaysWhenHesHomeAndNothingShowsOutsideTheCommute() {
        val v = view(at(17))!!
        assertEquals("HOME ABOUT 18:50", v.label)
        assertEquals("Leave work at 17:30", v.line)
        assertEquals("On your way home", view(at(18))!!.line)
        assertNull(view(at(12)))
        assertNull(view(at(5, 30)))
        // A work-from-home day (no office window): nothing.
        assertNull(WatchCommuteRules.view(null, bands, null, null, null, at(7), cal))
    }

    @Test
    fun theTileCarriesItAndTheInboxGivesWay() {
        val c = view(at(7, 35))!!
        val home = WatchHomeView("YOU'RE CLEAR", false, "Nothing else today", null, emptyList(), null,
            WatchHomeRules.fast(FastingView.EMPTY, at(7, 35)), WatchHomeRules.inbox(3, null), commute = c)
        val tile = WatchTileRules.tile(home)
        assertEquals("Leave about 07:40", tile.commuteLine)
        assertTrue(tile.commuteLit)
        assertNull(tile.inboxLine)
        assertEquals(60_000L - (at(7, 35) % 60_000L), WatchTileRules.refreshAfterMs(home, at(7, 35)))
        assertTrue(WatchQuietRules.urgent(home))
    }
}
