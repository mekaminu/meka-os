package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Fresh hello openers on the wire (hello slice 4): only a count goes, openers come back. */
class HelloOpenersCodecTest {
    private val c = HelloOpenersCodec

    @Test
    fun onlyTheCountGoes() {
        val body = c.encodeRequest(HelloOpenersCodec.Request(6))
        assertEquals(HelloOpenersCodec.Request(6), c.decodeRequest(body))
        assertFalse("name" in body || "date" in body, body)
        assertFailsWith<WireFormatException> { c.decodeRequest("""{"w":${WireCodec.VERSION},"perPart":50}""") }
        assertFailsWith<WireFormatException> { c.decodeRequest("""{"w":${WireCodec.VERSION}}""") }
    }

    @Test
    fun answersRoundTripAndChatterIsNothing() {
        val r = HelloOpenersCodec.Response("answered", listOf(HelloOpenersCodec.Opener("evening", "Hey {n}, all good?", "What's up?")))
        assertEquals(r, c.decodeResponse(c.encodeResponse(r)))
        val parsed = c.parseModelAnswer(
            "```json\n{\"openers\":[{\"part\":\"Evening\",\"open\":\"Hey {n}.\",\"ask\":\"What's up?\"},{\"part\":\"noon\",\"open\":\"Hi {n}.\",\"ask\":\"Yes?\"},{\"part\":\"late\",\"open\":\"\",\"ask\":\"Yes?\"}]}\n```",
        )
        assertEquals(listOf(HelloOpenersCodec.Opener("evening", "Hey {n}.", "What's up?")), parsed)
        assertTrue(c.parseModelAnswer("Here are some hellos!").isEmpty())
    }
}
