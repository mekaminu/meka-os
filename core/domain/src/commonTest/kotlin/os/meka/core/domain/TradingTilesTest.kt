package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trading's month as four tiles (the mockup's 2×2) and ALERTS folded into one row with a sheet. */
class TradingTilesTest {
    private val a1 = "0x" + "1".repeat(40)
    private val a2 = "0x" + "2".repeat(40)

    private fun stats(coin: String, trades: Int, wins: Int, realised: Long, fees: Long, funding: Long) =
        TradingCoinStats(coin, trades, wins, trades - wins, realised, fees, funding, 0, 0, 0, 0)

    private fun wallet(address: String, trades: List<TradingCoinStats>, read: Boolean = true) = TradingWallet(
        TradingTracker.walletId(address), address, "Main", TradingTracker.OK, 1L, 1L, 100_000, null, null, null, null, 0, emptyList(),
        trades = trades, tradesAtMs = if (read) 1L else null,
    )

    @Test
    fun fourTilesFromTheWindowsTradesAcrossAccounts() {
        val v = assertNotNull(TradingTiles.view(listOf(
            wallet(a1, listOf(stats("BTC", 15, 10, 50_000, 3_000, -2_000))),
            wallet(a2, listOf(stats("ETH", 8, 4, 11_000, 2_200, -1_800), stats("BTC", 0, 0, 0, 0, 0))),
        )))
        assertEquals("LAST 30 DAYS", v.head)
        assertEquals(listOf("Closed profit", "Funding paid", "Fees", "Winning trades"), v.tiles.map { it.label })
        assertEquals(listOf("+$610.00", "−$38.00", "−$52.00", "14 of 23"), v.tiles.map { it.value })
        assertEquals(listOf(true, false, false, null), v.tiles.map { it.up })
        assertTrue(v.spoken.startsWith("Last 30 days. Closed profit +$610.00"))
    }

    @Test
    fun fundingReceivedSaysSoAndNoTradesSaysNoneClosed() {
        val v = assertNotNull(TradingTiles.view(listOf(wallet(a1, listOf(stats("ETH", 0, 0, 0, 0, 1_250))))))
        assertEquals("Funding received", v.tiles[1].label)
        assertEquals("+$12.50", v.tiles[1].value)
        assertEquals(true, v.tiles[1].up)
        assertEquals("None closed", v.tiles[3].value)
    }

    @Test
    fun noTilesUntilTradesAreReadOrWithNothingInTheWindow() {
        assertNull(TradingTiles.view(emptyList()))
        assertNull(TradingTiles.view(listOf(wallet(a1, emptyList(), read = false))))
        assertNull(TradingTiles.view(listOf(wallet(a1, emptyList()))))
    }

    @Test
    fun theViewCarriesTheTilesAndTradesDropsWhatTheySay() {
        val cal = object : LocalCalendar {
            override fun epochDayOf(epochMs: Long) = epochMs.floorDiv(86_400_000L)
            override fun minuteOfDay(epochMs: Long) = (epochMs.mod(86_400_000L) / 60_000L).toInt()
            override fun toEpochMs(epochDay: Long, minuteOfDay: Int) = epochDay * 86_400_000L + minuteOfDay * 60_000L
        }
        val t = TradingTracker.view(listOf(wallet(a1, listOf(stats("BTC", 3, 2, 9_000, 600, -300)))), 2L, cal)
        assertNotNull(t.tiles)
        val full = assertNotNull(t.trades)
        assertNotNull(full.summary)
        val shown = assertNotNull(TradingTiles.trades(t))
        assertNull(shown.summary)
        assertEquals(listOf("After fees and funding"), shown.lines.map { it.label })
        assertEquals(full.coins, shown.coins)
        // Without tiles, YOUR TRADES is as it was.
        assertEquals(full, TradingTiles.trades(t.copy(tiles = null)))
        assertNull(TradingTiles.trades(t.copy(trades = null)))
    }

    @Test
    fun alertsFoldIntoOneRow() {
        val on = TradingAlerts.view(TradingAlertSetting.DEFAULT)
        val r = TradingTiles.alertsRow(on)
        assertEquals("All 4 alerts on", r.title)
        assertTrue(r.line.startsWith("Near liquidation "), r.line)
        assertTrue(r.spoken.endsWith("Opens a sheet"))
        val off = TradingAlerts.view(TradingAlertSetting(TradingLiqAlert.OFF, TradingDropAlert.OFF, TradingFillAlert.OFF, TradingFundingAlert.OFF, false))
        val ro = TradingTiles.alertsRow(off)
        assertEquals("All alerts off", ro.title)
        assertEquals("Near liquidation off · a drop off · a big order off · funding off", ro.line)
        val some = TradingAlerts.view(TradingAlertSetting(TradingLiqAlert.OFF, TradingDropAlert.OFF, TradingFillAlert.FIVE, TradingFundingAlert.OFF, false))
        val rs = TradingTiles.alertsRow(some)
        assertEquals("1 of 4 alerts on", rs.title)
        assertTrue("a big order 5×" in rs.line)
        assertFalse("a big order off" in rs.line)
    }
}
