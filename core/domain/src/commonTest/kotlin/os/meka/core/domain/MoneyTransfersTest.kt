package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Money bugs (A1): card repayments and moves between his own accounts are transfers. Invented accounts and amounts shaped like his. */
class MoneyTransfersTest {
    private val aug = CivilDate.toEpochDay(2026, 8, 31)
    private val bank = MoneyAccount("a-bank", "Current account", "csv", null)
    private val hsbcCard = MoneyAccount("a-hsbc", "HSBC Credit card", "csv", null)
    private val amex = MoneyAccount("a-amex", "Amex", "csv", null)
    private val savings = MoneyAccount("a-save", "Revolut", "csv", null)
    private val accounts = listOf(bank, hsbcCard, amex, savings)

    private fun t(id: String, account: MoneyAccount, d: Int, pence: Long, text: String) =
        MoneyTxn(id, account.id, CivilDate.toEpochDay(2026, 8, d), pence, text)

    private val august = listOf(
        // The card repayment: "Hsbc −£767.72" on the bank, "Payment Thank YOU +£767.72" on the card, two days apart.
        t("r1", bank, 3, -76_772, "Hsbc"), t("r2", hsbcCard, 5, 76_772, "Payment Thank YOU"),
        // Amex: "American EXP −£1,000" and "Payment Received +£1,000".
        t("x1", bank, 10, -100_000, "American EXP"), t("x2", amex, 11, 100_000, "Payment Received"),
        // A move between his accounts: "…sent From +£500" on the bank, −£500 on the other.
        t("m1", savings, 14, -50_000, "To M Smith"), t("m2", bank, 14, 50_000, "Money sent From M Smith"),
        // Real spending and pay stay as they are.
        t("s1", bank, 4, -6_000, "TESCO STORES"), t("s2", hsbcCard, 6, -2_500, "DELIVEROO"),
        t("p1", bank, 25, 250_000, "SALARY OAKFIELD LTD"),
    )

    @Test
    fun repaymentsAndOwnMovesAreTransfers() {
        val moves = MoneyTransfers.ids(accounts, august, emptyMap())
        assertEquals(setOf("r1", "r2", "x1", "x2", "m1", "m2"), moves)
    }

    @Test
    fun theMonthCountsOnlyRealSpendingAndPay() {
        val month = MoneyRules.view(accounts, august, emptyMap(), aug, LocalCalendar.UTC, 0).month!!
        assertEquals("Spent £85 · in £2,500", month.spentLine)
        // Money's wrong numbers (3): Spending's "Left" is the month's in less out, not a balance.
        assertEquals("£2,415", month.spending!!.tiles.single { it.label == "Left" }.value)
        assertFalse(month.categories.any { it.key == MoneyCategory.OTHER.key })
        val rows = month.txns.associateBy { it.id }
        for (id in listOf("r1", "r2", "x1", "x2", "m1", "m2")) assertEquals(MoneyCategory.TRANSFERS.key, rows.getValue(id).category, id)
        assertEquals(MoneyCategory.GROCERIES.key, rows.getValue("s1").category)
        assertEquals(MoneyCategory.INCOME.key, rows.getValue("p1").category)
    }

    @Test
    fun aShopAndARefundElsewhereAreNotAPair() {
        val lines = listOf(t("a", bank, 3, -5_000, "TESCO STORES"), t("b", hsbcCard, 4, 5_000, "TESCO STORES REFUND"))
        assertTrue(MoneyTransfers.ids(accounts, lines, emptyMap()).isEmpty())
    }

    @Test
    fun payIsNeverAMoveAndPairsNeedTwoAccountsAndNearDays() {
        val pay = listOf(t("a", savings, 3, -250_000, "To M Smith"), t("b", bank, 3, 250_000, "SALARY OAKFIELD LTD"))
        assertTrue(MoneyTransfers.ids(accounts, pay, emptyMap()).isEmpty())
        val same = listOf(t("a", bank, 3, -4_200, "M SMITH"), t("b", bank, 3, 4_200, "M SMITH"))
        assertTrue(MoneyTransfers.ids(accounts, same, emptyMap()).isEmpty())
        val far = listOf(t("a", bank, 1, -4_200, "M SMITH"), t("b", amex, 9, 4_200, "M SMITH"))
        assertTrue(MoneyTransfers.ids(accounts, far, emptyMap()).isEmpty())
        // A penny out is still the same move; each line pairs once.
        val penny = listOf(t("a", bank, 1, -4_201, "M SMITH"), t("b", amex, 2, 4_200, "M SMITH"), t("c", amex, 2, 4_200, "M SMITH"))
        assertEquals(2, MoneyTransfers.ids(accounts, penny, emptyMap()).size)
    }

    @Test
    fun hisCorrectionWins() {
        val rules = mapOf("HSBC" to MoneyCategory.BILLS)
        val moves = MoneyTransfers.ids(accounts, august, rules)
        assertFalse("r1" in moves)
        val month = MoneyRules.view(accounts, august, rules, aug, LocalCalendar.UTC, 0).month!!
        assertEquals(MoneyCategory.BILLS.key, month.txns.first { it.id == "r1" }.category)
    }

    @Test
    fun aPayeeThatIsAnotherOfHisAccountsIsAMoveWithoutItsPair() {
        // Only the bank's statement is in: "Hsbc" still names his HSBC card, so it isn't spending.
        val moves = MoneyTransfers.ids(accounts, listOf(t("r1", bank, 3, -76_772, "Hsbc")), emptyMap())
        assertEquals(setOf("r1"), moves)
        // On the HSBC card itself, "HSBC" is the bank's own line (interest, a fee…), not a move.
        assertTrue(MoneyTransfers.ids(accounts, listOf(t("f", hsbcCard, 3, -1_200, "HSBC")), emptyMap()).isEmpty())
        assertEquals(setOf("hsbc"), MoneyTransfers.bankWords(listOf(hsbcCard)).getValue("a-hsbc"))
    }
}
