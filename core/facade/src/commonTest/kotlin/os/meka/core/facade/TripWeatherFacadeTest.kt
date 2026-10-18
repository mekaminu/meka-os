package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.WeatherCodec
import os.meka.core.domain.WeatherFields
import os.meka.core.domain.WeatherHour
import os.meka.core.domain.WeatherStore
import os.meka.core.sync.FieldValue
import os.meka.core.sync.HlcClock
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Travel mode completeness, slice 3: the trip destination's forecast the server writes reaches the brief on both apps. */
class TripWeatherFacadeTest {
    private var now = 1_792_317_600_000L // Sun 18 Oct 2026, 11:00 London
    private val server = InMemoryServerOpStore()
    private val transport = os.meka.core.testing.FaultyTransport(SyncService(server))

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(if (device == "android") 11 else 12), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    /** What the server's weather poll writes for Barcelona (invented readings): dry and clear, 18–24° on Monday. */
    private fun serverWritesBarcelona() {
        val sun = 20744L
        val start = sun * 86_400_000L - 3_600_000L // Sun 18 Oct 00:00 London
        val hours = (0 until 72).map { WeatherHour(start + it * 3_600_000L, 20, 0, 0) }
        val clock = HlcClock("server", { now })
        val fields = linkedMapOf(
            WeatherFields.HOURS to FieldValue.Text(WeatherCodec.encodeHours(hours)),
            WeatherFields.DAYS to FieldValue.Text("2026-10-18=15,20,3,10;2026-10-19=18,24,0,0;2026-10-20=17,22,0,0"),
            WeatherFields.PLACE to FieldValue.Text("Barcelona"),
            WeatherFields.ASKED to FieldValue.Text("Barcelona"),
            WeatherFields.FOUND to FieldValue.Bool(true),
        )
        server.transaction {
            fields.entries.forEachIndexed { i, (k, v) ->
                server.append(Op("srv$i", "hh", EntityTypes.CONTEXT_MODE, WeatherStore.TRIP_ENTITY_ID, k, v, clock.now(), emptyList(), "server"))
            }
        }
    }

    @Test
    fun theBriefSaysTheDestinationsWeatherTheDayBeforeAndWhileAway() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertTrue(!fold.addTrip("Barcelona 19–21 Oct").refused)
        serverWritesBarcelona()
        assertTrue(fold.syncNow()); assertTrue(mac.syncNow())
        for (c in listOf(fold, mac)) {
            c.tick()
            val b = c.briefView.value
            assertEquals("Tomorrow in Barcelona, 18–24°, clear.", b.story.tripTomorrowWeather)
            assertEquals(null, b.story.awayWeather)
        }
        now += 20 * 3_600_000L // Mon 19 Oct, 07:00
        for (c in listOf(fold, mac)) {
            c.tick()
            val b = c.briefView.value
            assertEquals("In Barcelona today, 18–24°, clear.", b.story.awayWeather)
            assertEquals("Barcelona · 18–24°, clear", b.weatherLine)
            val script = os.meka.core.domain.BriefScriptRules.script(b)
            assertTrue("In Barcelona today, 18 to 24 degrees, clear." in script, script)
        }
    }
}
