package os.meka.backend

import os.meka.backend.integrations.HyperliquidInfo
import os.meka.backend.integrations.TradingAccountRead
import os.meka.backend.integrations.TradingFeed
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.MekaSchema
import os.meka.core.domain.TradingCoinStats
import os.meka.core.domain.TradingPosition
import os.meka.core.domain.TradingStore
import os.meka.core.domain.TradingTracker
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trading tracker on the server: Hyperliquid's public answers become synced figures, at the right pace. Invented addresses and figures. */
class TradingSyncTest {
    private var nowMs = 1_792_224_000_000L // 2026-10-17
    private val ops = InMemoryServerOpStore()
    private val woken = mutableListOf<String>()
    private val address = "0x" + "ab12".repeat(10)
    private var reads = 0
    private var fail = false
    private var answer = TradingAccountRead(1_234_567, 12_050, -4_000, 30_000, 250_000, 34_000,
        listOf(TradingPosition("ETH", "2", "3000", "3170", "2100", 34_000, 3)))
    private var tradeReads = 0
    private var tradesFrom = 0L
    private val feed = object : TradingFeed {
        override fun read(address: String): TradingAccountRead {
            assertEquals(this@TradingSyncTest.address, address)
            reads++
            if (fail) error("down")
            return answer
        }

        override fun trades(address: String, fromMs: Long, toMs: Long): List<TradingCoinStats> {
            tradeReads++
            tradesFrom = fromMs
            return listOf(TradingCoinStats("ETH", 2, 1, 1, 5_000, 300, -100, 8_000, -3_000, 8_000, -3_000))
        }
    }
    private val sync = TradingSync(ops, BankSync.scanning(ops), { "hh" }, feed, { nowMs }, onWritten = { woken += it })

    private val store = InMemoryReplicaStore()
    private var n = 0
    private val fold = Replica("hh", "fold", HlcClock("fold", { nowMs }), store, MekaSchema) { "fold-${n++}" }

    private fun push() = store.pendingPush(100_000).forEach { ops.append(it) }
    private fun pull() { fold.applyRemoteBatch(ops.after("hh", 0, 100_000).map { it.op }) }

    @Test
    fun anAddressHeTypesIsReadAndItsFiguresReachTheFold() {
        assertFalse(sync.tick())
        TradingStore(fold).add("Main $address")
        push()
        assertTrue(sync.tick())
        assertEquals(listOf("hh"), woken)
        pull()
        val w = TradingStore(fold).wallets().single()
        assertEquals(1_234_567L, w.equityCents)
        assertEquals(250_000L, w.pnlAllCents)
        assertEquals("ETH", w.positions.single().coin)
        assertEquals(TradingTracker.OK, w.state)
        // With a position open it's read again after 5 minutes, not before; nothing new, nothing written but the times.
        nowMs += 60_000
        assertFalse(sync.tick())
        assertEquals(1, reads)
        nowMs += TradingTracker.OPEN_MS
        assertFalse(sync.tick())
        assertEquals(2, reads)
        // A failure says so and keeps the figures.
        fail = true
        nowMs += TradingTracker.OPEN_MS
        assertTrue(sync.tick())
        pull()
        val failed = TradingStore(fold).wallets().single()
        assertEquals(TradingTracker.ERROR, failed.state)
        assertEquals(1_234_567L, failed.equityCents)
        // Positions closed: back to OK, the list empties.
        fail = false
        answer = answer.copy(positions = emptyList(), unrealisedCents = 0)
        nowMs += TradingTracker.RETRY_MS
        assertTrue(sync.tick())
        pull()
        assertTrue(TradingStore(fold).wallets().single().positions.isEmpty())
    }

    @Test
    fun aRemovedAddressIsNeverReadAgain() {
        val s = TradingStore(fold)
        s.add("Main $address")
        push()
        sync.tick()
        pull()
        s.remove(TradingTracker.walletId(address))
        push()
        nowMs += TradingTracker.IDLE_MS * 2
        val before = reads
        assertFalse(sync.tick())
        assertEquals(before, reads)
        pull()
        assertTrue(TradingStore(fold).wallets().isEmpty())
        // The server never writes the address itself.
        assertTrue(ops.after("hh", 0, 100_000).none { it.op.entityType == EntityTypes.TRADING_WALLET && it.op.field == "address" && it.op.deviceId != "fold" })
    }

