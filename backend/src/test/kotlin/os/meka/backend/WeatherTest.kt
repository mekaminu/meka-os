package os.meka.backend

import os.meka.backend.integrations.InMemoryIntegrationStore
import os.meka.backend.integrations.Integrations
import os.meka.backend.integrations.OpenMeteoWeather
import os.meka.backend.integrations.TokenCipher
import os.meka.core.domain.CivilDate
import os.meka.core.domain.LocalCalendar
import os.meka.core.domain.MekaSchema
import os.meka.core.domain.WeatherRules
import os.meka.core.domain.WeatherStore
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Replica
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WeatherTest {
    // Friday 9 October 2026, 08:30 BST (07:30Z).
    private var now = 1_791_531_000_000L
    private val midnight = 1_791_500_400L // Fri 9 Oct 00:00 BST, in Unix seconds (Open-Meteo's timeformat=unixtime)
    private val day = 86_400L

    /**
     * The shape of Open-Meteo's /v1/forecast answer (fields we read only), as saved from a real response with
     * timezone=Europe/London and timeformat=unixtime: hourly from local midnight, daily at local midnights.
     */
    private fun sample(rainFrom: Int = 16, hourCount: Int = 168, warm: Double = 14.4) = """
        {"latitude":52.08,"longitude":-0.26,"generationtime_ms":0.1,"utc_offset_seconds":3600,"timezone":"Europe/London",
         "timezone_abbreviation":"GMT+1","elevation":36.0,
         "hourly_units":{"time":"unixtime","temperature_2m":"°C","weather_code":"wmo code","precipitation_probability":"%"},
         "hourly":{
           "time":[${(0 until hourCount).joinToString(",") { (midnight + it * 3600L).toString() }}],
           "temperature_2m":[${(0 until hourCount).joinToString(",") { if (it == 5) "null" else "$warm" }}],
           "weather_code":[${(0 until hourCount).joinToString(",") { if (it in rainFrom until rainFrom + 3) "61" else "2" }}],
           "precipitation_probability":[${(0 until hourCount).joinToString(",") { if (it in rainFrom until rainFrom + 3) "72" else "8" }}]
         },
         "daily_units":{"time":"unixtime"},
         "daily":{
           "time":[${(0 until 7).joinToString(",") { (midnight + it * day).toString() }}],
           "weather_code":[61,61,3,3,2,1,0],
           "temperature_2m_max":[15.2,14.6,13.0,13.4,12.9,14.0,15.1],
           "temperature_2m_min":[8.1,8.6,7.0,6.5,5.2,6.8,7.7],
           "precipitation_probability_max":[72,81,12,10,5,3,0]
         }}"""

    private val noCipher = object : TokenCipher {
        override fun encrypt(plain: ByteArray, context: Map<String, String>) = plain
        override fun decrypt(cipher: ByteArray, context: Map<String, String>) = cipher
    }
    private val bst = LocalCalendar.fixedOffset(3_600_000L)

    @Test
    fun parsesOpenMeteosForecastForHome() {
        var asked = ""
        val source = OpenMeteoWeather({ url -> asked = url; sample() }, { now })
        val f = source.forecast()
        assertTrue(asked.startsWith("https://api.open-meteo.com/v1/forecast?latitude=52.0868&longitude=-0.2645&"), asked)
        assertTrue("timezone=Europe%2FLondon" in asked && "timeformat=unixtime" in asked)
        assertEquals("Open-Meteo · Biggleswade", source.label)
        assertEquals("Biggleswade", f.place)
        // From local midnight, 72 hours one after another (the one without a temperature borrows its neighbour's);
        // rounded readings.
        assertEquals(midnight * 1000, f.hours.first().startMs)
        assertEquals(72, f.hours.size)
        assertEquals(14, f.hours[5].tempC)
        assertEquals(72, os.meka.core.domain.WeatherCodec.decodeHours(os.meka.core.domain.WeatherCodec.encodeHours(f.hours)).size)
        assertEquals(14, f.hours.first().tempC)
        assertEquals(70, f.hours.first { it.code == 61 }.rainChance)
        assertEquals(10, f.hours.first().rainChance)
        assertEquals(7, f.days.size)
        assertEquals(CivilDate.toEpochDay(2026, 10, 9), f.days.first().epochDay)
        assertEquals(8 to 15, f.days.first().minC to f.days.first().maxC)
        assertEquals("14° · light rain from 16:00", WeatherRules.nowLine(f, now, bst))
        assertEquals("Tomorrow 9–15°, mostly dry", WeatherRules.tomorrowLine(f, CivilDate.toEpochDay(2026, 10, 10), bst))
        // Hours before today (a late answer after midnight) aren't kept.
        now += 86_400_000L
        assertEquals((midnight + day) * 1000, OpenMeteoWeather({ sample() }, { now }).forecast().hours.first().startMs)
        assertEquals(6, OpenMeteoWeather({ sample() }, { now }).forecast().days.size)
    }

    @Test
    fun anUnreadableForecastIsAFaultNotNoWeather() {
        assertFailsWith<Exception> { OpenMeteoWeather({ "<html>down</html>" }, { now }).forecast() }
        assertFailsWith<Exception> { OpenMeteoWeather({ """{"daily":{}}""" }, { now }).forecast() }
        assertFailsWith<Exception> { OpenMeteoWeather({ sample(hourCount = 0) }, { now }).forecast() }
    }

    @Test
    fun theForecastReachesDevicesEveryHalfHourOnlyWhenItChanged() {
        var fetches = 0
        var body = sample()
        val source = OpenMeteoWeather({ fetches++; body }, { now })
        val store = InMemoryIntegrationStore(knownHouseholds = listOf("home"))
        val ops = InMemoryServerOpStore()
        val woken = mutableListOf<String>()
        val integrations = Integrations(
            store, ops, emptyMap(), { null }, noCipher, "https://meka.example", { now },
            weather = mapOf(source.id to source), onChanged = { woken += it },
        )
        integrations.syncAll()
        assertEquals(listOf("Open-Meteo · Biggleswade"), store.accounts("home").map { it.email })
        assertEquals("weather", store.accounts("home").single().provider)

        val r = Replica("home", "fold", HlcClock("fold", { now }), InMemoryReplicaStore(), MekaSchema) { "d" + System.nanoTime() }
        var cursor = 0L
        fun pull() { val page = ops.after("home", cursor, 10_000); r.applyRemoteBatch(page.map { it.op }); page.lastOrNull()?.let { cursor = it.seq } }
        pull()
        assertEquals("14° · light rain from 16:00", WeatherRules.nowLine(WeatherStore(r).forecast(), now, bst))
        val opsAfterFirst = ops.after("home", 0, 10_000).size
        assertEquals(3, opsAfterFirst) // hours, days, place

        // Within the half hour: not fetched again.
        now += 10 * 60_000L
        integrations.syncAll()
        assertEquals(1, fetches)

        // Half an hour on, a wobble under the rounding: fetched, nothing written.
        body = sample(warm = 14.3)
        now += 25 * 60_000L
        integrations.syncAll()
        assertEquals(2, fetches)
        assertEquals(opsAfterFirst, ops.after("home", 0, 10_000).size)

        // The rain moves to 18:00: one op (the hours), chained on the last, so the devices never see a conflict.
        body = sample(rainFrom = 18)
        now += 35 * 60_000L
        integrations.syncAll()
        assertEquals(opsAfterFirst + 1, ops.after("home", 0, 10_000).size)
        pull()
        assertEquals("14° · light rain from 18:00", WeatherRules.nowLine(WeatherStore(r).forecast(), now, bst))
        assertTrue(r.conflicts(os.meka.core.domain.EntityTypes.CONTEXT_MODE).isEmpty())
        // Weather wakes nobody: the devices pick it up at their next sync.
        assertTrue(woken.isEmpty())

        // The source failing leaves the forecast as it was and is retried at the next poll.
        body = "<html>down</html>"
        now += 35 * 60_000L
        runCatching { integrations.syncAll() }
        assertEquals("error", store.accounts("home").single().status)
        assertEquals("14° · light rain from 18:00", WeatherRules.nowLine(WeatherStore(r).forecast(), now - 70 * 60_000L, bst))
        val before = fetches
        integrations.syncAll()
        assertEquals(before + 1, fetches)
    }

    /** Open-Meteo's /v1/search answer (fields we read): Bedford, Texas first, then Bedfordshire's. */
    private val bedford = """
        {"results":[
          {"id":4673353,"name":"Bedford","latitude":32.84402,"longitude":-97.14307,"country_code":"US","admin1":"Texas"},
          {"id":2656192,"name":"Bedford","latitude":52.13459,"longitude":-0.46632,"country_code":"GB","admin1":"England"}
        ],"generationtime_ms":0.7}"""

    @Test
    fun aTownNameIsLookedUpOncePreferringGreatBritain() {
        val asked = mutableListOf<String>()
        val source = OpenMeteoWeather({ url -> asked += url; if ("geocoding" in url) (if ("Xyzzy" in url) """{"generationtime_ms":0.2}""" else bedford) else sample() }, { now })
        val at = source.locate("Bedford")!!
        assertEquals("Bedford", at.name)
        assertEquals(52.13459, at.latitude)
        assertEquals("https://geocoding-api.open-meteo.com/v1/search?name=Bedford&count=10&language=en&format=json", asked.single())
        // A name is only sent once; nothing found is remembered too.
        source.locate("bedford ")
        assertEquals(1, asked.size)
        assertEquals(null, source.locate("Xyzzy"))
        assertEquals(null, source.locate("xyzzy"))
        assertEquals(2, asked.size)
        // Only the name travels, encoded.
        source.locate("St Neots")
        assertTrue(asked.last().contains("name=St+Neots&"), asked.last())
        // The forecast for a place is asked for at its coordinates and named after it.
        val f = source.forecast(at)
        assertTrue(asked.last().startsWith("https://api.open-meteo.com/v1/forecast?latitude=52.13459&longitude=-0.46632&"), asked.last())
        assertEquals("Bedford", f.place)
        assertEquals("Biggleswade", source.forecast().place)
        // Junk from the network finds nothing rather than a broken place.
        assertEquals(null, source.parseLocation("""{"results":[{"name":"Nowhere","latitude":"x"}]}"""))
    }

    @Test
    fun aPlaceSetOnADeviceIsFollowedAtTheNextPoll() {
        val forecasts = mutableListOf<String>()
        val source = OpenMeteoWeather({ url ->
            when {
                "geocoding" in url -> if ("Xyzzy" in url) "{}" else bedford
                else -> { forecasts += url.substringAfter("latitude=").substringBefore("&"); sample() }
            }
        }, { now })
        val store = InMemoryIntegrationStore(knownHouseholds = listOf("home"))
        val ops = InMemoryServerOpStore()
        val integrations = Integrations(store, ops, emptyMap(), { null }, noCipher, "https://meka.example", { now }, weather = mapOf(source.id to source))
        val r = Replica("home", "fold", HlcClock("fold", { now }), InMemoryReplicaStore(), MekaSchema) { "d" + System.nanoTime() }
        var cursor = 0L
        fun pull() { val page = ops.after("home", cursor, 10_000); r.applyRemoteBatch(page.map { it.op }); page.lastOrNull()?.let { cursor = it.seq } }
        val deviceClock = HlcClock("fold", { now })
        var n = 0
        fun setPlace(name: String?) = ops.transaction {
            ops.append(os.meka.core.sync.Op(
                "fold${n++}", "home", os.meka.core.domain.EntityTypes.CONTEXT_MODE, os.meka.core.domain.WeatherPlaceStore.ENTITY_ID,
                os.meka.core.domain.WeatherPlaceFields.NAME, name?.let { os.meka.core.sync.FieldValue.Text(it) } ?: os.meka.core.sync.FieldValue.Null,
                deviceClock.now(), emptyList(), "fold",
            ))
        }

        // Home by default: nothing extra written (hours, days, place).
        integrations.syncAll()
        assertEquals(3, ops.after("home", 0, 10_000).size)
        assertEquals(listOf("52.0868"), forecasts)

        // Meka types Bedford: followed at the next poll, inside the half hour.
        now += 5 * 60_000L
        setPlace("Bedford")
        integrations.syncAll()
        assertEquals(listOf("52.0868", "52.13459"), forecasts)
        pull()
        val f = WeatherStore(r).forecast()
        assertEquals("Bedford", f.place)
        assertEquals("Bedford", f.asked)
        assertTrue(f.found)
        assertEquals("Forecast for Bedford · from Open-Meteo", os.meka.core.domain.WeatherPlaceRules.view("Bedford", f).line)
        // Then back on the half-hour rhythm.
        now += 5 * 60_000L
        integrations.syncAll()
        assertEquals(2, forecasts.size)

        // A name that can't be found: home's forecast, said so.
        now += 5 * 60_000L
        setPlace("Xyzzy")
        integrations.syncAll()
        assertEquals("52.0868", forecasts.last())
        pull()
        val missing = WeatherStore(r).forecast()
        assertEquals("Biggleswade", missing.place)
        assertEquals("Xyzzy", missing.asked)
        assertTrue(!missing.found)
        assertEquals("ok", store.accounts("home").single().status)

        // Back home: asked and found cleared, home's forecast.
        now += 5 * 60_000L
        setPlace(null)
        integrations.syncAll()
        pull()
        val back = WeatherStore(r).forecast()
        assertEquals(null, back.asked)
        assertTrue(back.found)
        assertEquals(os.meka.core.domain.WeatherPlaceView.HOME, os.meka.core.domain.WeatherPlaceRules.view(null, back))
        assertTrue(r.conflicts(os.meka.core.domain.EntityTypes.CONTEXT_MODE).isEmpty())
    }
}
