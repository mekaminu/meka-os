package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Travel mode, slice 2b: a shared booking confirmation becomes a booking card for the trip it falls on ("Handle this"),
 * and the evening before a trip MEKA says what's first, with its time and reference.
 */
class TripShareTest {
    private val cal = LocalCalendar.fixedOffset(0)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val mon = d(10, 12)
    private val bcn = TripItem("b", "Barcelona", d(11, 14), d(11, 18), 0)

    private val flight = RawRequestProposal("booking", "Flight BA478 to Barcelona", date = "2026-11-14", time = "07:40", type = "flight", ref = "abc123")
    private val hotel = RawRequestProposal("booking", "Hotel Arts", date = "2026-11-14", type = "stay", ref = "XKQPLM", until = "2026-11-18")

    @Test
    fun aBookingIsReadOnlyForAShareWithItsKindLastDayAndReference() {
        assertTrue(MessageRequestRules.check(listOf(flight), mon, 600).isEmpty())
        val (f, h) = MessageRequestRules.check(listOf(flight, hotel), mon, 600, allowBooking = true)
        assertEquals(RequestProposal(RequestKind.BOOKING, "Flight BA478 to Barcelona", d(11, 14), 7 * 60 + 40,
            booking = BookingDetails(BookingKind.FLIGHT, null, "ABC123")), f)
        assertEquals(BookingDetails(BookingKind.STAY, d(11, 18), "XKQPLM"), h.booking)
        // A flight has no last day; a reference that isn't one is dropped; an unknown kind is "other"; no day, no card.
        val odd = MessageRequestRules.check(listOf(
            flight.copy(until = "2026-11-18", ref = "a b"),
            RawRequestProposal("booking", "Sagrada Família tickets", date = "2026-11-16", time = "10:00", type = "museum"),
            RawRequestProposal("booking", "Hotel Arts", type = "stay"),
        ), mon, 600, allowBooking = true)
        assertEquals(2, odd.size)
        assertEquals(BookingDetails(BookingKind.FLIGHT, null, null), odd[0].booking)
        assertEquals(BookingKind.OTHER, odd[1].booking?.kind)
        assertTrue(HandleThisRules.allowed(listOf(flight)).isNotEmpty())
    }

    @Test
    fun itNamesTheTripItFallsOnDropsOneAlreadyThereAndWithoutATripIsAnEvent() {
        val checked = MessageRequestRules.check(listOf(flight, hotel), mon, 600, allowBooking = true)
        val booked = bcn.copy(bookings = listOf(TripBooking("x", "b", BookingKind.STAY, "Hotel Arts", d(11, 14), d(11, 18), null, null, 0)))
        val out = HandleThisRules.withTrips(checked, listOf(booked), mon)
        assertEquals(1, out.size)
        assertEquals("Barcelona", out[0].booking?.tripName)

        val card = MessageRequestRules.card(RequestMessage("share:1:1", HandleThisRules.PERSON, "Your flight", 0), 0, out[0], mon, cal)
        assertEquals("Add to Barcelona · Flight BA478 to Barcelona · Sat 14 Nov 07:40 · ref ABC123", card.action)
        assertNull(card.changeLabel)
        assertEquals("Not needed", card.declineLabel)

        val none = HandleThisRules.withTrips(checked, emptyList(), mon)
        assertEquals(listOf(RequestKind.EVENT, RequestKind.EVENT), none.map { it.kind })
        assertEquals("Flight BA478 to Barcelona · ref ABC123", none[0].title)
        assertEquals(7 * 60 + 40, none[0].minute)
    }

    @Test
    fun addPutsItInTheTripAndTheCardKeepsItsDetailsAcrossDevices() {
        val world = SyncWorld()
        world.clock.nowMs = cal.toEpochMs(mon, 600)
        val fold = world.device("android")
        val mac = world.device("mac")
        val cards = RequestCards(fold.replica, { world.clock.nowMs }, cal)
        val p = HandleThisRules.withTrips(MessageRequestRules.check(listOf(hotel), mon, 600, allowBooking = true), listOf(bcn), mon)
        cards.save(RequestMessage("share:2:1", HandleThisRules.PERSON, "Booking confirmed", world.clock.nowMs), p)
        fold.sync(); mac.sync()
        val shown = RequestCards(mac.replica, { world.clock.nowMs }, cal).open().single()
        assertEquals(p.single(), shown.proposal)
        assertEquals("Add to Barcelona · Hotel Arts · Sat 14 – Wed 18 Nov · ref XKQPLM", shown.action)

        val plan = RequestAcceptRules.plan(shown.proposal, canAddEvent = true) as RequestPlan.AddBooking
        assertEquals(BookingEntry("", BookingKind.STAY, "Hotel Arts", d(11, 14), d(11, 18), null, "XKQPLM"), plan.entry)
        assertNull(RequestAcceptRules.plan(shown.proposal, canAddEvent = true, change = true))
        assertEquals("Added to Barcelona · Hotel Arts · Sat 14 – Wed 18 Nov · ref XKQPLM",
            RequestAcceptRules.doneLine(plan, RequestKind.BOOKING, mon))
        assertEquals(RequestPlan.AddTask("Hotel Arts · ref XKQPLM", d(11, 14), null, null), RequestAcceptRules.bookingAsTask(plan))
    }

    @Test
    fun theEveningBeforeSaysWhatsFirstWithItsReference() {
        val trip = bcn.copy(bookings = listOf(
            TripBooking("h", "b", BookingKind.STAY, "Hotel Arts", d(11, 14), d(11, 18), null, "XKQPLM", 0),
            TripBooking("f", "b", BookingKind.FLIGHT, "Flight BA478", d(11, 14), null, 7 * 60 + 40, "ABC123", 0),
        ))
        // Not yet a week out.
        assertTrue(TripRules.notices(listOf(trip), cal.toEpochMs(d(11, 7), 600), cal).isEmpty())
        val n = TripRules.notices(listOf(trip), cal.toEpochMs(d(11, 13), 9 * 60), cal).single()
        assertEquals("Barcelona tomorrow · Flight BA478 07:40", n.title)
        assertEquals("ref ABC123 · then Hotel Arts until Wed 18 Nov", n.text)
        assertEquals(cal.toEpochMs(d(11, 13), 18 * 60), n.atMs)
        assertEquals(cal.toEpochMs(d(11, 14), 12 * 60), n.expiresAtMs)
        assertEquals(NoticeSource.TRIPS, n.source)
        assertEquals("trip:b:${d(11, 14)}", n.key)

        // Nothing booked; and a hotel the night before makes that night the first.
        val bare = TripRules.notices(listOf(bcn), cal.toEpochMs(d(11, 13), 9 * 60), cal).single()
        assertEquals("Barcelona tomorrow", bare.title)
        assertEquals("Nothing booked in Trips yet · Sat 14 – Wed 18 Nov", bare.text)
        val early = bcn.copy(bookings = listOf(TripBooking("h", "b", BookingKind.STAY, "Airport hotel", d(11, 13), null, null, null, 0)))
        assertEquals(cal.toEpochMs(d(11, 12), 18 * 60), TripRules.notices(listOf(early), cal.toEpochMs(d(11, 12), 9 * 60), cal).single().atMs)
        // Over: nothing.
        assertTrue(TripRules.notices(listOf(trip), cal.toEpochMs(d(11, 15), 9 * 60), cal).isEmpty())
    }
}
