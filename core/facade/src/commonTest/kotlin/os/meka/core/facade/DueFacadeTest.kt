package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.DecisionKind
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The quality gate's Needs you shots (2026-10-22): a task's due time set and cleared through the facade. */
class DueFacadeTest {
    private val now = 1_792_599_300_000L // Wed 21 Oct 2026, 17:15 in London
    private val hour = 3_600_000L
    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aDueTimeOnTheFoldPutsTheTaskInNeedsYouOnTheMac() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val late = fold.addTask("Renew the parking permit")
        val soon = fold.addTask("Call the garage")
        fold.setDue(late, now - 24 * hour)
        fold.setDue(soon, now + 2 * hour)
        fold.syncNow(); mac.syncNow()
        val kinds = mac.needsYouStack.value.cards.associate { it.taskId to it.kind }
        assertEquals(DecisionKind.OVERDUE, kinds[late])
        assertEquals(DecisionKind.DUE_TODAY, kinds[soon])
        mac.setDue(late, null)
        assertTrue(mac.needsYouStack.value.cards.none { it.taskId == late })
    }
}
