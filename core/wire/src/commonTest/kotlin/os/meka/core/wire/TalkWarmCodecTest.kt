package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The orb speed pass, slice 5: the warm-up request carries only the version; the answer only a state. */
class TalkWarmCodecTest {
    @Test
    fun theRequestIsJustTheVersion() {
        assertEquals("""{"w":1}""", TalkWarmCodec.encodeRequest())
        TalkWarmCodec.decodeRequest(TalkWarmCodec.encodeRequest())
    }

    @Test
    fun aWrongVersionIsRefused() {
        assertFailsWith<WireFormatException> { TalkWarmCodec.decodeRequest("""{"w":9}""") }
        assertFailsWith<WireFormatException> { TalkWarmCodec.decodeRequest("not json") }
    }

    @Test
    fun theStateRoundTrips() {
        for (s in TalkWarmCodec.Response.STATES) {
            assertEquals(s, TalkWarmCodec.decodeResponse(TalkWarmCodec.encodeResponse(TalkWarmCodec.Response(s))).state)
        }
    }

    @Test
    fun anUnknownStateIsOff() {
        assertEquals(TalkWarmCodec.Response.OFF, TalkWarmCodec.decodeResponse("""{"w":1,"state":"boiling"}""").state)
        assertEquals(TalkWarmCodec.Response.OFF, TalkWarmCodec.decodeResponse("""{"w":1}""").state)
    }
}
