package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.Replica
import os.meka.core.sync.fv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trading tracker, slice 1: typed addresses, the server's figures read back, and the TRADING view. Invented addresses only. */
class TradingTrackerTest {
    private val cal = object : LocalCalendar {
        override fun epochDayOf(epochMs: Long) = epochMs.floorDiv(86_400_000L)
        override fun minuteOfDay(epochMs: Long) = (epochMs.mod(86_400_000L) / 60_000L).toInt()
        override fun toEpochMs(epochDay: Long, minuteOfDay: Int) = epochDay * 86_400_000L + minuteOfDay * 60_000L
    }
    private val now = cal.toEpochMs(CivilDate.toEpochDay(2026, 10, 17), 14 * 60)
    private val a1 = "0x" + "ab12".repeat(10)
    private val a2 = "0x" + "00ff".repeat(10)

    private fun replica(): Replica {
        var n = 0
        return Replica("hh", "fold", HlcClock("fold", { now }), InMemoryReplicaStore(), MekaSchema) { "op${n++}" }
    }

    @Test
    fun addressesAreReadFromWhatHeTypesAndKeysAreRefused() {
        assertEquals(TradingAddressTyped(a1, "Main"), TradingTracker.parse("Main ${a1.uppercase().replace("0X", "0x")}"))
        assertEquals(TradingAddressTyped(a1, "Bot two"), TradingTracker.parse("$a1 · Bot two"))
        assertEquals(TradingAddressTyped(a1, null), TradingTracker.parse(" $a1 "))
        assertNull(TradingTracker.parse("0x1234"))
        assertNull(TradingTracker.parse("my key 0x" + "1f".repeat(32)))
        assertNull(TradingTracker.parse("1f".repeat(32)))
        assertEquals("0xab12…ab12", TradingTracker.short(a1))
        assertEquals(TradingTracker.walletId(a1), TradingTracker.walletId(a1.uppercase().replace("0X", "0x")))
    }

    @Test
    fun moneyAndPricesReadNaturally() {
        assertEquals("$12,345.67", TradingTracker.usd(1_234_567))
        assertEquals("$123,457", TradingTracker.usd(12_345_678))
        assertEquals("+$120.50", TradingTracker.signedUsd(12_050))
        assertEquals("−$40.00", TradingTracker.signedUsd(-4_000))
        assertEquals("$0.00", TradingTracker.signedUsd(0))
        assertEquals("$61,200", TradingTracker.price("61200.4"))
        assertEquals("$3,012.5", TradingTracker.price("3012.5"))
        assertEquals("$1.234", TradingTracker.price("1.2340"))
        assertEquals("$0.000123", TradingTracker.price("0.000123"))
        assertNull(TradingTracker.price("0"))
    }

    @Test
    fun positionsRoundTripAndLiquidationDistance() {
        val p = TradingPosition("BTC", "-0.25", "61200", "60000", "66000", -12_345, 5)
        val back = TradingTracker.decode(TradingTracker.encode(listOf(p, p.copy(coin = "ETH", size = "2", liquidation = null))))
        assertEquals(2, back.size)
        assertEquals(p, back[0])
        assertFalse(back[0].long)
        assertTrue(back[1].long)
        assertTrue(kotlin.math.abs(back[0].liquidationDistance!! - 0.1) < 1e-9)
        assertNull(back[1].liquidationDistance)
        assertEquals(emptyList(), TradingTracker.decode("junk\nBTC|x|1|2|3|4"))
    }

    @Test
    fun readAtItsPace() {
        val w = TradingWallet("w", a1, "Main", null, null, null, null, null, null, null, null, null, emptyList())
        assertTrue(TradingTracker.due(w, now))
        val idle = w.copy(state = TradingTracker.OK, triedAtMs = now - 10 * 60_000L)
        assertFalse(TradingTracker.due(idle, now))
        assertTrue(TradingTracker.due(idle.copy(triedAtMs = now - TradingTracker.IDLE_MS), now))
        val open = idle.copy(positions = listOf(TradingPosition("BTC", "1", null, null, null, 0, null)))
        assertTrue(TradingTracker.due(open, now))
        assertTrue(TradingTracker.due(idle.copy(state = TradingTracker.ERROR, triedAtMs = now - TradingTracker.RETRY_MS), now))
    }

