package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Notifications never seen, slice 2: the trace report, the newest report for a build run, and Send a test's push. */
class NotifyDiagnosticsCodecTest {
    private val report = NotifyDiagnosticsCodec.Report(
        model = "SM-F000X", sentAtMs = 1_700_000_000_000, allowed = true, phone = "ALL",
        channels = listOf(NotifyDiagnosticsCodec.Channel("meka_heads_up", "Heads-ups", true), NotifyDiagnosticsCodec.Channel("meka_digest", "Digests", false)),
        trace = listOf(
            NotifyDiagnosticsCodec.Entry(1_700_000_000_000, "LOOKED", "nothing due · next look 12:30"),
            NotifyDiagnosticsCodec.Entry(1_699_999_000_000, "PUSH", "sync"),
        ),
    )

    @Test
    fun aReportRoundTripsAndIsCapped() {
        assertEquals(report, NotifyDiagnosticsCodec.decodeReport(NotifyDiagnosticsCodec.encodeReport(report)))
        val long = report.copy(trace = List(60) { NotifyDiagnosticsCodec.Entry(1_000L + it, "LOOKED", "x".repeat(300) + "\nmore") })
        val back = NotifyDiagnosticsCodec.decodeReport(NotifyDiagnosticsCodec.encodeReport(long))
        assertEquals(NotifyDiagnosticsCodec.TRACE_MAX, back.trace.size)
        assertTrue(back.trace.all { it.detail.length <= NotifyDiagnosticsCodec.DETAIL_MAX && '\n' !in it.detail })
        assertFailsWith<WireFormatException> { NotifyDiagnosticsCodec.decodeReport("""{"w":1,"sent":5,"trace":[]}""") }
        assertFailsWith<WireFormatException> { NotifyDiagnosticsCodec.decodeReport("""{"w":2,"sent":5,"allowed":true,"trace":[]}""") }
    }

    @Test
    fun theLatestCarriesTheServersLastPushAndNoneYetReadsAsNull() {
        val l = NotifyDiagnosticsCodec.Latest(report, lastPushAtMs = 9, lastPushResult = "sent", hasAddress = true)
        assertEquals(l, NotifyDiagnosticsCodec.decodeLatest(NotifyDiagnosticsCodec.encodeLatest(l)))
        val none = NotifyDiagnosticsCodec.decodeLatest(NotifyDiagnosticsCodec.encodeLatest(NotifyDiagnosticsCodec.Latest(null)))
        assertNull(none.report)
        assertNull(none.lastPushResult)
        assertEquals("failed", NotifyDiagnosticsCodec.decodeLatest("""{"w":1,"report":null,"lastPushAt":3,"lastPush":"odd"}""").lastPushResult)
    }

    @Test
    fun aChannelsLastPostTravelsAndOlderReportsHaveNone() {
        val r = report.copy(channels = listOf(NotifyDiagnosticsCodec.Channel("meka_heads_up", "Heads-ups", true, 1_760_000_000_000L)))
        assertEquals(r, NotifyDiagnosticsCodec.decodeReport(NotifyDiagnosticsCodec.encodeReport(r)))
        assertEquals(null, NotifyDiagnosticsCodec.decodeReport(NotifyDiagnosticsCodec.encodeReport(report)).channels.first().lastPostMs)
    }

    @Test
    fun theTestAnswerIsOneOfTheKnownWords() {
        NotifyDiagnosticsCodec.RESULTS.forEach { assertEquals(it, NotifyDiagnosticsCodec.decodeTestAnswer(NotifyDiagnosticsCodec.encodeTestAnswer(it))) }
        assertEquals("failed", NotifyDiagnosticsCodec.decodeTestAnswer("""{"w":1,"result":"maybe"}"""))
        assertFailsWith<WireFormatException> { NotifyDiagnosticsCodec.decodeTestAnswer("""{"w":1}""") }
        assertEquals("""{"w":1}""", NotifyDiagnosticsCodec.encodeEmptyRequest())
        NotifyDiagnosticsCodec.decodeEmptyRequest("")
        assertFailsWith<WireFormatException> { NotifyDiagnosticsCodec.decodeEmptyRequest("""{"w":7}""") }
    }
}
