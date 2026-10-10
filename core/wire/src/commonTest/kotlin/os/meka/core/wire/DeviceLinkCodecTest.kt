package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Linking a watch on the wire (Galaxy Watch, slice 1): every body round-trips and is read strictly. */
class DeviceLinkCodecTest {
    private val watch = "watch-0123456789abcdef"
    private val link = "lnk" + "0123456789abcdef01234567"
    private val secret = "ab".repeat(32)
    private val key = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE" + "A".repeat(52)

    @Test
    fun idsCodesAndNamesAreWhatTheServerMakes() {
        assertTrue(DeviceLinkCodec.isWatchId(watch))
        assertFalse(DeviceLinkCodec.isWatchId("watch-short"))
        assertFalse(DeviceLinkCodec.isWatchId("fold-0123456789"))
        assertFalse(DeviceLinkCodec.isWatchId("watch-ABCDEF0123"))
        assertTrue(DeviceLinkCodec.isLinkId(link))
        assertFalse(DeviceLinkCodec.isLinkId("lnk123"))
        assertTrue(DeviceLinkCodec.isCode("01234567"))
        assertFalse(DeviceLinkCodec.isCode("1234 5678"))
        assertFalse(DeviceLinkCodec.isCode("1234567"))
        assertEquals("Galaxy Watch", DeviceLinkCodec.cleanName("  Galaxy   Watch "))
        assertNull(DeviceLinkCodec.cleanName(" "))
        assertNull(DeviceLinkCodec.cleanName("x".repeat(41)))
        assertNull(DeviceLinkCodec.cleanName("bad\nname"))
    }

    @Test
    fun theWatchsStartAndStatusRoundTrip() {
        val start = DeviceLinkCodec.Start(watch, "Galaxy Watch", key)
        assertEquals(start, DeviceLinkCodec.decodeStart(DeviceLinkCodec.encodeStart(start)))
        assertFailsWith<WireFormatException> { DeviceLinkCodec.decodeStart("""{"deviceId":"fold","name":"x","publicKey":"$key"}""") }
        assertFailsWith<WireFormatException> { DeviceLinkCodec.decodeStart("""{"deviceId":"$watch","name":"x","publicKey":"short"}""") }

        val started = DeviceLinkCodec.Started(link, "01234567", 99)
        assertEquals(started, DeviceLinkCodec.decodeStarted(DeviceLinkCodec.encodeStarted(started)))
        assertFailsWith<WireFormatException> { DeviceLinkCodec.decodeStarted("""{"linkId":"$link","code":"1234","expiresAtMs":1}""") }

        assertEquals(link, DeviceLinkCodec.decodeStatusRequest(DeviceLinkCodec.encodeStatusRequest(link)))
        val linked = DeviceLinkCodec.Status(DeviceLinkCodec.LINKED, "home", watch, secret)
        assertEquals(linked, DeviceLinkCodec.decodeStatus(DeviceLinkCodec.encodeStatus(linked)))
        assertEquals(DeviceLinkCodec.Status(DeviceLinkCodec.WAITING), DeviceLinkCodec.decodeStatus("""{"state":"waiting"}"""))
        // Anything the device doesn't know means start again.
        assertEquals(DeviceLinkCodec.EXPIRED, DeviceLinkCodec.decodeStatus("""{"state":"lost"}""").state)
        // A linked answer missing or mangling its secret is refused, never half-read.
        assertFailsWith<WireFormatException> { DeviceLinkCodec.decodeStatus("""{"state":"linked","householdId":"home","deviceId":"$watch"}""") }
        assertFailsWith<WireFormatException> {
            DeviceLinkCodec.decodeStatus("""{"state":"linked","householdId":"home","deviceId":"$watch","secret":"xyz"}""")
        }
    }

    @Test
    fun mekasApproveListAndUnlinkRoundTrip() {
        assertEquals("12345678", DeviceLinkCodec.decodeApprove(DeviceLinkCodec.encodeApprove("12345678")))
        assertFailsWith<WireFormatException> { DeviceLinkCodec.decodeApprove("""{"code":"abc"}""") }
        val l = DeviceLinkCodec.Linked(watch, "Galaxy Watch")
        assertEquals(l, DeviceLinkCodec.decodeLinked(DeviceLinkCodec.encodeLinked(l)))

        val list = listOf(DeviceLinkCodec.Watch(watch, "Galaxy Watch", 10))
        assertEquals(list, DeviceLinkCodec.decodeWatches(DeviceLinkCodec.encodeWatches(list)))
        val mixed = """{"watches":[{"id":"$watch","name":"Galaxy Watch","linkedAtMs":10},{"id":"mac1","name":"Mac","linkedAtMs":1},"junk"]}"""
        assertEquals(list, DeviceLinkCodec.decodeWatches(mixed))
        assertFailsWith<WireFormatException> { DeviceLinkCodec.decodeWatches("{}") }

        assertEquals(watch, DeviceLinkCodec.decodeUnlink(DeviceLinkCodec.encodeUnlink(watch)))
        assertFailsWith<WireFormatException> { DeviceLinkCodec.decodeUnlink("""{"id":"fold"}""") }
        assertEquals("code", DeviceLinkCodec.decodeError(DeviceLinkCodec.encodeError(DeviceLinkCodec.ERR_CODE)))
        assertNull(DeviceLinkCodec.decodeError("not json"))
    }
}