    @Test
    fun addRemoveUndoAndTheView() {
        val r = replica()
        val store = TradingStore(r)
        assertEquals(TradingTracker.EMPTY, TradingTracker.view(store.wallets(), now, cal).empty)
        assertTrue(store.add("hello").refused)
        val added = store.add("Main $a1")
        assertFalse(added.refused)
        assertEquals(1, store.wallets().size)
        var v = TradingTracker.view(store.wallets(), now, cal)
        assertNull(v.total)
        assertTrue(v.wallets.single().line.endsWith(TradingTracker.WAITING.replaceFirstChar { it.lowercase() }))
        // What the server writes.
        val id = TradingTracker.walletId(a1)
        r.commitLocal(EntityTypes.TRADING_WALLET, id, mapOf(
            TradingWalletFields.STATE to TradingTracker.OK.fv(), TradingWalletFields.READ_AT to (now - 60_000L).fv(),
            TradingWalletFields.EQUITY to 1_234_567L.fv(), TradingWalletFields.PNL_DAY to 12_050L.fv(), TradingWalletFields.PNL_WEEK to (-4_000L).fv(),
            TradingWalletFields.PNL_ALL to 250_000L.fv(), TradingWalletFields.UNREALISED to 34_000L.fv(),
            TradingWalletFields.POSITIONS to TradingTracker.encode(listOf(
                TradingPosition("ETH", "2", "3000", "3170", null, 34_000, 3),
                TradingPosition("SOL", "-10", "150", "150", "160", 0, 10),
            )).fv(),
        ))
        v = TradingTracker.view(store.wallets(), now, cal)
        assertEquals("$12,345.67", v.total)
        assertEquals("Equity · updated 13:59", v.totalLine)
        assertEquals(listOf("Today" to "+$120.50", "This week" to "−$40.00", "All time" to "+$2,500.00"), v.pnl.map { it.label to it.amount })
        assertEquals("2 open positions · +$340.00 not yet taken", v.unrealisedLine)
        // The one nearest liquidation first, lit.
        val sol = v.positions.first()
        assertEquals("SOL · short 10", sol.title)
        assertEquals("Liquidation at $160 · 7% away", sol.liquidationLine)
        assertTrue(sol.lit)
        assertEquals("ETH · long 2", v.positions[1].title)
        assertEquals("Entry $3,000 · now $3,170 · 3×", v.positions[1].line)
        assertFalse(v.positions[1].lit)
        assertTrue(v.spoken.contains("SOL · short 10 near liquidation"))
        // Remove, then Undo.
        val removed = store.remove(id)
        assertTrue(store.wallets().isEmpty())
        assertTrue(store.undo(removed.undo!!))
        assertEquals("Main", store.wallets().single().label)
        // Undo of the add takes it away again.
        assertTrue(store.undo(added.undo!!))
        assertTrue(store.wallets().isEmpty())
    }

    @Test
    fun twoAccountsAddUpAndAFailureSaysSo() {
        val r = replica()
        val store = TradingStore(r)
        store.add("Main $a1")
        assertNotNull(store.add("Bot $a2").undo)
        r.commitLocal(EntityTypes.TRADING_WALLET, TradingTracker.walletId(a1), mapOf(
            TradingWalletFields.STATE to TradingTracker.OK.fv(), TradingWalletFields.EQUITY to 100_000L.fv(), TradingWalletFields.PNL_DAY to 1_000L.fv(),
        ))
        r.commitLocal(EntityTypes.TRADING_WALLET, TradingTracker.walletId(a2), mapOf(
            TradingWalletFields.STATE to TradingTracker.ERROR.fv(), TradingWalletFields.TRIED_AT to (now - 5 * 60_000L).fv(),
            TradingWalletFields.EQUITY to 50_000L.fv(), TradingWalletFields.PNL_DAY to (-3_000L).fv(), TradingWalletFields.POSITIONS to FieldValue.Null,
        ))
        val v = TradingTracker.view(store.wallets(), now, cal)
        assertEquals("$1,500.00", v.total)
        assertEquals("Equity across 2 accounts", v.totalLine)
        assertEquals("−$20.00", v.pnl.single().amount)
        assertEquals("No open positions", v.unrealisedLine)
        val bot = v.wallets.first { it.name == "Bot" }
        assertTrue(bot.lit)
        assertTrue(bot.line.contains("couldn't read it from Hyperliquid at 13:55"))
        // The same address again renames it.
        assertEquals("Renamed 0x00ff…00ff to Scalper", store.add("Scalper $a2").line)
        store.deleteAll()
        assertTrue(store.wallets().isEmpty())
    }

