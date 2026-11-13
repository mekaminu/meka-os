package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OneVoiceTest {
    @Test
    fun everyClipAsksForObisVoiceWithLiveOnOrOff() {
        assertEquals("Charon", OneVoiceRules.asked(null, voice = "Charon"))
        // An older Polly pick no longer changes the voice.
        assertEquals("Puck", OneVoiceRules.asked("Arthur", voice = "Puck"))
        // A voice the picker doesn't offer is Obi's default.
        assertEquals(LiveModeRules.DEFAULT_VOICE, OneVoiceRules.asked(null, voice = "Nobody"))
    }

    @Test
    fun theDevicesOwnVoiceStaysTheDevices() {
        assertEquals(MekaVoiceRules.DEVICE, OneVoiceRules.asked(MekaVoiceRules.DEVICE, voice = "Charon"))
    }

    @Test
    fun settingsShowOneListOfObisVoicesThenTheDevice() {
        val v = OneVoiceRules.view("Orus", chosen = null, state = SpeechRules.ON, usageLine = "u", mac = false)
        assertEquals(LiveTalkRules.VOICES.map { it.name } + MekaVoiceRules.DEVICE, v.choices.map { it.id })
        assertEquals(listOf("Orus"), v.choices.filter { it.selected }.map { it.id })
        assertTrue(v.choices.dropLast(1).all { it.sample && it.group == OneVoiceRules.GROUP })
        assertEquals("This phone's own voice", v.choices.last().label)
        assertNull(v.statusLine)
        assertEquals("u", v.usageLine)
        assertEquals(OneVoiceRules.INTRO, v.intro)
        // No Polly rows any more.
        assertTrue(v.choices.none { it.id == "Brian" || it.id == "Amy" })
    }

    @Test
    fun settingsLightTheDeviceAndSayWhyObiCantBeHeard() {
        val d = OneVoiceRules.view("Charon", chosen = MekaVoiceRules.DEVICE, state = SpeechRules.ON, usageLine = null, mac = true)
        assertEquals(listOf(MekaVoiceRules.DEVICE), d.choices.filter { it.selected }.map { it.id })
        assertEquals("This Mac's own voice", d.choices.last().label)
        val off = OneVoiceRules.view("Charon", chosen = null, state = null, usageLine = null, mac = false, connected = false)
        assertTrue(off.statusLine!!.contains("Until this device is connected"))
        assertTrue(off.choices.none { it.sample && it.id != MekaVoiceRules.DEVICE })
        assertTrue(OneVoiceRules.view("Charon", null, "failed", null, mac = false).statusLine!!.startsWith("Couldn't reach Obi's voice"))
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

    @Test
    fun oneChoiceSetsObisVoiceEverywhereAndSyncs() {
        val world = os.meka.core.testing.SyncWorld()
        val a = world.device("android")
        val m = world.device("mac")
        val store = MekaVoiceStore(a.replica)
        assertTrue(store.choose("Brian")) // an older Polly pick
        assertTrue(store.chooseObiVoice(" orus "))
        assertEquals("Orus", store.liveVoice())
        assertNull(store.chosen()) // the old pick is let go
        a.syncWithRetry(); m.syncWithRetry()
        assertEquals("Orus", MekaVoiceStore(m.replica).liveVoice())
        // The device's own voice keeps Obi's voice for Live.
        assertTrue(store.chooseObiVoice("device"))
        assertEquals(MekaVoiceRules.DEVICE, store.chosen())
        assertEquals("Orus", store.liveVoice())
        assertEquals(MekaVoiceRules.DEVICE, OneVoiceRules.asked(store.chosen(), store.liveVoice()))
        // A Polly name isn't offered any more; nothing changes.
        assertFalse(store.chooseObiVoice("Amy"))
        assertEquals(MekaVoiceRules.DEVICE, store.chosen())
        // Null: both back to the defaults.
        assertTrue(store.chooseObiVoice(null))
        assertNull(store.chosen())
        assertEquals(LiveModeRules.DEFAULT_VOICE, store.liveVoice())
        assertEquals("Charon", OneVoiceRules.asked(store.chosen(), store.liveVoice()))
        assertNull(store.picked())
        // Picking the default by name still counts as picked (Setup's "Pick a voice").
        assertTrue(store.chooseObiVoice("Charon"))
        assertEquals("Charon", store.picked())
    }

    /** One voice for Obi, slice 3: Health → Obi's voice says which engine spoke last and why Polly stood in. */
    @Test
    fun healthSaysWhichEngineSpokeAndWhy() {
        assertEquals("Google Gemini", OneVoiceRules.spokeHow("Charon", "gemini", null))
        assertEquals("Google Chirp 3 HD", OneVoiceRules.spokeHow("Charon", "chirp3hd", null))
        assertEquals("Amazon Polly", OneVoiceRules.spokeHow("Brian", "neural", null))
        assertEquals("Amazon Polly, as Gemini is resting after a failure", OneVoiceRules.spokeHow("Brian", "generative", "resting"))
        assertEquals("Amazon Polly, as Gemini was too slow on that clip", OneVoiceRules.spokeHow("Brian", "neural", "slow"))
        assertEquals("Amazon Polly, as this month's AI budget is spent", OneVoiceRules.spokeHow("Brian", "neural", "budget"))
        assertEquals("Amazon Polly", OneVoiceRules.spokeHow("Brian", "neural", "odd"), "an unknown reason is left out")
        assertEquals("Google Gemini", OneVoiceRules.spokeHow("Charon", "gemini", "slow"), "a reason only follows Polly")
        // No engine sent (an answer's clip, a kept one): Live's voices read as Google, others as Polly.
        assertEquals("Google, kept on this device", OneVoiceRules.spokeHow("Puck", null, null, kept = true))
        assertEquals("Amazon Polly", OneVoiceRules.spokeHow("Brian", null, null))
        assertNull(OneVoiceRules.spokeHow(null, null, null))
        val line = OneVoiceRules.inUseHow("Brian", true, "Vindemiatrix", "neural", "resting", false)!!
        assertEquals("Brian · not Live's Vindemiatrix · Amazon Polly, as Gemini is resting after a failure", line)
        assertEquals("Speaking as $line", HealthRules.voiceInUseLine(line), "Health shows the whole line")
        assertNull(OneVoiceRules.inUseHow(null, true, "Charon", "gemini", null, false))
    }
}
