package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Walk-through week 5, A17: Settings → Talk's Turn on / Turn off pills are rows opening On · Off sheets. */
class TalkChoicesTest {
    private fun NotifyChoiceSheet.lit() = choices.filter { it.lit }.map { it.label }

    @Test
    fun aSwitchSectionIsARowSayingWhatsChosen() {
        assertEquals(
            NotifyChoiceRow("Talk over Navilife", "On", "Talk over Navilife, On. Opens the choices"),
            TalkChoiceRules.row(BargeInRules.section(true)),
        )
        assertEquals("Off", TalkChoiceRules.row(TalkOnOpenRules.section(false))?.value)
        assertEquals("On", TalkChoiceRules.row(HelloWakeRules.section(true, mac = false))?.value)
        assertEquals("On", TalkChoiceRules.row(TalkAnywhereRules.section(true, taken = false))?.value)
        assertEquals("Off", TalkChoiceRules.row(WelcomeBackRules.section(false))?.value)
    }

    @Test
    fun theSideButtonAndHeyObiWithoutSpeechHaveNoRow() {
        val side = TalkStartRules.setup(mac = false, assistantHeld = false, samsung = true).sections.first()
        assertFalse(TalkChoiceRules.isSwitch(side))
        assertNull(TalkChoiceRules.row(side))
        assertNull(TalkChoiceRules.row(HelloWakeRules.section(true, mac = false, available = false)))
    }

    @Test
    fun theSheetLightsTheChosenOneAndSaysWhatEachDoes() {
        val s = assertNotNull(TalkChoiceRules.sheet(BargeInRules.LABEL, on = true, mac = false))
        assertEquals(BargeInRules.LABEL, s.title)
        assertEquals(listOf("On", "Off"), s.choices.map { it.label })
        assertEquals(listOf("On"), s.lit())
        assertTrue(s.choices[0].line.startsWith("Start talking while Navilife speaks"), s.choices[0].line)
        assertTrue(s.choices[1].line.startsWith("Tap the orb"), s.choices[1].line)
        assertTrue(s.choices.none { it.line.startsWith("On:") || it.line.startsWith("Off:") })
        assertEquals("Kept on this phone only.", s.note)
        assertTrue(s.choices[0].spoken.endsWith(", chosen"))
        assertEquals(listOf("Off"), TalkChoiceRules.sheet(TalkOnOpenRules.LABEL, on = false, mac = true)!!.lit())
    }

    @Test
    fun theTwoThatListenSayTheyAskForTheMic() {
        assertTrue("microphone" in TalkChoiceRules.sheet(TalkOnOpenRules.LABEL, true, mac = false)!!.note)
        assertTrue("microphone" in TalkChoiceRules.sheet(HelloWakeRules.LABEL, false, mac = true)!!.note)
        assertTrue("Mac" in TalkChoiceRules.sheet(HelloWakeRules.LABEL, false, mac = true)!!.note)
        assertFalse("microphone" in TalkChoiceRules.sheet(WelcomeBackRules.LABEL, true, mac = true)!!.note)
    }

    @Test
    fun theMacOnlySwitchesHaveNoSheetOnTheFold() {
        assertNull(TalkChoiceRules.sheet(TalkAnywhereRules.LABEL, true, mac = false))
        assertNull(TalkChoiceRules.sheet(WelcomeBackRules.LABEL, true, mac = false))
        assertNotNull(TalkChoiceRules.sheet(TalkAnywhereRules.LABEL, true, mac = true))
        assertNull(TalkChoiceRules.sheet("Something else", true, mac = true))
    }

    @Test
    fun aChoiceSetsOnlyAChange() {
        assertEquals(false, TalkChoiceRules.named(on = true, name = TalkChoiceRules.OFF))
        assertEquals(true, TalkChoiceRules.named(on = false, name = TalkChoiceRules.ON))
        assertNull(TalkChoiceRules.named(on = true, name = TalkChoiceRules.ON))
        assertNull(TalkChoiceRules.named(on = false, name = "maybe"))
    }
}
