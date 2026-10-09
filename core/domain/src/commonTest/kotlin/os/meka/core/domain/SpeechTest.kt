package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MEKA's voice on the devices (Weather and a voice, item 3): pieces, pauses after a refusal, the synced choice. */
class SpeechTest {
    @Test
    fun theFirstSentenceIsSaidAloneAndTheRestJoinedSoTheFirstAudioComesQuickly() {
        assertEquals(
            listOf("You've got three things today.", "Training is at 18:00 at SG18. Anything else?"),
            SpeechRules.pieces("  You've got three things today.  Training is at 18:00 at SG18.\nAnything else? "),
        )
        assertEquals(listOf("Anything else?"), SpeechRules.pieces("Anything else?"))
        assertEquals(emptyList(), SpeechRules.pieces("   "))
        // A question mark, an exclamation and closing quotes end a sentence; a decimal or "e.g." doesn't.
        assertEquals(
            listOf("Done!", "It's 14.5° — e.g. light rain. “Take a coat.” OK?"),
            SpeechRules.pieces("Done! It's 14.5° — e.g. light rain. “Take a coat.” OK?"),
        )
    }

    @Test
    fun laterSentencesAreJoinedUpToAboutThreeHundredCharactersAndNoPieceIsTooLongToSend() {
        val s = "This sentence is exactly fifty characters long ok."
        val pieces = SpeechRules.pieces(List(9) { s }.joinToString(" "))
        assertEquals(s, pieces.first())
        assertTrue(pieces.drop(1).all { it.length <= SpeechRules.JOIN_TO }, pieces.toString())
        assertEquals(List(9) { s }.joinToString(" "), pieces.joinToString(" "))
        // One very long sentence is cut at a comma that fits, never over the server's limit.
        val long = List(40) { "and then some more words" }.joinToString(", ") + "."
        val cut = SpeechRules.pieces(long)
        assertTrue(cut.size >= 2)
        assertTrue(cut.all { it.length <= SpeechRules.MAX_PIECE })
        assertTrue(cut.first().endsWith(","))
        assertEquals(long, cut.joinToString(" "))
    }

    @Test
    fun aRefusalLeavesTheServerAloneOverUntilTheFirstOfNextMonthUtc() {
        val now = 1_791_476_100_000L // Thu 8 Oct 2026, 16:15 UTC
        assertEquals(1_793_491_200_000L, SpeechRules.quietUntil("over", now)) // 1 Nov 2026 00:00 UTC
        assertEquals(1_798_761_600_000L, SpeechRules.nextUtcMonthStartMs(1_798_000_000_000L)) // in Dec → 1 Jan 2027
        assertEquals(now + 6 * 3_600_000L, SpeechRules.quietUntil("off", now))
        assertEquals(now + 60_000L, SpeechRules.quietUntil("failed", now))
        assertNull(SpeechRules.quietUntil("spoken", now))
        assertEquals("Amy|Hello.", SpeechRules.cacheKey("Amy", "Hello."))
        assertEquals("|Hello.", SpeechRules.cacheKey(null, "Hello."))
        assertTrue(TalkRules.ANYTHING_ELSE in SpeechRules.COMMON)
    }

    @Test
    fun theChosenVoiceIsSyncedAndOnlyARealVoiceNameIsKept() {
        val world = os.meka.core.testing.SyncWorld()
        val a = world.device("android")
        val m = world.device("mac")
        val voice = MekaVoiceStore(a.replica)
        assertNull(voice.chosen())
        assertTrue(voice.choose(null))
        assertNull(a.replica.entity(EntityTypes.CONTEXT_MODE, MekaVoiceStore.ENTITY_ID)) // nothing written for no change
        assertTrue(voice.choose("Brian"))
        assertEquals("Brian", voice.chosen())
        // Chosen on the Fold, used on the Mac.
        a.syncWithRetry(); m.syncWithRetry()
        assertEquals("Brian", MekaVoiceStore(m.replica).chosen())
        assertTrue(voice.choose(" device "))
        assertEquals(MekaVoiceRules.DEVICE, voice.chosen())
        assertFalse(voice.choose("Amy; drop"))
        assertEquals(MekaVoiceRules.DEVICE, voice.chosen())
        assertTrue(voice.choose(null))
        assertNull(voice.chosen())
        assertNull(MekaVoiceRules.normalize("amy"))
        assertEquals("Arthur", MekaVoiceRules.normalize(" Arthur "))
    }
}
