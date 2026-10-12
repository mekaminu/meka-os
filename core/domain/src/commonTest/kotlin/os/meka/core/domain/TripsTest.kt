package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Travel mode, slice 1: trips and bookings typed in one field, the day-by-day timeline, Today's line and the week ahead. */
class TripsTest {
    private val hour = 3_600_000L
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(0)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val mon = d(10, 12)
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val tFold = Trips(fold.replica, { "t${n++}" }, { world.clock.nowMs }, cal)
    private val tMac = Trips(mac.replica, { "t${n++}" }, { world.clock.nowMs }, cal)

    init { world.clock.nowMs = cal.toEpochMs(mon, 10 * 60) }

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    private val bcn = TripItem("b", "Barcelona", d(11, 14), d(11, 18), 0)

    @Test
    fun aTripIsADateOrARangeWithAName() {
        assertEquals(TripRules.Read.Trip(TripEntry("Barcelona", d(11, 14), d(11, 18))), TripRules.read("Barcelona 14–18 Nov", mon, 600, emptyList()))
        assertEquals(TripRules.Read.Trip(TripEntry("Rome trip", d(12, 3), d(12, 6))), TripRules.read("rome trip Thu 3 Dec - Sun 6 Dec", mon, 600, emptyList()))
        assertEquals(TripRules.Read.Trip(TripEntry("Manchester", d(10, 20), d(10, 20))), TripRules.read("Manchester 20 Oct", mon, 600, emptyList()))
        assertEquals(TripRules.Read.Refused(TripRules.NOT_READ), TripRules.read("Barcelona", mon, 600, emptyList()))
        assertEquals(TripRules.Read.Refused(TripRules.NOT_READ), TripRules.read("14–18 Nov", mon, 600, emptyList()))
    }

    @Test
    fun aBookingJoinsTheTripItFallsOnWithItsTimeAndReference() {
        val trips = listOf(bcn)
        assertEquals(
            TripRules.Read.Booking(BookingEntry("b", BookingKind.FLIGHT, "Flight BA478", d(11, 14), null, 7 * 60 + 40, "ABC123")),
            TripRules.read("Flight BA478 Sat 14 Nov 07:40 ref ABC123", mon, 600, trips),
        )
        assertEquals(
            TripRules.Read.Booking(BookingEntry("b", BookingKind.STAY, "Hotel Arts", d(11, 14), d(11, 18), null, "XKQPLM")),
            TripRules.read("Hotel Arts 14–18 Nov booking ref: XKQPLM", mon, 600, trips),
        )
        assertEquals(
            TripRules.Read.Booking(BookingEntry("b", BookingKind.STAY, "Check in", d(11, 14), null, 15 * 60, null)),
            TripRules.read("check in 3pm 14 Nov", mon, 600, trips),
        )
        // A night flight home the day after still belongs to the trip.
        assertEquals(
            TripRules.Read.Booking(BookingEntry("b", BookingKind.FLIGHT, "Flight BA479", d(11, 19), null, 30, null)),
            TripRules.read("Flight BA479 19 Nov 00:30", mon, 600, trips),
        )
        // No booking word, but a time on a trip day: a booking, not a trip.
        assertEquals(
            TripRules.Read.Booking(BookingEntry("b", BookingKind.OTHER, "Sagrada Família", d(11, 16), null, 10 * 60, null)),
            TripRules.read("Sagrada Família 16 Nov 10:00", mon, 600, trips),
        )
        assertEquals(TripRules.Read.Refused(TripRules.NO_DAY), TripRules.read("Flight BA478 07:40", mon, 600, trips))
        val away = TripRules.read("Flight BA478 3 Dec 07:40", mon, 600, trips) as TripRules.Read.Refused
        assertTrue(away.line.startsWith("No trip on Thu 3 Dec yet."), away.line)
        assertEquals(TripRules.Read.Refused(TripRules.NO_TRIP), TripRules.read("Flight BA478 14 Nov 07:40", mon, 600, emptyList()))
    }

    @Test
    fun aReferenceHasADigitOrCapitals() {
        assertEquals("ABC123" to "Flight 14 Nov", TripRules.takeRef("Flight ref ABC123 14 Nov"))
        assertEquals("4471-22" to "Ferry", TripRules.takeRef("Ferry confirmation no. 4471-22"))
        assertEquals(null to "Table booking for two", TripRules.takeRef("Table booking for two"))
        assertEquals(BookingKind.STAY, TripRules.kindOf("Check out 11:00"))
        assertEquals(BookingKind.CAR, TripRules.kindOf("Car hire at the airport"))
        assertNull(TripRules.kindOf("Barcelona 14–18 Nov"))
    }

