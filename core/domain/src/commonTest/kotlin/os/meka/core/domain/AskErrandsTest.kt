package os.meka.core.domain

import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Errands by location, slice 2b: Ask and Talk hear the errands, "add batteries to Tesco" is a card, Search finds them. */
class AskErrandsTest {
    private val hour = 3_600_000L
    private val world = SyncWorld()
    private val cal = LocalCalendar.fixedOffset(hour)
    private val sat = CivilDate.toEpochDay(2026, 10, 10)
    private fun at(day: Long, h: Int) = cal.toEpochMs(day, h * 60)
    private var n = 0
    private val fold = world.device("android")
    private val errands = Errands(fold.replica, { "e${n++}" }, { world.clock.nowMs }, cal)

    init { world.clock.nowMs = at(sat, 10) }

    private fun card(title: String?) =
        AskRules.card(AskRawAction("add_errand", title = title), AskContext.EMPTY, emptyMap(), world.clock.nowMs, cal, errands = errands.items())

    @Test
    fun askHearsTheErrandsByPlace() {
        assertEquals(emptyList(), ErrandAskRules.askLines(errands.view()))
        errands.add("Tesco: milk, batteries")
        world.clock.nowMs += 1_000
        errands.add("post office: parcel")
        val lines = ErrandAskRules.askLines(errands.view())
        assertEquals(listOf("Errands · 3 errands at 2 places", "At Tesco · milk, batteries", "At Post office · parcel"), lines)
        // They go with a question as `errands` lines, Meka's own words, so never untrusted.
        val today = TodayProjection.project(emptyList(), world.clock.nowMs, DayWindow(at(sat, 0), at(sat + 1, 0)))
        val ctx = AskRules.context(today, world.clock.nowMs, cal, errands = lines)
        assertEquals(lines, ctx.items.filter { it.kind == AskItemKind.ERRANDS }.map { it.line })
        assertFalse(ctx.untrusted)
        assertTrue("add_errand" in AskRules.KINDS)
    }

    @Test
    fun addErrandIsACardForWhatIsNewAtThatPlaceWithUndo() {
        errands.add("Tesco: milk")
        // The place as the list spells it; only what isn't already waiting there.
        val c = card("tesco: milk, batteries")!!
        assertEquals(AskProposal.AddErrand("Tesco", listOf("batteries")), c.proposal)
        assertEquals("Errand at Tesco · batteries", c.line)
        assertEquals("Add", c.button)
        assertEquals("At Tesco: batteries", AskRules.doneLine(c.proposal, sat))
        assertEquals("add batteries to your errands at Tesco", TalkRules.phrase(c.proposal, sat, past = false))
        assertEquals(
            "Errand at Post office · parcel and stamps",
            card("when I'm near the post office: parcel, stamps")!!.line,
        )
        // No place, nothing new, nothing at all: no card.
        assertNull(card("batteries"))
        assertNull(card("Tesco: milk"))
        assertNull(card(null))

        // On the tap: the list's own add; Undo takes back the new one and sends a revived one back under Done.
        val milk = errands.items().single().id
        assertTrue(errands.setDone(milk, true))
        val a = errands.addThings("Tesco", listOf("milk", "batteries"))
        assertEquals(listOf(milk), a.revived)
        assertEquals(1, a.created.size)
        assertEquals("2 errands at Tesco", errands.view().summary)
        assertTrue(errands.takeBack(a.created, a.revived))
        assertEquals(0, errands.view().count)
        assertTrue(errands.items().single { it.id == milk }.done)
        // Nothing left to take back.
        assertFalse(errands.takeBack(a.created, a.revived))
    }

    @Test
    fun searchFindsErrandsWaitingFirstThenDoneLately() {
        errands.add("Tesco: batteries")
        world.clock.nowMs += hour
        errands.add("Boots: batteries")
        val boots = errands.items().single { it.place == "Boots" }.id
        errands.setDone(boots, true)
        val sources = SearchSources(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), errands = errands.items())
        val g = Search.run("batt", sources, world.clock.nowMs, cal).groups.single()
        assertEquals(SearchKind.ERRAND, g.kind)
        assertEquals("Errands", g.label)
        assertEquals(listOf("batteries", "batteries"), g.hits.map { it.title })
        assertEquals(listOf("At Tesco", "Boots · done today"), g.hits.map { it.detail })
        assertTrue(g.hits.all { it.target == SearchTarget.ERRANDS })
        assertEquals("Errand · At Tesco", AskFieldRules.row(g.hits[0]).line)
        // The place's name finds what waits there.
        assertEquals(listOf("At Tesco"), Search.run("tesco", sources, world.clock.nowMs, cal).groups.single().hits.map { it.detail })
        // Done more than a week ago: gone from Search, as from the pane.
        world.clock.nowMs += 8 * 24 * hour
        assertEquals(listOf("At Tesco"), Search.run("batt", sources, world.clock.nowMs, cal).groups.single().hits.map { it.detail })
    }
}
