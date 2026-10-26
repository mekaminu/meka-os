package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Money manager, slice 6: payday, what's still to go out and what's safe to spend. Invented payers, shops and amounts only. */
class MoneyPaydayTest {
    private val today = CivilDate.toEpochDay(2026, 10, 16) // a Friday
    private val names = mapOf("a-current" to "Current account", "a-card" to "Credit card")
    private fun d(m: Int, day: Int) = CivilDate.toEpochDay(2026, m, day)

    private fun line(day: Long, pence: Long, text: String, account: String = "a-current") = MoneyPayLine(
        account, day, pence, MoneyCategoryRules.merchantKey(text), MoneyCategoryRules.categoryOf(text, pence),
    )

    /** Paid on the 30th (Fri 28 Aug, the 30th being a Sunday), Streamly on the 20th, coffee since the last pay. */
    private val lines = listOf(
        line(d(7, 30), 250_000, "ACME PAYROLL"), line(d(8, 28), 250_000, "ACME PAYROLL"), line(d(9, 30), 250_000, "ACME PAYROLL"),
        line(d(7, 20), -1_099, "STREAMLY.COM"), line(d(8, 20), -1_099, "STREAMLY.COM"), line(d(9, 20), -1_099, "STREAMLY.COM"),
        line(d(10, 2), -12_000, "TESCO STORES 2041"), line(d(10, 9), -3_000, "COSTA COFFEE", "a-card"),
        line(d(10, 5), -50_000, "TRANSFER TO SAVINGS"),
    )
    private val water = MoneyBill("Water bill", d(10, 25), 4_000)
    private val councilTax = MoneyBill("Council tax", d(11, 1), 15_000) // after payday
    private val streamlyOnRadar = MoneyBill("Streamly", d(10, 20), 1_099) // the same as the regular payment

    private fun view(balance: MoneyBalance? = null, bills: List<MoneyBill> = listOf(water, councilTax, streamlyOnRadar), ls: List<MoneyPayLine> = lines) =
        MoneyPayday.view(ls, today, names, balance?.let { mapOf("a-current" to it) } ?: emptyMap(), bills, HolidayCalendar())

    @Test
    fun paydayComesFromHisPayAndMovesBackOffAWeekend() {
        val v = view()
        assertNull(v.notReady)
        assertEquals("Payday Fri 30 Oct · in 14 days", v.payLine)
        assertEquals("Acme Payroll into Current account · about £2,500", v.fromLine)
        // Since Wed 30 Sep: groceries and coffee (on the card) count, the savings transfer doesn't.
        assertEquals("Since payday on Wed 30 Sep · spent £150 · about £9 a day", v.sinceLine)
        assertEquals("a-current", v.account)
        // Pay on the 25th: Sun 25 Oct 2026 moves to Fri 23 Oct; a bank holiday on Fri 25 Dec to Thu 24 Dec.
        assertEquals(d(10, 23), MoneyPayday.workingDayOnOrBefore(d(10, 25), HolidayCalendar()))
        assertEquals(d(12, 24), MoneyPayday.workingDayOnOrBefore(d(12, 25), HolidayCalendar(mapOf(d(12, 25) to "Christmas Day"))))
    }

    @Test
    fun onPaydayItCountsToTheNextPayday() {
        // Money's wrong numbers (1): Fri 30 Oct is payday and the pay hasn't landed yet; until payday runs to Mon 30 Nov,
        // never "£… a day" over 0 days, and the council tax on 1 Nov is still to go out.
        val onPayday = d(10, 30)
        val v = MoneyPayday.view(lines, onPayday, names, mapOf("a-current" to MoneyBalance(30_000, onPayday, typed = true)),
            listOf(councilTax), HolidayCalendar())
        assertEquals("Payday Mon 30 Nov · in 31 days", v.payLine)
        assertEquals(d(11, 30), v.paydayDay)
        assertEquals(listOf("Council tax"), v.rows.map { it.title })
        assertEquals("Safe to spend until payday · about £150 · about £5 a day", v.safe)
        assertEquals("Still to go out before payday · Mon 30 Nov", v.bills!!.head)
        // Once the pay lands that day, it's the same next payday.
        val landed = MoneyPayday.view(lines + line(onPayday, 250_000, "ACME PAYROLL"), onPayday, names, emptyMap(), emptyList(), HolidayCalendar())
        assertEquals("Payday Mon 30 Nov · in 31 days", landed.payLine)
    }

