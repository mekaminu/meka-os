package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.TripList
import os.meka.core.domain.TripListRules
import os.meka.core.domain.TripWho
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Trip lists through the facade (slice 1): added and ticked on the Fold, the same on the Mac. */
class TripListsFacadeTest {
    private var now = 1_793_008_800_000L // Mon 26 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun aTripsListsAddedAndTickedOnTheFoldShowOnTheMac() = runTest {
        val fold = core("android")
        val mac = core("mac")
        fold.addTrip("Lisbon 28–31 Oct")
        val tripId = fold.tripsView.value.rows.single().id
        val empty = fold.tripListsView.value.single { it.tripId == tripId }
        assertEquals(TripListRules.LISTS_EMPTY, empty.summary)
        assertEquals("2 days to go", empty.countdown)

        val added = fold.addTripItem(tripId, "pack 2 chargers", TripList.BUY)
        assertEquals(TripList.PACK, added.list)
        assertEquals("Added Chargers × 2 to To pack · Tech", added.line)
        fold.addTripItem(tripId, "passports × 4", TripList.PACK)
        fold.addTripItem(tripId, "sun cream", TripList.BUY)
        fold.addTripItem(tripId, "kids outfits × 10", TripList.PACK)
        assertTrue(fold.addTripItem("nope", "sun cream", TripList.BUY).refused)

        val v = fold.tripListsView.value.single()
        assertEquals("Bought 0 of 1 · packed 0 of 3", v.summary)
        assertEquals(listOf("Documents", "Clothes", "Tech"), v.tab(TripList.PACK).groups.map { it.name })
        assertEquals(listOf("Passports", "Kids outfits", "Chargers"), v.groups(TripList.PACK, TripWho.KIDS).flatMap { g -> g.rows.map { it.text } })

        val pass = v.tab(TripList.PACK).groups.first().rows.single().id
        assertTrue(fold.tickTripItem(pass, true))
        fold.syncNow(); mac.syncNow()
        val onMac = mac.tripListsView.value.single()
        assertEquals("1 of 3", onMac.packed.line)
        assertEquals("All done", onMac.tab(TripList.PACK).groups.first().count)

        // Removed on the Mac, Undo puts it back (ticked), and the Fold sees it.
        val sun = onMac.tab(TripList.BUY).groups.single().rows.single().id
        val removed = assertNotNull(mac.removeTripItem(sun))
        assertEquals("Removed Sun cream", removed.line)
        assertEquals(0, mac.tripListsView.value.single().bought.total)
        assertTrue(mac.undoTripItemRemoval(removed.token))
        mac.syncNow(); fold.syncNow()
        assertEquals(listOf("Sun cream"), fold.tripListsView.value.single().tab(TripList.BUY).groups.single().rows.map { it.text })
    }
}
