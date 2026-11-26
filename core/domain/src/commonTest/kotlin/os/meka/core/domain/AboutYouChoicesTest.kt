package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: About you's Watch for requests and Handle her requests switches are rows opening sheets. */
class AboutYouChoicesTest {
    private val rules = AboutYouChoiceRules
    private fun NotifyChoiceSheet.lit() = choices.filter { it.lit }.map { it.label }

    @Test
    fun watchIsARowOpeningOnOffByHerName() {
        assertEquals(NotifyChoiceRow("Watch for requests", "On", "Watch for requests, On. Opens the choices"), rules.watchRow(true))
        assertEquals("Off", rules.watchRow(false).value)
        val s = rules.watchSheet("Morgan", on = false)
        assertEquals(listOf("On", "Off"), s.choices.map { it.label })
        assertEquals(listOf("Off"), s.lit())
        assertEquals("Navilife flags what Morgan asks you for in messages", s.choices[0].line)
        assertEquals("Morgan's messages are treated like anyone's", s.choices[1].line)
        assertTrue(s.choices[1].spoken.endsWith(", chosen"))
    }

    @Test
    fun withNoNameTheSheetSaysTheyAndNeverStartsLowerCase() {
        val s = rules.watchSheet("  ", on = true)
        assertEquals(listOf("On"), s.lit())
        assertEquals("Navilife flags what they ask you for in messages", s.choices[0].line)
        assertEquals("Their messages are treated like anyone's", s.choices[1].line)
    }

    @Test
    fun handleIsTitledByHerName() {
        assertEquals(NotifyChoiceRow("Handle Morgan's requests for me", "On", "Handle Morgan's requests for me, On. Opens the choices"), rules.handleRow("Morgan", true))
        assertEquals(HandleForMeRules.TITLE, rules.handleRow(null, false).title)
        val s = rules.handleSheet("Morgan", on = true)
        assertEquals("Handle Morgan's requests for me", s.title)
        assertEquals(listOf("On"), s.lit())
        assertTrue(s.choices[0].line.startsWith("What Morgan asks for is added at once"), s.choices[0].line)
        assertEquals("Morgan's requests wait in Needs you for you to add", s.choices[1].line)
        assertEquals("Her requests wait in Needs you for you to add", rules.handleSheet(null, false).choices[1].line)
    }

    @Test
    fun aChoiceWritesOnlyWhatChanges() {
        assertEquals(true, rules.named(false, CallChoiceRules.ON))
        assertEquals(false, rules.named(true, CallChoiceRules.OFF))
        assertNull(rules.named(true, CallChoiceRules.ON))
        assertNull(rules.named(false, "later"))
    }
}
