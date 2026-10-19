package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DeviceJoinCodecTest {
    private val id = "join" + "0123456789abcdef".repeat(2)

    @Test
    fun theWaitingAnswerRoundTripsAndAnOlderServersAnswerIsNotOne() {
        val w = DeviceJoinCodec.Waiting(id, "482913", 1_800_000_600_000L)
        assertEquals(w, DeviceJoinCodec.decodeWaiting(DeviceJoinCodec.encodeWaiting(w)))
        assertNull(DeviceJoinCodec.decodeWaiting(WireCodec.encodeEnrolResponse("a".repeat(64))))
        assertNull(DeviceJoinCodec.decodeWaiting("""{"state":"waiting","requestId":"$id","code":"12","expiresAtMs":1}"""))
        assertNull(DeviceJoinCodec.decodeWaiting("not json"))
    }

    @Test
    fun statusAndAnswersRoundTrip() {
        assertEquals(id, DeviceJoinCodec.decodeStatusRequest(DeviceJoinCodec.encodeStatusRequest(id)))
        assertFailsWith<WireFormatException> { DeviceJoinCodec.decodeStatusRequest("""{"requestId":"join12"}""") }
        val enrolled = DeviceJoinCodec.Status(DeviceJoinCodec.ENROLLED, "b".repeat(64))
        assertEquals(enrolled, DeviceJoinCodec.decodeStatus(DeviceJoinCodec.encodeStatus(enrolled)))
        assertFailsWith<WireFormatException> { DeviceJoinCodec.decodeStatus("""{"state":"enrolled","secret":"short"}""") }
        assertEquals(DeviceJoinCodec.EXPIRED, DeviceJoinCodec.decodeStatus("""{"state":"something new"}""").state)
        val a = DeviceJoinCodec.Answer(id, false)
        assertEquals(a, DeviceJoinCodec.decodeAnswer(DeviceJoinCodec.encodeAnswer(a)))
        assertFailsWith<WireFormatException> { DeviceJoinCodec.decodeAnswer("""{"requestId":"$id","allow":"yes"}""") }
        assertEquals(DeviceJoinCodec.ALLOWED, DeviceJoinCodec.decodeAnswered(DeviceJoinCodec.encodeAnswered(DeviceJoinCodec.ALLOWED)))
        assertEquals(DeviceJoinCodec.GONE, DeviceJoinCodec.decodeAnswered("""{"state":"odd"}"""))
    }

    @Test
    fun onlyPhonesAndMacsAsk() {
        assertEquals(DeviceJoinCodec.KIND_PHONE, DeviceJoinCodec.kindOf("android0123456789"))
        assertEquals(DeviceJoinCodec.KIND_MAC, DeviceJoinCodec.kindOf("mac0123456789"))
        assertNull(DeviceJoinCodec.kindOf("watch0123456789abcdef"))
    }
}
