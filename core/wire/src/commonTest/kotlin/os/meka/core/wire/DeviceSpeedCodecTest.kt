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

    @Test
    fun theDaysSlowWorkAndFeelTimesRoundTripAsNamesAndNumbers() {
        val full = r.copy(
            slow = listOf(DeviceSpeedCodec.Work("Money view", 1_800, 12, 3), DeviceSpeedCodec.Work("Mail · list", 300, 1, 1)),
            feel = listOf(DeviceSpeedCodec.Feel("COLD_START", 2_100, null, 3, 1), DeviceSpeedCodec.Feel("TAB", 90, "Calendar", 40, 0)),
        )
        assertEquals(full, DeviceSpeedCodec.decodeReport(DeviceSpeedCodec.encodeReport(full)))
        assertEquals(listOf(full), DeviceSpeedCodec.decodeLatest(DeviceSpeedCodec.encodeLatest(listOf(full))))
        // An older report without them still reads.
        assertEquals(r, DeviceSpeedCodec.decodeReport(DeviceSpeedCodec.encodeReport(r)))
        // Only names and numbers get through: odd characters dropped, unknown moments, bad times and extra fields left out.
        val odd = DeviceSpeedCodec.decodeReport(
            """{"w":${WireCodec.VERSION},"device":"phone","sent":5,"hn":1,"an":0,""" +
                """"slow":[{"n":"Money <b>view</b> 12:00","ms":1800,"c":2,"s":1,"words":"hi"},{"n":"***","ms":5},{"n":"Big","ms":999999}],""" +
                """"feel":[{"m":"TAB","ms":90,"p":"Calendar 1 <x>","c":4,"o":0},{"m":"EVIL","ms":5},{"m":"TAB","ms":10}]}""",
        )
        assertEquals(listOf(DeviceSpeedCodec.Work("Money bviewb 1200", 1_800, 2, 1)), odd.slow)
        assertEquals(listOf(DeviceSpeedCodec.Feel("TAB", 90, "Calendar  x", 4, 0)), odd.feel)
    }

    @Test
    fun theDaysLiveCountsRoundTripAsNumbersOnly() {
        val full = r.copy(live = DeviceSpeedCodec.Live(3, 2, 2, 1, 3, 1_200))
        assertEquals(full, DeviceSpeedCodec.decodeReport(DeviceSpeedCodec.encodeReport(full)))
        assertEquals(listOf(full), DeviceSpeedCodec.decodeLatest(DeviceSpeedCodec.encodeLatest(listOf(full))))
        // No talks: nothing sent; parts can't exceed their wholes; a wait over a minute is held to one.
        assertEquals(r, DeviceSpeedCodec.decodeReport(DeviceSpeedCodec.encodeReport(r.copy(live = DeviceSpeedCodec.Live(0, 0, 0, 0, 0, 0)))))
        val odd = DeviceSpeedCodec.decodeReport(
            """{"w":${WireCodec.VERSION},"device":"phone","sent":5,"hn":1,"an":0,"live":{"t":2,"st":9,"c":1,"sc":5,"wn":1,"wl":999999,"words":"hi"}}""",
        )
        assertEquals(DeviceSpeedCodec.Live(2, 2, 1, 1, 1, DeviceSpeedCodec.MS_MAX), odd.live)
    }
}
