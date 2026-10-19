package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Deliveries completeness: the office-day flag, yesterday's parcel that didn't show, and Ask's answers. */
class ParcelCompletenessTest {
    private val mon = CivilDate.toEpochDay(2026, 10, 12)
    private fun parcel(id: String, day: Long, state: ParcelState = ParcelState.COMING, window: String? = null, carrier: String? = null, merchant: String? = "Shop", lastAt: Long = 0L) =
        Parcel(id, "Personal", state, day, window, carrier, merchant, lastAt)

    @Test
    fun anOfficeDayIsFlaggedAndLit() {
        val l = ParcelRules.todayLine(listOf(parcel("a", mon, window = "10:15–11:15")), mon, officeDay = true)!!
        assertEquals("Parcel arriving today · Shop · 10:15–11:15 · you're at the office", l.text)
        assertEquals("Parcel arriving today from Shop, between 10:15 and 11:15, while you're at the office.", l.spoken)
        assertTrue(l.lit)
        assertFalse(ParcelRules.todayLine(listOf(parcel("a", mon)), mon)!!.lit)
    }

    @Test
    fun yesterdaysParcelIsAskedAboutForOneDay() {
        val late = parcel("a", mon - 1, merchant = "Alpha")
        val l = ParcelRules.todayLine(listOf(late), mon)!!
        assertEquals("Not arrived? · Alpha was due yesterday", l.text)
        assertEquals("Your parcel from Alpha was due yesterday and no email has said it's delivered, so check it came.", l.spoken)
        assertEquals(0, l.parcelCount)
        assertFalse(l.lit)
        // With today's: after it.
        val both = ParcelRules.todayLine(listOf(late, parcel("b", mon, merchant = "Beta")), mon)!!
        assertEquals("Parcel arriving today · Beta · Alpha was due yesterday", both.text)
        assertTrue(both.spoken.endsWith("Beta. Your parcel from Alpha was due yesterday and no email has said it's delivered, so check it came."))
        // Two late ones.
        assertEquals("Not arrived? · Alpha, Gamma were due yesterday",
            ParcelRules.todayLine(listOf(late, parcel("c", mon - 1, merchant = "Gamma", lastAt = 1)), mon)!!.text)
        // Delivered yesterday, or two days late: nothing.
        assertNull(ParcelRules.todayLine(listOf(parcel("d", mon - 1, ParcelState.DELIVERED)), mon))
        assertNull(ParcelRules.todayLine(listOf(parcel("e", mon - 2)), mon))
    }

    @Test
    fun askKnowsWhatIsAParcelQuestion() {
        assertTrue(ParcelAskRules.isAbout("any parcels coming?"))
        assertTrue(ParcelAskRules.isAbout("Where's my Amazon parcel"))
        assertTrue(ParcelAskRules.isAbout("is anything being delivered today"))
        assertFalse(ParcelAskRules.isAbout("how do I set up deliveries?"))
        assertFalse(ParcelAskRules.isAbout("is this delivery text a scam?"))
        assertFalse(ParcelAskRules.isAbout("what's the weather tomorrow?"))
        assertNull(ParcelAskRules.answer("what's for dinner?", listOf(parcel("a", mon)), mon))
    }

    @Test
    fun askAnswersFromTheParcels() {
        val ps = listOf(
            parcel("a", mon, ParcelState.OUT_FOR_DELIVERY, window = "10:15–11:15", carrier = "DPD", merchant = "Alpha"),
            parcel("b", mon + 3, merchant = "Beta"),
            parcel("c", mon, ParcelState.DELIVERED, merchant = "Gamma"),
            parcel("old", mon - 5, merchant = "Old"),
        )
        assertEquals(
            "2 parcels on their way: Alpha is out for delivery today between 10:15 and 11:15; Beta on Thu 15 Oct. Delivered: Gamma today.",
            ParcelAskRules.answer("any parcels coming?", ps, mon),
        )
        // Named by the carrier.
        assertEquals("One parcel on its way: Alpha is out for delivery today between 10:15 and 11:15.",
            ParcelAskRules.answer("when's the DPD delivery?", ps, mon))
        assertEquals("No parcels on their way in your shipping emails from the last two weeks.",
            ParcelAskRules.answer("any parcels coming?", emptyList(), mon))
        assertEquals("There's no Old parcel on its way in your shipping emails from the last two weeks.",
            ParcelAskRules.answer("where's my Old parcel?", ps, mon))
    }
}
