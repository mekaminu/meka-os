package os.meka.core.domain

import os.meka.core.sync.fv
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Trip lists editing, slice 4a (Meka 2026-10-26: "doesn't allow me to move an item from one group header to another, and
 * can't rename a header to my liking"): moving things between groups, renaming, adding, deleting and reordering groups,
 * the item sheet, smart add into his own groups, and all of it on both devices.
 */
class TripGroupsTest {
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(0)
    private fun d(m: Int, day: Int, y: Int = 2026) = CivilDate.toEpochDay(y, m, day)
    private val mon = d(10, 26)
    private var n = 0
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private val sFold = Shopping(fold.replica, { "s${n++}" }, { world.clock.nowMs }, cal)
    private val lFold = TripLists(fold.replica, { "i${n++}" }, { world.clock.nowMs }, sFold)
    private val lMac = TripLists(mac.replica, { "i${n++}" }, { world.clock.nowMs })

    init {
        world.clock.nowMs = cal.toEpochMs(mon, 10 * 60)
        fold.replica.commitLocal(EntityTypes.TRIP, "t", mapOf(
            TripFields.NAME to "Lisbon".fv(), TripFields.START to d(10, 28).fv(), TripFields.END to d(11, 1).fv(), TripFields.ADDED_AT to 0L.fv(),
        ))
        fold.replica.commitLocal(EntityTypes.TRIP, "r", mapOf(
            TripFields.NAME to "Rome".fv(), TripFields.START to d(12, 20).fv(), TripFields.END to d(12, 23).fv(), TripFields.ADDED_AT to 0L.fv(),
        ))
    }

    private fun sync() { fold.sync(); mac.sync(); fold.sync() }

    private fun trips(r: os.meka.core.sync.Replica = fold.replica): List<TripItem> =
        TripRules.items(r.entities(EntityTypes.TRIP).associate { it.ref.entityId to it.fields }, emptyMap())

    private fun trip(r: os.meka.core.sync.Replica = fold.replica) = trips(r).first { it.id == "t" }

    private fun add(text: String, l: TripLists = lFold, r: os.meka.core.sync.Replica = fold.replica): TripListAdded =
        l.add("t", text, TripList.PACK, tripExists = true, tripLabel = "Lisbon trip", stored = trip(r).groups)

    private fun pack(l: TripLists = lFold, r: os.meka.core.sync.Replica = fold.replica): List<TripListGroup> =
        TripListRules.view(trip(r), l.items(), mon, trips(r)).tab(TripList.PACK).groups

    private fun names(l: TripLists = lFold, r: os.meka.core.sync.Replica = fold.replica) = pack(l, r).map { it.name }

    private fun id(text: String, l: TripLists = lFold) = l.items().first { it.text == text }.id

    @Test
    fun anItemMovesToAnotherGroupOrANewOneOnBothDevices() {
        add("passports × 4"); add("phone chargers"); add("umbrella")
        assertEquals(listOf("Documents", "Tech", "Other"), names())
        assertEquals("Moved Umbrella to Documents", lFold.moveItem(id("Umbrella"), "documents")?.line)
        assertEquals(listOf("Documents", "Tech"), names())
        assertEquals(listOf("Passports", "Umbrella"), pack().first().rows.map { it.text })
        // A new name makes the group, before Other.
        assertEquals("Moved Phone chargers to Bag 2", lFold.moveItem(id("Phone chargers"), "bag 2")?.line)
        assertEquals(listOf("Documents", "Bag 2"), names())
        sync()
        assertEquals(listOf("Documents", "Bag 2"), names(lMac, mac.replica))
        assertNull(lFold.moveItem("nope", "Tech"))
        assertTrue(lFold.moveItem(id("Umbrella"), "  ")!!.refused)
    }

    @Test
    fun aRenamedGroupKeepsItsPlaceAndItsThings() {
        add("passports"); add("t-shirts × 5"); add("phone chargers")
        val r = lFold.renameGroup("t", TripList.PACK, "Clothes", "Lily's clothes")
        assertEquals("Renamed Clothes to Lily's clothes", r.line)
        assertEquals(listOf("Documents", "Lily's clothes", "Tech"), names())
        assertEquals("T-shirts", pack()[1].rows.single().text)
        // A new thing with the group's words lands in it.
        add("socks")
        assertEquals("Clothes", lFold.items().first { it.text == "Socks" }.group)
        add("Lily's hat")
        assertEquals("Lily's clothes", lFold.items().first { it.text == "Lily's hat" }.group)
        // Renaming to another group's name puts them together; Other can't be renamed.
        assertEquals("Moved everything in Tech to Documents", lFold.renameGroup("t", TripList.PACK, "Tech", "documents").line)
        assertEquals(listOf("Passports", "Phone chargers"), pack().first().rows.map { it.text })
        assertTrue(lFold.renameGroup("t", TripList.PACK, TripListRules.ADDED, "Misc").refused)
        assertTrue(lFold.renameGroup("t", TripList.PACK, "Clothes", "").refused)
        sync()
        assertEquals(names(), names(lMac, mac.replica))
    }

    @Test
    fun anAddedGroupShowsEmptyAndCatchesItsThings() {
        add("passports")
        assertEquals("Added the group Kids' bag", lFold.addGroup("t", TripList.PACK, "kids' bag").line)
        assertEquals(listOf("Documents", "Kids' bag"), names())
        assertEquals(TripGroupRules.EMPTY, pack()[1].count)
        assertTrue(lFold.addGroup("t", TripList.PACK, "Kids' Bag").refused)
        assertTrue(lFold.addGroup("t", TripList.PACK, "Other").refused)
        // "Blender" used to land in Added: now Kitchen (built in), or his own Kitchen group by the name's word.
        add("blender")
        assertEquals("Kitchen", lFold.items().first { it.text == "Blender" }.group)
        lFold.addGroup("t", TripList.PACK, "Beach bag")
        add("bucket and spade")
        assertEquals("Beach bag", lFold.items().first { it.text == "Bucket and spade" }.group)
    }

    @Test
    fun aThingThatMatchesNoGroupAsksWhichOne() {
        add("passports"); add("t-shirts")
        val r = add("Mr Wobbles")
        assertEquals(TripListRules.ADDED, lFold.items().first { it.id == r.id }.group)
        assertEquals(listOf("Documents", "Clothes"), r.askGroups)
        assertTrue(add("phone charger").askGroups.isEmpty())
    }

    @Test
    fun aDeletedGroupsThingsGoToOtherAndUndoPutsItBack() {
        add("passports"); add("t-shirts × 5"); add("socks"); add("phone chargers")
        val del = lFold.deleteGroup("t", TripList.PACK, "Clothes")
        assertEquals("Deleted Clothes · 2 things moved to Other", del.line)
        assertEquals(listOf("Documents", "Tech", "Other"), names())
        assertEquals(listOf("T-shirts", "Socks"), pack().last().rows.map { it.text })
        assertTrue(lFold.undoDeleteGroup("t", del.token))
        assertEquals(listOf("Documents", "Clothes", "Tech"), names())
        assertEquals(listOf("T-shirts", "Socks"), pack()[1].rows.map { it.text })
        assertTrue(lFold.deleteGroup("t", TripList.PACK, TripListRules.ADDED).refused)
        assertFalse(lFold.undoDeleteGroup("t", "rubbish"))
    }

    @Test
    fun groupsMoveUpAndDownAndTakeAWholeOrder() {
        add("passports"); add("t-shirts"); add("phone chargers"); add("umbrella")
        assertTrue(lFold.moveGroup("t", TripList.PACK, "Tech", -1))
        assertEquals(listOf("Documents", "Tech", "Clothes", "Other"), names())
        assertFalse(lFold.moveGroup("t", TripList.PACK, "Documents", -1))
        assertTrue(lFold.setGroupOrder("t", TripList.PACK, listOf("Clothes", "Documents")))
        assertEquals(listOf("Clothes", "Documents", "Tech", "Other"), names())
        sync()
        assertEquals(listOf("Clothes", "Documents", "Tech", "Other"), names(lMac, mac.replica))
    }

    @Test
    fun theItemSheetChangesNameQuantityAndWhose() {
        add("t-shirts × 5")
        val i = id("T-shirts")
        assertEquals("Saved Swim shorts × 3", lFold.editItem(i, text = "swim shorts", qty = 3, who = TripWho.KIDS)?.line)
        val item = lFold.items().single()
        assertEquals("Swim shorts", item.text)
        assertEquals(3, item.qty)
        assertEquals(TripWho.KIDS, item.who)
        lFold.editItem(i, qty = 1)
        assertNull(lFold.items().single().qty)
        assertTrue(lFold.editItem(i, text = " ")!!.refused)
        // A To buy thing's name follows on the shopping list.
        lFold.add("t", "buy sun cream", TripList.BUY, tripExists = true, tripLabel = "Lisbon trip")
        lFold.editItem(id("Sun cream"), text = "Sun cream SPF 50", qty = 2)
        assertEquals("Sun cream SPF 50 × 2", sFold.view().toBuy.single().title)
    }

    @Test
    fun aTemplateAndACopyKeepHisGroups() {
        add("passports"); add("t-shirts")
        lFold.renameGroup("t", TripList.PACK, "Clothes", "Outfits")
        lFold.addGroup("t", TripList.PACK, "Snacks")
        lFold.saveTemplate(trip())
        val tpl = lFold.templates().single()
        lFold.copyFromTemplate(tpl.id, trips().first { it.id == "r" })
        val rome = trips().first { it.id == "r" }
        assertEquals(listOf("Documents", "Outfits", "Snacks"), TripListRules.view(rome, lFold.items(), mon, trips()).tab(TripList.PACK).groups.map { it.name })
    }

    @Test
    fun theOldAddedGroupReadsAsOther() {
        fold.replica.commitLocal(EntityTypes.TRIP_ITEM, "old", mapOf(
            TripItemFields.TRIP to "t".fv(), TripItemFields.LIST to "pack".fv(), TripItemFields.TEXT to "Umbrella".fv(),
            TripItemFields.GROUP to "Added".fv(), TripItemFields.ADDED_AT to 1L.fv(),
        ))
        assertEquals(listOf("Other"), names())
        assertFalse(pack().single().editable)
    }

    @Test
    fun thePreviewDrawsATapBeforeTheWriteComesBack() {
        add("passports"); add("t-shirts"); add("umbrella")
        val g = pack()
        val umbrella = id("Umbrella")
        val moved = TripGroupRules.preview(g, moves = mapOf(umbrella to "Documents"))
        assertEquals(listOf("Documents", "Clothes"), moved.map { it.name })
        assertEquals(listOf("Passports", "Umbrella"), moved.first().rows.map { it.text })
        val renamed = TripGroupRules.preview(g, renames = mapOf("Clothes" to "Outfits"))
        assertEquals(listOf("Documents", "Outfits", "Other"), renamed.map { it.name })
        val deleted = TripGroupRules.preview(g, deleted = setOf("Documents"))
        assertEquals(listOf("Clothes", "Other"), deleted.map { it.name })
        assertEquals(listOf("Passports", "Umbrella"), deleted.last().rows.map { it.text })
        val ordered = TripGroupRules.preview(g, order = listOf("Clothes", "Documents"))
        assertEquals(listOf("Clothes", "Documents", "Other"), ordered.map { it.name })
        val added = TripGroupRules.preview(g, added = listOf("Snacks"))
        assertEquals(listOf("Documents", "Clothes", "Snacks", "Other"), added.map { it.name })
    }
}
