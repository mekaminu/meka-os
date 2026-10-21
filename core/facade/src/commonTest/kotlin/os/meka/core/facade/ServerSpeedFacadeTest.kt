package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.ServerSpeedRules
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.ServerSpeedCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Talking to Ada like a conversation, slice 2: Speed shows the server's own timings, never failing. */
class ServerSpeedFacadeTest {
    private val now = 1_791_476_100_000L

    private class Server(service: SyncService) : SyncTransport, ServerSpeedApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        var turns: List<ServerSpeedCodec.Turn>? = emptyList()
        var down = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun serverSpeed(): List<ServerSpeedCodec.Turn>? {
            if (down) throw TransportException("offline")
            return turns
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun theServersTimingsBecomeSpeedsLines() = runTest {
        server.turns = listOf(ServerSpeedCodec.Turn(now - 60_000, open = false, spoken = true, model = "claude-haiku-4-5", firstTokenMs = 400, firstSentenceMs = 700, clipMs = 1_000, doneMs = 1_500))
        val v = core().serverSpeed()!!
        assertEquals(ServerSpeedRules.TITLE, v.title)
        assertEquals(listOf("Quick look-ups (1, all spoken): first words 0.4 s · first sentence 0.7 s · voice ready 1.0 s · whole answer 1.5 s · claude-haiku-4-5"), v.lines)
        server.turns = emptyList()
        assertEquals(ServerSpeedRules.EMPTY, core().serverSpeed()!!.empty)
    }

    @Test
    fun offlineOrAnOlderServerIsNothing() = runTest {
        server.turns = null
        assertNull(core().serverSpeed())
        server.down = true
        assertNull(core().serverSpeed())
    }
}
