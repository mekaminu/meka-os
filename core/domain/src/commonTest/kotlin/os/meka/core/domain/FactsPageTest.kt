package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every page under Ask in one shape, slice 13: Ask → What Navilife knows as the page shape (both apps). */
class FactsPageTest {
    private var n = 0
    private fun fact(label: String, value: String, group: FactGroup = FactRules.groupOf(label), aiOff: Boolean = false) =
        LifeFact("f${++n}", label, value, group, aiOff, n.toLong())

    private val some = listOf(
        fact("Car reg", "AB12 CDE"),
        fact("GP", "Dr Patel, 01632 123456"),
        fact("Energy supplier", "Oakfield Energy"),
        fact("Broadband", "Oakfield Fibre", aiOff = true),
    )

    @Test
    fun theThreeNumbersAreFactsWhatObiCanUseAndWhatIsKeptBack() {
        val p = FactsPageRules.page(FactRules.view(some))
        assertEquals(FactsPageRules.TITLE, p.title)
        assertEquals(listOf("4", "3", "1"), p.tiles.map { it.number })
        assertEquals(listOf("facts", "Obi can use", "kept from AI"), p.tiles.map { it.label })
        assertTrue(p.tiles.none { it.lit })
    }

    /** A13 (walk-through 2026-11-22): "kept" only ever means kept from the AI; "4 facts kept" meant stored. */
    @Test
    fun keptOnlyEverMeansKeptFromTheAi() {
        val v = FactRules.view(some)
        val p = FactsPageRules.page(v)
        assertEquals("4 facts · Obi can use 3 · 1 kept from AI", v.summary)
        assertEquals("Kept from AI", p.cards.last().rows.last().whenText)
        val words = p.tiles.map { it.label } + v.summary.split(" · ") + p.cards.flatMap { c -> c.rows.map { it.whenText } } + p.info
        words.forEach { w ->
            Regex("\\bkept\\b(?! from (the )?AI)", RegexOption.IGNORE_CASE).find(w)?.let { throw AssertionError("\"kept\" meaning stored: $w") }
        }
        assertEquals("1 fact · Obi can use it", FactRules.view(some.take(1)).summary)
    }

    @Test
    fun oneCardAGroupInThePageSOrder() {
        val p = FactsPageRules.page(FactRules.view(some))
        assertEquals(listOf("health", "car", "home"), p.cards.map { it.key })
        assertEquals(listOf("Health and care", "Car", "Home and utilities"), p.cards.map { it.title })
        // Home holds the energy supplier and the broadband; the kept one says so on the right.
        val home = p.cards.last().rows
        assertEquals(listOf("Energy supplier", "Broadband"), home.map { it.name })
        assertEquals("", home[0].whenText)
        assertEquals(FactsPageRules.KEPT_SHORT, home[1].whenText)
        assertEquals("Oakfield Energy", DateGlue.plain(home[0].line))
    }

    @Test
    fun aBlankPageSaysItOnceWithNoTilesCardsOrNote() {
        // Walk-through 2026-11-22 A15: the blank page said it four ways (the summary, three "None" tiles, Your facts'
        // line naming "+ Add", the stored-only note); now only the header's one line and its button.
        val v = FactsView.EMPTY
        val p = FactsPageRules.page(v)
        assertTrue(FactsPageRules.blank(v))
        assertFalse(FactsPageRules.showsTiles(v))
        assertFalse(FactsPageRules.showsNote(v))
        assertEquals(0, p.tileCount)
        assertEquals(0, p.cards.size)
        assertEquals(FactsPageRules.EMPTY_LINE, FactsPageRules.summary(v))
        assertFalse("+ Add" in FactsPageRules.EMPTY_LINE)
        assertEquals("Add a fact", FactsPageRules.ADD_FIRST)
        assertEquals(FactsPageRules.INFO, p.info)
        assertEquals(listOf(FactsPageRules.ADD_FACT, FactsPageRules.ADD_KEPT), p.addChoices.map { it.key })
        // One fact brings the tiles, the cards and the note back, numbers in words where there are none.
        val one = FactRules.view(listOf(fact("Car reg", "AB12 CDE")))
        assertFalse(FactsPageRules.blank(one))
        assertTrue(FactsPageRules.showsNote(one))
        assertEquals(one.summary, FactsPageRules.summary(one))
        val tiles = FactsPageRules.page(one).tiles
        assertEquals(listOf("1", "1", "None"), tiles.map { it.number })
        assertTrue(tiles.none { it.number == "—" || it.number == "-" })
    }

