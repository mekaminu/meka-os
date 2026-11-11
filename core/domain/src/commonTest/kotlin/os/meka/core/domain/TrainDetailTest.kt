package os.meka.core.domain

import os.meka.core.testing.InventedRoute
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Today regression 2 (Meka 2026-11-11 07:30): the Trains tile stays in Also today and its tap shows the details. */
class TrainDetailTest {
    @BeforeTest
    fun route() {
        Household.set(Household.names.copy(route = RouteRules.parse(InventedRoute.TYPED), homeStation = "Oakfield", walkHomeMin = 10, walkWorkMin = 15))
    }

    @AfterTest
    fun noHousehold() { Household.set(HouseholdNames.EMPTY) }

    private val cal = LocalCalendar.fixedOffset(3_600_000L)
    private val fri = CivilDate.toEpochDay(2026, 10, 9)
    private fun at(h: Int, m: Int = 0) = cal.toEpochMs(fri, h * 60 + m)
    private val office = PlacesRules.officeWindow(WorkHours(WorkSchedule.DEFAULT), fri, cal)

    private val trains = listOf(
        TrainDeparture(7 * 60 + 22, 7 * 60 + 22, platform = "3", arrMin = 7 * 60 + 51),
        TrainDeparture(7 * 60 + 34, 7 * 60 + 39, platform = "4", arrMin = 8 * 60 + 3),
        TrainDeparture(7 * 60 + 42, null, cancelled = true, arrMin = 8 * 60 + 11),
        TrainDeparture(7 * 60 + 54, null, delayed = true),
    )
    private val tfl = listOf(LineState(InventedRoute.FIRST, 9), LineState(InventedRoute.FALLBACK, 10), LineState(InventedRoute.ONWARD, 10))

    private fun board(dir: String, list: List<TrainDeparture> = trains, fallback: List<TrainDeparture> = emptyList(), checked: Long = at(7, 5)) =
        TrainBoard(list, dir, checked, TrainBoard.OK, checked, fallback)

    @Test
    fun theMorningDetailsHaveEveryTrainTheChangeAndTheFallback() {
        val fb = listOf(TrainDeparture(7 * 60 + 25, 7 * 60 + 25, platform = "1", arrMin = 8 * 60 + 2))
        val s = LineStatusSnapshot(tfl, at(7), board = board(TrainBoard.AM, fallback = fb))
        val d = RouteRules.detail(s, office, at(7, 10), cal)!!
        assertEquals("South Rail · Oakfield to Oakfield Junction", d.title)
        assertEquals("minor delays", d.status)
        assertEquals(listOf("07:22 on time", "07:34 5 min late", "07:42 cancelled", "07:54 delayed"), d.rows.map { "${it.time} ${it.status}" })
        assertEquals("platform 3 · arrives 07:51", d.rows[0].meta)
        assertEquals("", d.rows[3].meta)
        assertNull(d.rows[2].arrives)
        assertEquals(listOf(false, true, true, true), d.rows.map { it.bad })
        assertEquals("Change at Oakfield Junction for Metro line · good service", d.change)
        assertEquals("Or North Rail to Riverside · good service", d.fallback)
        assertEquals(listOf("07:25 on time"), d.fallbackRows.map { "${it.time} ${it.status}" })
        assertEquals("Live · checked 07:05", d.checked)
        assertTrue(d.lit)
        assertNull(d.noTrains)
        assertTrue(d.spoken.startsWith("South Rail, Oakfield to Oakfield Junction, minor delays."), d.spoken)
        assertTrue(d.spoken.contains("07:34 5 min late, platform 4, arrives 08:03."), d.spoken)
        // Shown whenever the line is, and never outside the commute.
        assertNull(RouteRules.detail(s, office, at(5, 50), cal))
    }

    @Test
    fun theEveningDetailsGoTheOtherWay() {
        val pm = listOf(TrainDeparture(17 * 60 + 52, 17 * 60 + 52, platform = "2", arrMin = 18 * 60 + 20))
        val s = LineStatusSnapshot(tfl.map { it.copy(severity = 10) }, at(17, 30), board = board(TrainBoard.PM, pm, checked = at(17, 25)))
        val d = RouteRules.detail(s, office, at(17, 30), cal)!!
        assertEquals("South Rail · Oakfield Junction to Oakfield", d.title)
        assertEquals("Start on Metro line to Oakfield Junction · good service", d.change)
        assertEquals("Or North Rail from Riverside · good service", d.fallback)
        assertEquals("17:52", d.rows.single().time)
        assertTrue(d.leaveBy!!.startsWith("Leave work by"), d.leaveBy)
        assertFalse(d.lit)
    }

    @Test
    fun withoutLiveTimesItSaysWhyAndKeepsTfLsWords() {
        val s = LineStatusSnapshot(tfl, at(7), board = TrainBoard(state = TrainBoard.KEY))
        val d = RouteRules.detail(s, office, at(7, 10), cal)!!
        assertTrue(d.rows.isEmpty())
        assertEquals("No live times · rail key refused", d.noTrains)
        assertEquals("TfL · checked 07:00", d.checked)
        assertEquals("Change at Oakfield Junction for Metro line · good service", d.change)
        Household.set(Household.names.copy(homeStation = null))
        assertEquals(RouteRules.NO_STATION, RouteRules.detail(s, office, at(7, 10), cal)!!.noTrains)
    }

    @Test
    fun theTileUnfoldsTheDetailsAndSaysSo() {
        val line = RouteLine("South Rail · good service", lit = false, spoken = "Trains: South Rail, good service")
        val tile = AlsoTodayRules.withHeader(emptyList(), line, null).single()
        assertEquals(AlsoKind.TRAINS, tile.kind)
        assertEquals(AlsoPlace.UNFOLD, AlsoTodayRules.place(tile))
        assertTrue(AlsoTodayRules.unfolds(AlsoKind.TRAINS))
        assertEquals("Shows the train details", AlsoTodayRules.hint(tile))
        assertEquals("Trains: South Rail, good service. Shows the train details.", tile.spoken)
    }
}
