package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trip lists, slice 1: the add field's reading, the groups, ticking, progress, and the lists on both devices. */
class TripListsTest {
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(0)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val mon = d(10, 26)
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val lFold = TripLists(fold.replica, { "i${n++}" }, { world.clock.nowMs })
    private val lMac = TripLists(mac.replica, { "i${n++}" }, { world.clock.nowMs })

    init { world.clock.nowMs = cal.toEpochMs(mon, 10 * 60) }

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    private val trip = TripItem("t", "Lisbon", d(10, 28), d(11, 1), 0)

    private fun read(text: String, open: TripList = TripList.PACK) = TripListRules.read(text, open)

    @Test
    fun buyAndPackChooseTheListAndTheRestGoesOnTheOpenTab() {
        assertEquals(TripListEntry(TripList.BUY, "Sun cream", "Pharmacy", null, TripWho.EVERYONE), read("buy sun cream"))
        assertEquals(TripListEntry(TripList.PACK, "Chargers", "Tech", 2, TripWho.EVERYONE), read("pack 2 chargers", TripList.BUY))
        assertEquals(TripListEntry(TripList.LEAVE, "Put the bins out", "The house", null, TripWho.EVERYONE), read("put the bins out", TripList.LEAVE))
        assertEquals(TripList.BUY, read("Get snacks for the journey")?.list)
        assertEquals(TripList.LEAVE, read("before we leave: check in online", TripList.BUY)?.list)
        assertEquals("On the day", read("check in online", TripList.LEAVE)?.group)
        assertEquals("Plasters", read("buy plasters for the trip")?.text)
        assertEquals(TripListEntry(TripList.BUY, "Sun cream", "Pharmacy", null, TripWho.EVERYONE), read("add sun cream to my trip list", TripList.BUY))
        assertNull(read("   "))
        assertNull(read("buy"))
        assertNull(read("pack × 3"))
    }

    @Test
    fun aCountBeforeOrAfterIsTheQuantity() {
        assertEquals(5, read("T-shirts × 5")?.qty)
        assertEquals("T-shirts", read("T-shirts × 5")?.text)
        assertEquals(5, read("t-shirts x5")?.qty)
        assertEquals(4, read("four passports")?.qty)
        assertEquals("Passports", read("four passports")?.text)
        assertEquals(2, read("a couple of jumpers")?.qty)
        assertEquals(null, read("a lock")?.qty)
        assertEquals("Lock", read("a lock")?.text)
        assertEquals(null, read("1 hat")?.qty)
        assertEquals(TripListRules.MAX_QTY, read("pack 99 socks")?.qty)
        assertEquals("× 5", TripListRules.qtyLabel(5))
        assertEquals("", TripListRules.qtyLabel(null))
    }

    @Test
    fun packingKnowsWhoseItIs() {
        assertEquals(TripWho.KIDS, read("pack swimwear for the kids")?.who)
        assertEquals("Swimwear", read("pack swimwear for the kids")?.text)
        assertEquals(TripWho.KIDS, read("kids outfits × 10")?.who)
        assertEquals("Clothes", read("kids outfits × 10")?.group)
        assertEquals(TripWho.ME, read("my watch charger")?.who)
        assertEquals("Watch charger", read("my watch charger")?.text)
        assertEquals(TripWho.ME, read("deodorant for me")?.who)
        assertEquals("Kids", read("comfort toys for the kids")?.group)
        assertEquals("Kids", read("Mr Bear for the kids")?.group)
        // Only packing has an owner.
        assertEquals(TripWho.EVERYONE, read("buy calpol for the kids")?.who)
    }

    @Test
    fun eachItemFallsInItsGroupOrAdded() {
        assertEquals("Documents", read("passports × 4")?.group)
        assertEquals("Toiletries", read("toothbrushes and paste")?.group)
        assertEquals("Medicines", read("inhaler")?.group)
        assertEquals("Online", read("EU plug adapters × 2", TripList.BUY)?.group)
        assertEquals("Supermarket", read("water bottles × 4", TripList.BUY)?.group)
        assertEquals(TripListRules.ADDED, read("Kids rain jackets", TripList.BUY)?.group)
        assertEquals("Added Sun cream to To buy · Pharmacy", TripListRules.addedLine(read("buy sun cream")!!))
        assertEquals("Added Chargers × 2 to To pack · Tech", TripListRules.addedLine(read("pack 2 chargers")!!))
        assertEquals("Added Umbrella to To pack", TripListRules.addedLine(read("umbrella")!!))
    }