    @Test
    fun aLongFactIsWholeOnTheCardForTheScreenToCut() {
        val long = listOf(
            fact("Boiler", "Oakfield Combi 30,  serviced every autumn by the plumber on the high street"),
            fact("Spare key", "with the neighbour at number twelve, behind the blue door", aiOff = true),
        )
        val v = FactRules.view(long)
        val rows = FactsPageRules.page(v).cards.flatMap { it.rows }
        // The whole fact, spaces tidied; the row cuts it by its width on screen (A14: "Oakfield Combi 30, serviced…"
        // with three-quarters of the open Fold's row empty), kept or not.
        assertEquals(
            setOf("Oakfield Combi 30, serviced every autumn by the plumber on the high street", "with the neighbour at number twelve, behind the blue door"),
            rows.map { DateGlue.plain(it.line) }.toSet(),
        )
        assertTrue(rows.none { it.line.endsWith("…") })
        // The sheet has it as typed.
        val row = FactsPageRules.fact(v, FactsPageRules.lineKey(long.first().id))!!
        assertEquals(long.first().value, row.value)
    }

    @Test
    fun onlyTheFirstFiveShowWithSeeAllForTheRest() {
        val many = (1..7).map { fact("Alex shoe size $it", "$it") }
        val card = FactsPageRules.page(FactRules.view(many)).cards.single()
        assertEquals(5, card.rows.size)
        assertEquals(7, card.count)
        assertEquals(LifePageRules.SEE_ALL, card.more)
        assertEquals(7, FactsPageRules.allRows(FactRules.view(many), card.key).size)
    }

    @Test
    fun aRowSKeyFindsItsFactUntilItGoes() {
        val v = FactRules.view(some)
        val key = FactsPageRules.page(v).cards.first().rows.first().key
        val row = FactsPageRules.fact(v, key)!!
        assertEquals("GP", row.label)
        assertEquals("Health and care", FactsPageRules.groupLabel(row))
        assertNull(FactsPageRules.fact(FactRules.view(some.filterNot { it.label == "GP" }), key))
    }

    @Test
    fun addOffersAFactAndOneKeptFromTheAi() {
        val p = FactsPageRules.page(FactsView.EMPTY)
        assertEquals(listOf("A fact", "A fact kept from the AI"), p.addChoices.map { it.label })
        assertFalse(FactsPageRules.keepsFromAi(FactsPageRules.ADD_FACT))
        assertTrue(FactsPageRules.keepsFromAi(FactsPageRules.ADD_KEPT))
        p.addChoices.forEach { c ->
            assertTrue(FactsPageRules.hint(c.key).contains(":"), "${c.label}'s hint shows a line to type")
            assertTrue(FactsPageRules.spokenHint(c.key).isNotEmpty())
        }
    }

    @Test
    fun theSheetSFieldChangesTheSameFactAndStillTurnsAwayANumber() {
        val v = FactRules.view(some)
        val row = FactsPageRules.page(v).cards.first().rows.first().let { FactsPageRules.fact(v, it.key)!! }
        assertNull(FactsPageRules.changeText(row, "   "))
        val text = FactsPageRules.changeText(row, "Dr Shah, 01632 654321")!!
        val (added, writes) = FactRules.addPlan(text, some, 10L) { "new" }
        assertFalse(added.refused)
        assertEquals(some.first { it.label == "GP" }.id, writes.single().first)
        // A card number typed into the same field is still turned away and nothing is written.
        val refused = FactRules.addPlan(FactsPageRules.changeText(row, "card 4929 1234 5678 9012")!!, some, 10L) { "new" }
        assertTrue(refused.first.refused)
        assertTrue(refused.second.isEmpty())
    }
}
