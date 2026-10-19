package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.ActivityKind
import os.meka.core.domain.DecisionEffect
import os.meka.core.domain.DecisionKind
import os.meka.core.domain.DecisionMove
import os.meka.core.domain.DeviceJoin
import os.meka.core.domain.DeviceJoinRules
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.NeedsYouStackRules
import os.meka.core.sync.Hlc
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.PullRequest
import os.meka.core.sync.PushRequest
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.sync.TransportException
import os.meka.core.wire.DeviceJoinCodec
import os.meka.core.wire.DeviceLinkCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Clean-ups (2026-10-19): a new Mac asking to join shows on the Fold's Needs you, and Allow / Not this one answer it. */
class DeviceJoinFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London
    private val ops = InMemoryServerOpStore()
    private var seq = 0
    private val id = "join" + "0123456789abcdef0123456789abcdef"

    private inner class Server : SyncTransport, DeviceLinkApi {
        val sync = os.meka.core.testing.FaultyTransport(SyncService(ops))
        val answers = mutableListOf<Pair<String, Boolean>>()
        var down = false
        override suspend fun push(request: PushRequest) = sync.push(request)
        override suspend fun pull(request: PullRequest) = sync.pull(request)
        override suspend fun linkWatch(code: String): DeviceLinkCodec.Linked = error("unused")
        override suspend fun linkedWatches(): List<DeviceLinkCodec.Watch> = emptyList()
        override suspend fun unlinkWatch(id: String) = Unit
        override suspend fun answerDeviceJoin(requestId: String, allow: Boolean): String {
            if (down) throw TransportException("offline")
            answers += requestId to allow
            val state = if (allow) DeviceJoinCodec.ALLOWED else DeviceJoinCodec.REFUSED
            serverWrites(ask().copy(state = state, answeredBy = "Fold"))
            return state
        }
    }

    private val server = Server()

    private fun core() = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(), transport = server,
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private fun ask() = DeviceJoin(id, "MacBook Air", DeviceJoinRules.MAC, "482913", now, now + DeviceJoinCodec.ASK_MS, DeviceJoinRules.WAITING)

    /** What the server's enrol route writes. */
    private fun serverWrites(j: DeviceJoin) {
        for ((f, v) in DeviceJoinRules.fields(j)) {
            seq++
            ops.append(Op("s$seq", "hh", EntityTypes.DEVICE_JOIN, j.id, f, v, Hlc(now + seq, 0, "server"), emptyList(), "server"))
        }
    }

    @Test
    fun aNewMacAsksTheFoldAllowsItAndTheCardLeaves() = runTest {
        val c = core()
        serverWrites(ask())
        c.syncNow()
        val top = c.needsYouStack.value.cards.first()
        assertEquals(DecisionKind.DEVICE_JOIN, top.kind)
        assertEquals("A new Mac wants to join", top.title)
        assertEquals("MacBook Air · code 482 913 · check the new Mac shows the same · 10 min left", top.why)
        assertEquals(DecisionEffect.ALLOW_DEVICE, top.effect(DecisionMove.YES))
        assertEquals("→ Allow · ← Not this one · ↑ Later", NeedsYouStackRules.hint(top))

        // Offline: nothing changes, the card stays, the bar says so.
        server.down = true
        assertEquals(DeviceJoinRules.OFFLINE_BAR, c.answerDeviceJoin(id, true))
        assertEquals(id, c.needsYouStack.value.cards.first().id)

        server.down = false
        assertEquals(DeviceJoinRules.ALLOWED_BAR, c.answerDeviceJoin(id, true))
        assertEquals(listOf(id to true), server.answers)
        c.syncNow()
        assertTrue(c.needsYouStack.value.cards.none { it.kind == DecisionKind.DEVICE_JOIN })
        val rows = c.activityView.value.days.flatMap { it.rows }.filter { it.kind == ActivityKind.DEVICE }.map { it.summary }
        assertTrue("Allowed MacBook Air to join" in rows, rows.toString())
    }

    @Test
    fun anAskThatRanOutLeavesWithoutAnAnswer() = runTest {
        val c = core()
        serverWrites(ask())
        c.syncNow()
        assertEquals(1, c.needsYouStack.value.cards.count { it.kind == DecisionKind.DEVICE_JOIN })
        now += DeviceJoinCodec.ASK_MS
        c.syncNow()
        assertTrue(c.needsYouStack.value.cards.none { it.kind == DecisionKind.DEVICE_JOIN })
    }
}
