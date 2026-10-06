package os.meka.core.domain

import os.meka.core.testing.Device
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RenewalsTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private var n = 0
    private fun ids(): String = "R${n++}"
    private fun renewals(d: Device) = Renewals(d.replica, ::ids, { world.clock.nowMs })

    private val d = world.device("android")
    private val r = renewals(d)

    private fun today() = world.clock.nowMs.floorDiv(dayMs)

    @Test
    fun anMotShowsFourWeeksAheadAndIsDueOnItsDay() {
        val due = today() + 40
        val id = r.add("  Golf MOT ", ObligationKind.MOT, due, RenewalRepeat.YEARLY, cost = "54.85")
        val item = r.items().single()
        assertEquals(id, item.id)
        assertEquals("Golf MOT", item.title)
        assertEquals(28, item.leadDays)
        assertEquals(RenewalState.LATER, item.state)
        assertEquals("due ${CivilDate.shortLabel(due)} · £54.85 a year", item.meta)
        assertEquals("Every year", item.repeatLabel)
        assertEquals("MOT done", item.doneLabel)
        assertEquals(listOf(id), r.view().upcoming.map { it.id })
        assertNull(r.view().dueLine)

        world.clock.advance(12 * dayMs) // 28 days to go
        assertEquals(RenewalState.SOON, r.items().single().state)
        assertEquals("1 renewal due", r.view().dueLine)
        assertEquals(1, r.view().dueCount)

        world.clock.advance(28 * dayMs)
        assertEquals("due today · £54.85 a year", r.items().single().meta)
        world.clock.advance(dayMs)
        assertEquals(RenewalState.OVERDUE, r.items().single().state)
        assertEquals("was due ${CivilDate.shortLabel(due)} · £54.85 a year", r.items().single().meta)
    }

    @Test
    fun renewedMovesARepeatingOneOnAYearAndTheCancelByDayWithIt() {
        val due = CivilDate.toEpochDay(2026, 11, 12)
        world.clock.nowMs = CivilDate.toEpochDay(2026, 11, 1) * dayMs + 10 * 3_600_000L
        val id = r.add("Car insurance", ObligationKind.INSURANCE, due, RenewalRepeat.YEARLY, cost = "£412", cancelByDaysBefore = 7)
        val item = r.items().single()
        assertEquals(CivilDate.toEpochDay(2026, 11, 5), item.cancelByDay)
        assertEquals(RenewalState.CANCEL_BY, item.state)
        assertEquals("renews Thu 12 Nov · cancel by Thu 5 Nov · £412 a year", item.meta)
        assertEquals("1 to cancel or keep", r.view().dueLine)
        assertEquals("Cancelled it", item.stopLabel)

        r.done(id)
        val next = r.items().single()
        assertEquals(CivilDate.toEpochDay(2027, 11, 12), next.dueDay)
        assertEquals(CivilDate.toEpochDay(2027, 11, 5), next.cancelByDay)
        assertEquals(RenewalState.LATER, next.state)
        assertEquals(listOf(id), r.view().later.map { it.id })
        assertEquals("renews 12 Nov 2027 · cancel by 5 Nov 2027 · £412 a year", next.meta)
    }

    @Test
    fun aMonthlyBillOnThe31stKeepsItsDayAfterAShortMonth() {
        world.clock.nowMs = CivilDate.toEpochDay(2027, 1, 20) * dayMs
        val id = r.add("Council tax", ObligationKind.BILL, CivilDate.toEpochDay(2027, 1, 31), RenewalRepeat.MONTHLY, cost = "182")
        r.done(id)
        assertEquals(CivilDate.toEpochDay(2027, 2, 28), r.items().single().dueDay)
        r.done(id)
        assertEquals(CivilDate.toEpochDay(2027, 3, 31), r.items().single().dueDay)
        assertEquals("Paid", r.items().single().doneLabel)
    }

    @Test
    fun aOneOffLeavesWhenDoneAndStoppingTakesItOffTheRadar() {
        val a = r.add("Boiler service", ObligationKind.BOILER, today() + 10)
        val b = r.add("Netflix", ObligationKind.SUBSCRIPTION, today() + 2, RenewalRepeat.MONTHLY, cost = "10.99")
        assertEquals(listOf(a, b).toSet(), r.view().attention.map { it.id }.toSet())
        r.done(a)
        r.stop(b)
        assertTrue(r.items().isEmpty())
        assertEquals(0, r.view().yearlyPence)
    }

    @Test
    fun repeatingCostsAddUpToAMonthAndAYear() {
        r.add("Netflix", ObligationKind.SUBSCRIPTION, today() + 5, RenewalRepeat.MONTHLY, cost = "10.99")
        r.add("Home insurance", ObligationKind.INSURANCE, today() + 100, RenewalRepeat.YEARLY, cost = "240")
        r.add("Water", ObligationKind.BILL, today() + 20, RenewalRepeat.QUARTERLY, cost = "90")
        r.add("Passport", ObligationKind.LICENCE, today() + 200, cost = "88.50") // one-off: not a repeating cost
        val v = r.view()
        assertEquals(1099L * 12 + 24000 + 9000 * 4, v.yearlyPence)
        assertEquals("£60.99 a month · £731.88 a year in repeating costs", v.costLine)
        assertEquals("£90 every 3 months", v.all.first { it.title == "Water" }.meta.substringAfter(" · "))
    }

    @Test
    fun movingTheDueDayMovesTheRepeatAndCancelBy() {
        world.clock.nowMs = CivilDate.toEpochDay(2026, 10, 1) * dayMs
        val id = r.add("Gym", ObligationKind.SUBSCRIPTION, CivilDate.toEpochDay(2026, 10, 12), RenewalRepeat.MONTHLY, cancelByDaysBefore = 3)
        r.setDue(id, CivilDate.toEpochDay(2026, 10, 20))
        val moved = r.items().single()
        assertEquals(CivilDate.toEpochDay(2026, 10, 17), moved.cancelByDay)
        assertEquals(RenewalRepeat.MONTHLY, moved.repeats)
        r.done(id)
        assertEquals(CivilDate.toEpochDay(2026, 11, 20), r.items().single().dueDay)

        r.setCancelBy(id, null)
        assertNull(r.items().single().cancelByDay)
        r.setRepeat(id, RenewalRepeat.NONE)
        assertEquals("Doesn't repeat", r.items().single().repeatLabel)
        r.setLead(id, 61)
        assertEquals(RenewalState.SOON, r.items().single().state)
        r.setKind(id, ObligationKind.OTHER)
        assertEquals("due ${CivilDate.shortLabel(CivilDate.toEpochDay(2026, 11, 20))}", r.items().single().meta)
        r.setCost(id, "15")
        assertEquals(1500L, r.items().single().costPence)
        r.setCost(id, " ")
        assertNull(r.items().single().costPence)
    }

    @Test
    fun costsAreReadAsPoundsAndBadOnesAreRefused() {
        assertEquals(999L, RenewalRules.parseCost("9.99"))
        assertEquals(41200L, RenewalRules.parseCost("£412"))
        assertEquals(124050L, RenewalRules.parseCost("1,240.5"))
        assertEquals(1200L, RenewalRules.parseCost("£ 12"))
        assertNull(RenewalRules.parseCost(""))
        assertNull(RenewalRules.costError("12.50"))
        assertEquals("Type the cost in pounds, like 9.99", RenewalRules.costError("abc"))
        assertFailsWith<ValidationException> { RenewalRules.parseCost("ten pounds") }
        assertFailsWith<ValidationException> { RenewalRules.parseCost("9.999") }
        assertFailsWith<ValidationException> { r.add("Thing", ObligationKind.OTHER, today(), cost = "-3") }
        assertFailsWith<ValidationException> { r.add("  ", ObligationKind.OTHER, today()) }
        assertEquals("£1,240.50", RenewalRules.formatPence(124050))
        assertEquals("£1,000,000", RenewalRules.formatPence(100_000_000))
        assertEquals("£0.05", RenewalRules.formatPence(5))
        assertEquals(RenewalRepeat.YEARLY, RenewalRules.defaultRepeat(ObligationKind.MOT))
        assertEquals(RenewalRepeat.MONTHLY, RenewalRules.defaultRepeat(ObligationKind.SUBSCRIPTION))
        assertEquals("Due tomorrow", RenewalRules.dueButton(today() + 1, today()))
    }

    @Test
    fun renewingOnBothDevicesOfflineMovesItOnOnceWithoutAConflict() {
        val mac = world.device("mac")
        val mr = renewals(mac)
        val due = today() + 2
        val id = r.add("Spotify", ObligationKind.SUBSCRIPTION, due, RenewalRepeat.MONTHLY, cost = "11.99")
        d.sync(); mac.sync()
        d.goOffline(); mac.goOffline()
        r.done(id)
        mr.done(id)
        d.goOnline(); mac.goOnline()
        d.sync(); mac.sync(); d.sync()
        val a = r.items().single()
        val b = mr.items().single()
        assertEquals(a.dueDay, b.dueDay)
        assertEquals(Recurrence.MonthlyOnDay(1, CivilDate.fromEpochDay(due).day).next(due), a.dueDay)
        assertTrue(!a.hasConflict && !b.hasConflict)
    }

    @Test
    fun renewalsCountInTheListsDueLine() {
        val l = Lists(d.replica, ::ids, { world.clock.nowMs })
        l.addWaiting("Refund", chaseInDays = 0)
        r.add("Car tax", ObligationKind.CAR_TAX, today() + 1, RenewalRepeat.YEARLY)
        val v = l.view(d.tasks.all(), r.view())
        assertEquals("1 to chase · 1 renewal due", v.dueLine)
        assertEquals(2, v.dueCount)
    }
}
