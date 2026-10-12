package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.FoldPostureLog
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import os.meka.core.sync.SyncTransport
import os.meka.core.wire.FoldPostureCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Fold's posture changes through the facade (bedside clock, slice 2): the server hears what the phone reported. */
class FoldPostureFacadeTest {
    private val now = 1_791_622_800_000L
    private val sent = mutableListOf<FoldPostureCodec.Report>()

    private fun core(reports: Boolean) = MekaCore(
        householdId = "hh", deviceId = "android", store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(InMemoryServerOpStore())).let { t ->
            if (!reports) t else object : SyncTransport by t, FoldPostureApi {
                override suspend fun reportFoldPostures(report: FoldPostureCodec.Report): Boolean { sent += report; return true }
            }
        },
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private val log = List(25) { FoldPostureLog.Change(now - 1_000L * (it + 1), "change $it") }

    @Test
    fun theLastTwentyChangesReachTheServer() = runTest {
        assertTrue(core(reports = true).reportFoldPostures("SM-F966B", log))
        val r = sent.single()
        assertEquals("SM-F966B", r.model)
        assertEquals(now, r.sentAtMs)
        assertEquals(FoldPostureCodec.CHANGES_MAX, r.changes.size)
        assertEquals(FoldPostureCodec.Change(now - 1_000, "change 0"), r.changes.first())
    }

    @Test
    fun nothingGoesWithoutTheRouteOrWithNothingToSay() = runTest {
        assertFalse(core(reports = false).reportFoldPostures("SM-F966B", log))
        assertFalse(core(reports = true).reportFoldPostures("SM-F966B", emptyList()))
        assertTrue(sent.isEmpty())
    }
}
