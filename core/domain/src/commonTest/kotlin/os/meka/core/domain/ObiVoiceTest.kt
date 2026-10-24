package os.meka.core.domain

import os.meka.core.sync.FieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Obi plan, slice 2: Obi speaks in a British man's voice; a woman's voice chosen for Ada goes back once. */
class ObiVoiceTest {
    @Test
    fun womensVoicesAreKnownByNameWhicheverProvider() {
        assertTrue(ObiVoiceRules.female("Amy"))
        assertTrue(ObiVoiceRules.female(" aoede-US "))
        assertTrue(ObiVoiceRules.female("Kore"))
        assertFalse(ObiVoiceRules.female("Brian"))
        assertFalse(ObiVoiceRules.female("Charon"))
        assertFalse(ObiVoiceRules.female(MekaVoiceRules.DEVICE))
        assertFalse(ObiVoiceRules.female(null))
    }

    @Test
    fun liveDefaultsToAMansVoiceAndTheMenLead() {
        assertEquals("Charon", LiveModeRules.DEFAULT_VOICE)
        val firstWoman = LiveTalkRules.VOICES.indexOfFirst { ObiVoiceRules.female(it.name) }
        assertTrue(firstWoman > 0)
        assertTrue(LiveTalkRules.VOICES.drop(firstWoman).all { ObiVoiceRules.female(it.name) }) // the men first
        assertFalse(LiveTalkRules.VOICES.take(firstWoman).any { ObiVoiceRules.female(it.name) })
    }

    @Test
    fun aWomansVoiceChosenForAdaGoesBackToObisOnceOnEveryDevice() {
        val world = os.meka.core.testing.SyncWorld()
        val a = world.device("android")
        val m = world.device("mac")
        val fold = MekaVoiceStore(a.replica)
        // Picked while the assistant was Ada (an older app: no mark).
        a.replica.commitLocal(
            EntityTypes.CONTEXT_MODE, MekaVoiceStore.ENTITY_ID,
            mapOf(MekaVoiceFields.NAME to FieldValue.Text("Aoede"), MekaVoiceFields.LIVE_VOICE to FieldValue.Text("Kore")),
        )
        a.syncWithRetry(); m.syncWithRetry()

        assertTrue(fold.obiVoiceOnce())
        assertNull(fold.chosen()) // the server's default: Brian
        assertEquals(LiveModeRules.DEFAULT_VOICE, fold.liveVoice())
        a.syncWithRetry(); m.syncWithRetry()
        val mac = MekaVoiceStore(m.replica)
        assertFalse(mac.obiVoiceOnce()) // already done on the Fold
        assertNull(mac.chosen())

        // After the switch a woman's voice he picks stays.
        mac.choose("Emma")
        assertFalse(mac.obiVoiceOnce())
        assertEquals("Emma", mac.chosen())
    }

    @Test
    fun aWomansVoicePickedWithThisVersionIsHisPickAndStays() {
        val world = os.meka.core.testing.SyncWorld()
        val a = world.device("android")
        val fold = MekaVoiceStore(a.replica)
        assertFalse(fold.obiVoiceOnce()) // nothing stored: nothing written
        assertNull(a.replica.entity(EntityTypes.CONTEXT_MODE, MekaVoiceStore.ENTITY_ID))
        fold.choose("Amy")
        fold.chooseLiveVoice("Sulafat")
        assertFalse(fold.obiVoiceOnce())
        assertEquals("Amy", fold.chosen())
        assertEquals("Sulafat", fold.liveVoice())
    }

    @Test
    fun aMansOrTheDevicesVoiceIsLeftAsItIs() {
        val world = os.meka.core.testing.SyncWorld()
        val a = world.device("android")
        val fold = MekaVoiceStore(a.replica)
        fold.choose("Arthur")
        fold.chooseLiveVoice("Puck")
        assertFalse(fold.obiVoiceOnce())
        assertEquals("Arthur", fold.chosen())
        assertEquals("Puck", fold.liveVoice())

        val b = world.device("mac")
        val mac = MekaVoiceStore(b.replica)
        mac.choose(MekaVoiceRules.DEVICE)
        assertFalse(mac.obiVoiceOnce())
        assertEquals(MekaVoiceRules.DEVICE, mac.chosen())
    }
}
