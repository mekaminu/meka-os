package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UpdateFailureCodecTest {
    private val c = UpdateFailureCodec
    private val report = UpdateFailureCodec.Report(
        stage = "install", status = 5, message = "INSTALL_FAILED_UPDATE_INCOMPATIBLE", fromCode = 425, toCode = 430,
        atMs = 1_760_000_000_000, storage = "over 5 GB", network = "wifi", attempts = 1,
    )

    @Test
    fun aReportRoundTrips() {
        assertEquals(report, c.decodeReport(c.encodeReport(report)))
        val bare = report.copy(stage = "download", status = null, message = null, storage = null, network = null, attempts = 3)
        assertEquals(bare, c.decodeReport(c.encodeReport(bare)))
    }

    @Test
    fun badReportsAreRefusedAndOddFieldsDropped() {
        assertFailsWith<WireFormatException> { c.decodeReport("""{"w":${WireCodec.VERSION},"stage":"party","from":1,"to":2,"at":3}""") }
        assertFailsWith<WireFormatException> { c.decodeReport("""{"w":${WireCodec.VERSION},"stage":"install","from":1,"at":3}""") }
        assertFailsWith<WireFormatException> { c.decodeReport("""{"w":999,"stage":"install","from":1,"to":2,"at":3}""") }
        val odd = c.decodeReport("""{"w":${WireCodec.VERSION},"stage":"install","from":1,"to":2,"at":3,"network":"satellite","status":500,"attempts":50}""")
        assertNull(odd.network)
        assertNull(odd.status)
        assertEquals(10, odd.attempts)
        val long = c.decodeReport(c.encodeReport(report.copy(message = "y".repeat(1000))))
        assertEquals(c.MESSAGE_MAX, long.message!!.length)
    }

    @Test
    fun theListKeepsOrderAndSkipsWhatItCantRead() {
        val two = listOf(report, report.copy(toCode = 429, atMs = 1_759_000_000_000))
        assertEquals(two, c.decodeList(c.encodeList(two)))
        val withJunk = """{"w":${WireCodec.VERSION},"failures":[{"stage":"nope"},${c.encodeReport(report).replace("\"w\":${WireCodec.VERSION},", "")}]}"""
        assertEquals(listOf(report), c.decodeList(withJunk))
        assertEquals(emptyList(), c.decodeList("""{"w":${WireCodec.VERSION}}"""))
        c.decodeListRequest(c.encodeListRequest())
        c.decodeListRequest("")
    }
}
