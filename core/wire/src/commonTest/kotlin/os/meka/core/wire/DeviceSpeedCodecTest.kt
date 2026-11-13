package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DeviceSpeedCodecTest {
    private val r = DeviceSpeedCodec.Report("phone", 1_791_476_100_000L, 3, 500, 900, 4, 1_700, 3_100)

    @Test
    fun aReportAndTheLatestRoundTrip() {
        assertEquals(r, DeviceSpeedCodec.decodeReport(DeviceSpeedCodec.encodeReport(r)))
        val mac = r.copy(device = "Mac", helloCount = 0, helloAvgMs = null, helloMaxMs = null)
        assertEquals(listOf(r, mac), DeviceSpeedCodec.decodeLatest(DeviceSpeedCodec.encodeLatest(listOf(r, mac))))
        DeviceSpeedCodec.decodeLatestRequest(DeviceSpeedCodec.encodeLatestRequest())
    }

    @Test
    fun onlyNumbersAndALabelGetThrough() {
        val odd = DeviceSpeedCodec.decodeReport(
            """{"w":${WireCodec.VERSION},"device":"Mac 12:00 \"hi\"","sent":5,"hn":-3,"ha":99999999,"an":2,"aa":800,"am":900,"words":"hello"}""",
        )
        assertEquals(DeviceSpeedCodec.Report("Mac  hi", 5, 0, null, null, 2, 800, 900), odd)
        assertFailsWith<WireFormatException> { DeviceSpeedCodec.decodeReport("""{"w":${WireCodec.VERSION},"device":"123","sent":5}""") }
        assertFailsWith<WireFormatException> { DeviceSpeedCodec.decodeReport("""{"w":${WireCodec.VERSION},"device":"Mac"}""") }
    }
}
