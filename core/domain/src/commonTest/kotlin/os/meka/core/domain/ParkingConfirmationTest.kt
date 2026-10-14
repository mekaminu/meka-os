package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Car and parking, slice 5: the parking timer from a shared confirmation, read on the device. Invented plates and codes. */
class ParkingConfirmationTest {
    private val cal = LocalCalendar.UTC
    private val day = 20_740L // Wed 14 Oct 2026
    private fun at(h: Int, m: Int = 0, d: Long = day) = cal.toEpochMs(d, h * 60 + m)
    private val now = at(13, 40)

    private fun cars(vararg c: Pair<String, String?>) = CarsView(
        c.mapIndexed { i, (t, reg) -> CarView.EMPTY.copy(carId = CarRules.CAR_IDS[i], title = t, registration = reg) }, true,
    )

    private fun read(text: String, cars: CarsView = CarsView.EMPTY, at: Long = now) = ParkingConfirmationRules.read(text, null, at, cal, cars)

    @Test
    fun theProvidersWordingsRead() {
        val ringgo = read("RingGo: Your parking session has started.\nLocation 81234\nVehicle AB12CDE\nExpires: 15:30 on 14/10/2026\nCost £3.40")!!
        assertEquals(at(15, 30), ringgo.endsAtMs)
        assertEquals("RingGo 81234", ringgo.note)
        assertFalse(ringgo.ended)

        // JustPark's booking: a range with dates either side.
        val jp = read("JustPark booking confirmed · Wed 14 Oct 13:30 - Wed 14 Oct 17:00 · Space at Oakfield Road")
        assertEquals(at(17), jp?.endsAtMs)
        assertEquals(at(13, 30), jp?.startedAtMs)
        assertEquals("JustPark", jp?.note)

        // PayByPhone: 12-hour clock.
        assertEquals(at(16, 15), read("PayByPhone parking receipt. Location number 4521. Expiry time 4:15 PM")?.endsAtMs)
        assertEquals("PayByPhone 4521", read("PayByPhone parking receipt. Location number 4521. Expiry time 4:15 PM")?.note)

        // No provider, but a parking session that says when it ends.
        val plain = read("Thanks for parking with us. Your session is valid until 18:00 today.")
        assertEquals(at(18), plain?.endsAtMs)
        assertNull(plain?.note)

        // A start and a duration.
        assertEquals(at(15, 40), read("Parking session · Start time 13:40 · Duration 2 hours")?.endsAtMs)
        assertEquals(at(14, 10), read("RingGo · started 13:40 · for 30 mins")?.endsAtMs)

        // An ISO date and a word date.
        assertEquals(at(9, d = day + 1), read("RingGo · Expires 2026-10-15 09:00")?.endsAtMs)
        assertEquals(at(9, d = day + 1), read("Your RingGo parking expires at 09:00 on 15th October 2026")?.endsAtMs)
    }

    @Test
    fun anOvernightSessionEndsTomorrowAndAnOldOneHasEnded() {
        assertEquals(at(8, d = day + 1), read("NCP parking ticket · from 20:00 to 08:00", at = at(20, 5))?.endsAtMs)
        val old = read("RingGo parking session · Expires 12:30")!!
        assertTrue(old.ended)
        val view = ParkingConfirmationRules.view("RingGo parking session · Expires 12:30", null, now, cal, CarsView.EMPTY)
        assertEquals("That parking ended at 12:30 · nothing to remind you of", view?.line)
        assertTrue(view!!.ended)
        // Over a day away, or a week gone: not a timer.
        assertNull(read("RingGo · Expires 15:30 on 20/10/2026"))
        assertNull(read("RingGo · Expires 15:30 on 01/10/2026"))
    }

    @Test
    fun notAConfirmationIsNotRead() {
        assertNull(read("Meeting at 15:30 about the budget"))
        assertNull(read("Dentist until 15:30"))
        assertNull(read("RingGo")) // a parking word but no time
        assertFalse(ParkingConfirmationRules.looksLikeOne("Lunch at 1pm"))
        assertTrue(ParkingConfirmationRules.looksLikeOne("JustPark"))
        // A price is never a time.
        assertNull(read("RingGo parking · paid £2.50"))
    }

    @Test
    fun theRegistrationPicksTheCarElseHeIsAsked() {
        val two = cars("the Golf" to "AB12 CDE", "the family car" to "XY34 ZZZ")
        val text = "RingGo · Vehicle XY34ZZZ · Location 81234 · Expires 15:30"
        val v = ParkingConfirmationRules.view(text, null, now, cal, two)!!
        assertEquals("car2", v.carId)
        assertEquals("Parking timer until 15:30 · the family car · RingGo 81234", v.line)
        assertEquals(0, v.choiceCount)

        val noPlate = ParkingConfirmationRules.view("RingGo · Location 81234 · Expires 15:30", null, now, cal, two)
        assertNull(noPlate?.carId)
        assertEquals(listOf("car", "car2"), noPlate!!.choices.map { it.first })
        assertEquals("Parking timer until 15:30 · RingGo 81234", noPlate.line)

        // One car: that one, and no car named in the line.
        val one = ParkingConfirmationRules.view("RingGo · Location 81234 · Expires 15:30", null, now, cal, cars("Car" to null))
        assertEquals("car", one?.carId)
        assertEquals("Parking timer until 15:30 · RingGo 81234", one?.line)
    }

    @Test
    fun aPastedConfirmationInTheTimerFieldStartsIt() {
        val w = SyncWorld()
        val timers = ParkingTimers(w.device("android").replica)
        val pasted = "Your RingGo parking session has started. Location 81234. Vehicle AB12CDE. Expires 15:30 on 14/10/2026. Cost £3.40"
        val t = timers.set("car", pasted, now, cal)
        assertEquals(at(15, 30), t?.endsAtMs)
        assertEquals("RingGo 81234", t?.note)
        // A short typed line still reads as before.
        assertEquals("RingGo 81234", timers.set("car", "till 6pm RingGo 81234", now, cal)?.note)
        assertEquals(at(18), timers.timer("car")?.endsAtMs)
        // An ended paste starts nothing.
        assertNull(timers.set("car2", "Your RingGo parking session has started. Location 81234. Expires 12:30 on 14/10/2026.", now, cal))
    }
}
