package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.AskItemKind
import os.meka.core.domain.TalkTurn
import os.meka.core.domain.WeatherCodec
import os.meka.core.domain.WeatherDay
import os.meka.core.domain.WeatherHour
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.HereCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Where I am now" through the facade (build plan "Places…", item 3): rounded before it leaves, kept in memory only. */
class HereFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London (09:00 UTC)
    private val midnight = 1_791_586_800_000L // Sat 10 Oct 00:00 in London

    private inner class Server(service: SyncService) : SyncTransport, AiApi, HereApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val points = mutableListOf<Pair<Double, Double>>()
        val asked = mutableListOf<Pair<String, AskContext>>()
        var away = true
        var state = HereCodec.Response.OK
        var down = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply {
            asked += question to context
            return AskReply.Answered("Mild.", emptyList())
        }
        override suspend fun hereWeather(lat: Double, lon: Double): HereCodec.Response {
            points += lat to lon
            if (down) throw TransportException("offline")
            if (state != HereCodec.Response.OK) return HereCodec.Response(state)
            val hours = (0 until 48).map { WeatherHour(midnight + it * 3_600_000L, 12, 2, 10) }
            val days = listOf(WeatherDay(20_736, 7, 13, 2, 10), WeatherDay(20_737, 8, 14, 3, 20))
            return HereCodec.Response(HereCodec.Response.OK, WeatherCodec.encodeHours(hours), WeatherCodec.encodeDays(days), away = away)
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun awayTodaySaysNearYouAndOnlyTheRoundedPointIsSent() = runTest {
        val c = core()
        assertNull(c.weatherView.value.nowLine)
        assertTrue(c.hereShouldLocate(on = true, permitted = true, question = null))
        assertFalse(c.hereShouldLocate(on = false, permitted = true, question = null))

        assertTrue(c.hereWeather(52.213456, 0.118912))
        assertEquals(listOf(52.21 to 0.12), server.points)
        assertEquals("Near you · 12° · partly cloudy, dry today", c.weatherView.value.nowLine)
        assertEquals("Near you · 12 degrees · partly cloudy, dry today", c.weatherView.value.nowSpoken)
        // A fresh fix isn't asked for again, even for a question about here.
        assertFalse(c.hereShouldLocate(on = true, permitted = true, question = "weather here?"))

        // Ask hears where Meka is only when the question is about here.
        c.askMeka("is it cold outside?")
        val lines = server.asked.last().second.items.filter { it.kind == AskItemKind.WEATHER }.map { it.line }
        assertTrue("now where Meka is (approximate location): 12° · partly cloudy, dry today" in lines, lines.toString())
        c.askMeka("what's on today?")
        assertTrue(server.asked.last().second.items.none { "where Meka is" in it.line })

        // The switch goes off: the fix is dropped and Today goes back to home's line.
        c.forgetHere()
        assertNull(c.weatherView.value.nowLine)
        assertTrue(c.hereShouldLocate(on = true, permitted = true, question = null))
    }

    @Test
    fun atHomeOrWorkTodayKeepsItsLineButAskStillHearsHere() = runTest {
        val c = core()
        server.away = false
        assertTrue(c.hereWeather(52.0868, -0.2645))
        assertNull(c.weatherView.value.nowLine)
        c.askMeka("what's the weather where I am?")
        assertTrue(server.asked.last().second.items.any { "where Meka is" in it.line })
        // Half an hour on, the fix is stale: Ask doesn't hear it and the app may locate again.
        now += 30 * 60_000L
        c.askMeka("what's the weather where I am?")
        assertTrue(server.asked.last().second.items.none { "where Meka is" in it.line })
        assertTrue(c.hereShouldLocate(on = true, permitted = true, question = "weather here?"))
    }

    @Test
    fun nothingOddIsSentAndAMissingAnswerIsJustFalse() = runTest {
        val c = core()
        assertFalse(c.hereWeather(Double.NaN, 0.0))
        assertFalse(c.hereWeather(95.0, 0.0))
        assertTrue(server.points.isEmpty())
        server.state = HereCodec.Response.OFF
        assertFalse(c.hereWeather(52.21, 0.12))
        server.state = HereCodec.Response.OK
        server.down = true
        assertFalse(c.hereWeather(52.21, 0.12))
        assertNull(c.weatherView.value.nowLine)
        // Without a server there's nothing to ask.
        val offline = MekaCore("hh", "android", InMemoryReplicaStore(), null, Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now })
        assertFalse(offline.hereWeather(52.21, 0.12))
        assertEquals("Off · the weather is home's and work's", c.hereSetting(on = false, permitted = false).statusLine)
    }
}
