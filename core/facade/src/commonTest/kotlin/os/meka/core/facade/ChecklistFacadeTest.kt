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

/** Checklists in tasks (slice 1): a pasted list on the Fold, ticked as you go on the Mac, reused with Untick all. */
class ChecklistFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    private fun MekaCore.task(id: String) =
        (listOfNotNull(today.value.upNext) + today.value.yourDay + today.value.needsYou.map { it.task }).single { it.id == id }

    @Test
    fun aPastedListTicksAcrossDevicesAndCanBeReused() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val id = fold.addTask("Weekly shop")
        val ids = fold.addSteps(id, "eggs, milk\nbread")
        assertEquals(3, ids.size)
        assertTrue(fold.addSteps(id, " , ").isEmpty())
        fold.syncNow(); mac.syncNow()

        ids.forEach { mac.setStepDone(it, true) }
        val v = mac.checklistView(mac.task(id))
        assertEquals("All 3 ticked", v.line)
        assertTrue(v.offerComplete)
        mac.syncNow(); fold.syncNow()
        assertEquals("All 3 ticked", fold.checklistView(fold.task(id)).line)

        val unticked = fold.untickAllSteps(id)
        assertEquals(ids.toSet(), unticked.toSet())
        assertEquals("3 items", fold.checklistView(fold.task(id)).line)
        fold.tickSteps(unticked) // Undo
        assertEquals("All 3 ticked", fold.checklistView(fold.task(id)).line)

        fold.removeStep(ids[0])
        assertEquals(2, fold.checklistView(fold.task(id)).size)
        fold.restoreStep(ids[0])
        fold.moveStep(id, ids[2], 0)
        fold.syncNow(); mac.syncNow()
        assertEquals(listOf("bread", "eggs", "milk"), mac.checklistView(mac.task(id)).ticked.map { it.text })
    }

    @Test
    fun aDraggedRowSyncsAndShopModeFollowsTheTicks() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val id = fold.addTask("Weekly shop")
        val ids = fold.addSteps(id, "eggs, milk, bread")
        fold.moveStepInGroup(id, ids[2], 0)
        fold.syncNow(); mac.syncNow()
        assertEquals(listOf("bread", "eggs", "milk"), mac.checklistView(mac.task(id)).toTick.map { it.text })

        assertEquals("3 left", fold.shopMode(fold.task(id)).line)
        mac.setStepDone(ids[0], true)
        mac.syncNow(); fold.syncNow()
        val shop = fold.shopMode(fold.task(id))
        assertEquals(listOf("bread", "milk"), shop.toGet.map { it.text })
        assertEquals("2 left · 1 ticked", shop.line)

        fold.addShopping("milk, eggs")
        assertEquals("2 left", fold.shopMode(fold.listsView.value.shopping).line)
    }
}
