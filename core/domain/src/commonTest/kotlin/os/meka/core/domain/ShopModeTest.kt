package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Checklists in tasks, slice 2: drag to reorder within a group, and Shop mode. */
class ShopModeTest {
    private val world = SyncWorld()
    private val d = world.device("android")

    private fun item(id: String, pos: Long, checked: Boolean = false) = ChecklistItem(id, "t", id, checked, pos)

    @Test
    fun aDragMovesWithinItsOwnGroup() {
        // List order a, x(ticked), b, c; shown: a, b, c | x.
        val items = listOf(item("a", 0), item("x", 1024, true), item("b", 2048), item("c", 3072))
        // c to the top of "to tick": before a (index 0 in the rest).
        assertEquals(0, ChecklistRules.listIndexForGroupMove(items, "c", 0))
        // a below b: right after b in the rest (x, b, c without a → after b = 2).
        assertEquals(2, ChecklistRules.listIndexForGroupMove(items, "a", 1))
        // Past the end clamps to after the group's last row.
        assertEquals(3, ChecklistRules.listIndexForGroupMove(items, "a", 9))
        // Alone in its group: nothing to move.
        assertNull(ChecklistRules.listIndexForGroupMove(items, "x", 0))
        assertNull(ChecklistRules.listIndexForGroupMove(items, "missing", 0))

        assertEquals(0, ChecklistRules.dropIndex(2, -150f, 48f, 4))
        assertEquals(3, ChecklistRules.dropIndex(1, 100f, 48f, 4))
        assertEquals(1, ChecklistRules.dropIndex(1, 20f, 48f, 4))
        assertEquals(3, ChecklistRules.dropIndex(1, 999f, 48f, 4))

        val id = d.tasks.create(NewTask("Weekly shop"))
        val ids = d.tasks.addChecklistItems(id, "eggs, milk, bread, butter")
        d.tasks.setChecklistItemChecked(ids[1], true)
        d.tasks.moveChecklistItemInGroup(id, ids[3], 0)
        var v = ChecklistRules.view(d.tasks.get(id)!!)
        assertEquals(listOf("butter", "eggs", "bread"), v.toTick.map { it.text })
        d.tasks.moveChecklistItemInGroup(id, ids[3], 2)
        v = ChecklistRules.view(d.tasks.get(id)!!)
        assertEquals(listOf("eggs", "bread", "butter"), v.toTick.map { it.text })
        assertEquals(listOf("milk"), v.ticked.map { it.text })
    }

    @Test
    fun shopModeShowsWhatsLeftFirst() {
        val id = d.tasks.create(NewTask("Party"))
        val ids = d.tasks.addChecklistItems(id, "balloons, cake, candles")
        var v = ShopModeRules.ofChecklist(d.tasks.get(id)!!)
        assertEquals("Party", v.title)
        assertEquals("3 left", v.line)
        assertFalse(v.allDone)
        d.tasks.setChecklistItemChecked(ids[0], true)
        v = ShopModeRules.ofChecklist(d.tasks.get(id)!!)
        assertEquals(listOf("cake", "candles"), v.toGet.map { it.text })
        assertEquals(listOf("balloons"), v.done.map { it.text })
        assertEquals("Ticked", v.doneLabel)
        assertEquals("2 left · 1 ticked", v.line)
        ids.forEach { d.tasks.setChecklistItemChecked(it, true) }
        v = ShopModeRules.ofChecklist(d.tasks.get(id)!!)
        assertTrue(v.allDone)
        assertEquals("All 3 ticked", v.line)

        val shop = ShoppingView(
            toBuy = listOf(ShoppingItem("s1", "milk", false, "From Jeanette · today", "jeanette", 0)),
            got = listOf(ShoppingItem("s2", "eggs", true, "Got today", "meka", 0)),
        )
        val s = ShopModeRules.ofShopping(shop)
        assertEquals("Shopping", s.title)
        assertEquals("1 left · 1 got", s.line)
        assertEquals("From Jeanette · today", s.toGet.single().meta)
        assertNull(s.done.single().meta)
        assertEquals("Got", s.doneLabel)
        assertEquals("All got", ShopModeRules.line(0, 1, "got"))
        assertEquals(ShopModeRules.EMPTY, ShopModeRules.ofShopping(ShoppingView.EMPTY).line)
        assertTrue(ShopModeRules.ofShopping(ShoppingView.EMPTY).isEmpty)
        assertFalse(ShopModeRules.offer(0))
    }
}
