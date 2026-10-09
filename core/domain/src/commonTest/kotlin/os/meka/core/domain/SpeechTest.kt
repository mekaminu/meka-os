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

    @Test
    fun activitySaysTheMonthsVoiceCharacters() {
        assertEquals(
            "MEKA's voice · Amy · 12,400 of 1,000,000 characters in October",
            SpeechRules.usageLine("on", "2026-10", 12_400, 1_000_000, "Amy", deviceChosen = false),
        )
        assertEquals("MEKA's voice · 0 of 1,000,000 characters in January", SpeechRules.usageLine("on", "2027-01", 0, 1_000_000, null, false))
        // Used up: the device speaks until the 1st (December rolls over to January).
        assertEquals(
            "MEKA's voice · December's 1,000,000 characters are used; the device's own voice speaks until 1 Jan",
            SpeechRules.usageLine("on", "2026-12", 1_000_250, 1_000_000, "Amy", false),
        )
        assertEquals("MEKA's voice · the device's own voice, nothing is sent", SpeechRules.usageLine("on", "2026-10", 5, 10, "Amy", deviceChosen = true))
        // Not on here, or an answer without a month: no line.
        assertNull(SpeechRules.usageLine("off", "2026-10", 0, 1_000_000, null, false))
        assertNull(SpeechRules.usageLine("failed", "2026-10", 9, 1_000_000, null, false))
        assertNull(SpeechRules.usageLine("on", null, 9, 1_000_000, null, false))
        assertNull(SpeechRules.usageLine("on", "2026-13", 9, 1_000_000, null, false))
        assertEquals("999", SpeechRules.grouped(999))
        assertEquals("1,000", SpeechRules.grouped(1_000))
        assertEquals("1,234,567", SpeechRules.grouped(1_234_567))
    }

    private val polly = listOf(
        OfferedVoice("Amy", "Female", "generative"), OfferedVoice("Emma", "Female", "neural"),
        OfferedVoice("Brian", "Male", "neural"), OfferedVoice("Arthur", "Male", "neural"),
    )

    @Test
    fun thePickerListsMekasVoicesFirstWithTheDefaultMarkedThenTheDevicesOwn() {
        val v = VoicePickerRules.view(SpeechRules.ON, polly, "Amy", chosen = null, usageLine = "MEKA's voice · Amy · 12 of 1,000,000 characters in October", mac = false)
        assertEquals(listOf("Amy", "Emma", "Brian", "Arthur", "device"), v.choices.map { it.id })
        assertEquals("British · female · most natural · MEKA's default", v.choices[0].detail)
        assertEquals("British · male · natural", v.choices[2].detail)
        assertEquals("This phone's own voice", v.choices.last().label)
        assertEquals("This Mac's own voice", VoicePickerRules.view(SpeechRules.ON, polly, "Amy", null, null, mac = true).choices.last().label)
        // Nothing chosen: MEKA's default is what speaks, so it's the one lit.
        assertEquals(listOf("Amy"), v.choices.filter { it.selected }.map { it.id })
        assertNull(v.statusLine)
        assertTrue(v.choices.all { it.sample })
        assertTrue(v.help.contains("Speech Services by Google"))
        assertTrue(VoicePickerRules.view(SpeechRules.ON, polly, "Amy", null, null, mac = true).help.contains("Manage Voices"))
        // A chosen voice is lit; the device's own when that's chosen.
        assertEquals(listOf("Brian"), VoicePickerRules.view(SpeechRules.ON, polly, "Amy", "Brian", null, mac = false).choices.filter { it.selected }.map { it.id })
        assertEquals(listOf("device"), VoicePickerRules.view(SpeechRules.ON, polly, "Amy", "device", null, mac = false).choices.filter { it.selected }.map { it.id })
        // The sample is MEKA's own words.
        assertTrue(VoicePickerRules.SAMPLE.startsWith("Good morning, Meka."))
    }

    @Test
    fun withoutMekasVoicesThePickerSaysWhyAndNeverHidesTheChoice() {
        // A chosen voice no longer offered: the default speaks and the picker says so.
        val gone = VoicePickerRules.view(SpeechRules.ON, polly, "Amy", "Joanna", null, mac = false)
        assertEquals(listOf("Amy"), gone.choices.filter { it.selected }.map { it.id })
        assertEquals("“Joanna” isn't offered any more, so Amy speaks.", gone.statusLine)
        // The server is off: only the device's voice, lit.
        val off = VoicePickerRules.view(SpeechRules.OFF, emptyList(), null, null, null, mac = false)
        assertEquals(listOf("device"), off.choices.map { it.id })
        assertTrue(off.choices.single().selected)
        assertEquals("MEKA's voices aren't switched on for this server, so the phone's own voice speaks.", off.statusLine)
        // Unreachable with Brian chosen: Brian stays listed and lit, with nothing to sample.
        val failed = VoicePickerRules.view(SpeechRules.FAILED, emptyList(), null, "Brian", null, mac = true)
        assertEquals(listOf("Brian", "device"), failed.choices.map { it.id })
        assertTrue(failed.choices[0].selected && !failed.choices[0].sample)
        assertFalse(failed.choices[1].selected)
        assertEquals("Couldn't reach MEKA's voices just now, so the Mac's own voice speaks. Try again in a moment.", failed.statusLine)
        // Not connected; and still loading says nothing yet.
        assertTrue(VoicePickerRules.view(null, emptyList(), null, null, null, mac = false, connected = false).statusLine!!.startsWith("MEKA's voices come from your MEKA server."))
        assertNull(VoicePickerRules.view(null, emptyList(), null, null, null, mac = false, loaded = false).statusLine)
    }

    @Test
    fun aLongReadWaitsLongerForItsFirstPieceAndOnlyHandsTheMissedPieceToTheDevice() {
        assertEquals(SpeechRules.FIRST_AUDIO_MS, SpeechRules.firstWaitMs(reading = false))
        assertEquals(SpeechRules.READ_FIRST_AUDIO_MS, SpeechRules.firstWaitMs(reading = true))
        assertTrue(SpeechRules.READ_FIRST_AUDIO_MS > SpeechRules.FIRST_AUDIO_MS)
        // A conversation stays in one voice once the device has taken over.
        assertEquals(SpeechRules.Miss.REST_ON_DEVICE, SpeechRules.onMiss(reading = false, resting = false))
        assertEquals(SpeechRules.Miss.REST_ON_DEVICE, SpeechRules.onMiss(reading = false, resting = true))
        // The brief goes back to MEKA's voice after one slow piece, unless the server is resting.
        assertEquals(SpeechRules.Miss.PIECE_ON_DEVICE, SpeechRules.onMiss(reading = true, resting = false))
        assertEquals(SpeechRules.Miss.REST_ON_DEVICE, SpeechRules.onMiss(reading = true, resting = true))
    }
}
