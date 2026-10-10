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

/** The shopping list through the facade (family sharing, slice 1): Lists' view follows every command and syncs. */
class ShoppingFacadeTest {
    private var now = 1_791_622_800_000L // Sat 10 Oct 2026, 10:00 in London

    private val ops = InMemoryServerOpStore()

    private fun core(device: String) = MekaCore(
        householdId = "hh", deviceId = device, store = InMemoryReplicaStore(),
        transport = os.meka.core.testing.FaultyTransport(SyncService(ops)),
        secureRandom = Random(device.hashCode()), timeZone = { TimeZone.of("Europe/London") }, nowMs = { now },
    )

    @Test
    fun listsShowsTheShoppingListAndTheMacSeesTheFoldsTicks() = runTest {
        val fold = core("android")
        val mac = core("mac")
        val (milk, _) = fold.addShopping("Milk, eggs")
        assertEquals(listOf("Milk", "eggs"), fold.listsView.value.shopping.toBuy.map { it.title })
        assertEquals("2 to buy", fold.listsView.value.shopping.line)
        assertTrue(fold.addShopping(" , ").isEmpty())

        assertTrue(fold.gotShopping(milk))
        assertEquals(listOf("Milk"), fold.listsView.value.shopping.got.map { it.title })
        assertEquals("Got today", fold.listsView.value.shopping.got.single().meta)

        fold.syncNow(); mac.syncNow()
        assertEquals("1 to buy · 1 got", mac.listsView.value.shopping.line)
        assertTrue(mac.putBackShopping(milk))
        assertEquals(0, mac.clearGotShopping()) // nothing under Got now
        mac.syncNow(); fold.syncNow()
        assertEquals(listOf("Milk", "eggs"), fold.listsView.value.shopping.toBuy.map { it.title })

        fold.gotShopping(milk)
        assertEquals(1, fold.clearGotShopping())
        assertTrue(fold.removeShopping(fold.listsView.value.shopping.toBuy.single().id))
        assertEquals("Nothing to buy", fold.listsView.value.shopping.line)
        // Shopping never counts as something due on Lists.
        assertEquals(null, fold.listsView.value.dueLine)
    }
}
