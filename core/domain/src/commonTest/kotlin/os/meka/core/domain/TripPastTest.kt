package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Travel mode completeness slice 4: past trips kept under PAST TRIPS, and × / Remove trip with Undo on both apps. */
class TripPastTest {
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(0)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val tFold = Trips(fold.replica, { "t${n++}" }, { world.clock.nowMs }, cal)
    private val tMac = Trips(mac.replica, { "t${n++}" }, { world.clock.nowMs }, cal)

    init { world.clock.nowMs = cal.toEpochMs(d(10, 12), 10 * 60) }

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    private fun trip(name: String, start: Long, end: Long) = TripItem(name.lowercase(), name, start, end, 0)

    @Test
    fun endedTripsMoveToPastTripsAfterAWeekNewestFirst() {
        val today = d(10, 18)
        val trips = listOf(
            trip("Lisbon", d(3, 2), d(3, 6)),
            trip("Rome", d(8, 1), d(8, 5)),
            trip("Paris", d(10, 9), d(10, 12)),
            trip("Madrid", d(10, 1), d(10, 4)),
            trip("Barcelona", d(11, 14), d(11, 18)),
        )
        val v = TripRules.view(trips, today)
        assertEquals(listOf("Barcelona", "Paris"), v.rows.map { it.name })
        assertEquals(listOf("Madrid", "Rome", "Lisbon"), v.past.map { it.name })
        assertEquals("PAST TRIPS · 3", v.pastLabel)
        assertEquals("Ended Wed 5 Aug", v.past[1].status)
        assertTrue(v.past.all { it.documents == null && !it.statusLit })
        // Nothing past: no label.
        assertEquals("", TripRules.view(listOf(trips[2], trips[4]), today).pastLabel)
        // Only past trips: the pane isn't empty.
        val onlyPast = TripRules.view(trips.take(2), today)
        assertFalse(onlyPast.isEmpty)
        assertTrue(onlyPast.rows.isEmpty())
        // At most PAST_MAX, the label counting them all.
        val many = (1..30).map { trip("Trip$it", d(1, 1) + it * 5, d(1, 1) + it * 5 + 1) }
        val vm = TripRules.view(many, d(10, 18))
        assertEquals(TripRules.PAST_MAX, vm.past.size)
        assertEquals("PAST TRIPS · 30", vm.pastLabel)
        assertEquals("Trip30", vm.past.first().name)
    }

    @Test
    fun removingATripOrABookingCanBeUndoneOnEitherDevice() {
        assertFalse(tFold.add("Barcelona 14–18 Nov").refused)
        assertFalse(tFold.add("Flight BA478 Sat 14 Nov 07:40 ref ABC123").refused)
        assertFalse(tFold.add("Hotel Arts 14–18 Nov").refused)
        sync()
        val trip = tMac.items().single()
        val flight = trip.bookings.first { it.kind == BookingKind.FLIGHT }

        // × on a booking on the Mac, then Undo.
        val one = tMac.removeBookingForUndo(flight.id)!!
        assertEquals("Removed Flight BA478 from Barcelona", one.line)
        assertNull(tMac.removeBookingForUndo(flight.id))
        sync()
        assertEquals(1, tFold.items().single().bookings.size)
        assertTrue(tMac.undoRemove(one.token))
        assertFalse(tMac.undoRemove(one.token))
        sync()
        assertEquals(2, tFold.items().single().bookings.size)

        // Remove trip on the Fold: the trip and its bookings go; Undo brings exactly those back.
        val hotel = trip.bookings.first { it.kind == BookingKind.STAY }
        assertTrue(tFold.removeBooking(hotel.id))
        val all = tFold.removeForUndo(trip.id)!!
        assertEquals("Removed Barcelona and its 1 booking", all.line)
        sync()
        assertTrue(tMac.view().isEmpty)
        assertTrue(tMac.undoRemove(all.token))
        sync()
        val back = tFold.items().single()
        assertEquals("Barcelona", back.name)
        // The hotel removed on its own before stays removed.
        assertEquals(listOf("Flight BA478"), back.bookings.map { it.title })

        assertNull(tFold.removeForUndo("nope"))
        assertFalse(tFold.undoRemove("rubbish"))
        assertEquals(null, TripRules.readToken(";"))
        assertEquals("Removed Rome", TripRules.removedLine(trip("Rome", 1, 2)))
    }
}
