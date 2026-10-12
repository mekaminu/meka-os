package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FoldPostureCodecTest {
    private val report = FoldPostureCodec.Report(
        "SM-F966B", 5_000,
        listOf(
            FoldPostureCodec.Change(4_000, "HALF_OPENED across=false sep=true 2176×1812 95° steady → TABLETOP/WINDOW"),
            FoldPostureCodec.Change(1_000, "FLAT 2176×1812 → FLAT"),
        ),
    )

    @Test
    fun aReportRoundTripsAndTheLatestAnswerToo() {
        assertEquals(report, FoldPostureCodec.decodeReport(FoldPostureCodec.encodeReport(report)))
        assertEquals(report, FoldPostureCodec.decodeLatest(FoldPostureCodec.encodeLatest(report)))
        assertNull(FoldPostureCodec.decodeLatest(FoldPostureCodec.encodeLatest(null)))
        FoldPostureCodec.decodeLatestRequest(FoldPostureCodec.encodeLatestRequest())
        FoldPostureCodec.decodeLatestRequest("")
    }

    @Test
    fun longOrBrokenPartsAreCutOrLeftOut() {
        val many = report.copy(
            model = "x".repeat(100),
            changes = List(30) { FoldPostureCodec.Change(1_000L + it, "e".repeat(300)) } + FoldPostureCodec.Change(0, "bad"),
        )
        val back = FoldPostureCodec.decodeReport(FoldPostureCodec.encodeReport(many))
        assertEquals(FoldPostureCodec.CHANGES_MAX, back.changes.size)
        assertEquals(FoldPostureCodec.ENTRY_MAX, back.changes.first().entry.length)
        assertEquals(FoldPostureCodec.MODEL_MAX, back.model!!.length)
        val odd = """{"w":${WireCodec.VERSION},"sent":9,"changes":[{"at":0,"entry":"x"},{"at":5,"entry":""},{"at":6,"entry":"ok"},3]}"""
        assertEquals(listOf(FoldPostureCodec.Change(6, "ok")), FoldPostureCodec.decodeReport(odd).changes)
        assertFailsWith<WireFormatException> { FoldPostureCodec.decodeReport("""{"w":${WireCodec.VERSION},"changes":[]}""") }
        assertFailsWith<WireFormatException> { FoldPostureCodec.decodeReport("""{"w":999,"sent":1,"changes":[]}""") }
    }
}
