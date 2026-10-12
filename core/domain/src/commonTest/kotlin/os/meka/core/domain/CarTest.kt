package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CarTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private var n = 0
    private fun ids(): String = "C${n++}"

    private val d = world.device("android")
    private val r = Renewals(d.replica, ::ids, { world.clock.nowMs })
    private val car = Car(d.replica)

    private fun today() = world.clock.nowMs.floorDiv(dayMs)
    private fun at(y: Int, m: Int, day: Int, hour: Int = 10) { world.clock.nowMs = CivilDate.toEpochDay(y, m, day) * dayMs + hour * 3_600_000L }

    @Test
    fun registrationsReadAndShowTheUsualWay() {
        assertEquals("AB12CDE", CarRules.normalise(" ab12 cde "))
        assertEquals("AB12CDE", CarRules.normalise("AB12-CDE"))
        assertEquals("A1MEK", CarRules.normalise("a1 mek"))
        assertNull(CarRules.normalise("  "))
        assertEquals("AB12 CDE", CarRules.display("AB12CDE"))
        assertEquals("A1MEK", CarRules.display("A1MEK"))
        assertFailsWith<ValidationException> { CarRules.normalise("my car") }
        assertFailsWith<ValidationException> { CarRules.normalise("ABCDEFG") }
        assertFailsWith<ValidationException> { CarRules.normalise("AB12CDEFG") }
        assertEquals("Type the registration, like AB12 CDE", CarRules.registrationError("??"))
        assertNull(CarRules.registrationError("AB12 CDE"))

        assertNull(car.registration())
        assertTrue(car.setRegistration("ab12 cde"))
        assertFalse(car.setRegistration("AB12CDE"))
        assertEquals("AB12CDE", car.registration())
        assertTrue(car.setRegistration(""))
        assertNull(car.registration())
    }

    @Test
    fun theCarsDatesGoOnTheRadarOnceAndMatchWhatIsThere() {
        at(2026, 10, 12)
        var v = CarRules.view(null, r.items())
        assertEquals(listOf("mot", "tax", "insurance", "service"), v.rows.map { it.presetId })
        assertEquals("MOT, tax, insurance and service · type the registration once", v.summary)
        assertEquals("Every year · reminders a month and a week before · you set the date", v.rows.first().line)

        val mot = r.addCarDate("mot", today(), "AB12CDE")
        val item = r.items().single { it.id == mot }
        assertEquals(ObligationKind.MOT, item.kind)
        assertEquals(RenewalRepeat.YEARLY, item.repeats)
        assertEquals(CarRules.MONTH_DAYS, item.leadDays)
        assertEquals(today() + CarRules.PLACEHOLDER_DAYS, item.dueDay)
        assertEquals("AB12 CDE", item.subject)
        assertFailsWith<ValidationException> { r.addCarDate("mot", today(), null) }

        // Home insurance is not the car's; a typed "Admiral car insurance" is; a service kind is the service.
        r.add("Home insurance", ObligationKind.INSURANCE, today() + 40, RenewalRepeat.YEARLY)
        r.add("Admiral car insurance", ObligationKind.INSURANCE, today() + 50, RenewalRepeat.YEARLY)
        r.add("Garage", ObligationKind.CAR_SERVICE, today() + 60, RenewalRepeat.HALF_YEARLY)
        v = CarRules.view("AB12CDE", r.items())
        assertEquals(listOf(true, false, true, true), v.rows.map { it.tracked })
        assertEquals("AB12 CDE · 3 of 4 on the radar", v.summary)
        assertTrue(v.rows[2].line.startsWith("On the radar · renews"))
        assertEquals("Serviced", r.items().single { it.title == "Garage" }.doneLabel)
        assertFalse(CarRules.isCar(r.items().single { it.title == "Home insurance" }))
        // "motor" isn't "mot".
        assertFalse(CarRules.matches(CarRules.preset("mot")!!, r.items().single { it.title == "Admiral car insurance" }))
    }

    @Test
    fun remindersComeFourWeeksAndAWeekBeforeAtNine() {
        at(2026, 10, 12)
        val id = r.addCarDate("mot", today(), "AB12CDE")
        val due = CivilDate.toEpochDay(2026, 11, 30)
        r.setDue(id, due)
        val cal = LocalCalendar.UTC
        fun on(day: Long) = CarRules.notices(r.items(), "AB12CDE", day * dayMs + 8 * 3_600_000L, cal)

        assertTrue(on(due - 29).isEmpty())
        val month = on(due - 28).single()
        assertEquals("MOT due Mon 30 Nov · AB12 CDE", month.title)
        assertEquals("In 4 weeks · an MOT up to a month early keeps the same date", month.text)
        assertEquals(NoticeSource.CAR, month.source)
        assertEquals((due - 28) * dayMs + 9 * 3_600_000L, month.atMs)
        assertTrue(on(due - 20).isEmpty())
        val week = on(due - 7).single()
        assertEquals("In a week · an MOT up to a month early keeps the same date", week.text)
        assertTrue(week.key != month.key)
        assertTrue(on(due).isEmpty())

        // Not a car date: no reminders of its own.
        r.add("Netflix", ObligationKind.SUBSCRIPTION, due, RenewalRepeat.MONTHLY)
        assertEquals(1, on(due - 7).size)
    }
}
