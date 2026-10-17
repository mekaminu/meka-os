package os.meka.core.domain

import os.meka.core.testing.InventedRoute
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Live trains (Needs Meka #16, 2026-10-17): the departure board on Today, in Ask and in Health. */
class TrainBoardTest {
    @BeforeTest
    fun route() { Household.set(Household.names.copy(route = RouteRules.parse(InventedRoute.TYPED), homeStation = "Oakfield")) }

    @AfterTest
    fun noHousehold() { Household.set(HouseholdNames.EMPTY) }

    // Friday 9 October 2026, British Summer Time (UTC+1).
    private val cal = LocalCalendar.fixedOffset(3_600_000L)
    private val fri = CivilDate.toEpochDay(2026, 10, 9)
    private fun at(h: Int, m: Int = 0) = cal.toEpochMs(fri, h * 60 + m)
    private val office = PlacesRules.officeWindow(WorkHours(WorkSchedule.DEFAULT), fri, cal)

    private val trains = listOf(
        TrainDeparture(7 * 60 + 12, 7 * 60 + 12, platform = "3", arrMin = 7 * 60 + 41),
        TrainDeparture(7 * 60 + 24, 7 * 60 + 29, platform = "4", arrMin = 7 * 60 + 53),
        TrainDeparture(7 * 60 + 42, null, cancelled = true, arrMin = 8 * 60 + 11),
        TrainDeparture(7 * 60 + 54, null, delayed = true),
    )

    private fun snap(board: TrainBoard, tfl: List<LineState> = emptyList(), checked: Long? = null) =
        LineStatusSnapshot(tfl, checked, board = board)

    private fun live(dir: String = TrainBoard.AM, checked: Long = at(7, 5), list: List<TrainDeparture> = trains) =
        TrainBoard(list, dir, checked, TrainBoard.OK, checked)

    @Test
    fun theCodecKeepsWhatTheBoardSaid() {
        val s = TrainBoardCodec.encode(trains)
        assertEquals("432,432,3,461;444,449,4,473;462,x,,491;474,d,,", s)
        assertEquals(trains, TrainBoardCodec.decode(s))
        // Junk from the network is skipped.
        assertEquals(listOf(TrainDeparture(600, 600), TrainDeparture(601, 601)), TrainBoardCodec.decode("600,600,,;9999,1,,;a,b,c,d;601,601,<b>,;1,2,3"))
        assertEquals(listOf(TrainDeparture(601, 601)), TrainBoardCodec.decode("601,601,<b>,"))
        assertTrue(TrainBoardCodec.decode(null).isEmpty())
        assertEquals(TrainBoardCodec.MAX, TrainBoardCodec.decode(List(9) { "60$it,60$it,," }.joinToString(";")).size)
    }

    @Test
    fun eachTrainInPlainWords() {
        assertEquals("07:12 on time", RouteRules.words(trains[0]))
        assertEquals("07:24 5 min late", RouteRules.words(trains[1]))
        assertEquals("07:42 cancelled", RouteRules.words(trains[2]))
        assertEquals("07:54 delayed", RouteRules.words(trains[3]))
        assertTrue(!RouteRules.bad(trains[0]))
        assertTrue(RouteRules.bad(trains[1]) && RouteRules.bad(trains[2]) && RouteRules.bad(trains[3]))
        assertTrue(!RouteRules.bad(TrainDeparture(600, 603)))
        // Early counts as on time; a time past midnight is minutes late, not a day early.
        assertEquals(0, TrainDeparture(600, 598).lateMin)
        assertEquals(4, TrainDeparture(1438, 2).lateMin)
    }

    @Test
    fun todayLeadsWithTheNextTrainsOnTheMorningCommute() {
        val line = RouteRules.todayLine(snap(live()), office, at(7, 10), cal)!!
        assertEquals("South Rail · 07:12 on time · 07:24 5 min late · 07:42 cancelled", line.text)
        assertTrue(line.lit)
        assertEquals("Trains: South Rail, 07:12 on time, 07:24 5 min late, 07:42 cancelled", line.spoken)
        // Gone trains drop off; all fine is not lit.
        val calm = RouteRules.todayLine(snap(live(list = listOf(TrainDeparture(7 * 60 + 30, 7 * 60 + 31), TrainDeparture(7 * 60 + 45, 7 * 60 + 45)))), office, at(7, 20), cal)!!
        assertEquals("South Rail · 07:30 1 min late · 07:45 on time", calm.text)
        assertTrue(!calm.lit)
        // With TfL's status: a leg that isn't good is added, and the fallback when the first line isn't good.
        val tfl = listOf(LineState(InventedRoute.FIRST, 6), LineState(InventedRoute.FALLBACK, 10), LineState(InventedRoute.ONWARD, 9))
        assertEquals("South Rail · 07:12 on time · 07:24 5 min late · 07:42 cancelled · South Rail severe delays · Metro line minor delays — North Rail to Riverside is running",
            RouteRules.todayLine(snap(live(), tfl, at(7)), office, at(7, 10), cal)!!.text)
    }

    @Test
    fun aStaleOrWrongWayBoardFallsBackToTflOrNothing() {
        val tfl = listOf(LineState(InventedRoute.FIRST, 10), LineState(InventedRoute.ONWARD, 10))
        // The evening's board in the morning, or one 25 minutes old: TfL's line as before.
        assertEquals("South Rail · Metro line · good service", RouteRules.todayLine(snap(live(dir = TrainBoard.PM), tfl, at(7)), office, at(7, 10), cal)!!.text)
        assertEquals("South Rail · Metro line · good service", RouteRules.todayLine(snap(live(checked = at(6, 35)), tfl, at(7)), office, at(7, 1), cal)!!.text)
        assertNull(RouteRules.todayLine(snap(live(checked = at(6, 35))), office, at(7, 1), cal))
        // A board whose state isn't ok isn't shown.
        assertNull(RouteRules.todayLine(snap(live().copy(state = TrainBoard.ERROR)), office, at(7, 10), cal))
        // Outside the commute: nothing.
        assertNull(RouteRules.todayLine(snap(live()), office, at(11), cal))
        // The evening's board on the way home.
        val pm = live(dir = TrainBoard.PM, checked = at(17, 5), list = listOf(TrainDeparture(17 * 60 + 20, 17 * 60 + 20)))
        assertEquals("South Rail · 17:20 on time", RouteRules.todayLine(snap(pm), office, at(17, 10), cal)!!.text)
    }

    @Test
    fun askHearsTheNextTrainsWithPlatformAndArrival() {
        val lines = RouteRules.askLines(snap(live()), at(7, 10), cal)
        assertEquals(listOf("next trains from Oakfield to Oakfield Junction (live departures, checked 07:05): 07:12 on time, platform 3, arrives 07:41; " +
            "07:24 5 min late, platform 4, arrives 07:53; 07:42 cancelled; 07:54 delayed"), lines)
        val pm = RouteRules.askLines(snap(live(dir = TrainBoard.PM, checked = at(17), list = listOf(TrainDeparture(17 * 60 + 20, 17 * 60 + 20)))), at(17, 5), cal)
        assertEquals(listOf("next trains from Oakfield Junction to Oakfield (live departures, checked 17:00): 17:20 on time"), pm)
        assertTrue(RouteRules.askLines(snap(live(checked = at(6))), at(7, 10), cal).isEmpty())
    }

    @Test
    fun healthSaysWhatTheBoardNeeds() {
        assertEquals("Live · checked 07:05", RouteRules.healthRow(live(), at(7, 10), cal)!!.line)
        assertEquals(HealthState.OK, RouteRules.healthRow(live(), at(7, 10), cal)!!.state)
        assertEquals(HealthState.WARN, RouteRules.healthRow(TrainBoard(state = TrainBoard.NO_STATION), at(7), cal)!!.state)
        assertEquals(HealthState.BAD, RouteRules.healthRow(TrainBoard(state = TrainBoard.KEY), at(7), cal)!!.state)
        assertEquals("Couldn't read live departures · last live 07:05", RouteRules.healthRow(live().copy(state = TrainBoard.ERROR), at(7, 10), cal)!!.line)
        assertEquals("Live departures start at the next check", RouteRules.healthRow(TrainBoard.EMPTY, at(7), cal)!!.line)
        Household.set(Household.names.copy(homeStation = null))
        assertTrue(RouteRules.healthRow(live(), at(7), cal)!!.line.startsWith("Line status from TfL · add your home station"))
        Household.set(HouseholdNames.EMPTY)
        assertNull(RouteRules.healthRow(live(), at(7), cal))
    }

    private val quiet = QuietHours.DEFAULT // 22:00–07:00

    @Test
    fun aLateOrCancelledTrainInTheWindowIsOneHeadsUpWithTheNextGoodOne() {
        // 07:10: the 07:24 is 5 min late (the first bad one in the next 45 min); the 07:12 before it is fine.
        val n = RouteRules.liveNotices(snap(live()), office, quiet, at(7, 10), cal).single()
        assertEquals("South Rail 07:24 5 min late", n.title)
        // No good train after it on the board (cancelled, delayed): the one before it.
        assertEquals("Or the 07:12 on time, platform 3, arrives 07:41 · also 07:42 cancelled, 07:54 delayed · live 07:05", n.text)
        assertEquals(NoticeSource.TRAINS, n.source)
        assertEquals(NoticeTier.HEADS_UP, n.tier)
        assertEquals(NoticeTarget.TODAY, n.target)
        assertEquals(at(7, 5), n.atMs)
        assertEquals(at(7, 30), n.expiresAtMs) // the 07:24 leaves at 07:29
        // RouteRules.notices carries it too (with no TfL status read).
        assertEquals(listOf(n.key), RouteRules.notices(snap(live()), office, quiet, at(7, 10), cal).map { it.key })

        // A cancelled train with a good one after it; a later read where it is still cancelled keeps the key (no second buzz).
        val list = listOf(
            TrainDeparture(7 * 60 + 30, null, cancelled = true),
            TrainDeparture(7 * 60 + 45, 7 * 60 + 45, platform = "2", arrMin = 8 * 60 + 14),
        )
        val c = RouteRules.liveNotices(snap(live(list = list)), office, quiet, at(7, 10), cal).single()
        assertEquals("South Rail 07:30 cancelled", c.title)
        assertEquals("Next good: 07:45 on time, platform 2, arrives 08:14 · live 07:05", c.text)
        val again = RouteRules.liveNotices(snap(live(checked = at(7, 15), list = list)), office, quiet, at(7, 16), cal).single()
        assertEquals(c.key, again.key)
        val gov = Governor.evaluate(listOf(c), NotificationSettings.DEFAULT, DeviceAlerts.ALL, GovernorState(), at(7, 10), cal)
        assertEquals(listOf(c.key), gov.post.map { it.key })
        assertTrue(Governor.evaluate(listOf(again), NotificationSettings.DEFAULT, DeviceAlerts.ALL, gov.state, at(7, 16), cal).post.isEmpty())

        // Nothing good on the board: the fallback when TfL says it runs, else check before you leave.
        val none = listOf(TrainDeparture(7 * 60 + 30, null, cancelled = true))
        assertEquals("No good train on the board yet · check before you leave · live 07:05",
            RouteRules.liveNotices(snap(live(list = none)), office, quiet, at(7, 10), cal).single().text)
        val tfl = listOf(LineState(InventedRoute.FIRST, 10), LineState(InventedRoute.FALLBACK, 10))
        assertEquals("North Rail to Riverside is running · live 07:05",
            RouteRules.liveNotices(snap(live(list = none), tfl, at(7)), office, quiet, at(7, 10), cal).single().text)

        // The evening: the board home, from 45 minutes before work ends.
        val pm = live(dir = TrainBoard.PM, checked = at(17, 5), list = listOf(TrainDeparture(17 * 60 + 40, 17 * 60 + 52), TrainDeparture(17 * 60 + 55, 17 * 60 + 55)))
        val e = RouteRules.liveNotices(snap(pm), office, quiet, at(17, 10), cal).single()
        assertEquals("South Rail 17:40 12 min late", e.title)
        assertEquals("Next good: 17:55 on time · live 17:05", e.text)
        assertTrue(e.key != c.key)
    }

    @Test
    fun allGoodFarOffStaleWrongWayOrOffCommuteTellsNothing() {
        val fine = listOf(TrainDeparture(7 * 60 + 20, 7 * 60 + 22), TrainDeparture(7 * 60 + 40, 7 * 60 + 40))
        assertTrue(RouteRules.liveNotices(snap(live(list = fine)), office, quiet, at(7, 10), cal).isEmpty())
        // A cancelled train more than 45 minutes away waits until it is in the window.
        val later = listOf(TrainDeparture(7 * 60 + 20, 7 * 60 + 20), TrainDeparture(8 * 60 + 10, null, cancelled = true))
        assertTrue(RouteRules.liveNotices(snap(live(list = later)), office, quiet, at(7, 10), cal).isEmpty())
        assertEquals("South Rail 08:10 cancelled", RouteRules.liveNotices(snap(live(checked = at(7, 25), list = later)), office, quiet, at(7, 30), cal).single().title)
        // Stale, the wrong way, not ok, outside the commute, or no route.
        assertTrue(RouteRules.liveNotices(snap(live(checked = at(6, 35))), office, quiet, at(7, 10), cal).isEmpty())
        assertTrue(RouteRules.liveNotices(snap(live(dir = TrainBoard.PM)), office, quiet, at(7, 10), cal).isEmpty())
        assertTrue(RouteRules.liveNotices(snap(live().copy(state = TrainBoard.ERROR)), office, quiet, at(7, 10), cal).isEmpty())
        assertTrue(RouteRules.liveNotices(snap(live(checked = at(11))), office, quiet, at(11, 5), cal).isEmpty())
        assertTrue(RouteRules.liveNotices(snap(live()), null, quiet, at(7, 10), cal).isEmpty())
        Household.set(HouseholdNames.EMPTY)
        assertTrue(RouteRules.liveNotices(snap(live()), office, quiet, at(7, 10), cal).isEmpty())
    }

    @Test
    fun quietHoursHoldItUntilTheyEndUnlessTheTrainHasGone() {
        val q = QuietHours(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60 + 15)
        // Read at 07:05 in quiet hours; the 07:24 leaves at 07:29, so it comes at 07:15.
        assertEquals(at(7, 15), RouteRules.liveNotices(snap(live()), office, q, at(7, 10), cal).single().atMs)
        // Quiet until 07:40: the train has gone by then, so nothing.
        val late = QuietHours(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60 + 40)
        assertTrue(RouteRules.liveNotices(snap(live()), office, late, at(7, 10), cal).isEmpty())
    }

    @Test
    fun aboutYouKeepsTheHomeStation() {
        assertEquals("King's Cross", AboutYouRules.clean(AboutField.HOME_STATION, "  King’s   Cross "))
        assertNull(AboutYouRules.clean(AboutField.HOME_STATION, "Platform 9¾"))
        assertEquals("", AboutYouRules.clean(AboutField.HOME_STATION, "  "))
        val a = AboutYouRules.read(mapOf(AboutYouFields.HOME_STATION to os.meka.core.sync.FieldValue.Text("Oakfield")))
        assertEquals("Oakfield", a.homeStation)
        assertEquals("Oakfield", HouseholdNames.of(a).homeStation)
        assertTrue(!a.isEmpty)
    }
}
