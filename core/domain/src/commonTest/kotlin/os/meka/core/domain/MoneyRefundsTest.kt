package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Money manager, slice 5: refunds on Waiting for checked against his statements. Invented shops and amounts only. */
class MoneyRefundsTest {
    private val cal = LocalCalendar.UTC
    private val today = CivilDate.toEpochDay(2026, 10, 16)
    private val names = mapOf("a-current" to "Current account")

    private fun wait(id: String, title: String, who: String?, sinceDay: Long, chasedDay: Long? = null) = WaitingItem(
        id, title, who, null, cal.toEpochMs(sinceDay, 9 * 60), chasedDay?.let { cal.toEpochMs(it, 10 * 60) }, null, DueState.NONE, "", false,
    )

    private var n = 0
    private fun line(day: Long, pence: Long, text: String) = MoneyWatchLine(
        "t${n++}", "a-current", day, pence, MoneyCategoryRules.merchantKey(text), MoneyCategoryRules.categoryOf(text, pence), text,
    )

    @Test
    fun aRefundBackInTheStatementOffersCloseIt() {
        val w = wait("w1", "Refund · Amazon order · £40", "Amazon", today - 6)
        val v = MoneyRefunds.view(listOf(w), listOf(line(today - 30, -4000, "AMAZON.CO.UK"), line(today - 1, 4000, "AMAZON.CO.UK REFUND"), line(today, -350, "COSTA")), today, cal, names)!!
        assertEquals("REFUNDS ON THE WAY · 1", v.head)
        val r = v.rows.single()
        assertEquals(MoneyRefunds.BACK, r.state)
        assertEquals("Back in your statement · Refund · Amazon order · £40", r.title)
        assertTrue(r.line.startsWith("+£40 from Amazon"), r.line)
        assertTrue(r.line.endsWith("Current account"), r.line)
        assertNull(r.chase)
        assertNull(v.foot)
    }

    @Test
    fun theAmountMustMatchWhenTheRowNamesOne() {
        val w = wait("w1", "Refund · Amazon order · £40", "Amazon", today - 6)
        val v = MoneyRefunds.view(listOf(w), listOf(line(today - 1, 1299, "AMAZON.CO.UK"), line(today, -500, "TESCO")), today, cal, names)!!
        assertEquals(MoneyRefunds.WAITING, v.rows.single().state)
        assertEquals("Not in your statements yet · 6 days since ${CivilDate.shortLabel(today - 6)}", v.rows.single().line)
    }

    @Test
    fun aRowWithNoShopOrAmountMatchesByItsTitle() {
        val w = wait("w1", "Refund from Zara", null, today - 3)
        val v = MoneyRefunds.view(listOf(w), listOf(line(today - 1, 2599, "ZARA UK LONDON"), line(today, -300, "PRET")), today, cal, names)!!
        assertEquals(MoneyRefunds.BACK, v.rows.single().state)
        // Another shop's money in never counts.
        val v2 = MoneyRefunds.view(listOf(w), listOf(line(today - 1, 2599, "NEXT RETAIL"), line(today, -300, "PRET")), today, cal, names)!!
        assertEquals(MoneyRefunds.WAITING, v2.rows.single().state)
    }

    @Test
    fun aCreditLongBeforeTheRowIsNotItsRefund() {
        val w = wait("w1", "Refund · Amazon order · £40", "Amazon", today - 3)
        val v = MoneyRefunds.view(listOf(w), listOf(line(today - 20, 4000, "AMAZON.CO.UK"), line(today, -300, "PRET")), today, cal, names)!!
        assertEquals(MoneyRefunds.WAITING, v.rows.single().state)
    }

    @Test
    fun oneCreditClosesOneRow() {
        val a = wait("w1", "Refund · Amazon order · £40", "Amazon", today - 8)
        val b = wait("w2", "Refund · Amazon order · £40", "Amazon", today - 4)
        val v = MoneyRefunds.view(listOf(b, a), listOf(line(today - 1, 4000, "AMAZON.CO.UK"), line(today, -300, "PRET")), today, cal, names)!!
        assertEquals(listOf("w1" to MoneyRefunds.BACK, "w2" to MoneyRefunds.WAITING), v.rows.map { it.id to it.state })
    }

