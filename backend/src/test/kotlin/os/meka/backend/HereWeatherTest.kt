package os.meka.backend

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import os.meka.backend.integrations.HereWeather
import os.meka.backend.integrations.OpenMeteoWeather
import os.meka.backend.integrations.WeatherLocation
import os.meka.core.domain.WeatherCodec
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.wire.HereCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** "Where I am now" (build plan "Places…", item 3): a rounded point's forecast, nothing kept. */
class HereWeatherTest {
    private val now = 1_791_531_000_000L // Fri 9 Oct 2026 08:30 BST
    private val midnight = 1_791_500_400L // Fri 9 Oct 00:00 BST, Unix seconds

    private fun sample(temp: Double = 11.2) = """
        {"hourly":{
           "time":[${(0 until 168).joinToString(",") { (midnight + it * 3600L).toString() }}],
           "temperature_2m":[${(0 until 168).joinToString(",") { "$temp" }}],
           "weather_code":[${(0 until 168).joinToString(",") { "2" }}],
           "precipitation_probability":[${(0 until 168).joinToString(",") { "8" }}]},
         "daily":{
           "time":[${(0 until 7).joinToString(",") { (midnight + it * 86_400L).toString() }}],
           "weather_code":[2,2,3,3,2,1,0],
           "temperature_2m_max":[13.0,14.0,13.0,13.4,12.9,14.0,15.1],
           "temperature_2m_min":[7.0,8.6,7.0,6.5,5.2,6.8,7.7],
           "precipitation_probability_max":[8,10,12,10,5,3,0]}}"""

    private val asked = mutableListOf<String>()
    private var failing = false
    private val source = OpenMeteoWeather({ url -> asked += url; if (failing) error("no network"); sample() }, { now })
    private val places = listOf(WeatherLocation("Biggleswade", 52.0868, -0.2645), WeatherLocation("Canary Wharf", 51.5054, -0.0235))
    private val here = HereWeather(source) { places }

    @Test
    fun aRoundedPointGetsItsForecastAndWhetherItIsAway() {
        val r = here.at("hh", HereCodec.Request(52.21, 0.12)) // Cambridge
        assertEquals(HereCodec.Response.OK, r.state)
        assertTrue(r.away)
        assertEquals(1, asked.size)
        // Only the rounded point goes to Open-Meteo.
        assertTrue(asked.single().contains("latitude=52.21&longitude=0.12&"), asked.single())
        val hours = WeatherCodec.decodeHours(r.hours)
        assertEquals(72, hours.size)
        assertEquals(11, hours.first().tempC)
        assertEquals(7, WeatherCodec.decodeDays(r.days).size)
        // The encoded answer fits the codec's limits, so the device keeps it.
        val back = HereCodec.decodeResponse(HereCodec.encodeResponse(r))
        assertEquals(r.hours, back.hours)
        assertEquals(r.days, back.days)
        assertTrue(back.away)
        // Sandy, 5 km from home: not away.
        assertFalse(here.at("hh", HereCodec.Request(52.13, -0.29)).away)
    }

    @Test
    fun aFailedForecastIsAnAnswerNotAnError() {
        failing = true
        val r = here.at("hh", HereCodec.Request(52.21, 0.12))
        assertEquals(HereCodec.Response.FAILED, r.state)
        assertFalse(r.away)
        assertTrue(r.reason!!.isNotBlank())
        assertFalse("52.21" in r.reason!!) // the point is never echoed
    }

    @Test
    fun withNoKnownPlacesTheDefaultsDecideAway() {
        val bare = HereWeather(source) { emptyList() }
        assertFalse(bare.at("hh", HereCodec.Request(52.09, -0.26)).away)
        assertTrue(bare.at("hh", HereCodec.Request(53.48, -2.24)).away)
        val broken = HereWeather(source) { error("db down") }
        assertTrue(broken.at("hh", HereCodec.Request(53.48, -2.24)).away)
    }

    @Test
    fun keyedDevicesAskAndAFinerPointIsRefused() = testApplication {
        val devices = InMemoryDeviceRegistry()
        val foldSecret = devices.enrol("hh", "fold")
        val foldKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "fold"), it.publicB64) }
        val bare = devices.enrol("hh", "old")
        application { mekaSync(InMemoryServerOpStore(), devices, here = here) }

        val path = "/v1/weather/here"
        val body = HereCodec.encodeRequest(HereCodec.Request(52.21, 0.12))
        val ok = client.post(path) { with(foldKey) { signed(foldSecret, path, body) } }
        assertEquals(HttpStatusCode.OK, ok.status)
        val r = HereCodec.decodeResponse(ok.bodyAsText())
        assertEquals(HereCodec.Response.OK, r.state)
        assertTrue(r.away)

        // A precise point never reaches Open-Meteo: refused, not rounded.
        val fine = HereCodec.encodeRequest(HereCodec.Request(52.2134, 0.1189))
        assertEquals(HttpStatusCode.BadRequest, client.post(path) { with(foldKey) { signed(foldSecret, path, fine) } }.status)
        assertEquals(1, asked.size)

        assertEquals(HttpStatusCode.Unauthorized, client.post(path) { header("Authorization", "Bearer $foldSecret"); setBody(body) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post(path) { header("Authorization", "Bearer $bare"); setBody(body) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.post(path) { header("Authorization", "Publisher github-build"); setBody(body) }.status)
        assertEquals(1, asked.size)
    }

    @Test
    fun withoutWeatherTheRouteIsLeftOut() = testApplication {
        val devices = InMemoryDeviceRegistry()
        val foldSecret = devices.enrol("hh", "fold")
        val foldKey = TestDeviceKey().also { devices.registerKey(DeviceIdentity("hh", "fold"), it.publicB64) }
        application { mekaSync(InMemoryServerOpStore(), devices) }
        val path = "/v1/weather/here"
        val body = HereCodec.encodeRequest(HereCodec.Request(52.21, 0.12))
        assertEquals(HttpStatusCode.NotFound, client.post(path) { with(foldKey) { signed(foldSecret, path, body) } }.status)
    }
}
