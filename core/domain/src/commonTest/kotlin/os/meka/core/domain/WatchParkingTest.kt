package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Car and parking, slice 7: the parking timer on the Galaxy Watch (screen, tile and complication). */
class WatchParkingTest {
    private val cal = LocalCalendar.UTC
    private val day = 20_740L // Wed 14 Oct 2026
    private fun at(h: Int, m: Int = 0) = cal.toEpochMs(day, h * 60 + m)

    private fun cars(vararg titles: String) = CarsView(
        titles.mapIndexed { i, t -> CarView.EMPTY.copy(carId = CarRules.CAR_IDS[i], title = t) }, true,
    )

    private fun home(parking: List<WatchParking>, fasting: FastingView = FastingView.EMPTY, inbox: WatchInbox? = null) =
        WatchHomeView("YOU'RE CLEAR", false, "Nothing else today", null, emptyList(), null,
            WatchHomeRules.fast(fasting, at(14)), inbox, parking)

    @Test
    fun oneCarsTimerCountsDownWithPlusFifteenAndLightsInItsLastTenMinutes() {
        val cars = cars(ParkingRules.TITLE)
        val t = ParkingTimer(CarRules.CAR_IDS[0], at(13, 30), at(15, 30), "RingGo 81234")
        val now = at(14, 50)
        val p = WatchHomeRules.parking(ParkingTimerRules.view(listOf(t), cars, now, cal), now).single()
        assertEquals("PARKING", p.label)
        assertEquals("Paid until 15:30 · 40 min left", p.line)
        assertEquals("RingGo 81234", p.note)
        assertEquals("+15 min", p.button)
        assertEquals("Parking · 40 min left", p.short)
        assertEquals("40 min", p.left)
        assertTrue(kotlin.math.abs(40f / 120f - p.leftShare) < 0.001f)
        assertFalse(p.lit)
        assertTrue(p.spoken.startsWith("Parking. Paid until 15:30, 40 min left"))

        val late = at(15, 22)
        val q = WatchHomeRules.parking(ParkingTimerRules.view(listOf(t), cars, late, cal), late).single()
        assertTrue(q.lit)
        assertEquals("Parking · 8 min left", q.short)

        val after = at(15, 35)
        val e = WatchHomeRules.parking(ParkingTimerRules.view(listOf(t), cars, after, cal), after).single()
        assertTrue(e.ended)
        assertNull(e.button)
        assertEquals("Parking · ended 15:30", e.short)
        assertEquals("Ended", e.left)
        assertEquals(0f, e.leftShare)
        // Two hours after it ends it has gone.
        val gone = at(17, 31)
        assertTrue(WatchHomeRules.parking(ParkingTimerRules.view(listOf(t), cars, gone, cal), gone).isEmpty())
    }

    @Test
    fun twoCarsAreNamedAndTheOneEndingFirstLeadsTheTileAndTheRing() {
        val cars = cars("the Golf", "the family car")
        val golf = ParkingTimer(CarRules.CAR_IDS[0], at(13), at(16), null)
        val family = ParkingTimer(CarRules.CAR_IDS[1], at(13), at(14, 30), null)
        val now = at(14)
        val rows = WatchHomeRules.parking(ParkingTimerRules.view(listOf(golf, family), cars, now, cal), now)
        assertEquals(listOf("PARKING · THE FAMILY CAR", "PARKING · THE GOLF"), rows.map { it.label })
        assertEquals("The family car · 30 min left", rows[0].short)

        val tile = WatchTileRules.tile(home(rows))
        assertEquals("The family car · 30 min left", tile.parkingLine)
        assertFalse(tile.parkingLit)
        assertTrue(tile.spoken.contains("Parking, the family car."))
        assertEquals(WatchHomeRules.nextTickMs(now) - now, WatchTileRules.refreshAfterMs(home(rows), now))

        val ring = WatchTileRules.complication(home(rows), 2, 3)
        assertEquals("30 min", ring.text)
        assertEquals("Park", ring.title)
        assertTrue(kotlin.math.abs(30f / 90f - ring.value) < 0.001f)
    }

    @Test
    fun theTileGivesTheInboxLineToARunningTimerAndAnEndedOneLeavesTheRingAlone() {
        val cars = cars(ParkingRules.TITLE)
        val inbox = WatchHomeRules.inbox(3, null)
        val running = ParkingTimer(CarRules.CAR_IDS[0], at(13), at(15), null)
        val now = at(14)
        val rows = WatchHomeRules.parking(ParkingTimerRules.view(listOf(running), cars, now, cal), now)
        val tile = WatchTileRules.tile(home(rows, inbox = inbox))
        assertEquals("Parking · 1 h left", tile.parkingLine)
        assertNull(tile.inboxLine)

        val ended = ParkingTimer(CarRules.CAR_IDS[0], at(12), at(13, 30), null)
        val endedRows = WatchHomeRules.parking(ParkingTimerRules.view(listOf(ended), cars, now, cal), now)
        val calm = WatchTileRules.tile(home(endedRows, inbox = inbox))
        assertNull(calm.parkingLine)
        assertEquals("Inbox · 3 need you", calm.inboxLine)
        assertEquals("2/5", WatchTileRules.complication(home(endedRows), 2, 3).text)
        assertEquals(WatchTileRules.CALM_REFRESH_MS, WatchTileRules.refreshAfterMs(home(endedRows), now))
    }
}
