package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.DueState
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** People, slice 1, through the facade: a promise made on the Fold shows on the Mac, comes due, and Kept on the Mac clears both. */
class PromisesFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()
    private val day = 24 * 3_600_000L

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aPromiseMadeOnTheFoldComesDueOnTheMacAndKeptClearsBoth() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val id = fold.addPromise("Fix Rex's bike", "Rex", 2)
        fold.addWaiting("Refund", "Argos", null)
        assertEquals(listOf("Rex · by Mon 12 Oct"), fold.listsView.value.promised.map { it.meta })
        assertEquals(listOf("Refund"), fold.listsView.value.waiting.map { it.title })

        fold.syncNow(); mac.syncNow()
        assertEquals(listOf("Fix Rex's bike"), mac.listsView.value.promised.map { it.title })
        assertEquals(2, mac.listsView.value.promisesBothWays)

        now += 2 * day
        mac.tick()
        val due = mac.listsView.value.promised.single()
        assertEquals(DueState.DUE, due.state)
        assertEquals("1 promise due", mac.listsView.value.dueLine)

        mac.keptPromise(id)
        mac.syncNow(); fold.syncNow()
        fold.tick()
        assertTrue(fold.listsView.value.promised.isEmpty())
        assertEquals(1, fold.listsView.value.waiting.size)
    }
}
