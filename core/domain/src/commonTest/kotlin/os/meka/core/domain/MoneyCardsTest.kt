package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Money manager, slice 7: cards — due dates, statements, limits, 0% ending, fees and the heads-ups. Invented cards only. */
class MoneyCardsTest {
    private val today = CivilDate.toEpochDay(2026, 10, 16) // a Friday
    private val cal = LocalCalendar.UTC
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private fun nowMs(day: Long = today, hour: Int = 10) = day * CivilDate.DAY_MS + hour * 3_600_000L

    private val credit = MoneyCard(
        "a-credit-card", "Credit card", MoneyCards.CREDIT,
        limitPence = 200_000, statementPence = 64_000, minPence = 2_500, dueDayOfMonth = 19, promoEndDay = d(11, 5),
    )
    private val charge = MoneyCard("a-charge", "Charge card", MoneyCards.CHARGE, statementPence = 120_000, minPence = 120_000, dueDayOfMonth = 2)

    @Test
    fun aCreditCardShowsWhenItsDueTheStatementTheLimitUsedAndZeroPercentEnding() {
        val r = MoneyCards.row(credit, today, emptyList())
        assertEquals("Credit card", r.kindLine)
        assertEquals("Due Mon 19 Oct · in 3 days", r.dueLine)
        assertTrue(r.dueLit)
        assertTrue(r.canPay)
        assertNull(r.paidLine)
        assertEquals("Statement £640 · minimum £25 · pay in full to avoid interest", r.statementLine)
        assertEquals("32% of your £2,000 limit", r.useLine)
        assertFalse(r.useLit)
        assertEquals("0% ends Thu 5 Nov · in 20 days", r.promoLine)
        assertTrue(r.promoLit)
        assertNull(r.feesLine)
        assertEquals(MoneyCards.FIELDS, r.fields.map { it.key })
        assertEquals(listOf("£640", "£25", "19th", "£2,000", "5 Nov 2026"), r.fields.map { it.value })
        assertTrue(r.spoken.startsWith("Credit card, Credit card, Due Mon 19 Oct"))
    }

    @Test
    fun aChargeCardIsPaidInFullAndShowsNoMinimum() {
        val r = MoneyCards.row(charge, today, emptyList())
        assertEquals("Charge card", r.kindLine)
        assertEquals("Due Mon 2 Nov · in 17 days", r.dueLine)
        assertFalse(r.dueLit)
        assertEquals("Statement £1,200 · a charge card is paid in full", r.statementLine)
        assertNull(r.useLine)
        assertNull(r.promoLine)
    }

    @Test
    fun paidMovesToTheNextDueDateAndAShortMonthUsesItsLastDay() {
        val paid = credit.copy(paidFor = d(10, 19))
        val r = MoneyCards.row(paid, today, emptyList())
        assertEquals("Due Thu 19 Nov · in 34 days", r.dueLine)
        assertEquals("Paid for Mon 19 Oct", r.paidLine)
        assertFalse(r.dueLit)
        // Due on the 31st: November's is the 30th.
        assertEquals(d(11, 30), MoneyCards.nextDue(credit.copy(dueDayOfMonth = 31), d(11, 15)))
        // Past this month's day: next month's.
        assertEquals(d(11, 2), MoneyCards.nextDue(charge, today))
        // No due day typed yet.
        assertEquals(MoneyCards.NO_DUE, MoneyCards.row(credit.copy(dueDayOfMonth = null), today, emptyList()).dueLine)
        assertNull(MoneyCards.nextDue(credit.copy(dueDayOfMonth = null), today))
    }

    @Test
    fun highUseIsLitAndANothingOwedStatementNeedsNoPayment() {
        val r = MoneyCards.row(credit.copy(statementPence = 160_000), today, emptyList())
        assertEquals("80% of your £2,000 limit", r.useLine)
        assertTrue(r.useLit)
        val zero = MoneyCards.row(credit.copy(statementPence = 0), today, emptyList())
        assertEquals("Statement £0 · nothing to pay", zero.statementLine)
        assertFalse(zero.dueLit)
        assertFalse(zero.canPay)
        assertEquals("Limit £2,000", MoneyCards.row(credit.copy(statementPence = null), today, emptyList()).useLine)
    }

