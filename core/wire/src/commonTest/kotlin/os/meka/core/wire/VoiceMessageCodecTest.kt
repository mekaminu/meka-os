package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `POST /v1/voice-message/audio` (call assistant polish 8c): only a held message's id travels. */
class VoiceMessageCodecTest {
    private val id = "h0123456789abcdef"

    @Test
    fun theIdRoundTripsAndNothingElseIsSent() {
        val body = VoiceMessageCodec.encodeRequest(id)
        assertEquals("""{"w":${WireCodec.VERSION},"id":"$id"}""", body)
        assertEquals(id, VoiceMessageCodec.decodeRequest(body))
        assertEquals(id, VoiceMessageCodec.decodeRequest("""{"w":${WireCodec.VERSION},"id":"$id","extra":1}"""))
    }

    @Test
    fun anythingThatCouldntBeAHeldIdIsRefused() {
        for (bad in listOf("../etc", "h0123", "H0123456789ABCDEF", "h0123456789abcdeg", "")) {
            assertFailsWith<WireFormatException>(bad) { VoiceMessageCodec.decodeRequest("""{"w":${WireCodec.VERSION},"id":"$bad"}""") }
        }
        assertFailsWith<WireFormatException> { VoiceMessageCodec.decodeRequest("""{"w":${WireCodec.VERSION},"id":5}""") }
        assertFailsWith<WireFormatException> { VoiceMessageCodec.decodeRequest("""{"id":"$id"}""") }
        assertFailsWith<WireFormatException> { VoiceMessageCodec.decodeRequest("""{"w":999,"id":"$id"}""") }
        assertFailsWith<WireFormatException> { VoiceMessageCodec.decodeRequest("not json") }
        assertFailsWith<IllegalArgumentException> { VoiceMessageCodec.encodeRequest("nope") }
    }
}
