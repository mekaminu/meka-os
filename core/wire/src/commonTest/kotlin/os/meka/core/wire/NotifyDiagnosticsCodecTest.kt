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
    fun aboutYouHeldTimesTravelWithoutAProfileOrAReport() {
        val l = NotifyDiagnosticsCodec.Latest(null, aboutFilled = true, aboutFoldAtMs = 5, aboutMacAtMs = null)
        assertEquals(l, NotifyDiagnosticsCodec.decodeLatest(NotifyDiagnosticsCodec.encodeLatest(l)))
        // Release 2 slice 5: whether the clubs and the council's holidays are copied travel too (never their values).
        val copies = l.copy(aboutClubs = true, aboutCouncil = false, aboutRoute = true)
        assertEquals(copies, NotifyDiagnosticsCodec.decodeLatest(NotifyDiagnosticsCodec.encodeLatest(copies)))
        // No profile: nothing sent, and the times are dropped with it.
        val none = NotifyDiagnosticsCodec.decodeLatest(NotifyDiagnosticsCodec.encodeLatest(NotifyDiagnosticsCodec.Latest(null, aboutFoldAtMs = 5)))
        assertNull(none.aboutFilled)
        assertNull(none.aboutFoldAtMs)
        assertNull(NotifyDiagnosticsCodec.decodeLatest("""{"w":1,"report":null,"aboutFilled":"yes"}""").aboutFilled)
    }

    @Test
    fun theCallAssistantsMessagesTravelAndOlderServersSendNone() {
        val calls = listOf(NotifyDiagnosticsCodec.Entry(1_760_000_000_000L, "automated", "message 0:02 · words 1 chars · urgent (answer: keypad) · recording kept 30 KB · likely automated"))
        val l = NotifyDiagnosticsCodec.Latest(null, assistant = calls)
        assertEquals(l, NotifyDiagnosticsCodec.decodeLatest(NotifyDiagnosticsCodec.encodeLatest(l)))
        assertNull(NotifyDiagnosticsCodec.decodeLatest("""{"w":1,"report":null}""").assistant)
        assertEquals(emptyList(), NotifyDiagnosticsCodec.decodeLatest(NotifyDiagnosticsCodec.encodeLatest(NotifyDiagnosticsCodec.Latest(null, assistant = emptyList()))).assistant)
        // Inbox fixes, slice 6: the mail accounts' lines round-trip; an older server has none.
        val mail = listOf(NotifyDiagnosticsCodec.Entry(1_790_000_000_000L, "Personal", "Gmail · ok · inbox 88"))
        assertEquals(mail, NotifyDiagnosticsCodec.decodeLatest(NotifyDiagnosticsCodec.encodeLatest(NotifyDiagnosticsCodec.Latest(null, mail = mail))).mail)
        // Money's bank sync: one line of counts, and absent from a server before it.
        val bank = "ok · 6 accounts · 412 transactions"
        assertEquals(bank, NotifyDiagnosticsCodec.decodeLatest(NotifyDiagnosticsCodec.encodeLatest(NotifyDiagnosticsCodec.Latest(null, bank = bank))).bank)
        assertEquals(null, NotifyDiagnosticsCodec.decodeLatest(NotifyDiagnosticsCodec.encodeLatest(NotifyDiagnosticsCodec.Latest(null))).bank)
        // KMS free tier (2026-10-18): the server's KMS-backed reads, counts only; absent from a server before it.
        val kms = "KMS-backed reads since start: 9 (secrets 6 · token decrypts 3 · token encrypts 0) · up 3 h 10 min"
        assertEquals(kms, NotifyDiagnosticsCodec.decodeLatest(NotifyDiagnosticsCodec.encodeLatest(NotifyDiagnosticsCodec.Latest(null, kms = kms))).kms)
        assertEquals(null, NotifyDiagnosticsCodec.decodeLatest(NotifyDiagnosticsCodec.encodeLatest(NotifyDiagnosticsCodec.Latest(null))).kms)
        assertNull(NotifyDiagnosticsCodec.decodeLatest("""{"w":1,"report":null}""").mail)
    }

    @Test
    fun aChannelsLastPostTravelsAndOlderReportsHaveNone() {
        val r = report.copy(channels = listOf(NotifyDiagnosticsCodec.Channel("meka_heads_up", "Heads-ups", true, 1_760_000_000_000L)))
        assertEquals(r, NotifyDiagnosticsCodec.decodeReport(NotifyDiagnosticsCodec.encodeReport(r)))
        assertEquals(null, NotifyDiagnosticsCodec.decodeReport(NotifyDiagnosticsCodec.encodeReport(report)).channels.first().lastPostMs)
    }

    @Test
    fun theCallScreeningTravelsCappedAndOlderReportsHaveNone() {
        val calls = NotifyDiagnosticsCodec.Calls(
            roleHeld = false, protection = true, lookups = true,
            screened = listOf(NotifyDiagnosticsCodec.Entry(1_700_000_000_000, "BLOCK", "#3fa2c1 · On your block list · net failed")),
        )
        val r = report.copy(calls = calls)
        assertEquals(r, NotifyDiagnosticsCodec.decodeReport(NotifyDiagnosticsCodec.encodeReport(r)))
        assertNull(NotifyDiagnosticsCodec.decodeReport(NotifyDiagnosticsCodec.encodeReport(report)).calls)
        val many = report.copy(calls = calls.copy(screened = List(30) { NotifyDiagnosticsCodec.Entry(1_000L + it, "RING", "#00000$it") }))
        assertEquals(NotifyDiagnosticsCodec.CALLS_MAX, NotifyDiagnosticsCodec.decodeReport(NotifyDiagnosticsCodec.encodeReport(many)).calls!!.screened.size)
        // A calls block without the role reads as none rather than failing the whole report.
        val odd = NotifyDiagnosticsCodec.encodeReport(report).dropLast(1) + ""","calls":{"protection":true}}"""
        assertNull(NotifyDiagnosticsCodec.decodeReport(odd).calls)
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