    @Test
    fun typedOnTheFoldTheMacHasTheTimelineAndTodaySaysWhenItsNear() {
        assertTrue(tFold.add("Flight BA478 14 Nov 07:40").refused)
        assertEquals("Added Barcelona · Sat 14 – Wed 18 Nov · in 33 days", tFold.add("Barcelona 14–18 Nov").line)
        assertEquals("Added to Barcelona · Flight BA478 · Sat 14 Nov 07:40 · ref ABC123", tFold.add("Flight BA478 Sat 14 Nov 07:40 ref ABC123").line)
        assertEquals("Added to Barcelona · Hotel Arts · Sat 14 – Wed 18 Nov", tFold.add("Hotel Arts 14–18 Nov").line)
        assertEquals("Added to Barcelona · Dinner at Can Solé · Sun 15 Nov 20:30", tFold.add("Dinner at Can Solé 15 Nov 20:30").line)
        // The same name again moves its dates.
        assertEquals("Updated Barcelona · Sat 14 – Thu 19 Nov · in 33 days", tFold.add("barcelona 14-19 Nov").line)
        assertEquals("Updated Barcelona · Sat 14 – Wed 18 Nov · in 33 days", tFold.add("Barcelona 14–18 Nov").line)
        sync()

        val v = tMac.view()
        assertEquals("Next: Barcelona · Sat 14 Nov · in 33 days", v.summary)
        val row = v.rows.single()
        assertEquals("Sat 14 – Wed 18 Nov · 5 days · 3 bookings", row.line)
        assertEquals("In 33 days", row.status)
        assertFalse(row.statusLit)
        assertEquals(listOf("Sat 14 Nov · day 1", "Sun 15 Nov · day 2", "Wed 18 Nov · day 5"), row.timeline.map { it.label })
        assertEquals(
            listOf("Stay" to "Hotel Arts · until Wed 18 Nov", "07:40" to "Flight BA478 · ref ABC123"),
            row.timeline[0].lines.map { it.lead to it.text },
        )
        assertEquals(listOf("Stay" to "Check out · Hotel Arts"), row.timeline[2].lines.map { it.lead to it.text })

        val trips = tMac.items()
        assertNull(TripRules.todayLine(trips, mon, 600))
        assertEquals(TripLine("Barcelona in 3 days · Flight BA478 Sat 07:40", "Barcelona in 3 days. Flight BA478 Sat 07:40.", false),
            TripRules.todayLine(trips, d(11, 11), 600))
        assertEquals("Barcelona tomorrow · Flight BA478 tomorrow 07:40", TripRules.todayLine(trips, d(11, 13), 600)?.text)
        assertEquals(true, TripRules.todayLine(trips, d(11, 13), 600)?.lit)
        val early = TripRules.todayLine(trips, d(11, 14), 6 * 60)!!
        assertEquals("Barcelona · day 1 of 5 · Flight BA478 07:40", early.text)
        assertTrue(early.lit)
        assertEquals("Barcelona · day 1 of 5 · Dinner at Can Solé tomorrow 20:30", TripRules.todayLine(trips, d(11, 14), 9 * 60)?.text)
        assertEquals("Barcelona · day 4 of 5", TripRules.todayLine(trips, d(11, 17), 9 * 60)?.text)
        assertNull(TripRules.todayLine(trips, d(11, 19), 9 * 60))
        assertEquals("On a trip: Barcelona · day 2 of 5", TripRules.view(trips, d(11, 15)).summary)
        assertEquals("Ended Wed 18 Nov", TripRules.view(trips, d(11, 20)).rows.single().status)
        assertTrue(TripRules.view(trips, d(12, 20)).isEmpty)

        // The week ahead: the trip on its first day, the bookings on theirs.
        assertEquals(
            listOf(Triple(null, "Barcelona trip · until Wed 18 Nov", "t-${row.id}"), Triple(7 * 60 + 40, "Flight BA478 · ref ABC123", "tb-${trips[0].bookings.first { it.kind == BookingKind.FLIGHT }.id}")),
            TripRules.weekLines(trips, d(11, 14), d(11, 9)).filter { !it.second.startsWith("Hotel") },
        )
        assertEquals("Barcelona trip · until Wed 18 Nov", TripRules.weekLines(trips, d(11, 16), d(11, 16)).first().second)
        assertEquals(listOf("Check out · Hotel Arts"), TripRules.weekLines(trips, d(11, 18), d(11, 16)).map { it.second })

        // A booking removed on the Mac, then the trip with the rest: gone on the Fold.
        assertTrue(tMac.removeBooking(trips[0].bookings.first { it.kind == BookingKind.FLIGHT }.id))
        sync()
        assertEquals(2, tFold.items().single().bookings.size)
        assertTrue(tFold.remove(row.id))
        assertFalse(tFold.remove(row.id))
        sync()
        assertTrue(tMac.view().isEmpty)
        assertTrue(mac.replica.entities(EntityTypes.TRIP_BOOKING).isEmpty())
    }
}