    @Test
    fun tickedItemsSinkAndTheBarsCount() {
        lFold.add("t", "passports × 4", TripList.PACK, tripExists = true)
        lFold.add("t", "GHIC cards × 4", TripList.PACK, tripExists = true)
        lFold.add("t", "boarding passes", TripList.PACK, tripExists = true)
        lFold.add("t", "phone chargers × 3", TripList.PACK, tripExists = true)
        lFold.add("t", "buy sun cream", TripList.PACK, tripExists = true)
        val pass = lFold.items().first { it.text == "Passports" }.id
        world.clock.nowMs += 60_000
        assertTrue(lFold.tick(pass, true))
        assertFalse(lFold.tick(pass, true))

        val v = TripListRules.view(trip, lFold.items(), mon)
        assertEquals("Lisbon trip", v.title)
        assertEquals("2 days to go", v.countdown)
        assertTrue(v.countdownLit)
        assertEquals("1 of 4", v.packed.line)
        assertEquals(0.25f, v.packed.fraction)
        assertEquals("0 of 1", v.bought.line)
        assertEquals("Bought 0 of 1 · packed 1 of 4", v.summary)
        val docs = v.tab(TripList.PACK).groups.first()
        assertEquals("Documents", docs.name)
        assertEquals("1 of 3", docs.count)
        assertEquals(listOf("GHIC cards", "Boarding passes", "Passports"), docs.rows.map { it.text })
        assertEquals(listOf(false, false, true), docs.rows.map { it.done })
        assertEquals("× 4", docs.rows.first().qty)
        assertEquals(listOf("Documents", "Tech"), v.tab(TripList.PACK).groups.map { it.name })
        assertEquals(3, v.tab(TripList.PACK).left)

        lFold.items().filter { it.list == TripList.PACK && it.group == "Documents" }.forEach { lFold.tick(it.id, true) }
        val all = TripListRules.view(trip, lFold.items(), mon).tab(TripList.PACK).groups.first()
        assertEquals("All done", all.count)
        assertTrue(all.allDone)
        // Unticking brings it back up.
        assertTrue(lFold.tick(pass, false))
        assertEquals("Passports", TripListRules.view(trip, lFold.items(), mon).tab(TripList.PACK).groups.first().rows.first().text)
    }

    @Test
    fun theFilterKeepsEveryonesThingsWithTheirs() {
        lFold.add("t", "passports × 4", TripList.PACK, tripExists = true)
        lFold.add("t", "kids outfits × 10", TripList.PACK, tripExists = true)
        lFold.add("t", "my watch charger", TripList.PACK, tripExists = true)
        val v = TripListRules.view(trip, lFold.items(), mon)
        assertEquals(listOf("Passports", "Kids outfits"), v.groups(TripList.PACK, TripWho.KIDS).flatMap { g -> g.rows.map { it.text } })
        assertEquals(listOf("Passports", "Watch charger"), v.groups(TripList.PACK, TripWho.ME).flatMap { g -> g.rows.map { it.text } })
        assertEquals(3, v.groups(TripList.PACK, TripWho.EVERYONE).sumOf { it.rows.size })
        assertEquals(listOf("Everyone", "Kids", "Me"), v.tab(TripList.PACK).groups.flatMap { g -> g.rows.map { it.sub } })
    }

    @Test
    fun anEmptyTripSaysWhatGoesWhere() {
        val v = TripListRules.view(trip, emptyList(), d(10, 20))
        assertEquals(TripListRules.LISTS_EMPTY, v.summary)
        assertEquals(TripListRules.NOTHING_YET, v.bought.line)
        assertEquals(0f, v.packed.fraction)
        assertEquals("8 days to go", v.countdown)
        assertFalse(v.countdownLit)
        assertEquals(listOf("To buy", "To pack", "Before we leave"), v.tabs.map { it.label })
        assertTrue(v.tabs.all { it.groups.isEmpty() })
        assertEquals("Tomorrow", TripListRules.view(trip, emptyList(), d(10, 27)).countdown)
        assertEquals("Day 2 of 5", TripListRules.view(trip, emptyList(), d(10, 29)).countdown)
    }

    @Test
    fun refusedLinesWriteNothing() {
        assertEquals(TripListAdded(TripListRules.NOT_READ, TripList.BUY, refused = true), lFold.add("t", "  ", TripList.BUY, tripExists = true))
        assertEquals(TripListAdded(TripListRules.GONE, TripList.BUY, refused = true), lFold.add("gone", "sun cream", TripList.BUY, tripExists = false))
        assertTrue(lFold.items().isEmpty())
    }

    @Test
    fun aListAddedAndTickedOnTheFoldShowsOnTheMac() {
        val added = lFold.add("t", "pack 2 chargers", TripList.BUY, tripExists = true)
        assertEquals(TripList.PACK, added.list)
        lFold.add("t", "buy sun cream", TripList.PACK, tripExists = true)
        sync()
        val onMac = lMac.items()
        assertEquals(listOf("Chargers", "Sun cream"), onMac.map { it.text })
        assertEquals(2, onMac.first().qty)
        // Ticked on the Mac, unticked on neither: the Fold shows it ticked.
        lMac.tick(onMac.last().id, true)
        sync()
        assertTrue(lFold.items().first { it.text == "Sun cream" }.done)
        // Removed on the Fold with Undo: back on both, still ticked.
        val removed = lFold.remove(onMac.last().id)!!
        assertEquals("Removed Sun cream", removed.line)
        sync()
        assertEquals(listOf("Chargers"), lMac.items().map { it.text })
        assertTrue(lFold.undoRemove(removed.token))
        assertFalse(lFold.undoRemove(removed.token))
        sync()
        val back = lMac.items().first { it.text == "Sun cream" }
        assertTrue(back.done)
        assertEquals(TripList.BUY, back.list)
    }

