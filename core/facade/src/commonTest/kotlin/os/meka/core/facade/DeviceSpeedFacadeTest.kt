package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.DeviceSpeedRules
import os.meka.core.domain.TurnKind
import os.meka.core.domain.TurnSpeedRules
import os.meka.core.domain.TurnTrace
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.DeviceSpeedCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Needs Meka #43: once a day each device sends its last day's response times, numbers only. */
class DeviceSpeedFacadeTest {
    private var now = 1_791_476_100_000L
    private val hour = 60 * 60_000L

    private class Server(service: SyncService) : SyncTransport, DeviceSpeedApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        val sent = mutableListOf<DeviceSpeedCodec.Report>()
        var down = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun reportDeviceSpeed(report: DeviceSpeedCodec.Report): Boolean {
            if (down) throw TransportException("offline")
            sent += report
            return true
        }
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))
    private val store = InMemoryReplicaStore()

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = store, transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private fun seed(vararg traces: TurnTrace) =
        store.transaction { store.setLocalValue(TurnSpeedRules.KEY, TurnSpeedRules.encode(traces.toList())) }

    @Test
    fun onceADayTheLastDaysTimesGoAsNumbersOnly() = runTest {
        seed(
            TurnTrace(TurnKind.HELLO, now - 2 * hour, toVoiceMs = 400),
            TurnTrace(TurnKind.HELLO, now - hour, toVoiceMs = 800),
            TurnTrace(TurnKind.TALK, now - hour, toVoiceMs = 2_000, toSentMs = 100, toFirstPieceMs = 1_500),
            TurnTrace(TurnKind.TALK, now - 30 * hour, toVoiceMs = 9_000), // older than a day: not counted
        )
        val c = core()
        c.turnEnded()
        assertEquals(listOf(DeviceSpeedCodec.Report("phone", now, 2, 600, 800, 1, 2_000, 2_000)), server.sent)
        // Not again the same day.
        now += hour
        c.turnEnded()
        assertEquals(1, server.sent.size)
        // A day later it goes again, with the Mac's label when the Mac says so.
        now += 20 * hour
        seed(TurnTrace(TurnKind.TALK, now - 60_000, toVoiceMs = 1_000))
        core().turnEnded(device = "Mac")
        assertEquals(DeviceSpeedCodec.Report("Mac", now, 0, null, null, 1, 1_000, 1_000), server.sent.last())
    }

    @Test
    fun offlineItTriesAgainAtTheNextTurnAndNoTurnsSendNothing() = runTest {
        val c = core()
        c.turnEnded()
        assertTrue(server.sent.isEmpty()) // no turns in the last day: nothing to send
        seed(TurnTrace(TurnKind.HELLO, now - 60_000, toVoiceMs = 500))
        server.down = true
        val d = core()
        d.turnEnded()
        assertTrue(server.sent.isEmpty())
        assertEquals(null, store.transaction { store.localValue(DeviceSpeedRules.SENT_KEY) })
        server.down = false
        d.turnEnded()
        assertEquals(1, server.sent.size)
        assertEquals(now.toString(), store.transaction { store.localValue(DeviceSpeedRules.SENT_KEY) })
    }
}
