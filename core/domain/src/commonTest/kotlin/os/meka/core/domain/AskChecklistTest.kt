package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Checklists in tasks, slice 3b: Ask and Talk hear the checklists, and "add eggs to the party checklist" is a card. */
class AskChecklistTest {
    private val hour = 3_600_000L
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(hour)
    private val sun = CivilDate.toEpochDay(2026, 10, 11)
    private fun at(day: Long, h: Int) = cal.toEpochMs(day, h * 60)
    private val d = world.device("android")

    init { world.clock.nowMs = at(sun, 10) }

    private fun card(title: String?) =
        AskRules.card(AskRawAction("add_checklist", title = title), AskContext.EMPTY, emptyMap(), world.clock.nowMs, cal, tasks = d.tasks.all())

    @Test
    fun askHearsEachOpenChecklistWithWhatIsLeft() {
        assertEquals(emptyList(), ChecklistAskRules.askLines(d.tasks.all(), sun))
        val party = d.tasks.create(NewTask("Party"))
        val items = d.tasks.addChecklistItems(party, "balloons, cake, candles")
        d.tasks.setChecklistItemChecked(items[1], true)
        world.clock.nowMs += 1_000
        val pack = d.tasks.create(NewTask("Packing"))
        d.tasks.addChecklistItems(pack, "passport")
        d.tasks.get(pack)!!.checklist.forEach { d.tasks.setChecklistItemChecked(it.id, true) }
        // A task without a checklist, and a done one with a checklist, aren't listed.
        d.tasks.create(NewTask("Call the dentist"))
        val old = d.tasks.create(NewTask("Old shop"))
        d.tasks.addChecklistItems(old, "bread")
        d.tasks.complete(old)

        val lines = ChecklistAskRules.askLines(d.tasks.all(), sun)
        assertEquals(
            listOf(
                "Checklists · 2 tasks with checklists",
                "Checklist “Party” · 1 of 3 ticked · to tick: balloons, candles",
                "Checklist “Packing” · all 1 ticked",
            ),
            lines,
        )
        // They go with a question as `checklists` lines, Meka's own words, so never untrusted.
        val today = TodayProjection.project(emptyList(), world.clock.nowMs, DayWindow(at(sun, 0), at(sun + 1, 0)))
        val ctx = AskRules.context(today, world.clock.nowMs, cal, checklists = lines)
        assertEquals(lines, ctx.items.filter { it.kind == AskItemKind.CHECKLISTS }.map { it.line })
        assertFalse(ctx.untrusted)
        assertTrue("add_checklist" in AskRules.KINDS)
    }

    @Test
    fun addChecklistIsACardForTheNamedTaskWithOnlyWhatIsNew() {
        val party = d.tasks.create(NewTask("Party shopping"))
        d.tasks.addChecklistItems(party, "balloons")
        d.tasks.create(NewTask("Weekly shop"))

        // "the party checklist" finds "Party shopping" by its words; balloons is already waiting.
        val c = card("the party checklist: eggs, balloons, Sun cream")!!
        assertEquals(AskProposal.AddChecklist(party, "Party shopping", listOf("eggs", "Sun cream")), c.proposal)
        assertEquals("Add to “Party shopping” · eggs and Sun cream", c.line)
        assertEquals("Add", c.button)
        assertEquals("Added eggs and Sun cream to “Party shopping”", AskRules.doneLine(c.proposal, sun))
        assertEquals("add eggs and Sun cream to the Party shopping checklist", TalkRules.phrase(c.proposal, sun, past = false))
        // A task without a checklist yet is found by its exact name.
        assertEquals("Add to “Weekly shop” · milk", card("weekly shop: milk")!!.line)

        // No colon, no such task, nothing new, nothing at all: no card.
        assertNull(card("eggs"))
        assertNull(card("Barbecue: eggs"))
        assertNull(card("Party: balloons"))
        assertNull(card(null))
        // Two lists the name could mean: no guess.
        val other = d.tasks.create(NewTask("Party games"))
        d.tasks.addChecklistItems(other, "music")
        assertNull(card("party: eggs"))
        assertTrue(card("party games: eggs") != null)
    }

    @Test
    fun theTapPlanBringsBackTickedItemsAndAddsTheRest() {
        val id = d.tasks.create(NewTask("Shop"))
        val ids = d.tasks.addChecklistItems(id, "eggs, milk")
        d.tasks.setChecklistItemChecked(ids[1], true)
        val plan = ChecklistAskRules.plan(d.tasks.get(id)!!.checklist, listOf("Eggs", "MILK", "bread"))
        assertEquals(listOf("bread"), plan.add)
        assertEquals(listOf(ids[1]), plan.revive)
        assertTrue(ChecklistAskRules.plan(d.tasks.get(id)!!.checklist, listOf("eggs")).isEmpty)
    }
}
