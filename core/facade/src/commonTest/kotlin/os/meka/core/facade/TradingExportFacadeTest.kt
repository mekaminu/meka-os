package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.TradingExportCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trading tracker, slice 8: EXPORT FOR TAX through the facade — locked, pending, offline and the file. Invented figures. */
class TradingExportFacadeTest {
    private val now = 1_792_144_800_000L // Fri 16 Oct 2026, 11:00 in London
    private val address = "0x" + "ab12".repeat(10)

    private inner class Server(service: SyncService) : SyncTransport, TradingExportApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val asked = mutableListOf<Pair<Long, Long>>()
        var pendingFor = 0
        var down = false
        var state = TradingExportCodec.Response.OK
        var walletId = ""
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun tradingExport(fromMs: Long, toMs: Long, waitMs: Long): TradingExportCodec.Response {
            asked += fromMs to toMs
            if (down) throw TransportException("network error: offline")
            if (pendingFor > 0) { pendingFor--; return TradingExportCodec.Response(TradingExportCodec.Response.PENDING) }
            if (state != TradingExportCodec.Response.OK) return TradingExportCodec.Response(state)
            return TradingExportCodec.Response(TradingExportCodec.Response.OK, listOf(
                TradingExportCodec.Account(walletId, listOf(TradingExportCodec.Order(fromMs + 1, "ETH", "Close Long", "2", "3100", 620_000, 20_000, 150)),
                    emptyList(), partial = false, failed = false),
                TradingExportCodec.Account("someone-else", listOf(TradingExportCodec.Order(fromMs + 2, "BTC", "Close Long", "1", "1", 100, 100, 0)),
                    emptyList(), partial = false, failed = false),
            ), listOf(TradingExportCodec.Rate(202504, 1_250_000)))
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun theYearsTradesBecomeAFileOnlyWhileMoneyIsOpen() = runTest {
        val c = core()
        assertTrue(c.tradingExport("2025-26").said.refused)
        assertTrue(server.asked.isEmpty())
        c.openMoney()
        assertTrue(c.tradingExport("2025-26").said.refused) // nothing followed yet
        c.tradingWalletAdd("Main $address")
        val chips = c.moneyView.value.trading!!.export!!.chips
        assertEquals(listOf("2025-26", "2026-27"), chips.map { it.key })
        server.walletId = c.moneyView.value.trading!!.wallets.single().id
        server.pendingFor = 2
        val r = c.tradingExport("2025-26")
        assertFalse(r.said.refused, r.said.line)
        assertEquals("navilife-hyperliquid-2025-26.csv", r.fileName)
        val csv = assertNotNull(r.csv)
        assertTrue("Main,Trade,ETH,Close Long" in csv, csv)
        assertFalse("BTC" in csv, "only his followed accounts")
        // Slice 10: pounds at HMRC's April rate ($200 closed − $1.50 fee at 1.25 = £160.00 − £1.20).
        assertTrue("Close Long,2,3100,6200.00,200.00,-1.50,,198.50,1.2500,160.00,-1.20,,158.80" in csv, csv)
        // Asked three times (twice pending), from midnight 6 Apr 2025 UK time (BST, so 23:00 UTC the day before).
        assertEquals(3, server.asked.size)
        assertEquals(1_743_894_000_000L, server.asked.first().first)
        assertTrue(r.said.line.startsWith("2025–26: 1 order · net +$198.50 (+£158.80 at HMRC's monthly rates)"), r.said.line)
    }

    @Test
    fun offlineOffAndSlowAreSaidPlainly() = runTest {
        val c = core()
        c.openMoney()
        c.tradingWalletAdd("Main $address")
        server.down = true
        assertEquals(os.meka.core.domain.TradingExportRules.OFFLINE, c.tradingExport("2025-26").said.line)
        server.down = false
        server.state = TradingExportCodec.Response.OFF
        assertEquals(os.meka.core.domain.TradingExportRules.OFF, c.tradingExport("2025-26").said.line)
        server.state = TradingExportCodec.Response.OK
        server.pendingFor = 99
        val slow = c.tradingExport("2025-26")
        assertEquals(os.meka.core.domain.TradingExportRules.SLOW, slow.said.line)
        assertNull(slow.csv)
        assertTrue(c.tradingExport("not a year").said.refused)
    }
}
