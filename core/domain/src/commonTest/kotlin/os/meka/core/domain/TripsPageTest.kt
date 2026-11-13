package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every page under Ask in one shape, slice 16: Ask → Trips as the page shape. */
class TripsPageTest {
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val today = d(11, 9)

    private fun bk(id: String, kind: BookingKind, title: String, day: Long, end: Long? = null, minute: Int? = null, ref: String? = null) =
        TripBooking(id, "b", kind, title, day, end, minute, ref, 0)

    private val bcn = TripItem(
        "b", "Barcelona", d(11, 14), d(11, 18), 0,
        bookings = listOf(
            bk("f1", BookingKind.FLIGHT, "Flight BA478", d(11, 14), minute = 7 * 60 + 40, ref = "ABC123"),
            bk("h1", BookingKind.STAY, "Hotel Arts", d(11, 14), end = d(11, 18)),
            bk("f2", BookingKind.FLIGHT, "Flight BA479", d(11, 18), minute = 18 * 60 + 20),
        ),
    )
    private val rome = TripItem("r", "Rome", d(3, 1), d(3, 5), 0)
    private val docs = listOf(
        DocumentItem("1", "Alex passport", DocKind.PASSPORT, d(5, 1, 2030), 0),
        DocumentItem("2", "GHIC", DocKind.HEALTH_CARD, d(11, 16), 0),
    )

    private fun page(trips: List<TripItem>, on: Long = today, withDocs: List<DocumentItem>? = docs) =
        TripsPageRules.page(TripRules.view(trips, on, withDocs), on)

    @Test
    fun noTripsSaysSoInWordsWithOneCard() {
        val p = page(emptyList())
        assertEquals(3, p.tileCount)
        assertEquals("No trips", p.tileAt(0).number)
        assertEquals("None", p.tileAt(1).number)
        assertTrue(p.tiles.none { it.number.isBlank() || it.number == "–" })
        assertEquals(listOf(TripsPageRules.TRIPS), p.cards.map { it.key })
        assertEquals(TripsPageRules.TRIPS_EMPTY, p.cardAt(0).empty)
        assertEquals(listOf("A trip", "A booking"), p.addChoices.map { it.label })
    }

    @Test
    fun aTripToComeShowsHowSoonItsBookingsAndDocuments() {
        val p = page(listOf(bcn))
        assertEquals("5 days", p.tileAt(0).number)
        assertEquals("to Barcelona", p.tileAt(0).label)
        assertTrue(p.tileAt(0).lit)
        assertEquals("3", p.tileAt(1).number)
        assertEquals("bookings for Barcelona", p.tileAt(1).label)
        // The GHIC runs out during the trip: one to check, in brass.
        assertEquals("1", p.tileAt(2).number)
        assertTrue(p.tileAt(2).lit)
        assertEquals(listOf(TripsPageRules.TRIPS, TripsPageRules.BOOKED, TripsPageRules.DOCS), p.cards.map { it.key })
        assertEquals("Booked for Barcelona", p.cardAt(1).title)
        val trip = p.cardAt(0).rowAt(0)
        assertEquals("trip:b", trip.key)
        assertEquals("In 5 days", trip.whenText)
        assertTrue(trip.lit)
        assertTrue(trip.line.length <= TripsPageRules.SHORT_LINE)
        // Each booking line a row in the timeline's order (a day's untimed first), the check-out its own, a time on
        // the right when there is one.
        val booked = p.cardAt(1).rows
        assertEquals(listOf("Hotel Arts", "Flight BA478", "Check out · Hotel Arts", "Flight BA479"), booked.map { it.name })
        assertEquals("", booked[0].whenText)
        assertEquals("07:40", booked[1].whenText)
        val gh = p.cardAt(2).rows.first { it.name == "GHIC" }
        assertEquals(TripsPageRules.CHECK, gh.whenText)
        assertTrue(gh.lit)
    }

    @Test
    fun onATripTheFirstNumberSaysWhichDayAndBookedStartsToday() {
        val on = d(11, 16)
        val p = page(listOf(bcn), on)
        assertEquals("Day 3 of 5", p.tileAt(0).number)
        assertEquals("in Barcelona", p.tileAt(0).label)
        val booked = p.cardAt(1).rows.map { it.name }
        assertFalse("Flight BA478" in booked)
        assertTrue("Flight BA479" in booked)
    }

    @Test
    fun noVaultReadMeansNoDocumentsCardAndPastTripsHaveTheirOwn() {
        val p = page(listOf(bcn, rome), withDocs = null)
        assertEquals(listOf(TripsPageRules.TRIPS, TripsPageRules.BOOKED, TripsPageRules.PAST), p.cards.map { it.key })
        assertEquals("None", p.tileAt(2).number)
        assertEquals("Rome", p.cardAt(2).rowAt(0).name)
        assertEquals("trip:r", p.cardAt(2).rowAt(0).key)
    }

    @Test
    fun rowsKeysOpenTheirThingUntilItGoes() {
        val v = TripRules.view(listOf(bcn, rome), today, docs)
        assertEquals("Barcelona", TripsPageRules.trip(v, "trip:b")?.name)
        assertEquals("Rome", TripsPageRules.trip(v, "trip:r")?.name)
        val key = TripsPageRules.bookingKey("bk-f1")
        assertEquals("f1", TripsPageRules.booking(v, key)?.bookingId)
        assertEquals("Barcelona", TripsPageRules.bookingTrip(v, key)?.name)
        assertEquals(d(11, 14), TripsPageRules.bookingDay(v, key)?.epochDay)
        assertEquals("Flight BA478", TripsPageRules.sheetTitle(v, key, today))
        assertNotNull(TripsPageRules.doc(v, "doc:2", today))
        val gone = TripRules.view(listOf(bcn.copy(bookings = bcn.bookings.drop(1))), today, docs)
        assertNull(TripsPageRules.booking(gone, key))
        assertNull(TripsPageRules.trip(gone, "trip:r"))
        assertEquals(TripsPageRules.TITLE, TripsPageRules.sheetTitle(gone, key, today))
    }

    @Test
    fun fiveRowsAThenSeeAll() {
        val many = (1..7).map { TripItem("t$it", "Trip $it", d(12, it * 2), d(12, it * 2 + 1), 0) }
        val c = page(many).cardAt(0)
        assertEquals(5, c.rowCount)
        assertEquals(7, c.count)
        assertEquals(LifePageRules.SEE_ALL, c.more)
        assertEquals(7, TripsPageRules.allRows(TripRules.view(many, today), TripsPageRules.TRIPS, today).size)
    }

    @Test
    fun aLongLineKeepsWholePartsThatFit() {
        assertEquals("Sat 14 – Wed 18 Nov · 5 days", TripsPageRules.shortLine("Sat 14 – Wed 18 Nov · 5 days · 3 bookings").replace(LifeTileRules.GLUE[0], ' '))
        val cut = TripsPageRules.shortLine("A very long single part that has no dots at all in it anywhere")
        assertTrue(cut.endsWith("…"))
        assertTrue(cut.length <= TripsPageRules.SHORT_LINE)
    }
}
