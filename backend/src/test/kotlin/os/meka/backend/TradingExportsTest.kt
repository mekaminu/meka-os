package os.meka.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import os.meka.backend.integrations.HyperliquidInfo
import os.meka.backend.integrations.TradingAccountRead
import os.meka.backend.integrations.TradingExportRead
import os.meka.backend.integrations.TradingFeed
import os.meka.core.domain.MekaSchema
import os.meka.core.domain.TradingExportFunding
import os.meka.core.domain.TradingExportOrder
import os.meka.core.domain.TradingStore
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Replica
import os.meka.core.wire.TradingExportCodec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Trading tracker, slice 8: the tax export on the server and Hyperliquid's fills made into orders. Invented addresses and figures. */
class TradingExportsTest {
    private val nowMs = 1_792_224_000_000L
    private val ops = InMemoryServerOpStore()
    private val address = "0x" + "ab12".repeat(10)
    private val from = 1_743_894_000_000L
    private val to = from + 365L * 86_400_000L
    private var reads = 0
    private var fail = false
    private var gate: CountDownLatch? = null
    private val feed = object : TradingFeed {
        override fun read(address: String): TradingAccountRead = error("not used")
        override fun export(address: String, fromMs: Long, toMs: Long): TradingExportRead {
            assertEquals(this@TradingExportsTest.address, address)
            assertEquals(from, fromMs)
            reads++
            gate?.await(5, TimeUnit.SECONDS)
            if (fail) error("down")
            return TradingExportRead(listOf(TradingExportOrder(from + 1, "ETH", "Close Long", "2", "3100", 620_000, 20_000, 150)),
                listOf(TradingExportFunding(20_200, "ETH", -325)), partial = false)
        }
    }
    private val exports = TradingExports(BankSync.scanning(ops), feed, { nowMs }, Executors.newSingleThreadExecutor())

    private val store = InMemoryReplicaStore()
    private var n = 0
    private val fold = Replica("hh", "fold", HlcClock("fold", { nowMs }), store, MekaSchema) { "fold-${n++}" }

    private fun follow() {
        TradingStore(fold).add("Main $address")
        store.pendingPush(100_000).forEach { ops.append(it) }
    }

    @Test
    fun aFollowedAccountsYearComesBackAndIsReadOnce() {
        follow()
        val r = exports.export("hh", TradingExportCodec.Request(from, to, 5_000))
        assertEquals(TradingExportCodec.Response.OK, r.state)
        val a = r.accounts.single()
        assertEquals(TradingStore(fold).wallets().single().id, a.id)
        assertEquals(20_000L, a.orders.single().closedCents)
        assertEquals(-325L, a.funding.single().cents)
        // Asked again within the keep: no second read.
        exports.export("hh", TradingExportCodec.Request(from, to, 5_000))
        assertEquals(1, reads)
        assertEquals("1 read · 0 failed · 1 orders", exports.lastReport)
    }

    @Test
    fun stillReadingIsPendingAndAFailureIsTriedAgain() {
        follow()
        gate = CountDownLatch(1)
        assertEquals(TradingExportCodec.Response.PENDING, exports.export("hh", TradingExportCodec.Request(from, to, 50)).state)
        gate!!.countDown()
        assertEquals(TradingExportCodec.Response.OK, exports.export("hh", TradingExportCodec.Request(from, to, 5_000)).state)
        assertEquals(1, reads)
        // A different window is its own read; a failed one is read again on the next ask.
        gate = null
        fail = true
        assertEquals(TradingExportCodec.Response.FAILED, exports.export("hh", TradingExportCodec.Request(from, to - 1, 5_000)).state)
        fail = false
        assertEquals(TradingExportCodec.Response.OK, exports.export("hh", TradingExportCodec.Request(from, to - 1, 5_000)).state)
        assertEquals(3, reads)
    }

    @Test
    fun nothingFollowedIsAnEmptyAnswer() {
        val r = exports.export("hh", TradingExportCodec.Request(from, to, 5_000))
        assertEquals(TradingExportCodec.Response.OK, r.state)
        assertTrue(r.accounts.isEmpty())
    }

    @Test
    fun fillsBecomeOrdersAndFundingBecomesDays() {
        fun objs(s: String): List<JsonObject> = Json.parseToJsonElement(s).jsonArray.map { it.jsonObject }
        val fills = objs("""[
            {"coin":"ETH","px":"3000","sz":"1","dir":"Open Long","time":1000,"oid":7,"tid":1,"closedPnl":"0","fee":"0.70","feeToken":"USDC"},
            {"coin":"ETH","px":"3010","sz":"1","dir":"Open Long","time":2000,"oid":7,"tid":2,"closedPnl":"0","fee":"0.70","feeToken":"USDC"},
            {"coin":"ETH","px":"3100","sz":"2","dir":"Close Long","time":90000000,"oid":8,"tid":3,"closedPnl":"190.0","fee":"1.5","feeToken":"USDC"},
            {"coin":"@107","px":"1","sz":"5","dir":"Buy","time":3000,"oid":9,"tid":4,"closedPnl":"0","fee":"0.1","feeToken":"HYPE"}
        ]""")
        val funding = objs("""[
            {"time":3600000,"hash":"a","delta":{"type":"funding","coin":"ETH","usdc":"-1.25"}},
            {"time":7200000,"hash":"b","delta":{"type":"funding","coin":"ETH","usdc":"-2.00"}},
            {"time":90000000,"hash":"c","delta":{"type":"funding","coin":"ETH","usdc":"0.50"}}
        ]""")
        val r = HyperliquidInfo.exportFrom(fills, funding)
        assertEquals(2, r.orders.size)
        val open = r.orders.first()
        assertEquals("2", open.size)
        assertEquals("3005", open.averagePrice)
        assertEquals(601_000L, open.valueCents)
        assertEquals(140L, open.feeCents)
        assertEquals(2000L, open.atMs)
        assertEquals(19_000L, r.orders.last().closedPnlCents)
        assertEquals(listOf(TradingExportFunding(0, "ETH", -325), TradingExportFunding(1, "ETH", 50)), r.funding)
    }
}
