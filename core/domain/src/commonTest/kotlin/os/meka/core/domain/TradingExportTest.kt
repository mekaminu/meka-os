package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trading tracker, slice 8: the tax export's years and the CSV it saves. Invented addresses and figures. */
class TradingExportTest {
    private val london = LocalCalendar.fixedOffset(3_600_000L) // BST, enough for the boundaries in April
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = CivilDate.toEpochDay(y, m, d) * CivilDate.DAY_MS + h * 3_600_000L
    private val address = "0x" + "ab12".repeat(10)
    private val wallet = TradingWallet(TradingTracker.walletId(address), address, "Main", TradingTracker.OK, null, null, null, null, null, null, null, null, emptyList())

    @Test
    fun taxYearsRunSixthAprilToFifthApril() {
        assertEquals(TradingTaxYear(2026), TradingTaxYear.of(at(2026, 10, 17), london))
        assertEquals(TradingTaxYear(2025), TradingTaxYear.of(at(2026, 4, 5), london))
        assertEquals(TradingTaxYear(2026), TradingTaxYear.of(at(2026, 4, 6), london))
        val y = TradingTaxYear(2025)
        assertEquals("2025–26", y.label)
        assertEquals("2025-26", y.key)
        assertEquals(TradingTaxYear(2099), TradingTaxYear.parse("2099-00"))
        assertEquals(y, TradingTaxYear.parse("2025-26"))
        assertNull(TradingTaxYear.parse("2025-27"))
        assertNull(TradingTaxYear.parse("next year"))
        // Midnight UK time on 6 April, both ends.
        assertEquals(CivilDate.toEpochDay(2025, 4, 6) * CivilDate.DAY_MS - 3_600_000L, y.fromMs(london))
        assertEquals(CivilDate.toEpochDay(2026, 4, 6) * CivilDate.DAY_MS - 3_600_000L, y.toMs(london))
        assertEquals("navilife-hyperliquid-2025-26.csv", TradingExportRules.fileName(y))
    }

    @Test
    fun theViewOffersLastYearAndThisYearSoFar() {
        assertNull(TradingExportRules.view(emptyList(), at(2026, 10, 17), london))
        val v = TradingExportRules.view(listOf(wallet), at(2026, 10, 17), london)!!
        assertEquals("EXPORT FOR TAX", v.head)
        assertEquals(listOf("2025-26" to "2025–26", "2026-27" to "2026–27 so far"), v.chips.map { it.key to it.label })
        assertTrue(TradingTracker.view(listOf(wallet), at(2026, 10, 17), london).export != null)
    }

    @Test
    fun theCsvListsOrdersAndFundingByTimeWithTotals() {
        val y = TradingTaxYear(2025)
        val a = TradingExportAccount(wallet.id, listOf(
            TradingExportOrder(at(2025, 6, 2, 14), "ETH", "Close Long", "2", "3100.5", 620_100, 20_000, 150),
            TradingExportOrder(at(2025, 5, 1, 9), "ETH", "Open Long", "2", "3000", 600_000, 0, 140),
        ), listOf(TradingExportFunding(CivilDate.toEpochDay(2025, 5, 20), "ETH", -325)))
        val csv = TradingExportRules.csv(y, listOf(a), mapOf(wallet.id to "Main"))
        val lines = csv.trimEnd().split("\r\n")
        assertEquals(TradingExportRules.COLUMNS.joinToString(","), lines[0])
        assertEquals("2025-05-01 09:00,Main,Trade,ETH,Open Long,2,3000,6000.00,0.00,-1.40,,-1.40", lines[1])
        assertEquals("2025-05-20,Main,Funding,ETH,,,,,,,-3.25,-3.25", lines[2])
        assertEquals("2025-06-02 14:00,Main,Trade,ETH,Close Long,2,3100.5,6201.00,200.00,-1.50,,198.50", lines[3])
        // closed 200.00 − fees 2.90 + funding −3.25 = 193.85
        assertEquals("Tax year 2025–26,Main,Total,,2 orders,,,,200.00,-2.90,-3.25,193.85", lines[4])
        assertEquals(5, lines.size)
        val said = TradingExportRules.saidReady(y, listOf(a)).line
        assertTrue(said.startsWith("2025–26: 2 orders and 1 day of funding · net +$193.85 after fees and funding"), said)
    }

    @Test
    fun severalAccountsAddUpAndAPartialOneSaysSo() {
        val y = TradingTaxYear(2025)
        val one = TradingExportAccount("w1", listOf(TradingExportOrder(at(2025, 7, 1), "BTC", "Close Short", "0.1", "60000", 600_000, -5_000, 100)), emptyList(), partial = true)
        val two = TradingExportAccount("w2", listOf(TradingExportOrder(at(2025, 8, 1), "SOL", "Close Long", "10", "150", 150_000, 2_500, 50)), emptyList())
        val failed = TradingExportAccount("w3", emptyList(), emptyList(), failed = true)
        val lines = TradingExportRules.csv(y, listOf(one, two, failed), mapOf("w1" to "Bot, fast", "w2" to "=Sum")).trimEnd().split("\r\n")
        // A comma is quoted; a leading = is kept as text.
        assertTrue(lines[1].contains("\"Bot, fast\""), lines[1])
        assertTrue(lines[2].contains(",'=Sum,"), lines[2])
        assertTrue(lines.any { it.contains(",Note,") }, lines.toString())
        assertEquals("Tax year 2025–26,All accounts,Total,,2 orders,,,,-25.00,-1.50,0.00,-26.50", lines.last())
        val said = TradingExportRules.saidReady(y, listOf(one, two, failed)).line
        assertTrue("across 2 accounts" in said && "1 account couldn't be read" in said && "most recent trades" in said, said)
        assertFalse(said.isEmpty())
    }

    @Test
    fun plainAmountsAndFields() {
        assertEquals("-0.05", TradingExportRules.plain(-5))
        assertEquals("1234567.89", TradingExportRules.plain(123_456_789))
        assertEquals("\"say \"\"hi\"\"\"", TradingExportRules.field("say \"hi\""))
        assertEquals("2026-04-06 00:00", TradingExportRules.utc(CivilDate.toEpochDay(2026, 4, 6) * CivilDate.DAY_MS))
    }
}
