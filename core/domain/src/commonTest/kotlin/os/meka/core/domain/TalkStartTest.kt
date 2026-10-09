package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Talk without tapping the mic, slice 1: the side button and the headphones' button open MEKA listening. */
class TalkStartTest {
    @Test
    fun theAssistGestureAndTheHeadsetButtonStartTalkNothingElseDoes() {
        assertEquals(TalkStart.SIDE_BUTTON, TalkStartRules.fromAction("android.intent.action.ASSIST"))
        assertEquals(TalkStart.HEADSET_BUTTON, TalkStartRules.fromAction("android.intent.action.VOICE_COMMAND"))
        assertNull(TalkStartRules.fromAction("android.intent.action.MAIN"))
        assertNull(TalkStartRules.fromAction(null))
    }

    @Test
    fun theStartTravelsToTheMainScreenAndBack() {
        for (s in TalkStart.entries) assertEquals(s, TalkStartRules.fromOpen(TalkStartRules.openValue(s)))
        assertEquals("talk:SIDE_BUTTON", TalkStartRules.openValue(TalkStart.SIDE_BUTTON))
        assertNull(TalkStartRules.fromOpen("talk:WAKE_WORD"))
        assertNull(TalkStartRules.fromOpen("news:"))
        assertNull(TalkStartRules.fromOpen(null))
    }

    @Test
    fun notTheAssistantYetSaysHowOnASamsungAndOffersDefaultApps() {
        val v = TalkStartRules.setup(mac = false, assistantHeld = false, samsung = true)
        assertEquals("Talk", v.title)
        assertEquals(listOf("Side button", "Headphones", "Safety"), v.sections.map { it.label })
        val side = v.sections[0]
        assertFalse(side.lit)
        assertEquals("Not set up yet: holding the side key opens another assistant.", side.status)
        assertEquals(2, side.steps.size)
        assertTrue(side.steps[0].startsWith("Settings → Apps → Choose default apps → Digital assistant app"))
        assertTrue(side.steps[1].contains("Side button → Press and hold → Digital assistant"))
        assertEquals("Open default apps", side.action)
        // Another phone: Android's own menu names, one step.
        val pixel = TalkStartRules.setup(mac = false, assistantHeld = false, samsung = false).sections[0]
        assertEquals(listOf("Settings → Apps → Default apps → Digital assistant app → MEKA."), pixel.steps)
    }

    @Test
    fun onceTheAssistantTheSideButtonIsLitAndNeedsNoButton() {
        val side = TalkStartRules.setup(mac = false, assistantHeld = true, samsung = true).sections[0]
        assertTrue(side.lit)
        assertTrue(side.status.startsWith("MEKA is this phone's digital assistant"))
        assertNull(side.action)
        assertTrue(side.steps.single().contains("Bixby"))
        assertTrue(TalkStartRules.setup(mac = false, assistantHeld = true, samsung = false).sections[0].steps.isEmpty())
    }

    @Test
    fun theMacSaysOptionSpaceAndKeepsTheSafetyLine() {
        val v = TalkStartRules.setup(mac = true)
        assertEquals(listOf("On the Mac", "Safety"), v.sections.map { it.label })
        assertTrue(v.sections[0].status.contains("⌥Space"))
        assertTrue(v.sections.all { it.action == null })
        assertTrue(v.sections[1].status.contains("Undo"))
    }
}
