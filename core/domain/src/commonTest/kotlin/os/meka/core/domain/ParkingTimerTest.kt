package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Car and parking, slice 2: the parking timer, typed. */
class ParkingTimerTest {
    private val cal = LocalCalendar.UTC
    private val day = 20_740L // Wed 14 Oct 2026
    private fun at(h: Int, m: Int = 0, d: Long = day) = cal.toEpochMs(d, h * 60 + m)
    private val min = 60_000L

    private fun cars(vararg titles: String) = CarsView(
        titles.mapIndexed { i, t -> CarView.EMPTY.copy(carId = CarRules.CAR_IDS[i], title = t) }, true,
    )

    private fun ends(text: String, now: Long = at(14, 5)) = ParkingTimerRules.parse(text, now, cal)?.endsAtMs

    @Test
    fun lengthsAndTimesRead() {
        val now = at(14, 5)
        assertEquals(now + 120 * min, ends("2 h"))
        assertEquals(now + 120 * min, ends("2h"))
        assertEquals(now + 90 * min, ends("90 min"))
        assertEquals(now + 90 * min, ends("90m"))
        assertEquals(now + 90 * min, ends("1h30"))
        assertEquals(now + 90 * min, ends("1 h 30"))
        assertEquals(now + 90 * min, ends("1.5 hours"))
        assertEquals(now + 60 * min, ends("an hour"))
        assertEquals(now + 30 * min, ends("half an hour"))
        assertEquals(now + 75 * min, ends("paid for 1 hour and 15 minutes"))
        assertEquals(at(15, 30), ends("until 15:30"))
        assertEquals(at(15, 30), ends("15.30"))
        assertEquals(at(18), ends("till 6pm"))
        assertEquals(at(18, 30), ends("6:30 pm"))
        assertEquals(at(18), ends("until 18"))
        // A time already gone today is tomorrow's.
        assertEquals(at(8, d = day + 1), ends("until 8am"))
        // Nothing that reads, or over a day.
        assertNull(ends("RingGo"))
        assertNull(ends(""))
        assertNull(ends("30 h"))
    }

    @Test
    fun theOtherWordsAreKeptAsTheNote() {
        val t = ParkingTimerRules.parse("till 6pm RingGo 81234", at(14, 5), cal)
        assertEquals("RingGo 81234", t?.note)
        assertNull(ParkingTimerRules.parse("2 h", at(14, 5), cal)?.note)
    }

    @Test
    fun theRowCountsDownLightsInTheLastTenMinutesAndShowsAnEndedOneForTwoHours() {
        val t = ParkingTimer("car", at(14), at(15, 30), "RingGo 81234")
        val one = cars("Car")
        val v = ParkingTimerRules.view(listOf(t), one, at(14, 10), cal)
        assertEquals("Paid until 15:30 · 1 h 20 left", v.rows.single().line)
        assertFalse(v.rows.single().lit)
        assertEquals(LifeTileLine("Paid until 15:30 · 1 h 20 left", true), v.tile)
        assertTrue(v.running)

        val late = ParkingTimerRules.view(listOf(t), one, at(15, 22), cal)
        assertEquals("Paid until 15:30 · 8 min left", late.rows.single().line)
        assertTrue(late.rows.single().lit)

        val ended = ParkingTimerRules.view(listOf(t), one, at(15, 35), cal)
        assertEquals("Ended 15:30 · 5 min ago", ended.rows.single().line)
        assertNull(ended.tile)
        assertFalse(ended.running)
        assertTrue(ParkingTimerRules.view(listOf(t), one, at(17, 31), cal).rows.isEmpty())

        // On the Fold the parked bay doesn't cover a running timer on the Car tile; an ended one gives way.
        val grid = LifeLines(mapOf(LifeTile.CAR to v.tile!!), emptySet())
        val bay = LifeTileLine("Parked · Level 3 · Row F", true)
        assertEquals(v.tile, ParkingRules.withParked(grid, bay, v).of(LifeTile.CAR))
        assertEquals(bay, ParkingRules.withParked(grid, bay, ended).of(LifeTile.CAR))
    }

    @Test
    fun twoCarsSayWhichAndTheTileCounts() {
        val two = cars("the Golf", "Car 2")
        val golf = ParkingTimer("car", at(14), at(15, 30))
        val v1 = ParkingTimerRules.view(listOf(golf), two, at(14, 10), cal)
        assertEquals("The Golf · paid until 15:30 · 1 h 20 left", v1.tile?.text)
        val v2 = ParkingTimerRules.view(listOf(golf, ParkingTimer("car2", at(14), at(16))), two, at(14, 10), cal)
        assertEquals(listOf("the Golf", "Car 2"), v2.rows.map { it.title })
        assertEquals("2 parking timers · next ends 15:30", v2.tile?.text)
        assertEquals("Parking timer until 15:30 · the Golf · reminds you at 15:20", ParkingTimerRules.setLine(golf, "the Golf", at(14, 10), cal))
        assertEquals("Parking timer ended · the Golf", ParkingTimerRules.clearedLine("the Golf"))
    }

    @Test
    fun theHeadsUpComesTenMinutesBeforeAndAgainWhenExtended() {
        val t = ParkingTimer("car", at(14), at(15, 30), "RingGo 81234")
        val n = ParkingTimerRules.notices(listOf(t), cars("Car"), at(14, 10), cal).single()
        assertEquals(NoticeSource.PARKING, n.source)
        assertEquals(at(15, 20), n.atMs)
        assertEquals(NoticePrecision.CLOCK, n.precision)
        assertEquals("Parking ends at 15:30", n.title)
        assertEquals("10 min left · extend it in your parking app, or move the car · RingGo 81234", n.text)
        val extended = ParkingTimerRules.notices(listOf(t.copy(endsAtMs = at(16))), cars("Car"), at(15, 21), cal).single()
        assertTrue(extended.key != n.key)
        // Set with under ten minutes to go: at once, with what's left.
        val short = ParkingTimerRules.notices(listOf(ParkingTimer("car", at(15, 25), at(15, 30))), cars("the Golf", "Car 2"), at(15, 25), cal).single()
        assertEquals(at(15, 25), short.atMs)
        assertEquals("Parking ends at 15:30 · the Golf", short.title)
        assertTrue(short.text.startsWith("5 min left"))
        // Ended: nothing.
        assertTrue(ParkingTimerRules.notices(listOf(t), cars("Car"), at(15, 30), cal).isEmpty())
    }

    @Test
    fun timersAreKeptOnEachCarExtendedAndEnded() {
        val timers = ParkingTimers(SyncWorld().device("android").replica)
        val now = at(14, 5)
        assertNull(timers.set("car", "nonsense", now, cal))
        assertNull(timers.set("not-a-car", "2 h", now, cal))
        val t = assertNotNull(timers.set("car", "2 h RingGo 81234", now, cal))
        assertEquals(now + 120 * min, t.endsAtMs)
        assertEquals("RingGo 81234", t.note)
        assertNotNull(timers.set("car2", "until 18:00", now, cal))
        assertEquals(listOf("car", "car2"), timers.all().map { it.carId })
        assertEquals(now + 150 * min, timers.extend("car", 30, now)?.endsAtMs)
        // An ended timer extends from now.
        assertEquals(at(19) + 15 * min, timers.extend("car2", 15, at(19))?.endsAtMs)
        val old = assertNotNull(timers.clear("car"))
        assertNull(timers.timer("car"))
        assertNull(timers.extend("car", 15, now))
        timers.put(old)
        assertEquals(old, timers.timer("car"))
    }
}
