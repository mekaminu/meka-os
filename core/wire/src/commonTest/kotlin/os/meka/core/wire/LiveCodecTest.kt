package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ada's Live mode, slice 1: the session, its meter and the usage report on the wire. */
class LiveCodecTest {
    private val meter = LiveCodec.Meter(configured = true, level = "heads_up", spentPence = 1_210, capPence = 1_500)

    @Test
    fun aReadySessionRoundTrips() {
        val s = LiveCodec.Session(
            LiveCodec.Session.READY, meter, sessionId = "ls_1", token = "auth_tokens/abc", model = "gemini-3.8-live",
            url = "wss://generativelanguage.googleapis.com/ws/x", voice = "Kore", startByMs = 60_000, expiresAtMs = 600_000,
        )
        assertEquals(s, LiveCodec.decodeSession(LiveCodec.encodeSession(s)))
        assertEquals(LiveCodec.SessionRequest("Kore"), LiveCodec.decodeSessionRequest(LiveCodec.encodeSessionRequest(LiveCodec.SessionRequest("Kore"))))
        assertEquals(meter, LiveCodec.decodeMeter(LiveCodec.encodeMeter(meter)))
    }

    @Test
    fun anIncompleteOrUnknownSessionIsAFailureNormalAdaAnswers() {
        val missingUrl = LiveCodec.encodeSession(LiveCodec.Session(LiveCodec.Session.READY, meter, sessionId = "ls_1", token = "t", model = "m", expiresAtMs = 1))
        val d = LiveCodec.decodeSession(missingUrl)
        assertEquals(LiveCodec.Session.FAILED, d.state)
        assertNull(d.token)
        // Only a wss:// URL is taken.
        val plain = LiveCodec.encodeSession(LiveCodec.Session(LiveCodec.Session.READY, meter, "ls_1", "t", "m", url = "http://x", expiresAtMs = 1))
        assertEquals(LiveCodec.Session.FAILED, LiveCodec.decodeSession(plain).state)
        val odd = """{"w":${WireCodec.VERSION},"state":"dancing","level":"weird"}"""
        val o = LiveCodec.decodeSession(odd)
        assertEquals(LiveCodec.Session.FAILED, o.state)
        assertEquals("ok", o.meter.level)
        assertFalse(o.meter.configured)
    }

    @Test
    fun theUsageReportCarriesSecondsOnly() {
        val r = LiveCodec.UsageRequest("ls_1", 95, 40)
        val body = LiveCodec.encodeUsageRequest(r)
        assertEquals(r, LiveCodec.decodeUsageRequest(body))
        assertEquals(setOf("w", "sessionId", "in", "out"), kotlinx.serialization.json.Json.parseToJsonElement(body).let { (it as kotlinx.serialization.json.JsonObject).keys })
        assertEquals(LiveCodec.UsageRequest("ls_1", 0, 0), LiveCodec.decodeUsageRequest("""{"w":${WireCodec.VERSION},"sessionId":"ls_1","in":-4}"""))
        assertFailsWith<WireFormatException> { LiveCodec.decodeUsageRequest("""{"w":${WireCodec.VERSION},"sessionId":""}""") }
        val u = LiveCodec.Usage(true, meter)
        assertEquals(u, LiveCodec.decodeUsage(LiveCodec.encodeUsage(u)))
        assertTrue(LiveCodec.decodeUsage(LiveCodec.encodeUsage(u)).counted)
    }

    @Test
    fun theMonthsTalksAndMinutesRideTheMeterAndAnOlderServerReadsAsNone() {
        // Slice 2b: additive fields for Activity's Live line.
        val m = meter.copy(month = "2026-10", sessions = 3, seconds = 754)
        assertEquals(m, LiveCodec.decodeMeter(LiveCodec.encodeMeter(m)))
        val older = LiveCodec.decodeMeter("""{"w":${WireCodec.VERSION},"configured":true,"level":"ok","spentPence":40,"capPence":1500}""")
        assertNull(older.month)
        assertEquals(0, older.sessions)
        assertEquals(0, older.seconds)
        val odd = LiveCodec.decodeMeter("""{"w":${WireCodec.VERSION},"month":"October","sessions":-2,"seconds":-1}""")
        assertNull(odd.month)
        assertEquals(0, odd.sessions)
        assertEquals(0, odd.seconds)
    }
}
