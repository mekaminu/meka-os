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

    @Test
    fun aTripsToBuyIsOnTheShoppingListAndATickOnEitherTicksBoth() = runTest {
        val fold = core("android")
        val mac = core("mac")
        fold.addTrip("Lisbon 28–31 Oct")
        val tripId = fold.tripsView.value.rows.single().id
        fold.addTripItem(tripId, "buy sun cream", TripList.PACK)
        val shop = fold.listsView.value.shopping.toBuy.single()
        assertEquals("Sun cream", shop.title)
        assertEquals("For " + fold.tripListsView.value.single().title, shop.meta)
        val row = fold.tripListsView.value.single().tab(TripList.BUY).groups.single().rows.single()
        assertEquals(TripListRules.ON_SHOPPING, row.sub)

        // Got in the shop on the Mac: the trip's item is ticked on both.
        fold.syncNow(); mac.syncNow()
        assertTrue(mac.gotShopping(shop.id))
        assertEquals("1 of 1", mac.tripListsView.value.single().bought.line)
        mac.syncNow(); fold.syncNow()
        assertTrue(fold.tripListsView.value.single().tab(TripList.BUY).groups.single().rows.single().done)
        // Unticked on the trip: back to buy on the shopping list.
        assertTrue(fold.tickTripItem(row.id, false))
        assertEquals(listOf("Sun cream"), fold.listsView.value.shopping.toBuy.map { it.title })
        // Ticked on the trip, then Got cleared: still ticked.
        assertTrue(fold.tickTripItem(row.id, true))
        assertEquals(1, fold.clearGotShopping())
        assertTrue(fold.tripListsView.value.single().tab(TripList.BUY).groups.single().rows.single().done)
    }

    @Test
    fun aNewTripStartsFromTheLastTripsListsAndUndoEmptiesItAgain() = runTest {
        val fold = core("android")
        val mac = core("mac")
        fold.addTrip("Porto 2–5 Oct")
        val porto = fold.tripsView.value.rows.single().id
        fold.addTripItem(porto, "pack 2 chargers", TripList.PACK)
        fold.addTripItem(porto, "buy sun cream", TripList.PACK)
        fold.addTrip("Lisbon 28–31 Oct")
        val lisbon = fold.tripsView.value.rows.first { it.id != porto }.id
        val offer = assertNotNull(fold.tripListsView.value.single { it.tripId == lisbon }.startFrom)
        assertEquals(porto, offer.sourceTripId)
        assertEquals("Copies its 2 things, all unticked", offer.sub)

        val copied = fold.copyTripLists(offer.sourceTripId, lisbon)
        assertEquals(false, copied.refused)
        val v = fold.tripListsView.value.single { it.tripId == lisbon }
        assertEquals(null, v.startFrom)
        assertEquals("Bought 0 of 1 · packed 0 of 1", v.summary)
        assertTrue(fold.copyTripLists(porto, lisbon).refused)
        fold.syncNow(); mac.syncNow()
        assertEquals("Bought 0 of 1 · packed 0 of 1", mac.tripListsView.value.single { it.tripId == lisbon }.summary)

        assertTrue(fold.undoTripListsCopy(copied.token))
        assertEquals(TripListRules.LISTS_EMPTY, fold.tripListsView.value.single { it.tripId == lisbon }.summary)
        assertNotNull(fold.tripListsView.value.single { it.tripId == lisbon }.startFrom)
    }

    @Test
    fun whosGoingAndTheLeaveTimeAreSetOnTheFoldAndShowOnTheMac() = runTest {
        val fold = core("android")
        val mac = core("mac")
        fold.setAboutYou(os.meka.core.domain.AboutField.PARTNER_NAME, "Jo")
        fold.addTrip("Lisbon 28–31 Oct")
        val tripId = fold.tripsView.value.rows.single().id
        val fresh = fold.tripListsView.value.single()
        assertTrue(fresh.needsSetUp)
        assertEquals(listOf("Me", "Jo", "The kids"), fresh.going.map { it.label })

        assertTrue(fold.setTripGoing(tripId, "me", true))
        assertTrue(fold.setTripGoing(tripId, "partner", true))
        assertEquals(false, fold.setTripGoing(tripId, "dog", true))
        assertEquals("Leaving home 06:30 on Wed 28 Oct", fold.setTripLeave(tripId, "6.30am").line)
        assertTrue(fold.setTripLeave(tripId, "soon").refused)
        val v = fold.tripListsView.value.single()
        assertEquals("Wed 28 – Sat 31 Oct · 4 days · 2 people", v.dates)
        assertEquals("2 days to go · leave home 06:30 Wednesday", v.countdown)
        assertEquals(listOf(TripWho.EVERYONE, TripWho.ME), v.whoFilters)

        fold.syncNow(); mac.syncNow()
        val onMac = mac.tripListsView.value.single()
        assertEquals("2 days to go · leave home 06:30 Wednesday", onMac.countdown)
        assertEquals("06:30", onMac.leave)
        assertEquals(listOf(true, true, false), onMac.going.map { it.on })
    }
}
