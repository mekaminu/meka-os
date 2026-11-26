package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: Speaking up's Turn on / Turn off and How often pills are rows opening sheets; Mute stays. */
class SpeakUpChoicesTest {
    private val cal = LocalCalendar.fixedOffset(0)
    private val now = cal.toEpochMs(20_000, 7 * 60 + 20)

    private fun view(s: SpeakUpSettings = SpeakUpSettings()) = SpeakUpRules.view(s, 0, now, cal)

    @Test
    fun everyMomentAndHowOftenIsARowMuteIsNot() {
        val v = view()
        val rows = v.sections.mapNotNull { SpeakUpChoiceRules.row(it) }
        assertEquals(SpeakMoment.entries.map { it.label } + SpeakUpRules.OFTEN_LABEL, rows.map { it.title })
        assertEquals("On", rows.first().value)
        assertEquals("Off", rows.first { it.title == SpeakMoment.REPLY.label }.value)
        assertEquals(SpeakOften.MORE.label, rows.last().value)
        assertEquals("${SpeakMoment.TRAIN.label}, On. Opens the choices", rows.first { it.title == SpeakMoment.TRAIN.label }.spoken)
        val mute = v.sections.last()
        assertFalse(SpeakUpChoiceRules.isChoice(mute))
        assertNull(SpeakUpChoiceRules.row(mute))
        assertNull(SpeakUpChoiceRules.sheet(mute))
    }

    @Test
    fun aMomentsSheetIsOnOffWithWhatItIs() {
        val reply = view().sections.first { it.label == SpeakMoment.REPLY.label }
        val sheet = SpeakUpChoiceRules.sheet(reply)!!
        assertEquals(SpeakMoment.REPLY.label, sheet.title)
        assertEquals(listOf("On", "Off"), sheet.choices.map { it.label })
        assertEquals("Off", sheet.choices.single { it.lit }.label)
        assertTrue(sheet.note.startsWith(SpeakMoment.REPLY.line))
        assertTrue(sheet.note.endsWith(SpeakUpChoiceRules.SYNC_NOTE))
        // Money and family say they break through focus.
        val urgent = SpeakUpChoiceRules.sheet(view().sections.first())!!
        assertTrue(ObiInterruptRules.BREAKS_THROUGH_LINE.removeSuffix(".") in urgent.note)
        assertEquals(true, SpeakUpChoiceRules.namedOn(reply, SpeakUpChoiceRules.ON))
        assertNull(SpeakUpChoiceRules.namedOn(reply, SpeakUpChoiceRules.OFF))
        assertNull(SpeakUpChoiceRules.namedOn(reply, "maybe"))
    }

    @Test
    fun howOftensSheetIsTheTwoSpeeds() {
        val often = view(SpeakUpSettings(often = SpeakOften.LESS)).sections.first { it.label == SpeakUpRules.OFTEN_LABEL }
        val sheet = SpeakUpChoiceRules.sheet(often)!!
        assertEquals(SpeakOften.entries.map { it.label }, sheet.choices.map { it.label })
        assertEquals(SpeakOften.LESS.label, sheet.choices.single { it.lit }.label)
        assertEquals("Speak less: at most 3 moments a day", sheet.choices.last().line)
        assertEquals(SpeakOften.MORE, SpeakUpChoiceRules.namedOften(often, SpeakOften.MORE.name))
        assertNull(SpeakUpChoiceRules.namedOften(often, SpeakOften.LESS.name))
        assertNull(SpeakUpChoiceRules.namedOn(often, SpeakUpChoiceRules.ON))
    }
}
