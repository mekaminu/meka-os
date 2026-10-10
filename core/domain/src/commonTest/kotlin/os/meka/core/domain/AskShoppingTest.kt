package os.meka.core.domain

import os.meka.core.sync.fv
import os.meka.core.testing.SyncWorld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shopping list in Ask, Talk and Search (family sharing, the nice-to-haves while slice 3 waits): "add milk to
 * shopping" becomes a card that adds through the list's own rules and can be taken back; the list goes with a question
 * as one line; Search finds what's on it.
 */
class AskShoppingTest {
    private val hour = 3_600_000L
    private val world = SyncWorld()
    private val cal = LocalCalendar.UTC
    private val sat = CivilDate.toEpochDay(2026, 10, 10)
    private var n = 0
    private val fold = world.device("android")
    private val shop = Shopping(fold.replica, { "shop${10 + n++}" }, { world.clock.nowMs }, cal)

    init { world.clock.nowMs = cal.toEpochMs(sat, 10 * 60) }

    private fun card(title: String?) =
        AskRules.card(AskRawAction("add_shopping", title = title), AskContext.EMPTY, emptyMap(), world.clock.nowMs, cal)

    private fun today() = TodayProjection.project(emptyList(), world.clock.nowMs, DayWindow(sat * CivilDate.DAY_MS, (sat + 1) * CivilDate.DAY_MS))

    @Test
    fun addShoppingBecomesACardWithTheThingsSplitAsTheListSplitsThem() {
        val c = card(" milk,  oat milk ; fish and chips, Milk ")!!
        assertEquals(AskProposal.AddShopping(listOf("milk", "oat milk", "fish and chips")), c.proposal)
        assertEquals("Add to shopping · milk, oat milk and fish and chips", c.line)
        assertEquals("Add", c.button)
        assertEquals("Added milk to shopping", AskRules.doneLine(AskProposal.AddShopping(listOf("milk")), sat))
        assertEquals("Added milk and eggs to shopping", AskRules.doneLine(AskProposal.AddShopping(listOf("milk", "eggs")), sat))
        // Nothing to add, or no title at all: no card. More than ten things: the first ten.
        assertNull(card(" , ; "))
        assertNull(card(null))
        assertEquals(AskRules.MAX_SHOPPING, (card((1..15).joinToString(", ") { "thing $it" })!!.proposal as AskProposal.AddShopping).items.size)
        assertTrue("add_shopping" in AskRules.KINDS)
    }

    @Test
    fun talkSaysItInWords() {
        val p = AskProposal.AddShopping(listOf("milk", "eggs", "bread"))
        assertEquals("add milk, eggs and bread to the shopping list", TalkRules.phrase(p, sat, past = false))
        assertEquals("added milk, eggs and bread to the shopping list", TalkRules.phrase(p, sat, past = true))
    }

    @Test
    fun theCardAddsThroughTheListAndUndoTakesBackOnlyWhatItDid() {
        val eggs = shop.add("eggs").single()
        val bread = shop.add("bread").single()
        assertTrue(shop.got(bread))
        // Eggs are already to buy (left alone), bread comes back from Got, milk is new.
        val a = shop.addTracked("eggs\nbread\nmilk")
        assertEquals(3, a.ids.size)
        assertEquals(eggs, a.ids[0])
        assertEquals(listOf(bread), a.revived)
        assertEquals(1, a.created.size)
        assertEquals(listOf("eggs", "bread", "milk"), shop.view().toBuy.map { it.title })

        assertTrue(shop.takeBack(a.created, a.revived))
        assertEquals(listOf("eggs"), shop.view().toBuy.map { it.title })
        assertEquals(listOf("bread"), shop.view().got.map { it.title })
        // Twice: nothing left to take back.
        assertFalse(shop.takeBack(a.created, a.revived))
    }

    @Test
    fun theListGoesWithAQuestionAsOneLine() {
        assertNull(AskRules.context(today(), world.clock.nowMs, cal, shopping = shop.view()).items.firstOrNull { it.kind == AskItemKind.SHOPPING })
        shop.add("milk, eggs")
        val ctx = AskRules.context(today(), world.clock.nowMs, cal, shopping = shop.view())
        val line = ctx.items.single { it.kind == AskItemKind.SHOPPING }
        assertEquals("Shopping list · 2 to buy: milk, eggs", line.line)
        assertEquals("", line.ref)
        assertEquals("shopping", AskItemKind.SHOPPING.wire)
        assertFalse(ctx.untrusted)
        // All got: the list is still there to ask about.
        shop.view().toBuy.forEach { shop.got(it.id) }
        assertEquals("Shopping list · nothing to buy",
            AskRules.context(today(), world.clock.nowMs, cal, shopping = shop.view()).items.single { it.kind == AskItemKind.SHOPPING }.line)
        // Something Jeanette added is someone else's words: proposals then count as from untrusted content.
        fold.replica.commitLocal(EntityTypes.SHOPPING_ITEM, "j1", mapOf(
            ShoppingFields.TITLE to "nappies".fv(), ShoppingFields.GOT to false.fv(),
            ShoppingFields.ADDED_AT to world.clock.nowMs.fv(), ShoppingFields.BY to "jeanette".fv(),
        ))
        assertTrue(AskRules.context(today(), world.clock.nowMs, cal, shopping = shop.view()).untrusted)
        // A long list is cut to one line.
        shop.add((1..40).joinToString(", ") { "thing number $it" })
        val long = AskRules.shoppingLine(shop.view()).line
        assertTrue(long.length <= AskRules.MAX_LINE, long)
        assertTrue(long.endsWith("…"))
    }

    @Test
    fun searchFindsWhatIsOnTheListToBuyFirst() {
        shop.add("free range eggs")
        world.clock.nowMs += hour
        val got = shop.add("duck eggs").single()
        shop.got(got)
        shop.add("milk")
        val v = shop.view()
        val sources = SearchSources(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), v.toBuy + v.got)
        val r = Search.run("eggs", sources, world.clock.nowMs, cal)
        val g = r.groups.single()
        assertEquals(SearchKind.SHOPPING, g.kind)
        assertEquals("Shopping", g.label)
        assertEquals(listOf("free range eggs", "duck eggs"), g.hits.map { it.title })
        assertEquals(listOf("To buy", "Got today"), g.hits.map { it.detail })
        assertTrue(g.hits.all { it.target == SearchTarget.LISTS_SHOPPING })
        assertEquals("Shopping · To buy", AskFieldRules.row(g.hits[0]).line)
        assertTrue(AskFieldRules.row(g.hits[0]).opens)
    }
}
