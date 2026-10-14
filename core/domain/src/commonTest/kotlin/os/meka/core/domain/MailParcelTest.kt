package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailParcelTest {
    /** Wed 14 Oct 2026. */
    private val day = CivilDate.toEpochDay(2026, 10, 14)
    private val at = day * CivilDate.DAY_MS + 9 * 3_600_000L

    private fun read(subject: String?, preview: String? = null, from: String = "auto-confirm@shop.example", name: String? = "Shop", id: String = "m1", d: Long = day) =
        ParcelRules.read(id, name, from, subject, preview, d * CivilDate.DAY_MS + 9 * 3_600_000L, d)

    @Test
    fun theDayAndStateFromTheWords() {
        assertEquals(ParcelState.COMING to day + 2, read("Your order has been dispatched", "Arriving Friday")!!.let { it.state to it.day })
        assertEquals(ParcelState.COMING to day, read("Arriving today", "Your order 112-4455")!!.let { it.state to it.day })
        assertEquals(day + 1, read("Your parcel will be delivered tomorrow between 10:15 and 11:15")!!.day)
        assertEquals(CivilDate.toEpochDay(2026, 10, 20), read("Order shipped", "Expected delivery: Tue 20 Oct")!!.day)
        assertEquals(ParcelState.OUT_FOR_DELIVERY, read("Your parcel is out for delivery", from = "noreply@dpd.co.uk", name = "DPD")!!.state)
        assertEquals(ParcelState.DELIVERED, read("Delivered: your Shop order")!!.state)
        assertEquals(ParcelState.DELIVERED, read("Your parcel has been delivered", "Delivered to your safe place at 14:02")!!.state)
        // A promise to deliver is not a delivery.
        assertEquals(ParcelState.COMING, read("Your order will be delivered on Thursday")!!.state)
    }

    @Test
    fun whoAndWhenItComes() {
        val dpd = read("Your Shop parcel is on its way", "Arriving tomorrow 9am-1pm", from = "noreply@dpdlocal.co.uk", name = "DPD")!!
        assertEquals("DPD", dpd.carrier)
        assertNull(dpd.merchant)
        assertEquals("09:00–13:00", dpd.window)
        assertEquals("10:15–11:15", ParcelRules.window("between 10:15 and 11:15"))
        assertEquals("11:00–13:00", ParcelRules.window("between 11 and 1pm"))
        val shop = read("Your order 112-4455 has shipped with Royal Mail", "Arriving Friday")!!
        assertEquals("Shop", shop.merchant)
        assertEquals("Royal Mail", shop.carrier)
        assertEquals("112-4455", shop.ref)
    }

    @Test
    fun nothingWhenItIsUnsure() {
        assertNull(read("Your order has been dispatched", "Track it in the app")) // no day
        assertNull(read("We missed you", "We couldn't deliver your parcel today"))
        assertNull(read("Your flight", "Arrive Barcelona 14 Nov 10:40")) // not a parcel
        assertNull(read("Order shipped", "Arriving 1 Oct")) // a day before the email
        assertNull(read(null, null))
    }

    @Test
    fun emailsFoldIntoOneParcelAndADeliveryClearsIt() {
        val a = read("Your order 112-4455 has shipped", "Arriving Friday", id = "a")!!
        val b = read("Your order 112-4455 is out for delivery", "Between 10:15 and 11:15", id = "b", d = day + 2)!!
        val c = read("Delivered: order 112-4455", id = "c", d = day + 2)!!
        val other = read("Your order 99887766 has shipped", "Arriving Friday", id = "o")!!
        val stray = read("Your parcel has been delivered", from = "x@unknown.example", name = "Elsewhere", id = "s")!!
        val two = ParcelRules.fold(listOf(b, a, other, stray))
        assertEquals(2, two.size)
        val p = two.first { it.key == "112-4455" }
        assertEquals(ParcelState.OUT_FOR_DELIVERY, p.state)
        assertEquals("10:15–11:15", p.window)
        val done = ParcelRules.fold(listOf(a, b, c, other)).first { it.key == "112-4455" }
        assertEquals(ParcelState.DELIVERED, done.state)
        // Without a number, the shop's latest parcel takes the delivery.
        val x = read("Your order has shipped", "Arriving Friday", id = "x")!!
        val y = read("Your parcel has been delivered", id = "y", d = day + 2)!!
        assertEquals(ParcelState.DELIVERED, ParcelRules.fold(listOf(x, y)).single().state)
    }

    @Test
    fun todaysLine() {
        fun parcel(id: String, state: ParcelState, d: Long = day, name: String? = "Amazon", window: String? = null) =
            Parcel(id, "Personal", state, d, window, null, name, at)
        assertNull(ParcelRules.todayLine(emptyList(), day))
        assertNull(ParcelRules.todayLine(listOf(parcel("a", ParcelState.COMING, d = day + 1)), day))
        assertNull(ParcelRules.todayLine(listOf(parcel("a", ParcelState.DELIVERED)), day)) // it clears itself
        val one = ParcelRules.todayLine(listOf(parcel("a", ParcelState.OUT_FOR_DELIVERY, window = "10:15–11:15")), day)!!
        assertEquals("Parcel out for delivery · Amazon · 10:15–11:15", one.text)
        assertTrue(one.lit)
        assertEquals("Parcel out for delivery from Amazon, between 10:15 and 11:15.", one.spoken)
        val three = ParcelRules.todayLine(listOf(
            parcel("a", ParcelState.COMING), parcel("b", ParcelState.COMING, name = "Next"),
            parcel("c", ParcelState.COMING, name = "DPD"), parcel("d", ParcelState.DELIVERED, name = "Boots"),
        ), day)!!
        assertEquals("3 parcels arriving today · Amazon, Next + 1 · 1 delivered", three.text)
        assertEquals(false, three.lit)
        assertEquals(3, three.parcelCount)
    }

    @Test
    fun theEntityRoundTrips() {
        val f = ParcelRules.fold(listOf(read("Your order 112-4455 has shipped", "Arriving Friday")!!)).single()
        val fields = ParcelRules.fields(f, "Personal")
        assertEquals(FieldValue.Text(Trust.UNTRUSTED_EXTERNAL.name), fields[ParcelFields.TRUST])
        val p = ParcelRules.read("pc1", fields)!!
        assertEquals("Personal", p.account)
        assertEquals(day + 2, p.day)
        assertEquals("Shop", p.name)
        assertEquals(ParcelRules.entityId("acc", "112-4455"), ParcelRules.entityId("acc", "112-4455"))
    }
}
