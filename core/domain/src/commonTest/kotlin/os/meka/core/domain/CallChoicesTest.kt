package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: Work mode → Call assistant's pill and switch are rows opening sheets. */
class CallChoicesTest {
    private fun NotifyChoiceSheet.lit() = choices.filter { it.lit }.map { it.label }

    @Test
    fun theAssistantIsARowOpeningOnAndOff() {
        assertEquals(NotifyChoiceRow("Call assistant", "On", "Call assistant, On. Opens the choices"), CallChoiceRules.assistantRow(true))
        assertEquals("Off", CallChoiceRules.assistantRow(false).value)
        val s = CallChoiceRules.assistantSheet(false)
        assertEquals(listOf("On", "Off"), s.choices.map { it.label })
        assertEquals(listOf("Off"), s.lit())
        assertTrue("takes a message" in s.choices[0].line, s.choices[0].line)
        assertTrue("rings as usual" in s.choices[1].line, s.choices[1].line)
        assertTrue(s.choices[1].spoken.endsWith(", chosen"))
        assertEquals(listOf("On"), CallChoiceRules.assistantSheet(true).lit())
    }

    @Test
    fun screenUnknownIsARowSayingWhatItNeeds() {
        assertEquals(NotifyChoiceRow("Screen unknown callers", "Off", "Screen unknown callers, Off. Opens the choices"), CallChoiceRules.unknownRow(false))
        val withAssistant = CallChoiceRules.unknownSheet(on = true, assistantOn = true)
        assertEquals(ScreenUnknownRules.TITLE, withAssistant.title)
        assertEquals(listOf("On"), withAssistant.lit())
        assertTrue("evenings and weekends" in withAssistant.choices[0].line)
        assertTrue("needs the call assistant" !in withAssistant.choices[0].line)
        assertTrue(CallChoiceRules.unknownSheet(on = false, assistantOn = false).choices[0].line.endsWith("needs the call assistant on"))
        assertTrue("Emergency numbers" in withAssistant.note)
    }

    @Test
    fun aChoiceWritesOnlyWhatChanges() {
        assertEquals(true, CallChoiceRules.named(false, CallChoiceRules.ON))
        assertEquals(false, CallChoiceRules.named(true, CallChoiceRules.OFF))
        assertNull(CallChoiceRules.named(true, CallChoiceRules.ON))
        assertNull(CallChoiceRules.named(false, CallChoiceRules.OFF))
        assertNull(CallChoiceRules.named(true, "maybe"))
    }
}
