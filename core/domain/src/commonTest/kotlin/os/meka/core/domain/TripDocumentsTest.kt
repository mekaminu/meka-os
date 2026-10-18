package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Travel mode × the Vault: a trip's documents, whether each lasts the trip, and Ask's answer. */
class TripDocumentsTest {
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val today = d(10, 18)
    private val copy = VaultFileRef("f1", VaultFileRules.JPEG, 412_000, "k1", 0, "Fold")
    private val bcn = TripItem("b", "Barcelona", d(11, 14), d(11, 18), 0)
    private val docs = listOf(
        DocumentItem("1", "Alex passport", DocKind.PASSPORT, d(5, 1, 2027), 0, file = copy),
        DocumentItem("2", "Sam passport", DocKind.PASSPORT, d(3, 3, 2030), 0),
        DocumentItem("3", "GHIC", DocKind.HEALTH_CARD, d(11, 16), 0),
        DocumentItem("4", "Driving licence", DocKind.DRIVING_LICENCE, d(1, 1, 2030), 0),
        DocumentItem("5", "Travel insurance", DocKind.INSURANCE, d(9, 1, 2027), 0),
        DocumentItem("6", "Home insurance", DocKind.INSURANCE, d(9, 1, 2027), 0),
        DocumentItem("7", "Railcard", DocKind.RAILCARD, d(5, 1, 2027), 0),
    )

    @Test
    fun theTripShowsItsTravelDocumentsWithWhatToCheckFirst() {
        val v = assertNotNull(TripDocRules.forTrip(bcn, docs, today))
        // No car booked: no licence; home insurance and the railcard aren't travel documents.
        assertEquals(listOf("Alex passport", "GHIC", "Sam passport", "Travel insurance"), v.rows.map { it.title })
        // Six months after 18 Nov is late May: 1 May 2027 is under it.
        assertEquals("Expires Sat 1 May 2027 · 5 months after you're back · many countries want 6 months left", v.rows[0].line)
        assertTrue(v.rows[0].lit)
        assertTrue(v.rows[0].hasCopy)
        assertEquals("Runs out during the trip · Mon 16 Nov 2026", v.rows[1].line)
        assertTrue(v.rows[1].lit)
        assertEquals("Lasts the trip · expires Sun 3 Mar 2030", v.rows[2].line)
        assertFalse(v.rows[2].lit)
        assertEquals("4 documents for this trip · 2 to check · 1 with copies", v.summary)
        assertTrue(v.summaryLit)
    }

    @Test
    fun aCarBookingBringsTheLicenceAndAnEndedTripShowsNothing() {
        val withCar = bcn.copy(bookings = listOf(TripBooking("c", "b", BookingKind.CAR, "Car hire", d(11, 14), null, null, null, 0)))
        assertTrue(TripDocRules.forTrip(withCar, docs, today)!!.rows.any { it.title == "Driving licence" })
        assertNull(TripDocRules.forTrip(bcn, docs, d(11, 19)))
        val empty = TripDocRules.forTrip(bcn, emptyList(), today)!!
        assertTrue(empty.isEmpty)
        assertEquals(TripDocRules.EMPTY, empty.summary)
        val fine = TripDocRules.forTrip(bcn, docs.subList(1, 2), today)!!
        assertEquals("1 document for this trip · it lasts the trip", fine.summary)
        assertFalse(fine.summaryLit)
    }

    @Test
    fun anExpiredOrEarlyExpiryIsLit() {
        val old = DocumentItem("x", "Old passport", DocKind.PASSPORT, d(9, 1), 0)
        assertEquals("Expired Tue 1 Sep 2026 · renew it before you go" to true, TripDocRules.check(old, bcn, today))
        val soon = old.copy(expiresDay = d(11, 1))
        assertEquals("Runs out before the trip · Sun 1 Nov 2026" to true, TripDocRules.check(soon, bcn, today))
        assertEquals("No expiry date in the Vault" to false, TripDocRules.check(old.copy(expiresDay = null), bcn, today))
    }

    @Test
    fun theTripsViewCarriesTheDocumentsWhenTheVaultWasRead() {
        assertNull(TripRules.view(listOf(bcn), today).rows.single().documents)
        assertEquals(4, TripRules.view(listOf(bcn), today, docs).rows.single().documents!!.rows.size)
    }

    @Test
    fun askAnswersAboutATripsDocuments() {
        val trips = listOf(bcn)
        val a = assertNotNull(TripDocRules.askAnswer("Are our passports OK for Barcelona?", trips, docs, today))
        assertTrue(a.startsWith("For Barcelona (Sat 14 Nov – Wed 18 Nov): Alex passport: expires Sat 1 May 2027"), a)
        assertTrue(a.endsWith("Worth checking before you go."), a)
        // "The trip" with one trip coming up.
        assertNotNull(TripDocRules.askAnswer("what documents do we need for the trip?", trips, docs, today))
        // Not about a trip's documents.
        assertNull(TripDocRules.askAnswer("when does my passport run out?", trips, docs, today))
        assertNull(TripDocRules.askAnswer("what's on in Barcelona?", trips, docs, today))
        assertNull(TripDocRules.askAnswer("how do I add a passport for the trip?", trips, docs, today))
        assertEquals(
            "For Barcelona (Sat 14 Nov – Wed 18 Nov): there are no passports or travel cards in your Vault yet. Add each with the day it runs out and Navilife checks them against the trip.",
            TripDocRules.askAnswer("is my passport ok for Barcelona?", trips, emptyList(), today),
        )
    }
}
