package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Money's layout rules on Trading: cards, five positions then See all, and one foot line whose ⓘ says the rest. */
class MoneyTradingLayoutTest {
    private fun pos(i: Int) = TradingPositionRow("w:C$i", "C$i long", "", "+$1.00", true, null, false, "C$i")

    private fun view(n: Int, trades: TradingTradesView? = null) = TradingView(
        TradingTracker.HEAD, "$1,000.00", "Equity", emptyList(), if (n == 0) "No open positions" else "$n open positions",
        (1..n).map { pos(it) }, emptyList(), null, TradingTracker.HINT, TradingTracker.FOOT, "spoken", trades = trades,
    )

    @Test
    fun fivePositionsShowThenSeeAll() {
        val seven = view(7)
        assertEquals(5, MoneyTradingLayout.positions(seven, all = false).size)
        assertEquals(7, MoneyTradingLayout.positions(seven, all = true).size)
        assertEquals("See all 7 ›", MoneyTradingLayout.more(seven, all = false))
        assertEquals("Show fewer", MoneyTradingLayout.more(seven, all = true))
        assertNull(MoneyTradingLayout.more(view(5), all = false))
        assertEquals(3, MoneyTradingLayout.positions(view(3), all = false).size)
    }

    @Test
    fun thePositionsCardShowsOnceAnAccountIsRead() {
        assertTrue(MoneyTradingLayout.hasPositions(view(0)))
        assertTrue(MoneyTradingLayout.hasPositions(view(2)))
        val waiting = view(0).copy(unrealisedLine = null)
        assertFalse(MoneyTradingLayout.hasPositions(waiting))
        // The equity card never sits empty: before the first read it says it's reading.
        assertEquals(TradingTracker.WAITING, MoneyTradingLayout.waiting(waiting.copy(total = null)))
        assertNull(MoneyTradingLayout.waiting(view(1)))
    }

    @Test
    fun theFootSaysEachCardsSourceOnce() {
        val trades = TradingTradesView(TradingTracker.TRADES_HEAD, null, emptyList(), null, null, emptyList(), null,
            TradingTracker.NO_TRADES, TradingTracker.TRADES_FOOT, TradingTracker.NO_TRADES)
        val t = view(1, trades)
        val info = MoneyTradingLayout.info(t)
        assertEquals(TradingTracker.FOOT, info.first())
        assertTrue(TradingTracker.TRADES_FOOT in info)
        assertEquals(info.distinct(), info)
        assertTrue(MoneyTradingLayout.FOOT_SHORT.contains("not trading advice"))
        // The tab's ⓘ is the tracker's, once there is one; with no trading view there is no foot.
        val v = MoneyView.LOCKED.copy(locked = false, trading = t)
        assertEquals(info, assertNotNull(MoneyTabs.info(MoneyTab.TRADING, v)))
        assertNull(MoneyTabs.info(MoneyTab.TRADING, MoneyView.LOCKED))
    }

    @Test
    fun aNewAddressViewStillHasAFoot() {
        val empty = TradingTracker.view(emptyList(), 0L, object : LocalCalendar {
            override fun epochDayOf(epochMs: Long) = epochMs.floorDiv(86_400_000L)
            override fun minuteOfDay(epochMs: Long) = 0
            override fun toEpochMs(epochDay: Long, minuteOfDay: Int) = epochDay * 86_400_000L
        })
        assertEquals(listOf(TradingTracker.FOOT), MoneyTradingLayout.info(empty))
        assertFalse(MoneyTradingLayout.hasPositions(empty))
        assertNull(MoneyTradingLayout.waiting(empty))
    }
}
