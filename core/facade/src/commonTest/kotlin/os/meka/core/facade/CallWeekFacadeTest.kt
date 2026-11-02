package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.CallAssistantRules
import os.meka.core.domain.EntityTypes
import os.meka.core.sync.Hlc
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.Op
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Call assistant completeness: the week ahead's "calls taken" line, the same on the Fold and the Mac. Invented numbers. */
class CallWeekFacadeTest {
    private val now = 1_791_450_000_000L // 2026-10-08 09:00 UTC
    private val ops = InMemoryServerOpStore()
    private var seq = 0
    private val service = SyncService(ops)
    private val cores = mutableListOf<MekaCore>()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(service),
        secureRandom = Random(1), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    ).also { cores += it }

    @AfterTest
    fun closeCores() { cores.forEach { it.close() }; cores.clear() }

    /** MEKA's server took a message on [call] [agoMs] before now. */
    private fun serverTakes(call: String, agoMs: Long) {
        val id = CallAssistantRules.heldId("twilio", call)
        for ((f, v) in CallAssistantRules.messageFields("+447700900123", now - agoMs, seconds = 24)) {
            ops.append(Op("s${seq++}", "hh", EntityTypes.HELD_MESSAGE, id, f, v, Hlc(now, seq, "server"), emptyList(), "server"))
        }
    }

    @Test
    fun theWeekAheadCountsTheCallsTheAssistantTookOnBothApps() = runTest {
        val fold = core("android")
        val mac = core("mac")
        assertNull(fold.weekAheadView.value.calls)
        serverTakes("CA1", 60 * 60_000L)
        serverTakes("CA2", 2 * 86_400_000L)
        serverTakes("CA3", 9 * 86_400_000L) // older than 7 days: not counted
        fold.syncNow()
        mac.syncNow()
        val line = "Calls · your call assistant took 2 in the last 7 days"
        assertEquals(line, fold.weekAheadView.value.calls)
        assertEquals(line, mac.weekAheadView.value.calls)
    }
}
