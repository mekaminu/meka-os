package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Where I am now" on the wire (Places item 3): a rounded point in, the forecast's compact text out. */
class HereCodecTest {

    @Test
    fun aRoundedPointRoundTripsAndAFinerOneIsRefusedNotRounded() {
        val r = HereCodec.Request(52.09, -0.26)
        assertEquals(r, HereCodec.decodeRequest(HereCodec.encodeRequest(r)))
        assertEquals(HereCodec.Request(-33.87, 151.21), HereCodec.decodeRequest("""{"w":1,"lat":-33.87,"lon":151.21}"""))
        assertEquals(HereCodec.Request(52.0, 0.0), HereCodec.decodeRequest("""{"w":1,"lat":52,"lon":0}"""))
        fun bad(body: String) = assertFailsWith<WireFormatException> { HereCodec.decodeRequest(body) }
        // A precise location never gets in, even by one more decimal place.
        bad(HereCodec.encodeRequest(HereCodec.Request(52.0868, -0.2645)))
        bad("""{"w":1,"lat":52.091,"lon":-0.26}""")
        bad("""{"w":1,"lat":91,"lon":0}""")
        bad("""{"w":1,"lat":0,"lon":-181}""")
        bad("""{"w":1,"lat":"52.09","lon":-0.26}""")
        bad("""{"w":1,"lat":52.09}""")
        bad("""{"w":2,"lat":52.09,"lon":-0.26}""")
        bad("not json")
    }

    @Test
    fun theAnswerCarriesTheCompactForecastAndWhetherThePointIsAway() {
        val ok = HereCodec.Response(HereCodec.Response.OK, hours = "490000|14,61,70;15,3,10", days = "2026-10-09=9,15,61,70", away = true)
        assertEquals(ok, HereCodec.decodeResponse(HereCodec.encodeResponse(ok)))
        val near = ok.copy(away = false)
        assertFalse(HereCodec.decodeResponse(HereCodec.encodeResponse(near)).away)
        val off = HereCodec.Response(HereCodec.Response.OFF)
        assertEquals(off, HereCodec.decodeResponse(HereCodec.encodeResponse(off)))
        assertTrue("away" !in HereCodec.encodeResponse(off))
        val failed = HereCodec.Response(HereCodec.Response.FAILED, reason = "Open-Meteo didn't answer")
        assertEquals(failed, HereCodec.decodeResponse(HereCodec.encodeResponse(failed)))
        // Oversized text is dropped, never cut; an odd "away" reads as false.
        val big = HereCodec.decodeResponse("""{"w":1,"state":"ok","hours":"${"1".repeat(HereCodec.MAX_HOURS_TEXT + 1)}","away":"yes"}""")
        assertNull(big.hours)
        assertFalse(big.away)
        assertFailsWith<WireFormatException> { HereCodec.decodeResponse("""{"w":1}""") }
    }
}
