package os.meka.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import os.meka.backend.integrations.GoogleMapsKey
import os.meka.backend.integrations.GoogleRoutes
import os.meka.backend.integrations.InMemoryIntegrationStore
import os.meka.backend.integrations.Integrations
import os.meka.backend.integrations.TokenCipher
import os.meka.backend.integrations.WeatherLocation
import os.meka.backend.integrations.WeatherProvider
import os.meka.core.domain.CivilDate
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.EventActions
import os.meka.core.domain.EventFields
import os.meka.core.domain.FootballRules
import os.meka.core.domain.LocalCalendar
import os.meka.core.domain.MekaSchema
import os.meka.core.domain.Tasks
import os.meka.core.domain.TravelRules
import os.meka.core.domain.WeatherForecast
import os.meka.core.domain.CalendarEvents
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Weekend football, slice 2b: drive times to the kids' football from Google's Routes API, with traffic. */
class TravelTimesTest {
    private val hour = 3_600_000L
    private val bst = LocalCalendar.fixedOffset(hour)
    private val thu = CivilDate.toEpochDay(2026, 10, 8)
    private fun at(day: Long, h: Int, m: Int = 0) = bst.toEpochMs(day, h * 60 + m)
    private var now = at(thu, 12)

    private val noCipher = object : TokenCipher {
        override fun encrypt(plain: ByteArray, context: Map<String, String>) = plain
        override fun decrypt(cipher: ByteArray, context: Map<String, String>) = cipher
    }

    private val home = WeatherLocation("Biggleswade", 52.09, -0.26)
    private val weather = object : WeatherProvider {
        override val id = "weather"
        override val label = "Open-Meteo · Biggleswade"
        override val home = this@TravelTimesTest.home
        override fun forecast(at: WeatherLocation) = WeatherForecast(at.name, emptyList(), emptyList())
        override fun locate(name: String): WeatherLocation? = null
    }

    @Test
    fun theRequestCarriesHomesCoordinatesAndThePlaceOnlyAndTheAnswerIsMinutes() {
        val body = Json.parseToJsonElement(GoogleRoutes.request(52.09, -0.26, "Bury Field, Biggleswade", at(thu + 2, 9, 5))).jsonObject
        assertEquals(setOf("origin", "destination", "travelMode", "routingPreference", "departureTime", "regionCode"), body.keys)
        assertEquals("""{"address":"Bury Field, Biggleswade"}""", body["destination"].toString())
        assertEquals("""{"location":{"latLng":{"latitude":52.09,"longitude":-0.26}}}""", body["origin"].toString())
        assertEquals("TRAFFIC_AWARE", body["routingPreference"]!!.jsonPrimitive.content)
        assertEquals("2026-10-10T08:05:00Z", body["departureTime"]!!.jsonPrimitive.content)

        assertEquals(26, GoogleRoutes.parse("""{"routes":[{"duration":"1534s"}]}"""))
        assertEquals(1, GoogleRoutes.parse("""{"routes":[{"duration":"0s"}]}"""))
        assertNull(GoogleRoutes.parse("{}"))
        assertNull(GoogleRoutes.parse("""{"routes":[{"duration":"20000s"}]}""")) // over 3 hours: not a football trip
        assertNull(GoogleRoutes.parse("""{"routes":[{"duration":1534}]}"""))
        assertNull(GoogleRoutes.parse("<html>"))

        assertNull(GoogleMapsKey.parse("{}"))
        assertNull(GoogleMapsKey.parse("""{"api_key":""}"""))
        assertNull(GoogleMapsKey.parse("""{"api_key":"has spaces in it which is wrong"}"""))
        assertEquals("AIzaSyA-0123456789abcdefghijklmnopqrstu", GoogleMapsKey.parse("""{"api_key":" AIzaSyA-0123456789abcdefghijklmnopqrstu "}"""))

        // The key goes in a header, the field mask asks for the duration only, and a refusal is a failure.
        var headers = emptyMap<String, String>()
        val routes = GoogleRoutes({ "AIzaKEY-123456789012345678901" }, { url, h, _ ->
            assertEquals(GoogleRoutes.URL, url); headers = h; 200 to """{"routes":[{"duration":"1500s"}]}"""
        })
        assertEquals(25, routes.driveMinutes(52.09, -0.26, "Bury Field", now))
        assertEquals("routes.duration", headers["X-Goog-FieldMask"])
        assertEquals("AIzaKEY-123456789012345678901", headers["X-Goog-Api-Key"])
        assertTrue(runCatching { GoogleRoutes({ "k".repeat(30) }, { _, _, _ -> 403 to "{}" }).driveMinutes(0.0, 0.0, "x", now) }.isFailure)
        assertFalse(GoogleRoutes({ null }, { _, _, _ -> error("never asked") }).configured())
    }

