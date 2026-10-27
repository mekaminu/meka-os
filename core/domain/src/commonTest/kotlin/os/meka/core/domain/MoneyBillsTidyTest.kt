package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Money review (Meka 2026-10-26 22:12), Bills: no form sits open on the page. A card is one row ("due ? · tap to add"
 * until its due day is in) and Until payday is one line, each opening a sheet. Invented cards and amounts (rule 13).
 */
class MoneyBillsTidyTest {
    private val today = CivilDate.toEpochDay(2026, 10, 27)

    private fun row(card: MoneyCard) = MoneyCards.row(card, today, emptyList())

    @Test
    fun aCardWithNothingTypedSaysDueQuestionMarkTapToAdd() {
        val b = MoneyCards.brief(row(MoneyCard("c1", "Platinum Card", MoneyCards.CREDIT)))
        assertEquals("Platinum Card", b.name)
        assertEquals(MoneyCards.ADD_DETAILS, b.line)
        assertNull(b.amount)
        assertFalse(b.lit)
        assertTrue(b.spoken.endsWith("opens its details"))
    }

    @Test
    fun aCardDueSoonWithItsStatementShowsTheDueLineLitAndTheStatementOnTheRight() {
        val b = MoneyCards.brief(row(MoneyCard("c1", "Platinum Card", MoneyCards.CREDIT, statementPence = 64_000, dueDayOfMonth = 29)))
        assertEquals("Due Thu 29 Oct · in 2 days", b.line)
        assertTrue(b.lit)
        assertEquals("£640", b.amount)
        assertEquals("Platinum Card, Due Thu 29 Oct · in 2 days, statement £640, opens its details", b.spoken)
    }

    @Test
    fun aDueDayWithoutAStatementAsksForTheStatement() {
        val b = MoneyCards.brief(row(MoneyCard("c1", "Gold Card", MoneyCards.CHARGE, dueDayOfMonth = 15)))
        assertEquals("Due Sun 15 Nov · in 19 days · ${MoneyCards.ADD_STATEMENT}", b.line)
        assertFalse(b.lit)
    }

    @Test
    fun aPaidCardSaysPaidUntilTheNextOneIsDue() {
        val due = CivilDate.toEpochDay(2026, 10, 29)
        val r = row(MoneyCard("c1", "Platinum Card", MoneyCards.CREDIT, statementPence = 0, dueDayOfMonth = 29, paidFor = due))
        assertEquals("Paid for Thu 29 Oct", MoneyCards.brief(r).line)
    }

    @Test
    fun theBriefsKeepTheViewsOrder() {
        val cv = MoneyCards.view(
            listOf(MoneyCard("late", "Late Card", MoneyCards.CREDIT, dueDayOfMonth = 20), MoneyCard("soon", "Soon Card", MoneyCards.CREDIT, dueDayOfMonth = 28)),
            emptyList(), emptyList(), today,
        )
        assertEquals(listOf("soon", "late"), MoneyCards.briefs(cv).map { it.id })
    }

    private fun payday(payLine: String? = "Payday Fri 30 Oct · in 3 days", safe: String? = null, safeLit: Boolean = false, account: String? = "a1", known: Boolean = false) =
        MoneyPaydayView(
            MoneyPayday.HEAD, notReady = if (payLine == null) MoneyPayday.NOT_READY else null, payLine = payLine, fromLine = null,
            sinceLine = "£212 spent since payday", goingOut = null, rows = emptyList(), more = 0, account = account, accountName = "Current",
            balanceLine = null, balanceKnown = known, safe = safe, safeLit = safeLit, foot = MoneyPayday.FOOT,
        )

    @Test
    fun untilPaydayIsOneLineThatAsksForTheBalanceOrSaysWhatIsSafe() {
        assertEquals(MoneyPaydayBrief("Payday Fri 30 Oct · in 3 days", MoneyPayday.ADD_BALANCE, false,
            "Payday Fri 30 Oct · in 3 days, ${MoneyPayday.ADD_BALANCE}, opens Until payday"), MoneyPayday.brief(payday()))
        val safe = MoneyPayday.brief(payday(safe = "Safe to spend until payday · about £86", safeLit = true, known = true))
        assertEquals("Safe to spend until payday · about £86", safe.line)
        assertTrue(safe.lit)
        val none = MoneyPayday.brief(payday(payLine = null, account = null))
        assertEquals(MoneyPayday.NOT_FOUND, none.title)
        assertEquals(MoneyPayday.HOW_FOUND, none.line)
        assertFalse(none.lit)
    }
}
