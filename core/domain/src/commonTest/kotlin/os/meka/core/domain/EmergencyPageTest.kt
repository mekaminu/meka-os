package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every page under Ask in one shape, slice 12: Ask → Emergency card as the page shape (both apps). */
class EmergencyPageTest {
    private var n = 0
    private fun entry(section: EmergencySection, title: String, detail: String = "") =
        EmergencyEntry("e${++n}", section, title, detail, n.toLong())

    private val some = listOf(
        entry(EmergencySection.CALL, "GP", "Dr Patel, 01632 123456"),
        entry(EmergencySection.CALL, "Plumber", "Sam, 07700 900123"),
        entry(EmergencySection.DOCUMENTS, "Passports", "study desk"),
        entry(EmergencySection.ACCOUNTS, "Home insurance"),
    )

    @Test
    fun theThreeNumbersCountEachPart() {
        val p = EmergencyPageRules.page(EmergencyRules.view(some))
        assertEquals("Emergency card", p.title)
        assertEquals(listOf("2", "1", "1"), p.tiles.map { it.number })
        assertEquals(listOf("to call", "thing placed", "key account"), p.tiles.map { it.label })
        assertTrue(p.tiles.none { it.lit })
        assertEquals(listOf("call", "documents", "accounts"), p.cards.map { it.key })
        assertEquals(listOf("Who to call", "Where things are", "Key accounts"), p.cards.map { it.title })
    }

    @Test
    fun anEmptyCardSaysHowToStartInWordsNeverADash() {
        val p = EmergencyPageRules.page(EmergencyView.EMPTY)
        assertEquals(listOf("None", "None", "None"), p.tiles.map { it.number })
        assertTrue(p.cards.all { it.rows.isEmpty() && it.empty!!.contains("+ Add") })
        assertTrue(p.tiles.none { it.number == "—" || it.number == "-" })
    }

    @Test
    fun aRowIsTheNameWithItsDetailsAndANameAloneSaysSo() {
        val p = EmergencyPageRules.page(EmergencyRules.view(some))
        val call = p.cards[0].rows
        assertEquals(listOf("GP", "Plumber"), call.map { it.name })
        assertEquals("Dr Patel, 01632 123456", DateGlue.plain(call[0].line))
        assertEquals(EmergencyPageRules.NAME_ONLY, p.cards[2].rows.single().line)
        assertTrue(call.all { it.whenText.isEmpty() && !it.lit })
    }

    @Test
    fun aLongLineIsShortOnTheCardAndWholeInTheSheet() {
        val long = listOf(
            entry(EmergencySection.CALL, "Neighbour", "Pat two doors down with the spare key, 07700 900123"),
            entry(EmergencySection.DOCUMENTS, "Wills", "the grey box file in the loft above the landing"),
        )
        val v = EmergencyRules.view(long)
        val p = EmergencyPageRules.page(v)
        assertEquals("07700 900123", DateGlue.plain(p.cards[0].rows.single().line))
        val docs = DateGlue.plain(p.cards[1].rows.single().line)
        assertEquals("the grey box file in the loft…", docs)
        assertTrue(docs.length <= EmergencyPageRules.SHORT_LINE)
        assertEquals("the grey box file in the loft above the landing", EmergencyPageRules.line(v, p.cards[1].rows.single().key)!!.detail)
    }

    @Test
    fun aCardShowsFiveAndSeeAllHasTheRest() {
        val many = (1..7).map { entry(EmergencySection.DOCUMENTS, "Thing $it", "drawer $it") }
        val v = EmergencyRules.view(many)
        val card = EmergencyPageRules.page(v).cards[1]
        assertEquals(5, card.rows.size)
        assertEquals(7, card.count)
        assertEquals(LifePageRules.SEE_ALL, card.more)
        assertEquals(7, EmergencyPageRules.allRows(v, "documents").size)
        assertTrue(EmergencyPageRules.allRows(v, "nope").isEmpty())
    }

    @Test
    fun aRowsKeyFindsItsLineUntilItIsRemoved() {
        val v = EmergencyRules.view(some)
        val key = EmergencyPageRules.page(v).cards[0].rows[0].key
        val row = EmergencyPageRules.line(v, key)!!
        assertEquals("GP", row.title)
        assertEquals("Who to call", EmergencyPageRules.partLabel(row))
        assertNull(EmergencyPageRules.line(EmergencyRules.view(some.drop(1)), key))
        assertNull(EmergencyPageRules.line(v, "other"))
    }

    @Test
    fun addHasThePartsAsChoicesWithTheirExamples() {
        val choices = EmergencyPageRules.page(EmergencyView.EMPTY).addChoices
        assertEquals(listOf("call", "documents", "accounts"), choices.map { it.key })
        assertEquals(listOf("Someone to call", "Where something is", "A key account"), choices.map { it.label })
        assertEquals("GP: Dr Patel, 01632 123456", EmergencyPageRules.hint("call"))
        assertEquals(EmergencySection.ACCOUNTS.spokenHint, EmergencyPageRules.spokenHint("accounts"))
    }

    @Test
    fun changingTheDetailsUpdatesTheSameLineAndStillTurnsAwayNumbers() {
        val v = EmergencyRules.view(some)
        val row = EmergencyPageRules.line(v, EmergencyPageRules.lineKey("e${n - 3}"))!!
        val text = EmergencyPageRules.changeText(row, "  Dr Shah, 01632 654321 ")!!
        val (added, writes) = EmergencyRules.addPlan(EmergencySection.CALL, text, some, 99, { "new" })
        assertFalse(added.refused)
        assertEquals("Updated GP", added.line)
        assertEquals(row.id, writes.single().first)
        val acct = EmergencyPageRules.line(v, EmergencyPageRules.lineKey("e$n"))!!
        val (refused, none) = EmergencyRules.addPlan(EmergencySection.ACCOUNTS, EmergencyPageRules.changeText(acct, "Aviva 12345678")!!, some, 99, { "new" })
        assertTrue(refused.refused)
        assertTrue(none.isEmpty())
        assertNull(EmergencyPageRules.changeText(row, "   "))
    }
}
