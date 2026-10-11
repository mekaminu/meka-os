package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChecklistTest {
    private val world = SyncWorld()
    private val d = world.device("android")

    private fun item(id: String, pos: Long, checked: Boolean = false) = ChecklistItem(id, "t", id, checked, pos)

    @Test
    fun aPastedListBecomesItsItems() {
        assertEquals(listOf("eggs", "milk", "bread"), ChecklistRules.split("eggs, milk\nbread"))
        assertEquals(
            listOf("Passport", "Chargers", "Sun cream", "fish and chips"),
            ChecklistRules.split("- Passport\n• Chargers\n1. Sun cream\n[ ] fish and chips\n\n  \n- passport"),
        )
        assertEquals(listOf("Oat  milk".replace("  ", " ")), ChecklistRules.split("  Oat   milk ; "))
        assertTrue(ChecklistRules.split(" , \n ;").isEmpty())
        assertEquals(ChecklistRules.MAX_ADD, ChecklistRules.split((1..50).joinToString(",") { "item $it" }).size)
    }

    @Test
    fun theViewShowsToTickFirstThenTickedAndOffersToComplete() {
        val id = d.tasks.create(NewTask("Weekly shop"))
        val added = d.tasks.addChecklistItems(id, "eggs, milk\nbread")
        assertEquals(3, added.size)
        var v = ChecklistRules.view(d.tasks.get(id)!!)
        assertEquals("Checklist", v.label)
        assertEquals(listOf("eggs", "milk", "bread"), v.toTick.map { it.text })
        assertEquals("3 items", v.line)
        assertFalse(v.canUntickAll)

        d.tasks.setChecklistItemChecked(added[1], true)
        v = ChecklistRules.view(d.tasks.get(id)!!)
        assertEquals(listOf("eggs", "bread"), v.toTick.map { it.text })
        assertEquals(listOf("milk"), v.ticked.map { it.text })
        assertEquals("1 of 3 ticked", v.line)
        assertFalse(v.offerComplete)

        // More pasted later go after the existing ones.
        d.tasks.addChecklistItems(id, "butter")
        assertEquals(listOf("eggs", "bread", "butter"), ChecklistRules.view(d.tasks.get(id)!!).toTick.map { it.text })

        d.tasks.get(id)!!.checklist.filter { !it.checked }.forEach { d.tasks.setChecklistItemChecked(it.id, true) }
        v = ChecklistRules.view(d.tasks.get(id)!!)
        assertEquals("All 4 ticked", v.line)
        assertTrue(v.offerComplete)
        d.tasks.complete(id)
        assertFalse(ChecklistRules.view(d.tasks.get(id)!!).offerComplete)
    }

    @Test
    fun untickAllRemoveAndRestoreAndReorder() {
        val id = d.tasks.create(NewTask("Packing"))
        val (a, b, c) = d.tasks.addChecklistItems(id, "a, b, c")
        d.tasks.setChecklistItemChecked(a, true)
        d.tasks.setChecklistItemChecked(c, true)
        assertEquals(setOf(a, c), d.tasks.untickAllChecklistItems(id).toSet())
        assertTrue(d.tasks.get(id)!!.checklist.none { it.checked })

        d.tasks.deleteChecklistItem(b)
        assertEquals(listOf("a", "c"), d.tasks.get(id)!!.checklist.map { it.text })
        d.tasks.restoreChecklistItem(b)
        assertEquals(listOf("a", "b", "c"), d.tasks.get(id)!!.checklist.map { it.text })

        d.tasks.moveChecklistItem(id, c, 0)
        assertEquals(listOf("c", "a", "b"), d.tasks.get(id)!!.checklist.map { it.text })
        d.tasks.moveChecklistItem(id, c, 2)
        assertEquals(listOf("a", "b", "c"), d.tasks.get(id)!!.checklist.map { it.text })
        d.tasks.moveChecklistItem(id, a, 1)
        assertEquals(listOf("b", "a", "c"), d.tasks.get(id)!!.checklist.map { it.text })
    }

    @Test
    fun reorderRenumbersOnlyWhenThereIsNoRoom() {
        val tight = listOf(item("a", 10), item("b", 11), item("c", 12))
        val moved = ChecklistRules.reorder(tight, "c", 1)
        val order = tight.map { it.id to (moved[it.id] ?: it.position) }.sortedBy { it.second }.map { it.first }
        assertEquals(listOf("a", "c", "b"), order)
        val roomy = listOf(item("a", 0), item("b", 1024), item("c", 2048))
        assertEquals(mapOf("c" to 512L), ChecklistRules.reorder(roomy, "c", 1))
        assertTrue(ChecklistRules.reorder(roomy, "b", 1).isEmpty())
        assertTrue(ChecklistRules.reorder(roomy, "zz", 0).isEmpty())
    }

    @Test
    fun linesReadNaturally() {
        assertNull(ChecklistRules.line(0, 0))
        assertEquals("1 item", ChecklistRules.line(0, 1))
        assertEquals("Ticked", ChecklistRules.line(1, 1))
        assertEquals("Added “eggs”", ChecklistRules.addedLine(listOf("eggs")))
        assertEquals("Added 3 items", ChecklistRules.addedLine(listOf("a", "b", "c")))
        assertEquals("Unticked 2 items", ChecklistRules.untickedLine(2))
        val routine = d.tasks.create(NewTask("Stretch"))
        d.tasks.setRepeat(routine, Recurrence.Daily())
        assertEquals("Routine steps", ChecklistRules.view(d.tasks.get(routine)!!).label)
    }
}