    @Test
    fun hyperliquidsAnswersAreReadFromTheirDecimals() {
        val sent = mutableListOf<String>()
        val info = HyperliquidInfo { url, body ->
            assertEquals(HyperliquidInfo.URL, url)
            sent += body
            200 to if (body.contains("clearinghouseState")) CLEARINGHOUSE else PORTFOLIO
        }
        val a = info.read(address.uppercase().replace("0X", "0x"))
        assertEquals(listOf("""{"type":"clearinghouseState","user":"$address"}""", """{"type":"portfolio","user":"$address"}"""), sent)
        assertEquals(1_310_451L, a.equityCents) // the portfolio's latest account value
        assertEquals(4_550L, a.pnlDayCents)
        assertEquals(-1_200L, a.pnlWeekCents)
        assertNull(a.pnlMonthCents)
        assertEquals(98_000L, a.pnlAllCents)
        val p = a.positions.single()
        assertEquals("ETH", p.coin)
        assertEquals("-0.0335", p.size)
        assertEquals("2986.3", p.entry)
        assertEquals("2985.8", p.mark)
        assertEquals("3100.26936529", p.liquidation)
        assertEquals(-1L, p.unrealisedCents)
        assertEquals(20, p.leverage)
        // Without a portfolio the perps' value stands.
        val bare = HyperliquidInfo { _, body -> if (body.contains("portfolio")) 500 to "" else 200 to CLEARINGHOUSE }.read(address)
        assertEquals(1_310_451L - 1L, bare.equityCents)
        assertNull(bare.pnlAllCents)
    }

    @Test
    fun tradesAreAddedUpHourlyAndReachTheFold() {
        TradingStore(fold).add("Main $address")
        push()
        assertTrue(sync.tick())
        pull()
        val w = TradingStore(fold).wallets().single()
        assertEquals("ETH", w.trades.single().coin)
        assertEquals(4_600L, w.trades.single().netCents)
        assertEquals(nowMs - 30 * 86_400_000L, tradesFrom)
        assertEquals(nowMs - 30 * 86_400_000L, w.tradesFromMs)
        // The account is read every 5 minutes with a position open, its trades only hourly.
        nowMs += TradingTracker.OPEN_MS
        sync.tick()
        assertEquals(2, reads)
        assertEquals(1, tradeReads)
        nowMs += TradingTracker.TRADES_MS
        assertFalse(sync.tick()) // the same trades: only the quiet times moved, nothing wakes the devices
        assertEquals(2, tradeReads)
    }

