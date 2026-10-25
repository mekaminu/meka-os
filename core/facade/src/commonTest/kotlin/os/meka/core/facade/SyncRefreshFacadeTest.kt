package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Taps feel instant, slice 4, through the facade: a sync round that pulls many pages of the other device's changes
 * rebuilds the app's views once, and a round that pulls nothing (every tap's own push) rebuilds them not at all.
 * The pulled changes still show as soon as the round returns.
 */
class SyncRefreshFacadeTest {
    private var now = 1_792_144_800_000L // Fri 16 Oct 2026, 11:00 in London
    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private fun MekaCore.titles(): Set<String> =
        (listOfNotNull(today.value.upNext) + today.value.yourDay + today.value.needsYou.map { it.task }).map { it.title }.toSet()

    @Test
    fun aRoundOfManyPagesIsOneRebuildAndAnEmptyRoundIsNone() = runTest {
        val fold = core("android")
        val mac = core("mac")
        // Enough of the Fold's changes for several pages of the server's pull.
        repeat(300) { fold.addTask("Job $it") }
        fold.syncNow()

        val before = mac.refreshPasses
        assertTrue(mac.syncNow())
        assertEquals(before + 1, mac.refreshPasses, "every page pulled is shown with one rebuild")
        assertTrue(mac.titles().any { it.startsWith("Job ") }, "the pulled tasks show as soon as the round returns")

        // A round with nothing to pull or push rebuilds nothing.
        val idle = mac.refreshPasses
        assertTrue(mac.syncNow())
        assertEquals(idle, mac.refreshPasses)

        // A tap still refreshes at once, and pushing it rebuilds nothing more.
        mac.addTask("Ring the garage")
        val afterTap = mac.refreshPasses
        assertTrue(afterTap > idle)
        assertTrue(mac.syncNow())
        assertEquals(afterTap, mac.refreshPasses)
        assertTrue("Ring the garage" in mac.titles())
    }
}
