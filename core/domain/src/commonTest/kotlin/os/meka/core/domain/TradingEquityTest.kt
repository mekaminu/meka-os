package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trading's equity card as the mockup (README item 5). Invented addresses and figures only. */
class TradingEquityTest {
    private val cal = object : LocalCalendar {
        override fun epochDayOf(epochMs: Long) = epochMs.floorDiv(86_400_000L)
        override fun minuteOfDay(epochMs: Long) = (epochMs.mod(86_400_000L) / 60_000L).toInt()
        override fun toEpochMs(epochDay: Long, minuteOfDay: Int) = epochDay * 86_400_000L + minuteOfDay * 60_000L
    }
    private val today = CivilDate.toEpochDay(2026, 10, 17)
    private val now = cal.toEpochMs(today, 14 * 60)
    private val a1 = "0x" + "ab12".repeat(10)
    private val a2 = "0x" + "cd34".repeat(10)

    private fun wallet(id: String = "w", address: String = a1, label: String = "Main", rate: Long? = 750_000, equity: Long? = 200_000, days: Int = 12) =
        TradingWallet(
            id, address, label, TradingTracker.OK, now, now, equity, 1_000, -2_000, 10_000, 40_000, 0, emptyList(),
            curve = (days downTo 1).map { TradingCurvePoint(today - it, 190_000 + (days - it) * 1_000L, (days - it) * 1_000L) },
            curveNowCents = days * 1_000L, gbpPerUsdMicros = rate, fxDay = rate?.let { today },
        )

    @Test
    fun theCardCarriesItsLabelTheOtherCurrencyAndTheMonth() {
        val v = TradingTracker.view(listOf(wallet()), now, cal)
        val c = assertNotNull(v.equity)
        assertEquals("Hyperliquid · Main 0xab12…ab12", c.label)
        assertEquals("$2,000.00", c.total)
        assertEquals("≈ £1,500", c.other)
        assertEquals(TradingCurrencyRules.GBP, c.swapKey)
        val m = TradingEquity.range(c, TradingEquity.DEFAULT)
        assertEquals("+$100.00 (5.3%) over the month", m.change)
        assertEquals(true, m.up)
        assertEquals(13, m.points.size, "the month draws every day end and now")
        assertEquals("−$20.00 (−1.0%) this week", TradingEquity.range(c, TradingEquity.WEEK).change)
        assertEquals(false, TradingEquity.range(c, TradingEquity.WEEK).up)
        assertEquals(8, TradingEquity.range(c, TradingEquity.WEEK).points.size)
        assertEquals("+$10.00 (0.5%) today", TradingEquity.range(c, TradingEquity.DAY).change)
        assertEquals(2, TradingEquity.range(c, TradingEquity.DAY).points.size)
        assertTrue(c.foot!!.startsWith("All time +$400.00 · updated"), c.foot)
        assertFalse(MoneyTradingLayout.showHead(v), "the card carries its own label")
        assertFalse(MoneyTradingLayout.showCurveCard(v), "the P&L line sits in the card")
    }

    @Test
    fun chipsAreTheThreeRangesTheDataHolds() {
        val c = assertNotNull(TradingTracker.view(listOf(wallet()), now, cal).equity)
        assertEquals(listOf("1D" to false, "1W" to false, "1M" to true), TradingEquity.chips(c, "nonsense").map { it.label to it.on })
        assertEquals(listOf(false, true, false), TradingEquity.chips(c, TradingEquity.WEEK).map { it.on })
        assertTrue(TradingEquity.chips(c, TradingEquity.WEEK)[1].spoken.endsWith(", chosen"))
    }

    @Test
    fun inPoundsTheOtherIsDollarsAndTheTapGoesBack() {
        val c = assertNotNull(TradingTracker.view(listOf(wallet()), now, cal, TradingAlertSetting(pounds = true)).equity)
        assertEquals("£1,500", c.total)
        assertEquals("≈ $2,000", c.other)
        assertEquals(TradingCurrencyRules.USD, c.swapKey)
        assertEquals("+£75 (5.3%) over the month", TradingEquity.range(c, TradingEquity.MONTH).change)
    }

    @Test
    fun withoutARateThereIsNoOtherAndNoSwap() {
        val c = assertNotNull(TradingTracker.view(listOf(wallet(rate = null)), now, cal).equity)
        assertNull(c.other)
        assertNull(c.swapKey)
    }

    @Test
    fun twoAccountsAreNamedByCountAndAShortCurveDrawsNoLine() {
        val v = TradingTracker.view(listOf(wallet(), wallet("w2", a2, "Spare", days = 1)), now, cal)
        val c = assertNotNull(v.equity)
        assertEquals("Hyperliquid · 2 accounts", c.label)
        assertEquals("$4,000.00", c.total)
        val one = assertNotNull(TradingTracker.view(listOf(wallet(days = 1)), now, cal).equity)
        assertTrue(TradingEquity.range(one, TradingEquity.WEEK).points.size == 2 || TradingEquity.range(one, TradingEquity.WEEK).points.isEmpty())
    }

    @Test
    fun beforeTheFirstReadThereIsNoCardAndTheSetUpStays() {
        val v = TradingTracker.view(listOf(wallet(equity = null)), now, cal)
        assertNull(v.equity)
        assertTrue(MoneyTradingLayout.showHead(v))
        assertNull(TradingTracker.view(emptyList(), now, cal).equity)
    }

    @Test
    fun theCurrencyLineMovesBehindTheFootInfo() {
        val v = TradingTracker.view(listOf(wallet()), now, cal)
        assertTrue(MoneyTradingLayout.info(v).any { it.startsWith("In US dollars") })
        assertFalse(MoneyTradingLayout.info(v).contains(TradingTracker.CURVE_FOOT))
    }

    @Test
    fun sharesReadWell() {
        assertEquals("7.8%", TradingEquity.pct1(0.0779))
        assertEquals("−2.1%", TradingEquity.pct1(-0.021))
        assertEquals("0%", TradingEquity.pct1(0.00001))
        assertEquals("150%", TradingEquity.pct1(1.5))
        assertEquals("≈ £6,512", TradingEquity.about(651_234, pounds = true))
        assertEquals("+$610.00", TradingEquity.change(61_000, 0, TradingCcy.USD))
    }
}
