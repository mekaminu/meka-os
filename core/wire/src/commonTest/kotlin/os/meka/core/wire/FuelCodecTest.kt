package os.meka.core.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Car and parking, slice 6: the fuel request and answer on the wire (invented forecourts). */
class FuelCodecTest {
    @Test
    fun aRoundedPointAndAKnownFuelGoThroughAnythingElseIsRefused() {
        val r = FuelCodec.decodeRequest(FuelCodec.encodeRequest(FuelCodec.Request(52.1, -0.3, "B7")))
        assertEquals(FuelCodec.Request(52.1, -0.3, "B7"), r)
        assertFailsWith<WireFormatException> { FuelCodec.decodeRequest(FuelCodec.encodeRequest(FuelCodec.Request(52.1234, -0.3, "E10"))) }
        assertFailsWith<WireFormatException> { FuelCodec.decodeRequest(FuelCodec.encodeRequest(FuelCodec.Request(52.12, -0.3, "LPG"))) }
        assertFailsWith<WireFormatException> { FuelCodec.decodeRequest("""{"w":${WireCodec.VERSION},"lat":"52.1","lon":-0.3,"fuel":"E10"}""") }
    }

    @Test
    fun theAnswerRoundTripsAndDropsWhatDoesNotRead() {
        val s = FuelCodec.Station("Oak Road", "Fuelco", "AB1 2CD", 52.11, -0.3, 139.9, 1_792_000_000_000L, 1_100.0)
        val back = FuelCodec.decodeResponse(FuelCodec.encodeResponse(FuelCodec.Response(FuelCodec.Response.OK, listOf(s, s.copy(brand = null, updatedMs = null)))))
        assertEquals(FuelCodec.Response.OK, back.state)
        assertEquals(listOf(s, s.copy(brand = null, updatedMs = null)), back.stations)
        val odd = FuelCodec.decodeResponse(
            """{"w":${WireCodec.VERSION},"state":"ok","stations":[{"name":"X","lat":1.0,"lon":2.0,"dist":3.0},{"name":"Y","lat":1.0,"lon":2.0,"pence":140.1,"dist":3.0}]}""",
        )
        assertEquals(listOf("Y"), odd.stations.map { it.name })
        val off = FuelCodec.decodeResponse(FuelCodec.encodeResponse(FuelCodec.Response(FuelCodec.Response.OFF)))
        assertEquals(FuelCodec.Response.OFF, off.state)
        assertTrue(off.stations.isEmpty())
        assertNull(off.reason)
        val many = FuelCodec.Response(FuelCodec.Response.OK, List(15) { s })
        assertEquals(FuelCodec.MAX_STATIONS, FuelCodec.decodeResponse(FuelCodec.encodeResponse(many)).stations.size)
    }
}
