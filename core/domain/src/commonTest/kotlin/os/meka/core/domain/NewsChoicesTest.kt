package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: News's Spanish sources is a row opening On · Off, not an open chip row. */
class NewsChoicesTest {
    private val rules = NewsChoiceRules

    @Test
    fun spanishIsARowSayingWhatIsInForce() {
        assertEquals(NotifyChoiceRow("Spanish sources", "Off", "Spanish sources, Off. Opens the choices"), rules.spanishRow(false))
        assertEquals("On", rules.spanishRow(true).value)
    }

    @Test
    fun itsSheetSaysWhatEachDoesWithTheOneInForceLit() {
        val s = rules.spanishSheet(on = false)
        assertEquals("Spanish sources", s.title)
        assertEquals(listOf("On", "Off"), s.choices.map { it.label })
        assertEquals(listOf("Off"), s.choices.filter { it.lit }.map { it.label })
        assertEquals("Adds Mundo Deportivo, Sport and Spanish Google News to News, the ticker and the brief", s.choices[0].line)
        assertEquals("English only, wherever news shows", s.choices[1].line)
        assertTrue(s.choices[1].spoken.endsWith(", chosen"))
        assertEquals(listOf("On"), rules.spanishSheet(on = true).choices.filter { it.lit }.map { it.label })
    }

    @Test
    fun aChoiceWritesOnlyWhatChanges() {
        assertEquals(true, rules.named(false, CallChoiceRules.ON))
        assertEquals(false, rules.named(true, CallChoiceRules.OFF))
        assertNull(rules.named(false, CallChoiceRules.OFF))
        assertNull(rules.named(true, "later"))
    }
}