    @Test
    fun tradesRoundTripAndAddUpAcrossAccounts() {
        val eth = TradingCoinStats("ETH", 3, 2, 1, 30_000, 1_500, -500, 40_000, -10_000, 25_000, -10_000)
        val sol = TradingCoinStats("SOL", 1, 0, 1, -5_000, 300, 200, 0, -5_000, -5_000, -5_000)
        val back = TradingTracker.decodeTrades(TradingTracker.encodeTrades(listOf(eth, sol)))
        assertEquals(listOf(eth, sol), back)
        assertEquals(28_000L, eth.netCents)
        assertEquals(emptyList(), TradingTracker.decodeTrades("ETH|x|1|1|1|1|1|1|1|1|1\nBTC|1|2"))
        // A coin with funding only doesn't drag the best and worst to zero.
        val fundingOnly = TradingCoinStats("ETH", 0, 0, 0, 0, 0, 700, 0, 0, 0, 0)
        val both = eth + fundingOnly
        assertEquals(25_000L, both.bestCents)
        assertEquals(-10_000L, both.worstCents)
        assertEquals(200L, both.fundingCents)
        val w = TradingWallet("w", a1, "Main", TradingTracker.OK, now, now, 100_000, null, null, null, null, 0, emptyList())
        assertTrue(TradingTracker.tradesDue(w, now))
        assertFalse(TradingTracker.tradesDue(w.copy(tradesAtMs = now - 10 * 60_000L), now))
        assertTrue(TradingTracker.tradesDue(w.copy(tradesAtMs = now - TradingTracker.TRADES_MS), now))
    }

    @Test
    fun yourTradesShowWinsFeesFundingAndEachCoin() {
        val r = replica()
        val store = TradingStore(r)
        store.add("Main $a1")
        store.add("Bot $a2")
        // Nothing read yet: no YOUR TRADES.
        assertNull(TradingTracker.view(store.wallets(), now, cal).trades)
        r.commitLocal(EntityTypes.TRADING_WALLET, TradingTracker.walletId(a1), mapOf(
            TradingWalletFields.STATE to TradingTracker.OK.fv(), TradingWalletFields.EQUITY to 100_000L.fv(),
            TradingWalletFields.TRADES_AT to (now - 60_000L).fv(), TradingWalletFields.TRADES_FROM to (now - 30 * 86_400_000L).fv(),
            TradingWalletFields.TRADES to TradingTracker.encodeTrades(listOf(
                TradingCoinStats("ETH", 3, 2, 1, 30_000, 1_500, -500, 40_000, -10_000, 25_000, -10_000),
                TradingCoinStats("SOL", 1, 0, 1, -5_000, 300, 200, 0, -5_000, -5_000, -5_000),
            )).fv(),
        ))
        r.commitLocal(EntityTypes.TRADING_WALLET, TradingTracker.walletId(a2), mapOf(
            TradingWalletFields.STATE to TradingTracker.OK.fv(), TradingWalletFields.EQUITY to 50_000L.fv(),
            TradingWalletFields.TRADES_AT to (now - 5 * 60_000L).fv(),
            TradingWalletFields.TRADES to TradingTracker.encodeTrades(listOf(
                TradingCoinStats("ETH", 1, 1, 0, 2_000, 200, 0, 2_000, 0, 2_000, 2_000),
            )).fv(),
        ))
        val t = TradingTracker.view(store.wallets(), now, cal).trades!!
        assertEquals(TradingTracker.TRADES_HEAD, t.head)
        assertEquals("5 trades closed · 3 won (60%)", t.summary)
        assertEquals(listOf(
            "Closed trades" to "+$270.00", "Fees" to "−$20.00", "Funding" to "−$3.00", "After fees and funding" to "+$247.00",
        ), t.lines.map { it.label to it.amount })
        assertEquals(false, t.lines[1].up)
        assertEquals(true, t.lines.last().up)
        assertEquals("Average win +$140.00 · average loss −$75.00", t.averageLine)
        assertEquals("Best +$250.00 on ETH · worst −$100.00 on ETH", t.bestLine)
        assertEquals(listOf("ETH", "SOL"), t.coins.map { it.title })
        assertEquals("+$298.00", t.coins[0].amount)
        assertEquals("4 trades · 3 won · fees −$17.00 · funding −$5.00", t.coins[0].line)
        assertEquals("−$51.00", t.coins[1].amount)
        assertEquals(false, t.coins[1].up)
        assertNull(t.moreLine)
        assertTrue(t.foot.endsWith("updated 13:59"))
        assertTrue(t.spoken.startsWith("Last 30 days: 5 trades closed"))
        // Read, but nothing traded in the window.
        val quiet = TradingTracker.tradesView(listOf(store.wallets().first().copy(trades = emptyList())), now, cal)!!
        assertEquals(TradingTracker.NO_TRADES, quiet.empty)
        assertTrue(quiet.coins.isEmpty())
        // Remove takes the trades with it.
        store.remove(TradingTracker.walletId(a1))
        assertEquals("1 trade closed · 1 won (100%)", TradingTracker.view(store.wallets(), now, cal).trades!!.summary)
    }