    // ---- Slice 2: To buy is on the shopping list too ----

    private val sFold = Shopping(fold.replica, { "s${n++}" }, { world.clock.nowMs }, cal)
    private val sMac = Shopping(mac.replica, { "s${n++}" }, { world.clock.nowMs }, cal)
    private val linkedFold = TripLists(fold.replica, { "i${n++}" }, { world.clock.nowMs }, sFold)
    private val linkedMac = TripLists(mac.replica, { "i${n++}" }, { world.clock.nowMs }, sMac)

    @Test
    fun aToBuyItemIsOnTheShoppingListWithTheTripsName() {
        linkedFold.add("t", "buy plug adapters × 2", TripList.PACK, tripExists = true, tripLabel = "Lisbon trip")
        linkedFold.add("t", "pack 2 chargers", TripList.PACK, tripExists = true, tripLabel = "Lisbon trip")
        val shop = sFold.view().toBuy.single()
        assertEquals("Plug adapters × 2", shop.title)
        assertEquals("For Lisbon trip", shop.meta)
        val item = linkedFold.items().first { it.list == TripList.BUY }
        assertEquals(shop.id, item.shopId)
        assertEquals(TripListRules.ON_SHOPPING, TripListRules.view(trip, linkedFold.items(), mon).tab(TripList.BUY).groups.single().rows.single().sub)
        // Packing never touches the shopping list.
        assertNull(linkedFold.items().first { it.list == TripList.PACK }.shopId)
        assertEquals("Sun cream, factor 50", TripListRules.read("buy sun cream, factor 50", TripList.BUY)?.text)
        assertEquals("Sun cream  factor 50".let(ShoppingRules::tidy), TripListRules.shoppingTitle("Sun cream, factor 50", null))
    }

    @Test
    fun tickingEitherTicksBothOnBothDevices() {
        linkedFold.add("t", "buy sun cream", TripList.BUY, tripExists = true, tripLabel = "Lisbon trip")
        val item = linkedFold.items().single()
        val shopId = item.shopId!!
        // Ticked on the trip: got on the shopping list.
        assertTrue(linkedFold.tick(item.id, true))
        assertTrue(sFold.view().got.any { it.id == shopId })
        // Put back on the shopping list (the Mac, after a sync): the trip's item is unticked there and on the Fold.
        sync()
        assertTrue(sMac.putBack(shopId))
        linkedMac.followShopping(shopId, got = false)
        assertFalse(linkedMac.items().single().done)
        sync()
        assertFalse(linkedFold.items().single().done)
        // Got on the shopping list alone (as his wife's page does): the trip shows it ticked.
        assertTrue(sFold.got(shopId))
        assertTrue(linkedFold.items().single().done)
        assertEquals("1 of 1", TripListRules.view(trip, linkedFold.items(), mon).bought.line)
        // Cleared from Got after following: the tick holds.
        linkedFold.followShopping(shopId, got = true)
        assertEquals(1, sFold.clearGot())
        assertTrue(linkedFold.items().single().done)
    }

    @Test
    fun aNameAlreadyToBuyIsSharedAndStaysWhenTheTripsItemGoes() {
        sFold.add("Milk, Sun cream")
        linkedFold.add("t", "buy sun cream", TripList.BUY, tripExists = true, tripLabel = "Lisbon trip")
        assertEquals(listOf("Milk", "Sun cream"), sFold.view().toBuy.map { it.title })
        val item = linkedFold.items().single()
        linkedFold.remove(item.id)
        assertEquals(listOf("Milk", "Sun cream"), sFold.view().toBuy.map { it.title })
    }

    @Test
    fun removingTakesItOffTheShoppingListAndUndoPutsItBack() {
        linkedFold.add("t", "buy snacks", TripList.BUY, tripExists = true, tripLabel = "Lisbon trip")
        val item = linkedFold.items().single()
        val removed = linkedFold.remove(item.id)!!
        assertTrue(sFold.view().toBuy.isEmpty())
        assertTrue(linkedFold.undoRemove(removed.token))
        val back = linkedFold.items().single()
        val shop = sFold.view().toBuy.single()
        assertEquals("Snacks", shop.title)
        assertEquals("For Lisbon trip", shop.meta)
        assertEquals(shop.id, back.shopId)
        // The new link holds: ticking the copy gets it.
        assertTrue(linkedFold.tick(back.id, true))
        assertTrue(sFold.view().toBuy.isEmpty())
    }
}
