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
        assertEquals(listOf("facts kept", "Obi can use", "kept from AI"), p.tiles.map { it.label })
        assertTrue(p.tiles.none { it.lit })
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
    fun anEmptyPageSaysHowToStartInWordsNeverADash() {
        val p = FactsPageRules.page(FactsView.EMPTY)
        assertEquals(listOf("None", "None", "None"), p.tiles.map { it.number })
        assertTrue(p.tiles.none { it.number == "—" || it.number == "-" })
        val card = p.cards.single()
        assertTrue(card.rows.isEmpty())
        assertTrue(card.empty!!.contains("+ Add"))
    }

    @Test
    fun aLongFactIsShortOnTheCardAndWholeInTheSheet() {
        val long = listOf(fact("Boiler", "Oakfield Combi 30, serviced every autumn by the plumber on the high street"))
        val v = FactRules.view(long)
        val line = DateGlue.plain(FactsPageRules.page(v).cards.single().rows.single().line)
        assertTrue(line.length <= FactsPageRules.SHORT_LINE, "the card's line is $line")
        assertTrue(line.endsWith("…"))
        assertTrue(long.single().value.startsWith(line.removeSuffix("…")))
        // The sheet has it whole.
        val row = FactsPageRules.fact(v, FactsPageRules.lineKey(long.single().id))!!
        assertEquals(long.single().value, row.value)
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
