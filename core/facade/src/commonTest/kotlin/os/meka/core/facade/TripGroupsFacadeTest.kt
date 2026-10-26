package os.meka.core.facade

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import os.meka.core.domain.TripList
import os.meka.core.domain.TripWho
import os.meka.core.sync.InMemoryReplicaStore
import os.meka.core.sync.InMemoryServerOpStore
import os.meka.core.sync.SyncService
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Trip lists editing, slice 4a, through the facade: groups moved, renamed, added and deleted on the Fold, the same on the Mac. */
class TripGroupsFacadeTest {
    private var now = 1_793_008_800_000L // Mon 26 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun hisGroupsOnTheFoldShowOnTheMac() = runTest {
        val fold = core("android")
        val mac = core("mac")
        fold.addTrip("Lisbon 28–31 Oct")
        val tripId = fold.tripsView.value.rows.single().id
        fold.addTripItem(tripId, "passports", TripList.PACK)
        fold.addTripItem(tripId, "t-shirts × 5", TripList.PACK)
        val blender = fold.addTripItem(tripId, "blender", TripList.PACK)
        assertEquals("Added Blender to To pack · Kitchen", blender.line)
        val odd = fold.addTripItem(tripId, "Mr Wobbles", TripList.PACK)
        assertEquals(listOf("Documents", "Clothes", "Kitchen"), odd.askGroups)

        assertEquals("Moved Mr Wobbles to Clothes", fold.moveTripItem(odd.id!!, "Clothes")?.line)
        assertFalse(fold.renameTripGroup(tripId, TripList.PACK, "Clothes", "Lily's bag").refused)
        assertFalse(fold.addTripGroup(tripId, TripList.PACK, "Snacks").refused)
        assertTrue(fold.moveTripGroup(tripId, TripList.PACK, "Kitchen", -1))
        val del = fold.deleteTripGroup(tripId, TripList.PACK, "Documents")
        assertEquals("Deleted Documents · 1 thing moved to Other", del.line)
        fold.editTripItem(blender.id!!, "Smoothie blender", 1, TripWho.ME)

        fold.syncNow(); mac.syncNow()
        val onMac = mac.tripListsView.value.single().tab(TripList.PACK)
        assertEquals(listOf("Kitchen", "Lily's bag", "Snacks", "Other"), onMac.groups.map { it.name })
        assertEquals(listOf("T-shirts", "Mr Wobbles"), onMac.groups[1].rows.map { it.text })
        assertEquals("Smoothie blender", onMac.groups[0].rows.single().text)
        assertEquals("Me", onMac.groups[0].rows.single().sub)
        assertEquals(listOf("Kitchen", "Lily's bag", "Snacks", "Other"), onMac.groupNames)

        assertTrue(mac.undoTripGroupDelete(tripId, del.token))
        assertTrue(mac.setTripGroupOrder(tripId, TripList.PACK, listOf("Snacks", "Documents")))
        mac.syncNow(); fold.syncNow()
        assertEquals(listOf("Snacks", "Documents", "Kitchen", "Lily's bag"), fold.tripListsView.value.single().tab(TripList.PACK).groups.map { it.name })
    }
}
