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

    @Test
    fun theDvlasAnswerShowsFillsANewDateAndOffersItsDayWhenTheRadarDiffers() {
        at(2026, 10, 12)
        val now = world.clock.nowMs
        val mot = CivilDate.toEpochDay(2026, 11, 2)
        val tax = CivilDate.toEpochDay(2026, 12, 1)
        val check = CarCheck(
            "AB12CDE", found = true, make = "FORD", motDueDay = mot, taxStatus = "Taxed", taxDueDay = tax,
            lastTestDay = CivilDate.toEpochDay(2025, 11, 3), lastPassed = true, advisories = 2, checkedMs = now,
        )
        // Written and read back as the server writes it.
        assertEquals(check, CarCheckRules.read(CarCheckRules.fields(check)))
        assertNull(CarCheckRules.read(emptyMap()))
        assertTrue(CarCheckRules.due("AB12CDE", null, now))
        assertFalse(CarCheckRules.due("AB12CDE", check, now + 3_600_000L))
        assertTrue(CarCheckRules.due("AB12CDE", check, now + CarCheckRules.PERIOD_MS))
        assertTrue(CarCheckRules.due("XY99ZZZ", check, now))
        assertFalse(CarCheckRules.due(null, check, now))

        val cal = LocalCalendar.UTC
        var v = CarRules.view("AB12CDE", r.items(), check, today(), cal)
        assertEquals("DVLA · Ford · MOT due Mon 2 Nov · taxed until Tue 1 Dec · checked today", v.dvlaLine)
        assertEquals("MOT history · passed 3 Nov 2025 · 2 advisories", v.historyLine)
        assertEquals("DVLA: due Mon 2 Nov · reminders a month and a week before", v.rows[0].line)
        assertEquals(tax, v.rows[1].dvlaDay)
        assertNull(v.rows[2].dvlaDay)
        // An answer for another plate is ignored.
        assertNull(CarRules.view("XY99ZZZ", r.items(), check, today(), cal).dvlaLine)

        // Adding the MOT takes the DVLA's day, not the placeholder.
        val id = r.addCarDate("mot", today(), "AB12CDE", check)
        assertEquals(mot, r.items().single { it.id == id }.dueDay)
        v = CarRules.view("AB12CDE", r.items(), check, today(), cal)
        assertNull(v.rows[0].dvlaOffer)
        // Meka typed another day: the row says the DVLA's and offers it.
        r.setDue(id, mot + 3)
        v = CarRules.view("AB12CDE", r.items(), check, today(), cal)
        assertEquals("Use the DVLA's date · Mon 2 Nov", v.rows[0].dvlaOffer)
        assertTrue(v.rows[0].line.endsWith("· the DVLA says Mon 2 Nov"))

        // Not known to the DVLA; untaxed; a failed test; a date next year.
        assertEquals("DVLA has no vehicle AB12 CDE · check the registration", CarCheckRules.line(CarCheck("AB12CDE", found = false), "AB12CDE", today()))
        val untaxed = check.copy(taxStatus = "Untaxed", taxDueDay = CivilDate.toEpochDay(2026, 8, 1), lastPassed = false, advisories = 0)
        assertEquals("untaxed · tax was due Sat 1 Aug", CarCheckRules.taxLine(untaxed, today()))
        assertNull(CarCheckRules.dayFor("tax", untaxed))
        assertEquals("MOT history · failed 3 Nov 2025", CarCheckRules.historyLine(untaxed))
        assertEquals("Tue 2 Nov 2027", CarCheckRules.dayLabel(CivilDate.toEpochDay(2027, 11, 2), today()))
    }

    @Test
    fun askAndTalkHearTheCarTheDvlasAnswerAndEachDate() {
        at(2026, 10, 12)
        val cal = LocalCalendar.UTC
        // Nothing typed, nothing tracked: no lines.
        assertEquals(emptyList(), CarAskRules.askLines(CarRules.view(null, r.items(), null, today(), cal), today()))
        // A registration but no DVLA answer yet: says so; untracked dates are "not on the radar".
        val mot = r.addCarDate("mot", today(), "AB12CDE")
        r.setDue(mot, CivilDate.toEpochDay(2026, 11, 5))
        assertEquals(listOf(
            "Car · AB12 CDE · 1 of 4 dates on the radar",
            CarAskRules.NO_ANSWER,
            "MOT · " + CarRules.view("AB12CDE", r.items()).rows[0].line,
            "Car tax · not on the radar",
            "Car insurance · not on the radar",
            "Car service · not on the radar",
        ), CarAskRules.askLines(CarRules.view("AB12CDE", r.items(), null, today(), cal), today()))
        // With the DVLA's answer: its line, the history, the differing MOT day and the tax day for an untracked date.
        val check = CarCheck(
            "AB12CDE", found = true, make = "FORD", motDueDay = CivilDate.toEpochDay(2026, 11, 2), taxStatus = "Taxed",
            taxDueDay = CivilDate.toEpochDay(2026, 12, 1), lastTestDay = CivilDate.toEpochDay(2025, 11, 3), lastPassed = true,
            advisories = 2, checkedMs = world.clock.nowMs,
        )
        val lines = CarAskRules.askLines(CarRules.view("AB12CDE", r.items(), check, today(), cal), today())
        assertEquals("DVLA · Ford · MOT due Mon 2 Nov · taxed until Tue 1 Dec · checked today", lines[1])
        assertEquals("MOT history · passed 3 Nov 2025 · 2 advisories", lines[2])
        assertTrue(lines[3].startsWith("MOT · On the radar") && lines[3].endsWith("· the DVLA says Mon 2 Nov"))
        assertEquals("Car tax · not on the radar · the DVLA says due Tue 1 Dec", lines[4])
        assertEquals(CarAskRules.ASK_LINES, lines.size)
        // A date tracked but no registration: still told, without a DVLA line.
        r.setDue(mot, CivilDate.toEpochDay(2026, 11, 2))
        val noReg = CarAskRules.askLines(CarRules.view(null, r.items(), check, today(), cal), today())
        assertEquals("Car · registration not typed · 1 of 4 dates on the radar", noReg.first())
        assertTrue(noReg.none { it.startsWith("DVLA") })
    }
}
