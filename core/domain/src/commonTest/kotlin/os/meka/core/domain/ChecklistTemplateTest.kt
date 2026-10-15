package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Checklists in tasks, slice 3a: Save as template and Start from template. */
class ChecklistTemplateTest {
    private val world = SyncWorld()
    private val fold = world.device("android")
    private val mac = world.device("mac")
    private var n = 0
    private val foldTemplates = ChecklistTemplates(fold.replica, { "f${n++}" }, { world.clock.nowMs })
    private val macTemplates = ChecklistTemplates(mac.replica, { "m${n++}" }, { world.clock.nowMs })

    @Test
    fun aChecklistSavedAsATemplateStartsAnotherTaskAndSyncs() {
        val shop = fold.tasks.create(NewTask("Weekly shop"))
        val ids = fold.tasks.addChecklistItems(shop, "eggs, milk\nbread")
        fold.tasks.setChecklistItemChecked(ids[1], true)
        val task = fold.tasks.get(shop)!!
        assertEquals("Weekly shop", ChecklistTemplateRules.suggestedName(task))
        assertEquals(listOf("eggs", "milk", "bread"), ChecklistTemplateRules.itemsOf(task))

        val saved = foldTemplates.save("  Weekly   shop ", ChecklistTemplateRules.itemsOf(task))
        assertTrue(saved.saved)
        assertFalse(saved.replaced)
        assertEquals("Saved “Weekly shop” as a template", saved.line)

        // The starter of the same name gives way to Meka's own.
        val v = foldTemplates.view()
        assertEquals(listOf("Weekly shop"), v.saved.map { it.name })
        assertTrue(v.starters.none { it.name == "Weekly shop" })
        assertEquals("3 items · eggs, milk, bread", v.saved[0].line)

        fold.sync(); mac.sync()
        val next = mac.tasks.create(NewTask("Shop Saturday"))
        mac.tasks.addChecklistItems(next, "Milk")
        val tpl = macTemplates.view().saved.single()
        val add = ChecklistTemplateRules.toAdd(tpl, mac.tasks.get(next)!!)
        assertEquals(listOf("eggs", "bread"), add)
        mac.tasks.addChecklistTexts(next, add)
        assertEquals(listOf("Milk", "eggs", "bread"), ChecklistRules.view(mac.tasks.get(next)!!).toTick.map { it.text })
        assertEquals("Added 2 items from “Weekly shop”", ChecklistTemplateRules.appliedLine(tpl.name, 2))
        assertEquals("Everything from “Weekly shop” is already here", ChecklistTemplateRules.appliedLine(tpl.name, 0))
    }

    @Test
    fun savingTheSameNameUpdatesItAndRemovingIsUndoable() {
        val first = foldTemplates.save("Packing", listOf("Passport", "Chargers"))
        world.clock.advance(1_000)
        val again = foldTemplates.save("packing", listOf("Passport", "passport", "Sun cream", " "))
        assertTrue(again.replaced)
        assertEquals(first.id, again.id)
        assertEquals("Updated the “packing” template", again.line)
        val t = foldTemplates.saved().single()
        assertEquals(listOf("Passport", "Sun cream"), t.items)

        assertFalse(foldTemplates.save("Empty", emptyList()).saved)
        assertFalse(foldTemplates.save("  ", listOf("x")).saved)

        val removed = assertNotNull(foldTemplates.remove(t.id))
        assertTrue(foldTemplates.saved().isEmpty())
        assertTrue(foldTemplates.view().starters.any { it.name == "Packing" })
        assertNull(foldTemplates.remove(t.id))
        foldTemplates.restore(removed)
        assertEquals(listOf("Passport", "Sun cream"), foldTemplates.saved().single().items)
    }

    @Test
    fun startersAreOfferedAndAddAsOrdinaryItems() {
        val v = foldTemplates.view()
        assertTrue(v.saved.isEmpty())
        assertEquals(listOf("Weekly shop", "Packing", "Football kit"), v.starters.map { it.name })
        assertTrue(v.starters.all { it.starter && it.id.startsWith(ChecklistTemplateRules.STARTER_PREFIX) })
        val kit = assertNotNull(foldTemplates.find("${ChecklistTemplateRules.STARTER_PREFIX}kit"))
        val id = fold.tasks.create(NewTask("Theo football"))
        // A template's item with a comma stays one item (never split again).
        fold.tasks.addChecklistTexts(id, ChecklistTemplateRules.toAdd(kit, fold.tasks.get(id)!!) + "Snacks, drinks")
        assertEquals(kit.items.size + 1, fold.tasks.get(id)!!.checklist.size)
        assertEquals("6 items · Boots, Shin pads, Socks…", ChecklistTemplateRules.line(kit.items))
    }
}