    @Test
    fun interestAndLateFeesOnTheCardAreFlagged() {
        val lines = listOf(
            MoneyCardLine("a-credit-card", d(10, 5), -1_240, "INTEREST CHARGED"),
            MoneyCardLine("a-credit-card", d(9, 28), -1_200, "LATE PAYMENT FEE"),
            MoneyCardLine("a-credit-card", d(10, 6), -3_000, "TESCO STORES"),
            MoneyCardLine("a-current", d(10, 6), -500, "OVERDRAFT INTEREST"), // another account
            MoneyCardLine("a-credit-card", d(6, 1), -900, "INTEREST CHARGED"), // too long ago
        )
        assertEquals("Interest £12.40 charged Mon 5 Oct · Late fee £12 charged Mon 28 Sep", MoneyCards.row(credit, today, lines).feesLine)
    }

    @Test
    fun zeroPercentEndedOrMonthsAway() {
        assertEquals("0% ended 1 Oct 2026 · anything still owed now costs interest", MoneyCards.row(credit.copy(promoEndDay = d(10, 1)), today, emptyList()).promoLine)
        val later = MoneyCards.row(credit.copy(promoEndDay = d(2, 1, 2027)), today, emptyList())
        assertEquals("0% ends Mon 1 Feb 2027 · in 3 months", later.promoLine)
        assertFalse(later.promoLit)
    }

    @Test
    fun typedValuesAreReadOrRefused() {
        assertEquals(23, MoneyCards.parseDue("23rd"))
        assertEquals(1, MoneyCards.parseDue("the 1st"))
        assertEquals(15, MoneyCards.parseDue("15 each month"))
        assertNull(MoneyCards.parseDue("32"))
        assertNull(MoneyCards.parseDue("soon"))
        assertEquals(123_450L, MoneyCards.parseAmount("£1,234.50"))
        assertEquals(5_000L, MoneyCards.parseAmount("−£50"))
        assertNull(MoneyCards.parseAmount("lots"))
        assertEquals(d(2, 1, 2027), MoneyCards.parsePromo("1 Feb 2027", today))
        assertEquals(d(2, 1, 2027), MoneyCards.parsePromo("01/02/2027", today))
        assertEquals(d(2, 1, 2027), MoneyCards.parsePromo("Feb 2027", today))
        assertNull(MoneyCards.parsePromo("never", today))
        assertNull(MoneyCards.parsePromo("1 Feb 2040", today))
        assertEquals("21st", MoneyCards.ordinal(21))
        assertEquals("12th", MoneyCards.ordinal(12))
        assertEquals("23rd", MoneyCards.ordinal(23))
    }

    @Test
    fun theBlockOffersCardLikeAccountsFirst() {
        val accounts = listOf(
            MoneyAccount("a-current", "Current account", "csv", null),
            MoneyAccount("a-amex", "Amex", "csv", null),
            MoneyAccount("a-credit-card", "Credit card", "csv", null),
        )
        val v = MoneyCards.view(listOf(credit), accounts, emptyList(), today)
        assertEquals(listOf("Credit card"), v.rows.map { it.name })
        assertEquals(listOf("Amex"), v.offers.map { it.name })
        assertEquals(listOf("Current account"), v.others.map { it.name })
        assertEquals("Show 1 more account", v.othersLabel)
        assertEquals(MoneyCards.OFFER_MORE, v.offerLine)
        assertEquals(MoneyCards.OFFER_FIRST, MoneyCards.view(emptyList(), accounts, emptyList(), today).offerLine)
    }

