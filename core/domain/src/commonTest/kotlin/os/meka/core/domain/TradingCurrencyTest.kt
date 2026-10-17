package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trading tracker, slice 12: TRADING in dollars or pounds. Invented addresses and figures only. */
class TradingCurrencyTest {
    private val cal = object : LocalCalendar {
        override fun epochDayOf(epochMs: Long) = epochMs.floorDiv(86_400_000L)
        override fun minuteOfDay(epochMs: Long) = (epochMs.mod(86_400_000L) / 60_000L).toInt()
        override fun toEpochMs(epochDay: Long, minuteOfDay: Int) = epochDay * 86_400_000L + minuteOfDay * 60_000L
    }
    private val today = CivilDate.toEpochDay(2026, 10, 17)
    private val now = cal.toEpochMs(today, 14 * 60)
    private val a1 = "0x" + "ab12".repeat(10)

    private fun wallet(rate: Long? = 750_000, equity: Long? = 200_000) = TradingWallet(
        "w", a1, "Main", TradingTracker.OK, now, now, equity, 1_000, -2_000, 10_000, 40_000, 1_000,
        listOf(TradingPosition("ETH", "0.5", "3000", "3100", "2500", 1_000, 3)),
        trades = listOf(TradingCoinStats("ETH", 2, 1, 1, 4_000, 400, -200, 6_000, -2_000, 6_000, -2_000)), tradesAtMs = now,
        gbpPerUsdMicros = rate, fxDay = rate?.let { today },
    )

    @Test
    fun dollarsByDefaultWithTheChoiceOffered() {
        val v = TradingTracker.view(listOf(wallet()), now, cal)
        assertEquals("$2,000.00", v.total)
        assertEquals("Equity · about £1,500 · updated now", v.totalLine?.replace(Regex("updated .*"), "updated now"))
        assertEquals("+$100.00", v.pnl.first { it.label == "This month" }.amount)
        val c = assertNotNull(v.currency)
        assertEquals(listOf("Dollars" to true, "Pounds" to false), c.chips.map { it.label to it.on })
        assertTrue(c.line.startsWith("In US dollars"))
        assertEquals(TradingTracker.FOOT, v.foot)
        assertNull(TradingTracker.view(emptyList(), now, cal).currency)
    }

    @Test
    fun poundsShowEveryAmountAtTheRateAndPricesStayInDollars() {
        val v = TradingTracker.view(listOf(wallet()), now, cal, TradingAlertSetting(pounds = true))
        assertEquals("£1,500", v.total)
        assertTrue(v.totalLine!!.startsWith("Equity · $2,000.00 · updated"))
        assertEquals("+£75", v.pnl.first { it.label == "This month" }.amount)
        assertEquals("−£15", v.pnl.first { it.label == "This week" }.amount)
        val p = v.positions.single()
        assertEquals("+£7.50", p.amount)
        assertTrue(p.line.contains("$3,000"), p.line)
        assertTrue(p.liquidationLine!!.contains("$2,500"))
        assertTrue(v.wallets.single().line.contains("£1,500"))
        assertEquals("+£25.50", v.trades!!.lines.last().amount)
        assertTrue(v.currency!!.line.contains("£0.75 to the dollar"))
        assertEquals(listOf(false, true), v.currency!!.chips.map { it.on })
        assertEquals(TradingTracker.FOOT_GBP, v.foot)
    }

    @Test
    fun poundsWaitForTheRate() {
        val v = TradingTracker.view(listOf(wallet(rate = null)), now, cal, TradingAlertSetting(pounds = true))
        assertEquals("$2,000.00", v.total)
        assertTrue(v.currency!!.line.startsWith("Showing dollars until"))
        assertEquals(listOf(false, true), v.currency!!.chips.map { it.on })
        // An unbelievable rate counts as none.
        assertFalse(TradingCcy.of(listOf(wallet(rate = 5_000_000)), true).pounds)
        assertTrue(TradingCcy.of(listOf(wallet()), true).pounds)
        assertFalse(TradingCcy.of(listOf(wallet()), false).pounds)
    }

    @Test
    fun theChoiceIsSyncedOnTheAlertsEntity() {
        val s = TradingAlerts.with(TradingAlertSetting.DEFAULT, TradingCurrencyRules.KIND, TradingCurrencyRules.GBP)!!
        assertTrue(s.pounds)
        assertEquals(s, TradingAlerts.read(TradingAlerts.settingFields(s, now)))
        assertFalse(TradingAlerts.read(null).pounds)
        assertNull(TradingAlerts.with(s, TradingCurrencyRules.KIND, "eur"))
        // Changing an alert keeps the currency.
        assertTrue(TradingAlerts.with(s, TradingAlerts.LIQ, "5")!!.pounds)
        assertTrue(TradingAlerts.switchedLine(TradingCurrencyRules.KIND, s).startsWith("Trading in pounds"))
        assertFalse(TradingAlerts.with(s, TradingCurrencyRules.KIND, TradingCurrencyRules.USD)!!.pounds)
    }
}
