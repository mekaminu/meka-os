package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BillsTest {
    private val world = SyncWorld()
    private val dayMs = CivilDate.DAY_MS
    private var n = 0
    private fun ids(): String = "B${n++}"

    private val d = world.device("android")
    private val r = Renewals(d.replica, ::ids, { world.clock.nowMs })
    private val cal = LocalCalendar.UTC

    private fun today() = world.clock.nowMs.floorDiv(dayMs)
    private fun at(y: Int, m: Int, day: Int, hour: Int = 10) { world.clock.nowMs = CivilDate.toEpochDay(y, m, day) * dayMs + hour * 3_600_000L }
    private fun notices() = BillRules.notices(r.items(), world.clock.nowMs, cal)

    @Test
    fun aBillRemindsThreeDaysBeforeWithTheAmountAndPayOpensTheSavedLink() {
        at(2026, 10, 12)
        val tax = r.add("Council tax", ObligationKind.BILL, today() + 3, RenewalRepeat.MONTHLY, cost = "180")
        // The pay link: cleaned to https, a typed non-address refused, blank clears it.
        assertFalse(r.setPayLink(tax, "not a link"))
        assertTrue(r.setPayLink(tax, "www.oakfield.gov.uk/council-tax"))
        val item = r.items().single()
        assertEquals("https://www.oakfield.gov.uk/council-tax", item.payLink)
        assertEquals("Pay on oakfield.gov.uk ↗", BillRules.payLabel(item.payLink!!))

        val n = notices().single()
        assertEquals("Council tax due Thu 15 Oct · £180", n.title)
        assertEquals("In 3 days · Pay opens oakfield.gov.uk", n.text)
        assertEquals(listOf(NoticeAction.PAY), n.actions)
        assertEquals("https://www.oakfield.gov.uk/council-tax", n.link)
        assertEquals(NoticeSource.BILLS, n.source)
        assertEquals(cal.toEpochMs(today(), BillRules.NOTICE_MIN), n.atMs)
        assertEquals("bill:$tax:${today() + 3}", n.key)

        // The day before, the same key (it posts once) with the words for tomorrow.
        at(2026, 10, 14)
        assertEquals("Council tax due tomorrow · £180" to "Tomorrow · Pay opens oakfield.gov.uk", notices().single().let { it.title to it.text })
        // Paid moves it to next month: nothing until three days before then.
        r.done(tax)
        assertTrue(notices().isEmpty())
        at(2026, 11, 12)
        assertEquals("bill:$tax:${CivilDate.toEpochDay(2026, 11, 15)}", notices().single().key)

        assertTrue(r.setPayLink(tax, " "))
        assertNull(r.items().single().payLink)
        val plain = notices().single()
        assertEquals("In 3 days · mark it Paid in Lists once it's done", plain.text)
        assertTrue(plain.actions.isEmpty())
        assertNull(plain.link)
    }

    @Test
    fun whatCountsAsABill() {
        at(2026, 10, 12)
        r.add("Water", ObligationKind.BILL, today() + 2, RenewalRepeat.MONTHLY)
        val netflix = r.add("Netflix", ObligationKind.SUBSCRIPTION, today() + 1, RenewalRepeat.MONTHLY, cost = "10.99")
        r.add("Spotify", ObligationKind.SUBSCRIPTION, today() + 1, RenewalRepeat.MONTHLY)
        r.add("Car tax", ObligationKind.CAR_TAX, today() + 2, RenewalRepeat.YEARLY)
        r.add("Broadband contract", ObligationKind.BILL, today() + 2)
        r.add("Electric", ObligationKind.BILL, today() + 10, RenewalRepeat.MONTHLY)
        // A subscription he pays by hand: a pay link makes it a bill.
        r.setPayLink(netflix, "netflix.com")
        assertEquals(listOf("Water due Wed 14 Oct", "Netflix due tomorrow · £10.99"), notices().map { it.title })
        assertEquals("Today", run { at(2026, 10, 14); notices().single { it.title.startsWith("Water") }.text.substringBefore(" ·") })
    }

    @Test
    fun aChangedAmountIsFlaggedOnTheReminderAndTheRowForThisPeriodOnly() {
        at(2026, 10, 12)
        val tax = r.add("Council tax", ObligationKind.BILL, today() + 3, RenewalRepeat.MONTHLY, cost = "180")
        assertNull(r.items().single().prevCostPence)
        // He types this month's new amount: the reminder and the row say so.
        r.setCost(tax, "190")
        val item = r.items().single()
        assertEquals(18_000L, item.prevCostPence)
        assertTrue(item.meta.endsWith("£190 a month · up £10 on last time"), item.meta)
        assertEquals("In 3 days · £10 more than last time (£180) · mark it Paid in Lists once it's done", notices().single().text)
        // A second change in the same period keeps the first "before"; back to the old amount ends the flag.
        r.setCost(tax, "175")
        assertEquals("In 3 days · £5 less than last time (£180) · mark it Paid in Lists once it's done", notices().single().text)
        r.setCost(tax, "180")
        assertNull(r.items().single().prevCostPence)
        // Pence don't count; Paid moves on to next month, where the flag is gone.
        r.setCost(tax, "180.40")
        assertNull(r.items().single().prevCostPence)
        r.setCost(tax, "200")
        assertEquals(18_000L, r.items().single().prevCostPence)
        r.done(tax)
        at(2026, 11, 12)
        assertNull(r.items().single().prevCostPence)
        assertEquals("In 3 days · mark it Paid in Lists once it's done", notices().single().text)
    }

    @Test
    fun aBillFromEmailIsComparedWithTheSamePayeesLastPaidBill() {
        at(2026, 9, 25)
        val sept = r.add("Octopus Energy bill", ObligationKind.BILL, today() + 3, cost = "84.20")
        r.add("Other Water bill", ObligationKind.BILL, today() + 3, cost = "30")
        assertTrue(r.items().all { it.prevCostPence == null })
        r.done(sept)
        at(2026, 10, 25)
        r.add("Octopus Energy bill", ObligationKind.BILL, today() + 3, cost = "92.10")
        val oct = r.items().single { it.title == "Octopus Energy bill" }
        assertEquals(8_420L, oct.prevCostPence)
        val n = notices().single { it.title.startsWith("Octopus") }
        assertEquals("Octopus Energy bill due Wed 28 Oct · £92.10", n.title)
        assertEquals("In 3 days · £7.90 more than last time (£84.20) · mark it Paid in Lists once it's done", n.text)
        // The other payee's bill (not paid yet, and not the same payee) has no "last time".
        assertNull(r.items().single { it.title == "Other Water bill" }.prevCostPence)
        assertEquals(8_420L, BillRules.previousCost("octopus energy bill ", CivilDate.toEpochDay(2026, 10, 28), listOf(PaidBill("Octopus Energy bill", CivilDate.toEpochDay(2026, 9, 28), 8_420))))
        // Too recent (a reminder email's twin) or too old isn't "last time".
        assertNull(BillRules.previousCost("Octopus Energy bill", CivilDate.toEpochDay(2026, 10, 28), listOf(PaidBill("Octopus Energy bill", CivilDate.toEpochDay(2026, 10, 20), 8_420))))
        assertNull(BillRules.previousCost("Octopus Energy bill", CivilDate.toEpochDay(2026, 10, 28), listOf(PaidBill("Octopus Energy bill", CivilDate.toEpochDay(2026, 6, 1), 8_420))))
    }

    @Test
    fun aCardsPaymentOnTheRadarGetsOneReminderTheCardsWithItsPayLink() {
        at(2026, 10, 16)
        val amexBill = r.add("American Express statement", ObligationKind.BILL, CivilDate.toEpochDay(2026, 10, 18), cost = "640")
        assertTrue(r.setPayLink(amexBill, "https://www.americanexpress.com/uk"))
        val gas = r.add("Gas bill", ObligationKind.BILL, CivilDate.toEpochDay(2026, 10, 19), cost = "40")
        val amex = MoneyCard("a-amex", "Amex", MoneyCards.CREDIT, statementPence = 64_000, dueDayOfMonth = 19)
        val generic = MoneyCard("a-card", "Credit card", MoneyCards.CREDIT, statementPence = 10_000, dueDayOfMonth = 19)
        val items = r.items()
        val now = world.clock.nowMs
        // Without cards, both bills remind.
        assertEquals(2, BillRules.notices(items, now, cal).size)
        // With the Amex card, its statement on the radar is left to the card's reminder, which carries the Pay link.
        val bills = BillRules.notices(items, now, cal, listOf(amex, generic))
        assertEquals(listOf("bill:$gas:${CivilDate.toEpochDay(2026, 10, 19)}"), bills.map { it.key })
        val card = MoneyCards.notices(listOf(amex), now, cal, items).single()
        assertEquals("Amex payment due Mon 19 Oct", card.title)
        assertEquals("In 3 days · pay in full to avoid interest · Pay opens americanexpress.com · the amounts are in Money · mark it Paid there", card.text)
        assertEquals(listOf(NoticeAction.PAY), card.actions)
        assertEquals("https://www.americanexpress.com/uk", card.link)
        // Marked Paid on the card: the radar's copy stays quiet too.
        val paid = amex.copy(paidFor = CivilDate.toEpochDay(2026, 10, 19))
        assertTrue(MoneyCards.notices(listOf(paid), now, cal, items).isEmpty())
        assertEquals(1, BillRules.notices(items, now, cal, listOf(paid)).size)
        // A bill a week away from the card's due day is another payment.
        assertFalse(BillRules.isCardsBill("Amex bill", CivilDate.toEpochDay(2026, 10, 26), amex, today()))
        // Until payday counts the card once.
        val radar = listOf(MoneyBill("American Express statement", CivilDate.toEpochDay(2026, 10, 18), 64_000), MoneyBill("Gas bill", CivilDate.toEpochDay(2026, 10, 19), 4_000))
        assertEquals(listOf("Gas bill"), BillRules.withoutCards(radar, listOf(amex), today()).map { it.title })
        assertEquals(radar, BillRules.withoutCards(radar, emptyList(), today()))
    }
}
