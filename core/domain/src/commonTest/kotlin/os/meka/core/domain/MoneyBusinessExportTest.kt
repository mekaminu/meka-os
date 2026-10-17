package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyBusinessExportTest {
    private val accounts = listOf(
        MoneyAccount("a-current", "Current account", "csv", null),
        MoneyAccount("a-shop", "Shop account", "csv", null),
    )

    private fun txn(id: String, account: String, y: Int, m: Int, d: Int, pence: Long, text: String) =
        MoneyTxn(id, account, CivilDate.toEpochDay(y, m, d), pence, text)

    private val txns = listOf(
        txn("t1", "a-shop", 2025, 5, 1, 150_000, "STRIPE PAYOUT"),
        txn("t2", "a-shop", 2025, 6, 2, -2_000, "CANVA PRO"),
        txn("t3", "a-shop", 2025, 6, 3, -30_000, "TRANSFER TO CURRENT"),
        txn("t4", "a-current", 2025, 7, 1, -5_000, "TESCO STORES 2041"),
        txn("t5", "a-current", 2025, 8, 9, 40_000, "ETSY, \"PAYOUT\""),
        txn("t6", "a-shop", 2026, 4, 6, 10_000, "STRIPE PAYOUT"), // next tax year
        txn("t7", "a-shop", 2025, 4, 5, 10_000, "STRIPE PAYOUT"), // the year before
    )

    private fun etsy() = MoneyCategoryRules.merchantKey("ETSY, \"PAYOUT\"")

    @Test
    fun theYearsLinesTotalsAndProfitInOrder() {
        val r = MoneyBusinessExport.result("2025-26", accounts, txns, emptyMap(), setOf("a-shop"), mapOf(etsy() to true))
        assertEquals("navilife-side-hustle-2025-26.csv", r.fileName)
        val rows = r.csv!!.split("\r\n").filter { it.isNotEmpty() }
        assertEquals("Date,Account,Description,Category,Money in (GBP),Money out (GBP)", rows[0])
        // Only this tax year's side hustle lines (no transfer, no personal shopping), oldest first, quotes escaped.
        assertTrue(rows[1].startsWith("2025-05-01,Shop account,STRIPE PAYOUT,") && rows[1].endsWith(",1500.00,"), rows[1])
        assertTrue(rows[2].startsWith("2025-06-02,Shop account,CANVA PRO,") && rows[2].endsWith(",,20.00"), rows[2])
        assertTrue(rows[3].startsWith("2025-08-09,Current account,\"ETSY, \"\"PAYOUT\"\"\","), rows[3])
        assertTrue(r.csv!!.lines().none { it.contains("TESCO") || it.contains("TRANSFER") || it.startsWith("2026-04-06") || it.startsWith("2025-04-05") })
        assertTrue(rows.any { it == "Tax year 2025–26,,Total money in,,1900.00," }, r.csv)
        assertTrue(rows.any { it == "Tax year 2025–26,,Total money out,,,20.00" }, r.csv)
        assertTrue(rows.any { it == "Tax year 2025–26,,Profit,,1880.00," }, r.csv)
        assertTrue(rows.any { it.startsWith("Tax year 2025–26,,Money out · ") }, r.csv)
        assertTrue(rows.last().contains("Not tax advice"))
        assertEquals("2025–26: 3 payments · in £1,900 · out £20 · profit £1,880 · choose where to save it", r.said.line)
    }

    @Test
    fun aLossIsInTheMoneyOutColumn() {
        val r = MoneyBusinessExport.result("2025-26", accounts, listOf(txn("x", "a-shop", 2025, 9, 1, -5_000, "CANVA PRO")),
            emptyMap(), setOf("a-shop"), emptyMap())
        assertTrue(r.csv!!.split("\r\n").any { it == "Tax year 2025–26,,Loss,,,50.00" }, r.csv)
        assertTrue(r.said.line.contains("loss £50"))
    }

    @Test
    fun nothingMarkedNothingInTheYearOrABadYearSaveNothing() {
        assertEquals(MoneyBusinessExport.NOTHING_MARKED, MoneyBusinessExport.result("2025-26", accounts, txns, emptyMap(), emptySet(), emptyMap()).said.line)
        val empty = MoneyBusinessExport.result("2023-24", accounts, txns, emptyMap(), setOf("a-shop"), emptyMap())
        assertNull(empty.csv)
        assertEquals("No side hustle money in 2023–24 · nothing to save", empty.said.line)
        assertTrue(MoneyBusinessExport.result("soon", accounts, txns, emptyMap(), setOf("a-shop"), emptyMap()).said.refused)
    }

    @Test
    fun theBlockOffersLastYearAndThisYearOnceSomethingIsMarked() {
        val today = CivilDate.toEpochDay(2026, 10, 17)
        val lines = listOf(MoneyBusinessLine("a-shop", today, 1_000, "stripe", MoneyCategory.INCOME))
        assertNull(MoneyBusiness.view(accounts, lines, emptySet(), emptyMap(), 20, today)!!.export)
        val e = MoneyBusiness.view(accounts, lines, setOf("a-shop"), emptyMap(), 20, today)!!.export!!
        assertEquals(MoneyBusinessExport.HEAD, e.head)
        assertEquals(listOf("2025–26", "2026–27 so far"), e.chips.map { it.label })
    }
}
