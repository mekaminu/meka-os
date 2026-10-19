package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** The Health screen's server half on the wire (Reliability first, item 3). */
class HealthCodecTest {

    @Test
    fun aResponseRoundTripsAndAnUnknownPushStateReadsAsOff() {
        val r = HealthCodec.Response(HealthCodec.Response.PUSH_MISSING, calls = true, speech = false, atMs = 1_700_000_000_000)
        assertEquals(r, HealthCodec.decodeResponse(HealthCodec.encodeResponse(r)))
        val odd = HealthCodec.decodeResponse("""{"w":1,"push":"maybe","calls":"yes","at":5}""")
        assertEquals(HealthCodec.Response.PUSH_OFF, odd.push)
        assertFalse(odd.calls)
        assertFalse(odd.speech)
        assertFailsWith<WireFormatException> { HealthCodec.decodeResponse("""{"w":1,"push":"on"}""") }
        assertFailsWith<WireFormatException> { HealthCodec.decodeResponse("""{"w":2,"push":"on","at":5}""") }
        assertFailsWith<WireFormatException> { HealthCodec.decodeResponse("nope") }
    }

    @Test
    fun macsRoundTripAndAnOlderServerSaysNothing() {
        // Setup's "Mac" step: how many Macs are connected; an older server leaves it out (null, "Couldn't check").
        val r = HealthCodec.Response(HealthCodec.Response.PUSH_ON, calls = true, speech = true, atMs = 5, macs = 1)
        assertEquals(r, HealthCodec.decodeResponse(HealthCodec.encodeResponse(r)))
        assertEquals(null, HealthCodec.decodeResponse("""{"w":1,"push":"on","at":5}""").macs)
        assertEquals(null, HealthCodec.decodeResponse("""{"w":1,"push":"on","at":5,"macs":-1}""").macs)
        assertEquals(null, HealthCodec.decodeResponse("""{"w":1,"push":"on","at":5,"macs":"2"}""").macs)
    }

    @Test
    fun theLastPushRoundTripsAndAnUnknownResultReadsAsFailed() {
        // Notifications never seen, slice 2: the server's last push to this device, for Health's Instant updates row.
        val r = HealthCodec.Response(HealthCodec.Response.PUSH_ON, calls = true, speech = true, atMs = 5, lastPushAtMs = 4, lastPushResult = "sent")
        assertEquals(r, HealthCodec.decodeResponse(HealthCodec.encodeResponse(r)))
        assertEquals("failed", HealthCodec.decodeResponse("""{"w":1,"push":"on","at":5,"lastPushAt":4,"lastPush":"odd"}""").lastPushResult)
        val half = HealthCodec.decodeResponse("""{"w":1,"push":"on","at":5,"lastPush":"sent"}""")
        assertEquals(null, half.lastPushResult)
        assertEquals(null, half.lastPushAtMs)
        assertEquals(null, HealthCodec.decodeResponse("""{"w":1,"push":"on","at":5}""").lastPushAtMs)
    }

    @Test
    fun googlesVoicesRoundTripAndAnUnknownStateReadsAsFailed() {
        // Natural voice, slice 3: Google's voices on the server and when a chosen one last failed.
        val r = HealthCodec.Response(HealthCodec.Response.PUSH_ON, calls = true, speech = true, atMs = 5, speechGoogle = "on", speechGoogleFailedAtMs = 4)
        assertEquals(r, HealthCodec.decodeResponse(HealthCodec.encodeResponse(r)))
        // The session latch's length and whether Polly still stands in.
        val held = r.copy(speechGooglePollyForMs = 900_000, speechGooglePollyNow = true)
        assertEquals(held, HealthCodec.decodeResponse(HealthCodec.encodeResponse(held)))
        assertEquals(false, HealthCodec.decodeResponse(HealthCodec.encodeResponse(r)).speechGooglePollyNow)
        assertEquals("failed", HealthCodec.decodeResponse("""{"w":1,"push":"on","at":5,"speechGoogle":"odd"}""").speechGoogle)
        assertEquals(null, HealthCodec.decodeResponse("""{"w":1,"push":"on","at":5,"speechGoogle":"nokey"}""").speechGoogle)
        assertEquals(null, HealthCodec.decodeResponse("""{"w":1,"push":"on","at":5}""").speechGoogle)
        assertEquals(null, HealthCodec.decodeResponse("""{"w":1,"push":"on","at":5,"speechGoogleFailedAt":"4"}""").speechGoogleFailedAtMs)
    }

    @Test
    fun theRequestCarriesOnlyTheVersion() {
        assertEquals("""{"w":1}""", HealthCodec.encodeRequest())
        HealthCodec.decodeRequest(HealthCodec.encodeRequest())
        HealthCodec.decodeRequest("")
        assertFailsWith<WireFormatException> { HealthCodec.decodeRequest("""{"w":9}""") }
    }
}
