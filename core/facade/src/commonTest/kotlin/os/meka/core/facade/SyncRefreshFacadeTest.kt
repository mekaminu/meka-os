package os.meka.core.facade

import kotlinx.coroutines.async
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

    /** Holds the next pull until [gate] opens (the network's wait), then asks the server. */
    private class GatedTransport(private val inner: os.meka.core.sync.SyncTransport) : os.meka.core.sync.SyncTransport {
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val waiting = kotlinx.coroutines.CompletableDeferred<Unit>()
        override suspend fun push(request: os.meka.core.sync.PushRequest) = inner.push(request)
        override suspend fun pull(request: os.meka.core.sync.PullRequest): os.meka.core.sync.PullResponse {
            gate?.let { waiting.complete(Unit); it.await(); gate = null }
            return inner.pull(request)
        }
    }

    @Test
    fun aTapQueuedWhileTheRoundWaitedGoesFirstAndTheRoundAddsNoRebuild() = runTest {
        val fold = core("android")
        repeat(5) { fold.addTask("Job $it") }
        fold.syncNow()

        val transport = GatedTransport(os.meka.core.testing.FaultyTransport(SyncService(ops)))
        val mac = MekaCore(
            householdId = "hh", deviceId = "mac", store = InMemoryReplicaStore(), transport = transport,
            secureRandom = Random(7), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
        )
        transport.gate = kotlinx.coroutines.CompletableDeferred()
        val round = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async { mac.syncNow() }
        transport.waiting.await()
        val before = mac.refreshPasses
        var tapDoneBeforeRoundShowed = false
        val tapped = kotlinx.coroutines.CompletableDeferred<Unit>()
        // On the core thread: the pull's answer comes back, and a tap queues right behind it.
        mac.onCoreForTest {
            transport.gate!!.complete(Unit)
            mac.launchOnCoreForTest {
                mac.addTask("Ring the garage")
                tapDoneBeforeRoundShowed = !round.isCompleted
                tapped.complete(Unit)
            }
        }
        assertTrue(round.await())
        tapped.await()
        assertTrue(tapDoneBeforeRoundShowed, "the tap ran before the round finished")
        assertEquals(before + 1, mac.refreshPasses, "the tap's rebuild showed the pulled jobs too; the round added none")
        assertTrue(mac.titles().any { it.startsWith("Job ") })
        assertTrue("Ring the garage" in mac.titles())
    }
}
