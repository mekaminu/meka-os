package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MoneyDatesTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private var n = 0
    private fun ids(): String = "M${n++}"

    private val d = world.device("android")
    private val r = Renewals(d.replica, ::ids, { world.clock.nowMs })

    private fun today() = world.clock.nowMs.floorDiv(dayMs)
    private fun at(y: Int, m: Int, day: Int, hour: Int = 10) { world.clock.nowMs = CivilDate.toEpochDay(y, m, day) * dayMs + hour * 3_600_000L }

    @Test
    fun theFiveDatesGoOnTheRadarOnceOnTheirDays() {
        at(2026, 10, 12)
        var v = MoneyDateRules.view(r.items(), today())
        assertEquals(listOf("self_assessment", "isa", "energy", "broadband", "pension"), v.rows.map { it.presetId })
        assertEquals("Tax, ISA, contracts and pension · dates only", v.summary)
        assertEquals("Every year · next Sun 31 Jan · reminders 4 weeks and a week before", v.rows[0].line)
        assertEquals("Every year · next Mon 5 Apr · reminders 4 weeks and a week before", v.rows[1].line)
        assertEquals("You set the end date · reminders 6 weeks and a week before", v.rows[2].line)
        assertEquals("Every year · you set the date · a reminder on the day", v.rows[4].line)
        assertEquals(listOf(true, true, false, false, false), v.rows.map { it.dateKnown })

        val sa = r.addMoneyDate("self_assessment", today())
        val item = r.items().single { it.id == sa }
        assertEquals(CivilDate.toEpochDay(2027, 1, 31), item.dueDay)
        assertEquals(RenewalRepeat.YEARLY, item.repeats)
        assertEquals(28, item.leadDays)
        assertFailsWith<ValidationException> { r.addMoneyDate("self_assessment", today()) }
        assertFailsWith<ValidationException> { r.addMoneyDate("lottery", today()) }

        val energy = r.addMoneyDate("energy", today())
        val e = r.items().single { it.id == energy }
        assertEquals(today() + MoneyDateRules.PLACEHOLDER_DAYS, e.dueDay)
        assertEquals(RenewalRepeat.NONE, e.repeats)
        assertEquals(MoneyDateRules.SWITCH_DAYS, e.leadDays)
        val pension = r.addMoneyDate("pension", today())
        assertEquals(7, r.items().single { it.id == pension }.leadDays)

        // Typed by hand: "Octopus energy deal" is the energy contract; a monthly "Octopus Energy" bill and "Visa" aren't.
        r.add("Octopus Energy", ObligationKind.BILL, today() + 20, RenewalRepeat.MONTHLY)
        r.add("Visa card", ObligationKind.BILL, today() + 20, RenewalRepeat.MONTHLY)
        r.add("BT broadband contract", ObligationKind.OTHER, today() + 90)
        v = MoneyDateRules.view(r.items(), today())
        assertEquals(listOf(true, false, true, true, true), v.rows.map { it.tracked })
        assertEquals("4 of 5 on the radar", v.summary)
        assertTrue(v.rows[0].line.startsWith("On the radar · due Sun 31 Jan"), v.rows[0].line)
        assertFalse(MoneyDateRules.isMoney(r.items().single { it.title == "Octopus Energy" }))
        assertFalse(MoneyDateRules.isMoney(r.items().single { it.title == "Visa card" }))
        // A day past 31 January: next year's.
        assertEquals(CivilDate.toEpochDay(2028, 1, 31), MoneyDateRules.nextFixed(1 to 31, CivilDate.toEpochDay(2027, 2, 1)))
        assertEquals(CivilDate.toEpochDay(2027, 1, 31), MoneyDateRules.nextFixed(1 to 31, CivilDate.toEpochDay(2027, 1, 31)))
    }

    @Test
    fun remindersComeOnTheirDaysAtNineAndLeaveTheDigest() {
        at(2026, 10, 12)
        val cal = LocalCalendar.UTC
        val sa = CivilDate.toEpochDay(2027, 1, 31)
        r.addMoneyDate("self_assessment", today())
        val energy = r.addMoneyDate("energy", today())
        val end = CivilDate.toEpochDay(2026, 12, 11)
        r.setDue(energy, end)
        val pension = r.addMoneyDate("pension", today())
        r.setDue(pension, end)
        fun on(day: Long) = MoneyDateRules.notices(r.items(), day * dayMs + 8 * 3_600_000L, cal)

        val month = on(sa - 28).single()
        assertEquals("Self Assessment due Sun 31 Jan", month.title)
        assertEquals("In 4 weeks · file and pay online by 31 January", month.text)
        assertEquals(NoticeSource.MONEY, month.source)
        assertEquals((sa - 28) * dayMs + 9 * 3_600_000L, month.atMs)
        assertEquals("In a week · file and pay online by 31 January", on(sa - 7).single().text)
        assertTrue(on(sa - 20).isEmpty())

        val switch = on(end - 42).single()
        assertEquals("Energy contract ends Fri 11 Dec", switch.title)
        assertEquals("In 6 weeks · time to compare deals before it ends", switch.text)
        assertEquals(listOf("Energy contract ends Fri 11 Dec"), on(end - 7).map { it.title })
        val p = on(end).single()
        assertEquals("Pension check-in · Fri 11 Dec", p.title)
        assertEquals("Once a year · look over your pension statements", p.text)

        // The radar's digest leaves them to these until overdue.
        world.clock.nowMs = (end - 3) * dayMs + 10 * 3_600_000L
        val renewals = r.view()
        assertTrue(renewals.attention.any { it.id == energy })
        val lists = ListsView(waiting = emptyList(), someday = emptyList(), decisions = emptyList(), renewals = renewals)
        val digest = NoticeSources.collect(
            lists, FastingView.EMPTY, ShutdownView.EMPTY, Today(emptyList(), null, emptyList(), emptyList()), world.clock.nowMs, cal,
        )
        assertTrue(digest.none { it.source == NoticeSource.RENEWAL && it.title == "Energy contract" })
    }
}
