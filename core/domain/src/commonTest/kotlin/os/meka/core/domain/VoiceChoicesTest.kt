package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: Obi's voice page's four on/off pairs are rows opening sheets, not chip rows. */
class VoiceChoicesTest {
    private fun NotifyChoiceSheet.lit() = choices.filter { it.lit }.map { it.label }

    @Test
    fun eachRowSaysWhatsChosen() {
        assertEquals(NotifyChoiceRow("Obi calls you", "By name", "Obi calls you, By name. Opens the choices"), VoiceChoiceRules.callRow(false))
        assertEquals("Sir", VoiceChoiceRules.callRow(true).value)
        assertEquals(NotifyChoiceRow("Face", "On", "Face, On. Opens the choices"), VoiceChoiceRules.faceRow(true))
        assertEquals("Off", VoiceChoiceRules.faceRow(false).value)
        assertEquals("Remember", VoiceChoiceRules.memoryRow(true).value)
        assertEquals("Don't remember", VoiceChoiceRules.memoryRow(false).value)
        assertEquals(NotifyChoiceRow("Live", "Off", "Live, Off. Opens the choices"), VoiceChoiceRules.liveRow(false))
    }

    @Test
    fun eachSheetKeepsThePagesOrderAndLightsTheOneInForce() {
        assertEquals(listOf("By name", "Sir"), VoiceChoiceRules.callSheet(true).choices.map { it.label })
        assertEquals(listOf("Sir"), VoiceChoiceRules.callSheet(true).lit())
        // On · Off, the same order as before (walk-through A11).
        assertEquals(listOf("On", "Off"), VoiceChoiceRules.faceSheet(false).choices.map { it.label })
        assertEquals(listOf("Off"), VoiceChoiceRules.faceSheet(false).lit())
        assertEquals(listOf("Remember", "Don't remember"), VoiceChoiceRules.memorySheet(true).choices.map { it.label })
        assertEquals(listOf("On", "Off"), VoiceChoiceRules.liveSheet(true).choices.map { it.label })
        assertEquals(listOf("On"), VoiceChoiceRules.liveSheet(true).lit())
        listOf(VoiceChoiceRules.callSheet(false), VoiceChoiceRules.faceSheet(true), VoiceChoiceRules.memorySheet(false), VoiceChoiceRules.liveSheet(false))
            .forEach { s ->
                assertTrue(s.note.isNotBlank(), s.title)
                assertTrue(s.choices.all { it.line.isNotBlank() }, s.title)
                assertTrue(s.choices.single { it.lit }.spoken.endsWith(", chosen"), s.title)
                assertEquals(1, s.choices.count { it.spoken.endsWith(", chosen") }, s.title)
            }
        assertTrue(VoiceChoiceRules.LIVE_NOTE.contains("Google"))
    }

    @Test
    fun aChoiceGivesTheValueItSetsAndNullWhenAlreadyInForce() {
        assertEquals(true, VoiceChoiceRules.callNamed(false, VoiceChoiceRules.SIR))
        assertEquals(false, VoiceChoiceRules.callNamed(true, VoiceChoiceRules.NAME))
        assertNull(VoiceChoiceRules.callNamed(true, VoiceChoiceRules.SIR))
        assertEquals(false, VoiceChoiceRules.faceNamed(true, VoiceChoiceRules.OFF))
        assertNull(VoiceChoiceRules.faceNamed(true, VoiceChoiceRules.ON))
        assertEquals(true, VoiceChoiceRules.memoryNamed(false, VoiceChoiceRules.ON))
        assertEquals(true, VoiceChoiceRules.liveNamed(false, VoiceChoiceRules.ON))
        assertNull(VoiceChoiceRules.liveNamed(false, VoiceChoiceRules.OFF))
        assertNull(VoiceChoiceRules.liveNamed(false, "maybe"))
        assertNull(VoiceChoiceRules.callNamed(false, "On"))
    }
}