    @Test
    fun stillToGoOutCountsRegularPaymentsAndRadarBillsBeforePaydayOnce() {
        val v = view()
        assertEquals(listOf("Streamly.com", "Water bill"), v.rows.map { it.title })
        assertEquals(listOf("Tue 20 Oct", "Sun 25 Oct"), v.rows.map { it.day })
        assertEquals("Still to go out before payday · about £51 · 2 payments", v.goingOut)
        assertEquals("Type Current account's balance now to see what's safe to spend until payday", v.balanceLine)
        assertNull(v.safe)
        assertFalse(v.balanceKnown)
    }

    @Test
    fun safeToSpendFromTheBalanceAndTheWarningWhenItsTight() {
        val ok = view(MoneyBalance(30_000, today, typed = true))
        assertEquals("Current account · £300 · you typed it today", ok.balanceLine)
        assertEquals("Safe to spend until payday · about £249 · about £18 a day", ok.safe)
        assertFalse(ok.safeLit)
        val low = view(MoneyBalance(12_000, today, typed = true))
        assertEquals("After the bills before payday you'd have about £69 · about £5 a day", low.safe)
        assertTrue(low.safeLit)
        val short = view(MoneyBalance(2_000, today, typed = true))
        assertEquals("What's still to go out is £31 more than Current account holds · move money in or check the dates", short.safe)
        assertTrue(short.safeLit)
    }

    @Test
    fun aStatementBalanceCountsThePaymentsAfterIt() {
        // From a statement ending Fri 9 Oct; Tesco on the 2nd is already in it, a later £100 out isn't.
        val ls = lines + line(d(10, 13), -10_000, "ARGOS")
        val v = view(MoneyBalance(50_000, d(10, 9), typed = false), ls = ls)
        assertEquals("Current account · £400 · from your statement, Fri 9 Oct · with the payments since", v.balanceLine)
        assertEquals("Safe to spend until payday · about £349 · about £25 a day", v.safe)
        // Statements that stop a week ago say newer payments aren't in.
        val stale = view(MoneyBalance(50_000, d(10, 9), typed = false))
        assertTrue(stale.balanceLine!!.endsWith("newer payments aren't in"), stale.balanceLine)
    }

    @Test
    fun noRegularPayMeansNoPaydayYet() {
        val v = view(ls = lines.filterNot { it.day == d(7, 30) || it.day == d(8, 28) })
        assertEquals(MoneyPayday.NOT_READY, v.notReady)
        assertNull(v.payLine)
        // A one-off big credit isn't pay either; nor is pay that stopped coming.
        val old = lines.map { if (it.category == MoneyCategory.INCOME) it.copy(day = it.day - 60) else it }
        assertEquals(MoneyPayday.NOT_READY, view(ls = old).notReady)
    }

    @Test
    fun balancesAreTypedAsHeSaysThem() {
        assertEquals(124_050L, MoneyPayday.parseBalance("£1,240.50"))
        assertEquals(-5_000L, MoneyPayday.parseBalance("-£50"))
        assertEquals(-5_000L, MoneyPayday.parseBalance("−£50"))
        assertEquals(-5_000L, MoneyPayday.parseBalance("£50 overdrawn"))
        assertEquals(30_000L, MoneyPayday.parseBalance("300"))
        assertNull(MoneyPayday.parseBalance("lots"))
    }

    @Test
    fun aStatementFilesBalanceColumnGivesTheLatestBalance() {
        // Newest first: the first line on the latest day holds the balance after it.
        val newest = "Date,Description,Amount,Balance\n15/10/2026,COSTA,-3.20,1236.80\n15/10/2026,TESCO,-20.00,1240.00\n14/10/2026,ACME PAYROLL,2500.00,1260.00\n"
        val r = MoneyCsv.read(newest)
        assertEquals(123_680L, r.balancePence)
        assertEquals(d(10, 15), r.balanceDay)
        // Oldest first: the last line.
        val oldest = "Date,Description,Paid out,Paid in,Balance\n14/10/2026,ACME PAYROLL,,2500.00,1260.00\n15/10/2026,TESCO,20.00,,1240.00\n15/10/2026,COSTA,3.20,,1236.80\n"
        assertEquals(123_680L, MoneyCsv.read(oldest).balancePence)
        // No Balance column: none.
        assertNull(MoneyCsv.read("Date,Description,Amount\n15/10/2026,TESCO,-20.00\n").balancePence)
    }
}
