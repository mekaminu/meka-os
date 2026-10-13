package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.NotificationHealth
import os.meka.core.domain.NotifyChannelState
import os.meka.core.domain.NotifyTraceEntry
import os.meka.core.domain.NotifyTraceKind
import os.meka.core.domain.PushTestResult
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.wire.NotifyDiagnosticsCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Notifications never seen, slice 2, through the facade: the trace reaches the server and Send a test's push. */
class NotifyDiagnosticsFacadeTest {
    private val now = 1_791_622_800_000L
    private val sent = mutableListOf<NotifyDiagnosticsCodec.Report>()
    private var answer: String? = "sent"
    private var fails = false

    private fun core(api: Boolean) = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(InMemoryServerOpStore())).let { t ->
            if (!api) t else object : SyncTransport by t, NotifyDiagnosticsApi {
                override suspend fun reportNotifyTrace(report: NotifyDiagnosticsCodec.Report): Boolean { sent += report; return true }
                override suspend fun testPush(): String? { if (fails) error("offline"); return answer }
            }
        },
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val health = NotificationHealth(
        allowed = true,
        channels = listOf(NotifyChannelState("meka_heads_up", "Heads-ups", true)),
        trace = List(50) { NotifyTraceEntry(now - 1_000L * (it + 1), NotifyTraceKind.LOOKED, "nothing due $it") },
    )

    @Test
    fun theTraceReachesTheServerCapped() = runTest {
        assertTrue(core(api = true).reportNotifyTrace("SM-F000X", health, "ALL"))
        val r = sent.single()
        assertEquals(now, r.sentAtMs)
        assertEquals("ALL", r.phone)
        assertEquals(NotifyDiagnosticsCodec.TRACE_MAX, r.trace.size)
        assertEquals(NotifyDiagnosticsCodec.Entry(now - 1_000, "LOOKED", "nothing due 0"), r.trace.first())
        assertEquals(listOf(NotifyDiagnosticsCodec.Channel("meka_heads_up", "Heads-ups", true)), r.channels)
    }

    @Test
    fun nothingGoesWithoutTheRouteOrAnEmptyTrace() = runTest {
        assertFalse(core(api = false).reportNotifyTrace("SM-F000X", health, "ALL"))
        assertFalse(core(api = true).reportNotifyTrace("SM-F000X", health.copy(trace = emptyList()), "ALL"))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun theTestPushSaysWhatTheServerSaid() = runTest {
        assertEquals(PushTestResult.SENT, core(api = true).testPush())
        answer = "no_address"
        assertEquals(PushTestResult.NO_ADDRESS, core(api = true).testPush())
        answer = null // an older server without the route
        assertEquals(PushTestResult.UNREACHABLE, core(api = true).testPush())
        fails = true
        assertEquals(PushTestResult.UNREACHABLE, core(api = true).testPush())
        assertEquals(PushTestResult.UNREACHABLE, core(api = false).testPush())
    }
}
