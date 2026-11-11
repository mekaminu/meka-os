package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Meka 2026-11-11 (no Also today tile may be a dead end): the PARCELS tile unfolds every parcel ([ParcelRules.detail]). */
class ParcelDetailTest {
    private val today = 20_400L
    private fun p(id: String, state: ParcelState, day: Long = today, window: String? = null, carrier: String? = null, merchant: String? = null, at: Long = 0L) =
        Parcel(id, "Mail", state, day, window, carrier, merchant, at)

    @Test
    fun noDetailWhenTheTileWouldNotShow() {
        assertNull(ParcelRules.detail(emptyList(), today))
        assertNull(ParcelRules.detail(listOf(p("a", ParcelState.COMING, day = today + 2)), today))
    }

    @Test
    fun everyParcelTodayThenDeliveredThenYesterdaysLateOnes() {
        val d = ParcelRules.detail(
            listOf(
                p("a", ParcelState.COMING, window = "14:00–16:00", carrier = "DPD", merchant = "Oakfield Books"),
                p("b", ParcelState.OUT_FOR_DELIVERY, window = "10:15–11:15", carrier = "Evri", merchant = "Shop One"),
                p("c", ParcelState.DELIVERED, window = "09:00–10:00", carrier = "Royal Mail", merchant = "Shop Two"),
                p("d", ParcelState.COMING, day = today - 1, carrier = "DPD"),
            ),
            today,
        )!!
        assertEquals("Parcels today", d.title)
        assertEquals(listOf("Shop One", "Oakfield Books", "Shop Two", "DPD"), d.rows.map { it.name })
        assertEquals(listOf(ParcelRules.SAYS_OUT, ParcelRules.SAYS_ARRIVING, ParcelRules.SAYS_DELIVERED, ParcelRules.SAYS_LATE), d.rows.map { it.status })
        assertEquals("10:15–11:15 · with Evri", d.rows[0].meta)
        // A delivered parcel's window no longer matters; a carrier that is also the name isn't said twice.
        assertEquals("with Royal Mail", d.rows[2].meta)
        assertEquals("", d.rows[3].meta)
        assertEquals(listOf(false, false, false, true), d.rows.map { it.bad })
        assertEquals("From your shipping emails", d.note)
        assertTrue(d.spoken.startsWith("Parcels today. Shop One: out for delivery, 10:15 to 11:15, with Evri."), d.spoken)
    }

    @Test
    fun anOfficeDaySaysSo() {
        val d = ParcelRules.detail(listOf(p("a", ParcelState.COMING, merchant = "Shop One")), today, officeDay = true)!!
        assertEquals("Parcels today · you're at the office", d.title)
        assertTrue(d.spoken.startsWith("Parcels today, while you're at the office."))
        // Only yesterday's late one: no office note, it's just asking whether it came.
        val late = ParcelRules.detail(listOf(p("b", ParcelState.COMING, day = today - 1)), today, officeDay = true)!!
        assertEquals("Parcels today", late.title)
        assertEquals("A parcel", late.rows.single().name)
    }
}
