package os.meka.backend

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import os.meka.backend.integrations.FuelFinder
import os.meka.backend.integrations.FuelFinderKeys
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.wire.FuelCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Car and parking, slice 6: Fuel Finder's prices near a rounded point (a recorded-shape sample, invented forecourts, no real key). */
class FuelFinderTest {
    private var now = 1_792_000_000_000L
    private val calls = mutableListOf<String>()
    private var tokenCalls = 0
    private var failing = false
    private var keys: FuelFinderKeys? = FuelFinderKeys("client-abc", "secret-xyz")

    private val sites = """{"data":[
        {"node_id":"n1","trading_name":"Oak Road Service Station","brand_name":"Fuelco","temporary_closure":false,
         "location":{"latitude":52.110,"longitude":-0.300,"postcode":"AB1 2CD"}},
        {"node_id":"n2","trading_name":"Elm Lane","brand_name":"Petrolia",
         "location":{"latitude":"52.120","longitude":"-0.300","postcode":"AB1 3EF"}},
        {"node_id":"n3","trading_name":"Closed One","brand_name":"Fuelco","permanent_closure":true,
         "location":{"latitude":52.101,"longitude":-0.300}},
        {"node_id":"n4","trading_name":"Far Away","location":{"latitude":53.500,"longitude":-0.300}}
    ]}"""
    private val prices = """[
        {"node_id":"n1","fuel_prices":[{"fuel_type":"E10","price":142.9,"price_last_updated":"2026-10-14T08:10:00Z"},
                                        {"fuel_type":"B7_STANDARD","price":"1.509","price_last_updated":"2026-10-14T08:10:00Z"}]},
        {"node_id":"n2","fuel_prices":[{"fuel_type":"E10","price":139.9,"price_change_effective_timestamp":"2026-10-14 09:00:00"},
                                        {"fuel_type":"HVO","price":180.0}]},
        {"node_id":"n3","fuel_prices":[{"fuel_type":"E10","price":120.0}]},
        {"node_id":"n4","fuel_prices":[{"fuel_type":"E10","price":110.0}]}
    ]"""

    private val http = object : FuelFinder.Http {
        override fun postJson(url: String, body: String): Pair<Int, String> {
            calls += url
            tokenCalls++
            assertTrue(url.endsWith("/oauth/generate_access_token"))
            assertTrue("client-abc" in body && "secret-xyz" in body)
            return 200 to """{"success":true,"data":{"access_token":"tok-$tokenCalls","expires_in":3600}}"""
        }

        override fun get(url: String, headers: Map<String, String>): Pair<Int, String> {
            calls += url
            if (failing) return 503 to "down"
            assertEquals("Bearer tok-$tokenCalls", headers["Authorization"])
            val n = url.substringAfter("batch-number=").toInt()
            if (n > 1) return 404 to "{}"
            return 200 to if ("/pfs/fuel-prices" in url) prices else sites
        }
    }

    private val queued = mutableListOf<() -> Unit>()
    private fun finder() = FuelFinder({ keys }, http, { now }, { queued += it }, baseUrl = "https://ff.test/api/v1")
    private fun runQueued() { while (queued.isNotEmpty()) queued.removeAt(0)() }

    @Test
    fun theFirstAskLoadsThenTheCheapestNearbyComeBackFromTheCopy() {
        val f = finder()
        val ask = FuelCodec.Request(52.1, -0.3, "E10")
        assertEquals(FuelCodec.Response.LOADING, f.near(ask).state)
        assertEquals(1, queued.size)
        assertEquals(FuelCodec.Response.LOADING, f.near(ask).state) // one refresh at a time
        assertEquals(1, queued.size)
        runQueued()
        val r = f.near(ask)
        assertEquals(FuelCodec.Response.OK, r.state)
        // Closed and far-away forecourts are left out; the cheapest first.
        assertEquals(listOf("Elm Lane", "Oak Road Service Station"), r.stations.map { it.name })
        assertEquals(139.9, r.stations[0].pence)
        assertEquals("Petrolia", r.stations[0].brand)
        assertNotNull(r.stations[0].updatedMs)
        assertTrue(r.stations[0].distanceM in 2_000.0..2_400.0)
        // Diesel in pounds became pence; premium diesel: none nearby.
        assertEquals(150.9, f.near(ask.copy(fuel = "B7")).stations.single().pence)
        assertTrue(f.near(ask.copy(fuel = "SDV")).stations.isEmpty())
        // Only Fuel Finder was asked, never with the point.
        assertTrue(calls.none { "52.1" in it || "-0.3" in it })
        assertEquals(1, tokenCalls)
        // Within the hour: no new fetch.
        val before = calls.size
        now += 30 * 60_000L
        f.near(ask)
        assertTrue(queued.isEmpty())
        assertEquals(before, calls.size)
        // After it: the copy answers at once and refreshes in the background (prices only; forecourts are a day old at most).
        now += 40 * 60_000L
        assertEquals(FuelCodec.Response.OK, f.near(ask).state)
        runQueued()
        assertTrue(calls.drop(before).all { "/pfs/fuel-prices" in it || "oauth" in it }, calls.drop(before).toString())
    }