    @Test
    fun aCurrentOrSavingsAccountIsNotOfferedAsACard() {
        assertTrue(MoneyCards.looksNotCard("Current account"))
        assertTrue(MoneyCards.looksNotCard("Easy Saver"))
        assertTrue(MoneyCards.looksNotCard("Cash ISA"))
        assertTrue(MoneyCards.looksNotCard("Joint"))
        assertFalse(MoneyCards.looksNotCard("Credit card"))
        assertFalse(MoneyCards.looksNotCard("Joint credit card"))
        assertFalse(MoneyCards.looksNotCard("Monzo Flex"))
        val only = MoneyCards.view(emptyList(), listOf(MoneyAccount("a", "Current account", "csv", null), MoneyAccount("b", "Savings", "csv", null)), emptyList(), today)
        assertFalse(only.shown)
        assertTrue(only.offers.isEmpty())
        assertNull(only.offerLine)
        assertEquals("Show 2 more accounts", only.othersLabel)
        val none = MoneyCards.view(emptyList(), listOf(MoneyAccount("c", "Amex", "csv", null)), emptyList(), today)
        assertTrue(none.shown)
        assertNull(none.othersLabel)
    }

    @Test
    fun aReminderThreeDaysBeforeWithNoAmountsUntilPaid() {
        val n = MoneyCards.notices(listOf(credit.copy(promoEndDay = null)), nowMs(), cal).single()
        assertEquals("card:a-credit-card:due:${d(10, 19)}", n.key)
        assertEquals(NoticeSource.CARDS, n.source)
        assertEquals(NoticeTarget.MONEY, n.target)
        assertEquals("Credit card payment due Mon 19 Oct", n.title)
        assertEquals("In 3 days · pay in full to avoid interest · the amounts are in Money · mark it Paid there", n.text)
        assertFalse(n.title.contains("£") || n.text.contains("£"))
        assertEquals(cal.toEpochMs(today, MoneyCards.NOTICE_MIN), n.atMs)
        // Four days out: nothing yet. Paid, or nothing owed: nothing.
        assertTrue(MoneyCards.notices(listOf(credit.copy(promoEndDay = null)), nowMs(today - 1), cal).isEmpty())
        assertTrue(MoneyCards.notices(listOf(credit.copy(promoEndDay = null, paidFor = d(10, 19))), nowMs(), cal).isEmpty())
        assertTrue(MoneyCards.notices(listOf(credit.copy(promoEndDay = null, statementPence = 0)), nowMs(), cal).isEmpty())
        // A charge card's words.
        val c = MoneyCards.notices(listOf(charge), nowMs(d(10, 30)), cal).single()
        assertEquals("Charge card payment due Mon 2 Nov", c.title)
        assertTrue(c.text.startsWith("In 3 days · a charge card is paid in full"))
    }

    @Test
    fun zeroPercentEndingWarnsAMonthAndAWeekBefore() {
        val card = credit.copy(dueDayOfMonth = null, promoEndDay = d(11, 15))
        val month = MoneyCards.notices(listOf(card), nowMs(d(10, 16)), cal).single()
        assertEquals("0% on Credit card ends Sun 15 Nov", month.title)
        assertEquals("In a month · anything still owed then starts costing interest", month.text)
        assertEquals("card:a-credit-card:promo:${d(11, 15)}:30", month.key)
        val week = MoneyCards.notices(listOf(card), nowMs(d(11, 9)), cal).single()
        assertTrue(week.text.startsWith("In a week"))
        assertTrue(MoneyCards.notices(listOf(card), nowMs(d(10, 15)), cal).isEmpty())
        assertTrue(MoneyCards.notices(listOf(card), nowMs(d(11, 16)), cal).isEmpty())
    }

    @Test
    fun theTileLightsWhenACardIsDueAndPaydayCountsTheStatement() {
        assertEquals(LifeTileLine("Credit card due Mon 19 Oct", true), MoneyCards.tileLine(listOf(credit, charge), today))
        assertNull(MoneyCards.tileLine(listOf(charge), today))
        assertEquals(listOf(MoneyBill("Credit card", d(10, 19), 64_000)), MoneyCards.bills(listOf(credit), today))
        assertTrue(MoneyCards.bills(listOf(credit.copy(statementPence = 0)), today).isEmpty())
    }
}
