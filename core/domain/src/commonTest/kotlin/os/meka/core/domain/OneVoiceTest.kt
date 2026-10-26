package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OneVoiceTest {
    @Test
    fun withLiveOnEveryClipAsksForLivesVoice() {
        assertEquals("Charon", OneVoiceRules.asked(null, liveOn = true, liveVoice = "Charon"))
        assertEquals("Puck", OneVoiceRules.asked("Arthur", liveOn = true, liveVoice = "Puck"))
        // A voice the picker doesn't offer is Live's default.
        assertEquals(LiveModeRules.DEFAULT_VOICE, OneVoiceRules.asked(null, liveOn = true, liveVoice = "Nobody"))
    }

    @Test
    fun withLiveOffOrTheDevicesVoiceNothingChanges() {
        assertNull(OneVoiceRules.asked(null, liveOn = false, liveVoice = "Charon"))
        assertEquals("Arthur", OneVoiceRules.asked("Arthur", liveOn = false, liveVoice = "Charon"))
        assertEquals(MekaVoiceRules.DEVICE, OneVoiceRules.asked(MekaVoiceRules.DEVICE, liveOn = true, liveVoice = "Charon"))
    }

    @Test
    fun healthSaysWhetherTheVoiceIsLives() {
        assertNull(OneVoiceRules.inUse(null, true, "Charon"))
        assertEquals("Brian", OneVoiceRules.inUse("Brian", false, "Charon"))
        assertEquals("Charon · the same as Live", OneVoiceRules.inUse("Charon", true, "Charon"))
        assertEquals("Brian · not Live's Charon", OneVoiceRules.inUse("Brian", true, "Charon"))
        listOf(OneVoiceRules.inUse("Brian", true, "Vindemiatrix"), OneVoiceRules.inUse("Achernar", true, "Achernar")).forEach {
            assertTrue(it!!.length <= 40, it)
            assertEquals("Speaking as $it", HealthRules.voiceInUseLine(it))
        }
    }
}
