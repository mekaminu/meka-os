package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.LiveCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Ada's Live mode, slice 1: the device asks for a session token and reports seconds, never failing. */
class LiveFacadeTest {
    private val meter = LiveCodec.Meter(true, "ok", 40, 1_500)

    private class Server(service: SyncService, private val meter: LiveCodec.Meter) : SyncTransport, LiveApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        var down = false
        val reports = mutableListOf<Triple<String, Long, Long>>()
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun liveStatus(): LiveCodec.Meter? { if (down) throw TransportException("offline"); return meter }
        override suspend fun liveSession(voice: String?): LiveCodec.Session? {
            if (down) throw TransportException("offline")
            return LiveCodec.Session(LiveCodec.Session.READY, meter, "ls_1", "auth_tokens/t", "gemini-3.8-live", "wss://x", voice ?: "Kore", 1, 2)
        }
        override suspend fun liveUsage(sessionId: String, inSeconds: Long, outSeconds: Long): LiveCodec.Usage? {
            if (down) throw TransportException("offline")
            reports += Triple(sessionId, inSeconds, outSeconds)
            return LiveCodec.Usage(true, meter)
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()), meter)

    private fun core(transport: SyncTransport = server) = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { 1_792_632_000_000L },
    )

    @Test
    fun aSessionAndItsSeconds() = runTest {
        val c = core()
        assertEquals(meter, c.liveStatus())
        val s = c.liveSession("Leda")!!
        assertEquals(LiveCodec.Session.READY, s.state)
        assertEquals("Leda", s.voice)
        assertEquals(true, c.liveUsage("ls_1", 90, 30)?.counted)
        assertEquals(listOf(Triple("ls_1", 90L, 30L)), server.reports)
    }

    @Test
    fun offlineOrAnOlderServerIsNullAndNormalAdaAnswers() = runTest {
        server.down = true
        val c = core()
        assertNull(c.liveStatus())
        assertNull(c.liveSession(null))
        assertNull(c.liveUsage("ls_1", 1, 1))
        val plain = object : SyncTransport by os.meka.core.testing.FaultyTransport(SyncService(InMemoryServerOpStore())) {}
        assertNull(core(plain).liveSession(null))
    }

    @Test
    fun theLiveSwitchAndVoiceAreKeptAndTheSettingsReadTheMeter() = runTest {
        // Slice 2.
        val c = core()
        assertEquals(false, c.liveOn())
        assertEquals(os.meka.core.domain.LiveTalkRules.OFF_LINE, c.liveSettings().line)
        assertEquals("£0.40 of £15 this month", c.liveSettings().meterLine)
        c.setLiveOn(true)
        assertEquals(true, c.setLiveVoice("Aoede"))
        assertEquals(false, c.setLiveVoice("Nobody"))
        val on = c.liveSettings()
        assertEquals(true, on.on)
        assertEquals("£0.40 of £15 this month", on.line)
        assertEquals("Aoede", on.voices.single { it.selected }.name)
        server.down = true
        assertEquals(os.meka.core.domain.LiveTalkRules.UNREACHABLE, c.liveSettings().line)
    }
}
