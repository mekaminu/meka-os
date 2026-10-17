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
