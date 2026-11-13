package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.EntityTypes
import os.meka.core.domain.ParkedSpot
import os.meka.core.domain.SharedSpotFields
import os.meka.core.sync.FieldValue
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.PullRequest
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Find my car on the watch (Needs Meka #35): the Fold's spot reaches the watch, and Drove off clears it everywhere. */
class ParkingShareFacadeTest {
    private var now = 1_791_795_600_000L // Mon 12 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun theFoldsSpotReachesTheWatchAndDroveOffLeavesNothingOnTheServer() = runTest {
        val fold = core("android")
        val watch = core("watch")
        val spot = ParkedSpot("car", 51.5, -0.1, 7.0, now - 30 * 60_000L, "Level 3 · Row F", photoAtMs = now)
        assertEquals(1, fold.shareParkedSpots(listOf(spot)))
        assertEquals(0, fold.shareParkedSpots(listOf(spot)), "unchanged: nothing written")
        fold.syncNow(); watch.syncNow()
        assertEquals(spot.copy(photoAtMs = null), watch.parkedSpotsView.value["car"])
        val row = watch.watchSpots().single()
        assertEquals("PARKED", row.label)
        assertEquals("Level 3 · Row F · parked 09:30 · 30 min ago", row.line)

        // A new spot, then Drove off.
        assertEquals(1, fold.shareParkedSpots(listOf(spot.copy(latitude = 51.6, savedAtMs = now))))
        assertEquals(1, fold.shareParkedSpots(emptyList()))
        fold.syncNow(); watch.syncNow()
        assertTrue(watch.parkedSpotsView.value.isEmpty())
        assertTrue(watch.watchSpots().isEmpty())
        val stored = SyncService(ops).pull(PullRequest("hh", "watch", 0, 1000)).ops.map { it.op }
            .filter { it.entityType == EntityTypes.CONTEXT_MODE && it.field == SharedSpotFields.SPOT }
        assertEquals(3, stored.size)
        assertTrue(stored.all { it.value == FieldValue.Null }, "the server keeps no spot once he drove off")
    }
}
