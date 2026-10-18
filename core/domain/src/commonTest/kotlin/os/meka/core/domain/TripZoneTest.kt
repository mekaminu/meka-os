package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Travel mode completeness, slice 2: bookings at the destination show UK time beside them. */
class TripZoneTest {
    private val hour = 3_600_000L
    // Fixed clocks keep the test free of real zone data: the UK at +0, "Europe/Madrid" at +1, "America/New_York" at −5.
    private val clocks = TripClocks(LocalCalendar.UTC) { id ->
        when (id) {
            "Europe/Madrid" -> LocalCalendar.fixedOffset(hour)
            "America/New_York" -> LocalCalendar.fixedOffset(-5 * hour)
            "Europe/Lisbon" -> LocalCalendar.UTC
            else -> null
        }
    }
    private val sat = 20771L // Sat 14 Nov 2026
    private fun b(id: String, kind: BookingKind, title: String, day: Long, minute: Int?, end: Long? = null) =
        TripBooking(id, "t", kind, title, day, end, minute, null, 0)
    private fun trip(name: String, vararg bs: TripBooking) = TripItem("t", name, sat, sat + 4, 0, bs.toList())

    @Test
    fun aTripNamesItsPlaceWithItsCapital() {
        assertEquals("Europe/Madrid", TripZoneRules.placeOf("Barcelona")?.id)
        assertEquals("Atlantic/Canary", TripZoneRules.placeOf("Tenerife with the boys")?.id)
        assertEquals("Gran Canaria", TripZoneRules.placeOf("Gran Canaria")?.label)
        assertEquals("Europe/Paris", TripZoneRules.placeOf("Disneyland Paris")?.id)
        assertNull(TripZoneRules.placeOf("A nice weekend away"))
        assertNull(TripZoneRules.placeOf("Indianapolis"))
        assertNull(TripZoneRules.placeOf("Cornwall"))
    }

    @Test
    fun theWayThereIsUkTimeAndEverythingAfterShowsUkTimeBeside() {
        val out = b("o", BookingKind.FLIGHT, "Flight BA478", sat, 7 * 60 + 40)
        val dinner = b("d", BookingKind.OTHER, "Dinner", sat, 21 * 60)
        val late = b("l", BookingKind.OTHER, "Late show", sat + 1, 30)
        val back = b("r", BookingKind.FLIGHT, "Flight BA479", sat + 4, 18 * 60 + 20)
        val hotel = b("h", BookingKind.STAY, "Hotel Arts", sat, null, sat + 4)
        val t = trip("Barcelona", out, hotel, dinner, late, back)
        assertNull(TripZoneRules.ukTime(out, t, clocks))
        assertNull(TripZoneRules.ukTime(hotel, t, clocks))
        assertEquals("20:00 UK time", TripZoneRules.ukTime(dinner, t, clocks))
        assertEquals("Sat 23:30 UK time", TripZoneRules.ukTime(late, t, clocks))
        assertEquals("17:20 UK time", TripZoneRules.ukTime(back, t, clocks))
        assertEquals("1 h ahead of UK time", TripZoneRules.tripLine(t, clocks))

        val view = TripRules.view(listOf(t), sat - 3, clocks = clocks).rows.single()
        assertEquals("Sat 14 – Wed 18 Nov · 5 days · 5 bookings · 1 h ahead of UK time", view.line)
        val lines = view.timeline.flatMap { it.lines }
        assertEquals("Flight BA478", lines.first { it.bookingId == "o" }.text)
        assertEquals("Flight BA479 · 17:20 UK time", lines.first { it.bookingId == "r" }.text)
        assertEquals("18:20, Flight BA479 · 17:20 UK time", lines.first { it.bookingId == "r" }.spoken)

        val ask = TripAskRules.askLines(listOf(t), sat - 3, clocks)
        assertEquals(
            listOf("Trip · Barcelona · Sat 14 – Wed 18 Nov · 5 days · in 3 days · 5 bookings", "Barcelona · local time 1 h ahead of UK time · the way there in UK time"),
            ask.subList(1, 3),
        )
        assertTrue(ask.all { it.length <= 160 })
        assertEquals("Barcelona · Flight · Wed 18 Nov 18:20 · Flight BA479 · (17:20 UK time)", ask.last())
    }

    @Test
    fun behindTheUkTheSameClockOrAnUnknownPlaceAddNothingOrSayBehind() {
        val back = b("r", BookingKind.FLIGHT, "Flight BA112", sat + 4, 9 * 60)
        val ny = trip("New York", back)
        assertEquals("5 h behind UK time", TripZoneRules.tripLine(ny, clocks))
        assertEquals("14:00 UK time", TripZoneRules.ukTime(back, ny, clocks))
        assertEquals("4 h 30 ahead of UK time", TripZoneRules.aheadText(270))
        assertEquals("30 min behind UK time", TripZoneRules.aheadText(-30))
        assertNull(TripZoneRules.tripLine(trip("Lisbon", back), clocks))
        assertNull(TripZoneRules.ukTime(back, trip("Lisbon", back), clocks))
        assertNull(TripZoneRules.ukTime(back, trip("Cornwall", back), clocks))
        assertNull(TripZoneRules.tripLine(trip("Barcelona"), null))
        assertEquals("Sat 14 – Wed 18 Nov · 5 days", TripRules.view(listOf(trip("Cornwall")), sat, clocks = clocks).rows.single().line)
    }
}