    @Test
    fun withoutKeysItIsOffAndAFailedFirstFetchSaysSo() {
        keys = null
        assertEquals(FuelCodec.Response.OFF, finder().near(FuelCodec.Request(52.1, -0.3, "E10")).state)
        assertTrue(calls.isEmpty())
        keys = FuelFinderKeys("client-abc", "secret-xyz")
        failing = true
        val f = finder()
        assertEquals(FuelCodec.Response.LOADING, f.near(FuelCodec.Request(52.1, -0.3, "E10")).state)
        runQueued()
        val r = f.near(FuelCodec.Request(52.1, -0.3, "E10"))
        assertEquals(FuelCodec.Response.FAILED, r.state)
        assertFalse("52.1" in r.reason.orEmpty())
        assertTrue(queued.isEmpty()) // not retried for ten minutes
    }

    @Test
    fun keysAndRecordsReadSafely() {
        assertNull(FuelFinderKeys.parse("{}"))
        assertNull(FuelFinderKeys.parse("junk"))
        assertEquals(FuelFinderKeys("abcd", "efgh"), FuelFinderKeys.parse("""{"client_id":"abcd","client_secret":"efgh"}"""))
        assertFalse("efgh" in FuelFinderKeys("abcd", "efgh").toString())
        assertEquals(emptyList(), FuelFinder.list("""{"success":true}"""))
        assertNull(FuelFinder.list("not json"))
        assertNotNull(FuelFinder.time("2026-10-14T09:10:00.000+01:00"))
        assertNull(FuelFinder.time("yesterday"))
    }

    @Test
    fun keyedDevicesAskAndAFinerPointIsRefused() = testApplication {
        val devices = InMemoryDeviceRegistry()
        val foldSecret = devices.enrol("hh", "fold")
        val foldKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "fold"), it.publicB64) }
        val f = finder()
        application { mekaSync(InMemoryServerOpStore(), devices, fuel = f) }
        val path = FuelCodec.PATH
        val body = FuelCodec.encodeRequest(FuelCodec.Request(52.1, -0.3, "E10"))
        val first = client.post(path) { with(foldKey) { signed(foldSecret, path, body) } }
        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(FuelCodec.Response.LOADING, FuelCodec.decodeResponse(first.bodyAsText()).state)
        runQueued()
        val ok = FuelCodec.decodeResponse(client.post(path) { with(foldKey) { signed(foldSecret, path, body) } }.bodyAsText())
        assertEquals(2, ok.stations.size)
        val fine = FuelCodec.encodeRequest(FuelCodec.Request(52.1012, -0.3, "E10"))
        assertEquals(HttpStatusCode.BadRequest, client.post(path) { with(foldKey) { signed(foldSecret, path, fine) } }.status)
        assertEquals(HttpStatusCode.Unauthorized, client.post(path) { header("Authorization", "Bearer $foldSecret"); setBody(body) }.status)
    }

    @Test
    fun withoutFuelTheRouteIsLeftOut() = testApplication {
        val devices = InMemoryDeviceRegistry()
        val foldSecret = devices.enrol("hh", "fold")
        val foldKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "fold"), it.publicB64) }
        application { mekaSync(InMemoryServerOpStore(), devices) }
        val body = FuelCodec.encodeRequest(FuelCodec.Request(52.1, -0.3, "E10"))
        assertEquals(HttpStatusCode.NotFound, client.post(FuelCodec.PATH) { with(foldKey) { signed(foldSecret, FuelCodec.PATH, body) } }.status)
    }
}