    @Test
    fun fillsAndFundingAddUpPerCoin() {
        val sent = mutableListOf<String>()
        val info = HyperliquidInfo { _, body ->
            sent += body
            200 to if (body.contains("userFillsByTime")) FILLS else FUNDING
        }
        val stats = info.trades(address, 1_000L, 9_000_000L)
        assertEquals(2, sent.size)
        assertTrue(sent[0].contains(""""type":"userFillsByTime","user":"$address","startTime":1000,"endTime":9000000,"aggregateByTime":true"""))
        assertTrue(sent[1].contains(""""type":"userFunding""""))
        assertEquals(listOf("BTC", "ETH"), stats.map { it.coin }) // spot left out
        val eth = stats.first { it.coin == "ETH" }
        assertEquals(2, eth.trades) // order 11's two fills are one trade
        assertEquals(1, eth.wins)
        assertEquals(1, eth.losses)
        assertEquals(10_000L, eth.realisedCents) // 80 + 40 − 20
        assertEquals(12_000L, eth.bestCents)
        assertEquals(-2_000L, eth.worstCents)
        assertEquals(150L, eth.feesCents) // 0.5 + 0.5 + 0.25 + 0.25
        assertEquals(-123L, eth.fundingCents)
        val btc = stats.first { it.coin == "BTC" }
        assertEquals(0, btc.trades)
        assertEquals(45L, btc.fundingCents)
        // A full page asks for the next one from just after its latest fill.
        val pages = mutableListOf<String>()
        val full = (1..500).joinToString(",", "[", "]") { i -> """{"coin":"ETH","dir":"Open Long","closedPnl":"0","fee":"0.01","feeToken":"USDC","oid":$i,"tid":$i,"time":${1000 + i}}""" }
        HyperliquidInfo { _, body -> pages += body; 200 to if (body.contains("userFillsByTime") && pages.size == 1) full else "[]" }.trades(address, 1_000L, 9_000_000L)
        assertTrue(pages[1].contains(""""startTime":1501"""))
    }

    private companion object {
        const val FILLS = """[
            {"coin":"ETH","dir":"Open Long","px":"3000","sz":"1","side":"B","closedPnl":"0.0","fee":"0.5","feeToken":"USDC","oid":10,"tid":1,"time":2000},
            {"coin":"ETH","dir":"Close Long","px":"3080","sz":"0.5","side":"A","closedPnl":"80.0","fee":"0.5","feeToken":"USDC","oid":11,"tid":2,"time":3000},
            {"coin":"ETH","dir":"Close Long","px":"3080","sz":"0.5","side":"A","closedPnl":"40.0","fee":"0.25","feeToken":"USDC","oid":11,"tid":3,"time":3001},
            {"coin":"ETH","dir":"Close Short","px":"3100","sz":"0.1","side":"B","closedPnl":"-20.0","fee":"0.25","feeToken":"USDC","oid":12,"tid":4,"time":4000},
            {"coin":"ETH","dir":"Close Short","px":"3100","sz":"0.1","side":"B","closedPnl":"-20.0","fee":"0.25","feeToken":"USDC","oid":12,"tid":4,"time":4000},
            {"coin":"@107","dir":"Buy","px":"1","sz":"10","side":"B","closedPnl":"0","fee":"0.1","feeToken":"HYPE","oid":13,"tid":5,"time":5000}
        ]"""
        const val FUNDING = """[
            {"time":2500,"hash":"0x1","delta":{"type":"funding","coin":"ETH","usdc":"-1.2345","szi":"1","fundingRate":"0.0001"}},
            {"time":2600,"hash":"0x2","delta":{"type":"funding","coin":"BTC","usdc":"0.45","szi":"-0.1","fundingRate":"0.0001"}}
        ]"""

        const val CLEARINGHOUSE = """{"assetPositions":[{"position":{"coin":"ETH","cumFunding":{"allTime":"514.08"},"entryPx":"2986.3",
            "leverage":{"rawUsd":"-95.05","type":"isolated","value":20},"liquidationPx":"3100.26936529","marginUsed":"4.96",
            "maxLeverage":50,"positionValue":"100.02430","returnOnEquity":"-0.0026","szi":"-0.0335","unrealizedPnl":"-0.0134"},"type":"oneWay"},
            {"position":{"coin":"BTC","szi":"0.0","positionValue":"0","unrealizedPnl":"0"},"type":"oneWay"}],
            "crossMarginSummary":{"accountValue":"13104.50"},"marginSummary":{"accountValue":"13104.50","totalMarginUsed":"4.96"},
            "time":1708622398623,"withdrawable":"13099.5"}"""
        const val PORTFOLIO = """[["day",{"accountValueHistory":[[1708622300000,"13050.00"],[1708622398623,"13104.51"]],
            "pnlHistory":[[1708622300000,"10.00"],[1708622398623,"55.50"]],"vlm":"0"}],
            ["week",{"accountValueHistory":[],"pnlHistory":[[1,"20"],[2,"8"]]}],
            ["month",{"accountValueHistory":[],"pnlHistory":[]}],
            ["allTime",{"accountValueHistory":[],"pnlHistory":[[1,"0"],[2,"980"]]}]]"""
    }
}
