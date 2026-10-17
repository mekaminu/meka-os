package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Trading tracker, slice 8: the export's request and answer on the wire. Invented figures. */
class TradingExportCodecTest {
    private val from = 1_743_894_000_000L // 6 Apr 2025, 00:00 UK time
    private val to = from + 365L * 86_400_000L

    @Test
    fun theRequestRoundTripsAndRefusesAWindowTooLong() {
        val r = TradingExportCodec.decodeRequest(TradingExportCodec.encodeRequest(TradingExportCodec.Request(from, to, 99_000)))
        assertEquals(TradingExportCodec.Request(from, to, TradingExportCodec.MAX_WAIT_MS), r)
        assertFailsWith<WireFormatException> { TradingExportCodec.decodeRequest(TradingExportCodec.encodeRequest(TradingExportCodec.Request(from, from + 400L * 86_400_000L, 0))) }
        assertFailsWith<WireFormatException> { TradingExportCodec.decodeRequest(TradingExportCodec.encodeRequest(TradingExportCodec.Request(to, from, 0))) }
        assertFailsWith<WireFormatException> { TradingExportCodec.decodeRequest("""{"w":${WireCodec.VERSION},"from":"x","to":1}""") }
    }

    @Test
    fun theAnswerRoundTrips() {
        val a = TradingExportCodec.Account("w1",
            listOf(TradingExportCodec.Order(from + 1, "ETH", "Open Long", "2", "3000", 600_000, 0, 140)),
            listOf(TradingExportCodec.Funding(20_200, "ETH", -325)), partial = true, failed = false)
        val ok = TradingExportCodec.Response(TradingExportCodec.Response.OK, listOf(a, TradingExportCodec.Account("w2", emptyList(), emptyList(), false, true)),
            listOf(TradingExportCodec.Rate(202504, 1_331_200), TradingExportCodec.Rate(202505, 1_345_000)))
        assertEquals(ok, TradingExportCodec.decodeResponse(TradingExportCodec.encodeResponse(ok)))
        val pending = TradingExportCodec.Response(TradingExportCodec.Response.PENDING)
        assertEquals(pending, TradingExportCodec.decodeResponse(TradingExportCodec.encodeResponse(pending)))
        assertFailsWith<WireFormatException> { TradingExportCodec.decodeResponse("""{"w":${WireCodec.VERSION},"state":"maybe"}""") }
    }

    @Test
    fun anOlderServerSendsNoRatesAndBadOnesAreDropped() {
        val old = TradingExportCodec.decodeResponse("""{"w":${WireCodec.VERSION},"state":"ok","accounts":[]}""")
        assertEquals(emptyList(), old.rates)
        val bad = TradingExportCodec.decodeResponse("""{"w":${WireCodec.VERSION},"state":"ok","accounts":[],"rates":[[202513,1],[202509,0],[202509,1342100],[202509,1]]}""")
        assertEquals(listOf(TradingExportCodec.Rate(202509, 1_342_100)), bad.rates)
    }
}
