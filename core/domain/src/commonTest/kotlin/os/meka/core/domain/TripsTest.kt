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
    fun theAddHintsAreExamplesThatRead() {
        // Quality gate slice 20: one short example per hint (a trip, then a booking once there is a trip), each one read.
        assertEquals(TripRules.ADD_HINT, TripRules.addHint(hasTrips = false))
        assertEquals(TripRules.BOOKING_HINT, TripRules.addHint(hasTrips = true))
        assertTrue(TripRules.read(TripRules.ADD_HINT, mon, 600, emptyList()) is TripRules.Read.Trip)
        assertTrue(TripRules.read(TripRules.BOOKING_HINT, mon, 600, listOf(bcn)) is TripRules.Read.Booking)
        assertTrue(TripRules.ADD_HINT.length <= 26 && TripRules.BOOKING_HINT.length <= 26)
        assertTrue(GiftRules.read(GiftRules.ADD_HINT, 2026) != null && GiftRules.ADD_HINT.length <= 26)
        assertTrue(BinRules.read(BinRules.ADD_HINT, mon) != null && BinRules.ADD_HINT.length <= 26)
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
        // A week after it ended it moves to PAST TRIPS (completeness slice 4), kept.
        assertEquals("Ended Wed 18 Nov", TripRules.view(trips, d(11, 25)).rows.single().status)
        val later = TripRules.view(trips, d(12, 20))
        assertTrue(later.rows.isEmpty())
        assertEquals(listOf("Barcelona"), later.past.map { it.name })
        assertEquals("PAST TRIPS · 1", later.pastLabel)
        assertEquals("No trips coming up", later.summary)
        assertEquals("Ended Wed 18 Nov 2026", TripRules.view(trips, d(1, 5, 2027)).past.single().status)

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

    @Test
    fun askAndTalkKnowTheTripsAndAddABooking() {
        assertTrue(TripAskRules.askLines(emptyList(), mon).isEmpty())
        val flight = TripBooking("f", "b", BookingKind.FLIGHT, "Flight BA478", d(11, 14), null, 7 * 60 + 40, "ABC123", 1)
        val hotel = TripBooking("h", "b", BookingKind.STAY, "Hotel Arts", d(11, 14), d(11, 18), null, null, 2)
        val trip = bcn.copy(bookings = listOf(hotel, flight))
        val gone = TripItem("o", "Leeds", d(10, 1), d(10, 2), 0)
        val trips = listOf(gone, trip)
        assertEquals(
            listOf(
                "Trips · 1 on now or coming",
                "Trip · Barcelona · Sat 14 – Wed 18 Nov · 5 days · in 33 days · 2 bookings",
                "Barcelona · Stay · Sat 14 – Wed 18 Nov · Hotel Arts",
                "Barcelona · Flight · Sat 14 Nov 07:40 · Flight BA478 · ref ABC123",
            ),
            TripAskRules.askLines(trips, mon),
        )
        assertEquals("Trip · Barcelona · Sat 14 – Wed 18 Nov · 5 days · day 2 of 5 · 2 bookings", TripAskRules.askLines(trips, d(11, 15))[1])

        val ctx = AskRules.context(Today(emptyList(), null, emptyList(), emptyList()), cal.toEpochMs(mon, 600), cal, trips = TripAskRules.askLines(trips, mon))
        assertEquals(4, ctx.items.count { it.kind == AskItemKind.TRIPS })
        assertFalse(ctx.untrusted)

        fun card(title: String?, date: String? = null, time: String? = null) =
            AskRules.card(AskRawAction("add_booking", title = title, date = date, time = time), ctx, emptyMap(), cal.toEpochMs(mon, 600), cal, trips = trips)
        // Typed as in Trips: the day, time and reference read from the title.
        val c = card("Flight BA479 Wed 18 Nov 18:20 ref XYZ789")!!
        assertEquals(AskProposal.AddBooking(BookingEntry("b", BookingKind.FLIGHT, "Flight BA479", d(11, 18), null, 18 * 60 + 20, "XYZ789"), "Barcelona"), c.proposal)
        assertEquals("Add to Barcelona · Flight BA479 · Wed 18 Nov 18:20 · ref XYZ789", c.line)
        assertEquals("Add", c.button)
        assertEquals("Added to Barcelona · Flight BA479 · Wed 18 Nov 18:20 · ref XYZ789", AskRules.doneLine(c.proposal, mon))
        assertEquals("add Flight BA479 on Wednesday 18 November at 18:20 to the Barcelona trip", TalkRules.phrase(c.proposal, mon, past = false))
        assertEquals("added Flight BA479 on Wednesday 18 November at 18:20 to the Barcelona trip", TalkRules.phrase(c.proposal, mon, past = true))
        assertNull(TrustRules.kindOf(c.proposal))
        // The day and time from the action's fields when the title has none.
        assertEquals(
            BookingEntry("b", BookingKind.OTHER, "Dinner at Can Solé", d(11, 15), null, 20 * 60 + 30, null),
            (card("Dinner at Can Solé", "2026-11-15", "20:30")!!.proposal as AskProposal.AddBooking).entry,
        )
        assertEquals(
            BookingEntry("b", BookingKind.CAR, "Car hire Sixt", d(11, 14), d(11, 18), null, null),
            (card("Car hire Sixt 14–18 Nov")!!.proposal as AskProposal.AddBooking).entry,
        )
        // Already there, no trip on that day, not a booking, a bad date: no card.
        assertNull(card("flight ba478 Sat 14 Nov 07:40"))
        assertNull(card("Flight BA478 3 Dec 07:40"))
        assertNull(card("Rome 3–6 Dec"))
        assertNull(card("Flight", "soon"))
        assertNull(card(null))
    }
}
