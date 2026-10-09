package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.AskContext
import os.meka.core.domain.DeviceHealth
import os.meka.core.domain.HealthState
import os.meka.core.domain.TalkTurn
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.HealthCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Health screen through the facade (Reliability first, item 3): three server answers and this device's facts. */
class HealthFacadeTest {
    private var now = 1_791_476_100_000L // Thu 8 Oct 2026, 17:15 in London (16:15 UTC)

    private inner class Server(service: SyncService) : SyncTransport, AiApi, AccountsApi, HealthApi {
        private val sync = os.meka.core.testing.FaultyTransport(service)
        var down = false
        var healthAsked = 0
        var push = HealthCodec.Response.PUSH_ON
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun ask(question: String, context: AskContext, history: List<TalkTurn>, voice: Boolean): AskReply = AskReply.Answered("", emptyList())
        override suspend fun aiStatus(): AiStatusReply? {
            if (down) throw TransportException("offline")
            return AiStatusReply("on", null, 120, 2000, "ok")
        }
        override suspend fun householdHealth(): HealthCodec.Response? {
            healthAsked++
            if (down) throw TransportException("offline")
            return HealthCodec.Response(push, calls = true, speech = true, atMs = now)
        }
        override suspend fun startConnect(provider: String, editing: Boolean): ConnectStart = ConnectStart.NotSetUp
        override suspend fun accounts(): List<ConnectedAccount> {
            if (down) throw TransportException("offline")
            return listOf(
                ConnectedAccount("google", "meka@gmail.com", "ok", now - 120_000),
                ConnectedAccount("weather", "home", "ok", now - 600_000),
            )
        }
        override suspend fun stopEditing(provider: String, email: String): List<ConnectedAccount> = accounts()
    }

    private val server = Server(SyncService(InMemoryServerOpStore()))

    private fun core(transport: SyncTransport? = server) = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = transport,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val fold = DeviceHealth(mac = false, batteryExempt = true, notificationAccess = true, lastNotificationMs = 1_791_476_000_000L, callRoleHeld = true, version = "0.1.214")

    @Test
    fun allWellAfterASyncAndTodayAsksTheServerAtMostEveryQuarterHour() = runTest {
        val c = core()
        c.syncNow()
        val v = c.refreshHealth(fold)
        assertEquals("Everything's working", v.summary, v.rows.toString())
        assertEquals("Connected · last synced just now", v.rows.first { it.key == "server" }.line)
        assertEquals("Calendar · Personal", v.rows.first { it.key.startsWith("calendar:google") }.title)
        assertNull(c.healthView.value?.todayLine)
        assertEquals(1, server.healthAsked)

        // Today's open within the quarter hour re-reads only this device: the notification access just went.
        val again = c.refreshHealth(fold.copy(notificationAccess = false), force = false)
        assertEquals(1, server.healthAsked)
        assertEquals("Messages capture · MEKA can't see notifications · messages aren't captured", again.todayLine)
        now += 16 * 60_000L
        c.refreshHealth(fold, force = false)
        assertEquals(2, server.healthAsked)
    }

    @Test
    fun offlineSaysCantReachAndCouldntCheckNeverTicks() = runTest {
        val c = core()
        server.down = true
        val v = c.refreshHealth(fold)
        assertEquals(HealthState.BAD, v.rows.first { it.key == "server" }.state)
        listOf("push", "calendar:", "voice", "ai").forEach { k -> assertEquals(HealthState.UNKNOWN, v.rows.first { it.key == k }.state, k) }
        // Not connected at all.
        val alone = core(transport = null).refreshHealth(DeviceHealth(mac = true))
        assertEquals("This device isn't connected yet", alone.rows.first { it.key == "server" }.line)
    }
}