    @Test
    fun theCurveAddsAccountsDayByDayAndEndsWithNow() {
        val today = CivilDate.toEpochDay(2026, 10, 17)
        val a = TradingWallet("wa", a1, "Main", TradingTracker.OK, now, now, 1_012_000, null, null, null, null, 0, emptyList(),
            curve = listOf(TradingCurvePoint(today - 2, 1_000_000, 0), TradingCurvePoint(today - 1, 1_005_000, 5_000)), curveNowCents = 12_000)
        val b = TradingWallet("wb", a2, "Bot", TradingTracker.OK, now, now, 48_000, null, null, null, null, 0, emptyList(),
            curve = listOf(TradingCurvePoint(today - 1, 50_000, 0)), curveNowCents = -2_000)
        assertEquals(a.curve, TradingTracker.decodeCurve(TradingTracker.encodeCurve(a.curve.reversed())))
        assertTrue(TradingTracker.decodeCurve("x|1|2\n3|4").isEmpty())
        val c = TradingTracker.curveView(listOf(a, b), now)!!
        assertEquals(TradingTracker.CURVE_HEAD, c.head)
        assertEquals(listOf("Thu 15 Oct" to 0L, "Fri 16 Oct" to 5_000L, "Now" to 10_000L), c.points.map { it.label to it.pence })
        assertEquals("Fri 16 Oct · +$50.00 since Thu 15 Oct · account $10,550.00", c.points[1].said)
        assertEquals("Now · +$100.00 since Thu 15 Oct · account $10,600.00", c.points[2].said)
        assertEquals("+$100.00 since Thu 15 Oct", c.summary)
        assertEquals(true, c.up)
        assertEquals("High +$100.00 now · low $0.00 on Thu 15 Oct", c.rangeLine)
        // The view carries it; one day alone isn't a line yet (it has "Now" too, so two points).
        assertNotNull(TradingTracker.view(listOf(a, b), now, cal).curve)
        assertEquals(2, TradingTracker.curveView(listOf(b), now)!!.points.size)
        assertNull(TradingTracker.curveView(listOf(b.copy(curve = emptyList())), now))
        // Through the store: what the server wrote reads back, and Remove clears it.
        val id = TradingTracker.walletId(a1)
        val r = replica()
        val s2 = TradingStore(r)
        s2.add("Main $a1")
        r.commitLocal(EntityTypes.TRADING_WALLET, id, mapOf(
            TradingWalletFields.EQUITY to 1_012_000L.fv(), TradingWalletFields.CURVE to TradingTracker.encodeCurve(a.curve).fv(),
            TradingWalletFields.CURVE_NOW to 12_000L.fv(),
        ))
        assertEquals(a.curve, s2.wallets().single().curve)
        assertEquals("+$120.00 since Thu 15 Oct", TradingTracker.view(s2.wallets(), now, cal).curve!!.summary)
        s2.remove(id)
        assertTrue(s2.wallets().isEmpty())
    }
}