    @Test
    fun fixturesGetTheirDriveOnTheDevicesAndWithoutTheKeyNothingIsSent() {
        val ops = InMemoryServerOpStore()
        val store = InMemoryIntegrationStore(knownHouseholds = listOf("home"))
        var key: String? = null
        val asked = mutableListOf<Triple<Double, String, Long>>()
        var drive = 1500
        val routes = GoogleRoutes({ key }, { _, _, body ->
            val o = Json.parseToJsonElement(body).jsonObject
            asked += Triple(o["origin"].toString().let { if ("52.09" in it) 52.09 else 0.0 }, o["destination"]!!.jsonObject["address"]!!.jsonPrimitive.content,
                java.time.Instant.parse(o["departureTime"]!!.jsonPrimitive.content).toEpochMilli())
            200 to """{"routes":[{"duration":"${drive}s"}]}"""
        })
        val woken = mutableListOf<String>()
        val integrations = Integrations(
            store, ops, emptyMap(), { null }, noCipher, "https://meka.example", { now },
            weather = mapOf(weather.id to weather), travel = mapOf(routes.id to routes), calendar = bst, onChanged = { woken += it },
        )

        // A device's replica stands in for the calendar mirror: Saturday's U7s at Bury Field, a dentist, a fixture with no place.
        val r = Replica("home", "fold", HlcClock("fold", { now }), InMemoryReplicaStore(), MekaSchema) { "d" + System.nanoTime() }
        val mirror = Replica("home", "mirror", HlcClock("mirror", { now }), InMemoryReplicaStore(), MekaSchema) { "m" + System.nanoTime() }
        fun event(id: String, title: String, start: Long, place: String?) {
            val fields = linkedMapOf<String, FieldValue>(
                EventFields.TITLE to FieldValue.Text(title), EventFields.START_AT to FieldValue.Int64(start),
                EventFields.END_AT to FieldValue.Int64(start + hour), EventFields.ALL_DAY to FieldValue.Bool(false),
                EventFields.PROVIDER to FieldValue.Text("google"), EventFields.ACCOUNT to FieldValue.Text("meka@gmail.com"),
                EventFields.CALENDAR to FieldValue.Text("Personal"),
            )
            place?.let { fields[EventFields.LOCATION] = FieldValue.Text(it) }
            ops.transaction { mirror.commitLocal(EntityTypes.EVENT, id, fields).forEach { ops.append(it) } }
        }
        event("ev1", "BUFC U7s v Arlesey", at(thu + 2, 10), "Bury Field, Biggleswade")
        event("ev2", "Dentist", at(thu + 2, 14), "High St Surgery")
        event("ev3", "SJFC training", at(thu + 1, 18), null)

        // No key: the feed is off, nothing is asked.
        integrations.syncAll()
        assertEquals(TravelRules.STATUS_OFF, store.accounts("home").single { it.provider == TravelRules.PROVIDER }.status)
        assertEquals(emptyList(), asked)

        // Meka pastes the key: the fixture is looked up once, from home's coordinates to its place only.
        key = "AIzaKEY-123456789012345678901"
        integrations.syncAll()
        assertEquals(listOf(Triple(52.09, "Bury Field, Biggleswade", at(thu + 2, 9, 5))), asked)
        assertEquals("ok", store.accounts("home").single { it.provider == TravelRules.PROVIDER }.status)
        assertEquals(listOf("home"), woken)

        // The Fold reads it and the detail offers "Leave by 09:10 · 25 min drive".
        var cursor = 0L
        fun pull() { val page = ops.after("home", cursor, 10_000); r.applyRemoteBatch(page.map { it.op }); page.lastOrNull()?.let { cursor = it.seq } }
        pull()
        val tasks = Tasks(r, { "t" + System.nanoTime() }, { now })
        val marks = EventActions(r, tasks, { now }, bst).marks()
        val e = CalendarEvents(r).all().single { it.id == "ev1" }
        assertEquals("Leave by 09:10 · 25 min drive", FootballRules.leaveOffer(e, marks, now, bst)?.label)

        // Not again within the day; again from 18:00 on Friday, with the drive's own departure.
        now = at(thu, 20)
        integrations.syncAll()
        assertEquals(1, asked.size)
        now = at(thu + 1, 18, 5)
        drive = 1800
        integrations.syncAll()
        assertEquals(2, asked.size)
        assertEquals(at(thu + 2, 9, 10), asked.last().third)
        pull()
        assertEquals("Leave by 09:05 · 30 min drive", FootballRules.leaveOffer(e, EventActions(r, tasks, { now }, bst).marks(), now, bst)?.label)
        // And an hour before leaving on the day.
        now = at(thu + 2, 8, 6)
        integrations.syncAll()
        assertEquals(3, asked.size)
        assertTrue(r.conflicts(EntityTypes.TRAVEL_TIME).isEmpty())

        // Google failing is the feed's error, tried again at the next pass; the drive already kept stays.
        val failing = Integrations(
            store, ops, emptyMap(), { null }, noCipher, "https://meka.example", { now },
            weather = mapOf(weather.id to weather), calendar = bst,
            travel = mapOf(TravelRules.PROVIDER to GoogleRoutes({ key }, { _, _, _ -> 500 to "{}" })),
        )
        now = at(thu + 2, 8, 30)
        event("ev4", "BUFC U10s v Potton", at(thu + 3, 11), "Potton Rec")
        failing.syncAll()
        assertEquals("error", store.accounts("home").single { it.provider == TravelRules.PROVIDER }.status)
    }
}
