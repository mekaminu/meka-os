package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Spam-call lookup on the wire (2026-10-17): one number in, a verdict out; anything that isn't a number is refused. */
class NumberLookupCodecTest {
    @Test
    fun theRequestCarriesTheNumberAndACappedWait() {
        val r = NumberLookupCodec.decodeRequest(NumberLookupCodec.encodeRequest(NumberLookupCodec.Request(" +44 1632 960691 ", 99_000)))
        assertEquals("+44 1632 960691", r.number)
        assertEquals(NumberLookupCodec.MAX_WAIT_MS, r.waitMs)
        assertFailsWith<WireFormatException> { NumberLookupCodec.decodeRequest("""{"w":${WireCodec.VERSION},"number":"call me"}""") }
        assertFailsWith<WireFormatException> { NumberLookupCodec.decodeRequest("""{"w":${WireCodec.VERSION}}""") }
        assertEquals(0L, NumberLookupCodec.decodeRequest("""{"w":${WireCodec.VERSION},"number":"01632960691"}""").waitMs)
    }

    @Test
    fun theAnswerRoundTripsAndOnlyAnOkAnswerCarriesAVerdict() {
        val ok = NumberLookupCodec.Response(NumberLookupCodec.Response.OK, "scam", 23, true, "a police-impersonation scam")
        assertEquals(ok, NumberLookupCodec.decodeResponse(NumberLookupCodec.encodeResponse(ok)))
        val pending = NumberLookupCodec.decodeResponse(NumberLookupCodec.encodeResponse(NumberLookupCodec.Response(NumberLookupCodec.Response.PENDING, "scam")))
        assertEquals(NumberLookupCodec.Response(NumberLookupCodec.Response.PENDING), pending)
        // An unknown verdict word is dropped, never guessed at.
        assertEquals(null, NumberLookupCodec.decodeResponse("""{"w":${WireCodec.VERSION},"state":"ok","verdict":"evil"}""").verdict)
    }
}