    @Test
    fun lateAfterTwoWeeksWithAChaseToCopyUntilChased() {
        val w = wait("w1", "Refund · Next order · £30", "Next", today - 16)
        val lines = listOf(line(today - 40, -3000, "NEXT RETAIL"), line(today, -300, "PRET"))
        val r = MoneyRefunds.view(listOf(w), lines, today, cal, names)!!.rows.single()
        assertEquals(MoneyRefunds.LATE, r.state)
        assertTrue(r.lit)
        assertEquals("Not in your statements after 16 days · since ${CivilDate.shortLabel(today - 16)}", r.line)
        val chase = assertNotNull(r.chase)
        assertTrue(chase.contains("the refund of £30"), chase)
        // Chased two days ago: quiet, with when.
        val c = MoneyRefunds.view(listOf(wait("w1", "Refund · Next order · £30", "Next", today - 16, chasedDay = today - 2)), lines, today, cal, names)!!.rows.single()
        assertFalse(c.lit)
        assertNull(c.chase)
        assertTrue(c.line.endsWith("chased ${CivilDate.shortLabel(today - 2)}"), c.line)
        // A week on, lit again.
        val again = MoneyRefunds.view(listOf(wait("w1", "Refund · Next order · £30", "Next", today - 16, chasedDay = today - 8)), lines, today, cal, names)!!.rows.single()
        assertTrue(again.lit)
    }

    @Test
    fun oldStatementsNeverCallARefundLate() {
        val w = wait("w1", "Refund · Next order · £30", "Next", today - 20)
        val v = MoneyRefunds.view(listOf(w), listOf(line(today - 10, -500, "PRET")), today, cal, names)!!
        assertEquals(MoneyRefunds.WAITING, v.rows.single().state)
        assertFalse(v.rows.single().lit)
        assertEquals("Your statements end ${CivilDate.shortLabel(today - 10)} · import a newer one to see if it's come", v.foot)
    }

    @Test
    fun onlyRefundRowsAndNoneMeansNoSection() {
        assertNull(MoneyRefunds.view(listOf(wait("w1", "Parcel from Next", "Next", today - 3)), listOf(line(today, 3000, "NEXT RETAIL")), today, cal, names))
        assertNull(MoneyRefunds.view(emptyList(), listOf(line(today, 3000, "NEXT RETAIL")), today, cal, names))
    }

    @Test
    fun genericWordsNeverMatch() {
        val w = wait("w1", "Refund · card payment", null, today - 3)
        val v = MoneyRefunds.view(listOf(w), listOf(line(today - 1, 2000, "CARD PAYMENT REFUND"), line(today, -300, "PRET")), today, cal, names)!!
        assertEquals(MoneyRefunds.WAITING, v.rows.single().state)
    }

    @Test
    fun theMoneyScreenCarriesItOnThisMonthOnly() {
        val accounts = listOf(MoneyAccount("a-current", "Current account", "csv", null))
        val txns = listOf(MoneyTxn("t1", "a-current", today - 40, -4000, "AMAZON.CO.UK"), MoneyTxn("t2", "a-current", today - 1, 4000, "AMAZON.CO.UK"))
        val w = wait("w1", "Refund · Amazon order · £40", "Amazon", today - 5)
        val now = MoneyRules.view(accounts, txns, emptyMap(), today, cal, 0, refundWaits = listOf(w))
        assertEquals(MoneyRefunds.BACK, now.refunds!!.rows.single().state)
        assertNull(MoneyRules.view(accounts, txns, emptyMap(), today, cal, 1, refundWaits = listOf(w)).refunds)
    }

    @Test
    fun compactRowsSayTheirStateInAFewWordsAndFoldAfterThree() {
        // Layout rules on Bills (Meka 2026-10-26 22:18): one line per refund, what · its state, three then "N more ›".
        val back = wait("w1", "Refund · Amazon order · £40", "Amazon", today - 6)
        val late = wait("w2", "Refund · Lamp Store · £18", "Lamp Store", today - 20)
        val fresh = wait("w3", "Refund · Kettle Co", "Kettle Co", today)
        val chased = wait("w4", "Refund · Book Barn", "Book Barn", today - 5, chasedDay = today - 2)
        val v = MoneyRefunds.view(listOf(back, late, fresh, chased),
            listOf(line(today - 1, 4000, "AMAZON.CO.UK REFUND"), line(today, -300, "PRET")), today, cal, names)!!
        val by = v.rows.associateBy { it.id }
        assertEquals("Refund · Amazon order · £40", by.getValue("w1").name)
        assertEquals("Back · +£40", by.getValue("w1").brief)
        assertEquals("Late · 20 days · chase it", by.getValue("w2").brief)
        assertEquals("Waiting · since today", by.getValue("w3").brief)
        assertEquals("Chased ${CivilDate.shortLabel(today - 2)} · 5 days", by.getValue("w4").brief)
        assertEquals(3, MoneyRefunds.shown(v.rows, open = false).size)
        assertEquals("1 more ›", MoneyRefunds.moreLabel(v.rows.size, open = false))
        assertEquals(MoneyRefunds.FEWER, MoneyRefunds.moreLabel(v.rows.size, open = true))
        assertNull(MoneyRefunds.moreLabel(3, open = false))
        assertEquals(4, MoneyRefunds.shown(v.rows, open = true).size)
    }
}
