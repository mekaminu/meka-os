package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: Protected time's evening, weekend and work-mail choices are rows opening sheets. */
class ProtectedChoicesTest {
    private fun view(evenings: Boolean, from: Int, weekends: Boolean, hold: Boolean = true) =
        ProtectedView.EMPTY.copy(evenings = evenings, fromMin = from, weekends = weekends, holdMail = hold)

    @Test
    fun theEveningRowSaysTheTimeOrOffAndTheSheetLightsIt() {
        val on = view(true, 19 * 60 + 30, false)
        assertEquals("Evenings from", ProtectedChoiceRules.eveningsRow(on).title)
        assertEquals("19:30", ProtectedChoiceRules.eveningsRow(on).value)
        assertEquals("Off", ProtectedChoiceRules.eveningsRow(ProtectedView.EMPTY).value)
        val sheet = ProtectedChoiceRules.eveningsSheet(on)
        assertEquals(listOf("Off", "18:00", "18:30", "19:00", "19:30", "20:00"), sheet.choices.map { it.label })
        assertEquals(listOf("19:30"), sheet.choices.filter { it.lit }.map { it.label })
        assertTrue(sheet.choices.all { it.line.isNotBlank() })
        assertTrue(sheet.choices.single { it.lit }.spoken.endsWith(", chosen"))
        // Off is lit when evenings are off, even though fromMin keeps the last time.
        assertEquals(listOf("Off"), ProtectedChoiceRules.eveningsSheet(view(false, 19 * 60, true)).choices.filter { it.lit }.map { it.label })
    }

    @Test
    fun anEveningChoiceReadsBackToTheSetting() {
        assertEquals(EveningPick(false, 19 * 60), ProtectedChoiceRules.eveningNamed(ProtectedChoiceRules.OFF, 19 * 60))
        assertEquals(EveningPick(true, 18 * 60), ProtectedChoiceRules.eveningNamed((18 * 60).toString(), 19 * 60))
        assertNull(ProtectedChoiceRules.eveningNamed("17", 19 * 60))
        assertNull(ProtectedChoiceRules.eveningNamed("soon", 19 * 60))
        ProtectedChoiceRules.eveningsSheet(ProtectedView.EMPTY).choices.forEach {
            assertTrue(ProtectedChoiceRules.eveningNamed(it.name, 19 * 60) != null, it.name)
        }
    }

    @Test
    fun weekendsAndWorkMailHaveTwoChoicesEach() {
        val v = view(true, 19 * 60, true, hold = false)
        assertEquals("Weekends and bank holidays", ProtectedChoiceRules.weekendsRow(v).title)
        assertEquals("Protected", ProtectedChoiceRules.weekendsRow(v).value)
        assertEquals("Open", ProtectedChoiceRules.weekendsRow(ProtectedView.EMPTY).value)
        val weekends = ProtectedChoiceRules.weekendsSheet(v)
        assertEquals(listOf("Open", "Protected"), weekends.choices.map { it.label })
        assertEquals(listOf(true), weekends.choices.filter { it.lit }.map { ProtectedChoiceRules.weekendNamed(it.name) })
        assertEquals(false, ProtectedChoiceRules.weekendNamed(weekends.choices.first().name))

        assertEquals("Work mail", ProtectedChoiceRules.mailRow(v).title)
        assertEquals("Shows as it comes", ProtectedChoiceRules.mailRow(v).value)
        assertEquals("Waits till morning", ProtectedChoiceRules.mailRow(v.copy(holdMail = true)).value)
        val mail = ProtectedChoiceRules.mailSheet(v)
        assertEquals(listOf("Waits till morning", "Shows as it comes"), mail.choices.map { it.label })
        assertEquals(listOf(false), mail.choices.filter { it.lit }.map { ProtectedChoiceRules.mailNamed(it.name) })
        assertNull(ProtectedChoiceRules.mailNamed("x"))
        assertTrue(mail.note.isNotBlank() && weekends.note.isNotBlank())
    }
}
