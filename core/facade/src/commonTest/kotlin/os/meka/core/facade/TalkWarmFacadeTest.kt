package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.TalkWarmRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The orb speed pass, slice 5: the device tells the server he's about to talk, at most every 20 s, never failing. */
class TalkWarmFacadeTest {
    private var now = 1_791_476_100_000L

    private class Server(service: SyncService) : SyncTransport, TalkWarmApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        var asked = 0
        var down = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun talkWarm(): Boolean {
            asked++
            if (down) throw TransportException("offline")
            return true
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core(transport: SyncTransport = server) = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aTapAsksTheServerToGetReady() = runTest {
        assertTrue(core().warmTalk())
        assertEquals(1, server.asked)
    }

    @Test
    fun againWithinTwentySecondsAsksNothing() = runTest {
        val c = core()
        c.warmTalk()
        now += TalkWarmRules.DEVICE_GAP_MS - 1
        assertFalse(c.warmTalk())
        assertEquals(1, server.asked)
        now += 1
        assertTrue(c.warmTalk())
        assertEquals(2, server.asked)
    }

    @Test
    fun offlineIsQuietlyNothing() = runTest {
        server.down = true
        assertFalse(core().warmTalk())
        assertEquals(1, server.asked)
    }

    @Test
    fun aServerWithoutTheRouteIsNothing() = runTest {
        val plain = object : SyncTransport {
            private val sync = os.meka.core.testing.FaultyTransport(SyncService(InMemoryServerOpStore()))
            override suspend fun push(request: PushRequest) = sync.push(request)
            override suspend fun pull(request: PullRequest) = sync.pull(request)
        }
        assertFalse(core(plain).warmTalk())
    }
}
