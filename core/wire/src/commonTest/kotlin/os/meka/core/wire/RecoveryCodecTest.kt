package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecoveryCodecTest {
    private val hash = "a".repeat(64)

    @Test
    fun bodiesRoundTripAndBadOnesAreRefused() {
        assertEquals(hash, RecoveryCodec.decodeSet(RecoveryCodec.encodeSet(hash)))
        assertFailsWith<WireFormatException> { RecoveryCodec.decodeSet("""{"verifier":"abc"}""") }
        assertFailsWith<WireFormatException> { RecoveryCodec.decodeSet("""{"verifier":"${"A".repeat(64)}"}""") }

        val e = RecoveryCodec.Enrol(hash, "android0123456789ab", "Galaxy Z Fold", "M".repeat(91))
        assertEquals(e, RecoveryCodec.decodeEnrol(RecoveryCodec.encodeEnrol(e)))
        assertFailsWith<WireFormatException> { RecoveryCodec.decodeEnrol(RecoveryCodec.encodeEnrol(e.copy(deviceId = "watch0123456789ab"))) }
        assertFailsWith<WireFormatException> { RecoveryCodec.decodeEnrol(RecoveryCodec.encodeEnrol(e.copy(name = " "))) }

        val done = RecoveryCodec.Enrolled("home", "mac0123456789ab", "b".repeat(64))
        assertEquals(done, RecoveryCodec.decodeEnrolled(RecoveryCodec.encodeEnrolled(done)))
        assertEquals(RecoveryCodec.ERR_KEY, RecoveryCodec.decodeError(RecoveryCodec.encodeError(RecoveryCodec.ERR_KEY)))
    }

    @Test
    fun statusReadsWhatItKnowsAndAnUnknownStateIsNone() {
        val s = RecoveryCodec.Status(RecoveryCodec.USED, 1L, "Fold", 2L, "Mac")
        assertEquals(s, RecoveryCodec.decodeStatus(RecoveryCodec.encodeStatus(s)))
        assertEquals(RecoveryCodec.Status(RecoveryCodec.NONE), RecoveryCodec.decodeStatus("""{"state":"later","createdAtMs":1}"""))
        assertEquals(RecoveryCodec.Status(RecoveryCodec.READY, 5L), RecoveryCodec.decodeStatus("""{"state":"ready","createdAtMs":5,"createdBy":"${"x".repeat(41)}"}"""))
    }

    @Test
    fun onlyPhoneAndMacIdsRecover() {
        assertTrue(RecoveryCodec.isDeviceId("android0123456789ab"))
        assertTrue(RecoveryCodec.isDeviceId("mac0123456789ab"))
        assertFalse(RecoveryCodec.isDeviceId("watch0123456789ab"))
        assertFalse(RecoveryCodec.isDeviceId("android"))
        assertFalse(RecoveryCodec.isDeviceId("mac0123-456789"))
        assertFalse(RecoveryCodec.isDeviceId("macABCDEF012345"))
    }
}
