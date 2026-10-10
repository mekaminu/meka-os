package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Talking over MEKA (voice barge-in): when Meka's voice clearly rises over MEKA's own, and when it doesn't. */
class BargeInTest {
    private val frame = 20L

    /** Runs [levels] (one per 20 ms frame) from [from] at [startMs]; the time of the first interrupt, or null. */
    private fun run(
        levels: List<Float>,
        output: BargeInOutput = BargeInOutput.SPEAKER,
        playing: (Int) -> Boolean = { true },
        from: BargeInState = BargeInRules.start(),
        startMs: Long = 0L,
    ): Pair<Long?, BargeInState> {
        var s = from
        levels.forEachIndexed { i, db ->
            val now = startMs + i * frame
            val step = BargeInRules.step(s, db, playing(i), output, now)
            s = step.state
            if (step.interrupt) return now to s
        }
        return null to s
    }

    private fun steady(db: Float, ms: Long) = List((ms / frame).toInt()) { db }

    /** MEKA's echo on the speaker: syllables between -30 and -24 dBFS. */
    private fun echo(ms: Long) = List((ms / frame).toInt()) { i -> if (i % 6 < 3) -24f else -30f }

    @Test
    fun mekasOwnVoiceNeverInterruptsItselfEvenAfterAPause() {
        // Two seconds of MEKA, a one-second pause between sentences (still "playing"), then the next sentence.
        val (at, _) = run(echo(2_000) + steady(-60f, 1_000) + echo(2_000))
        assertEquals(null, at)
    }

    @Test
    fun mekaTalkingClearlyOverItStopsItAfterAMoment() {
        // MEKA speaks for a second, then Meka talks at -10 dBFS (well over the echo) and keeps going.
        val (at, s) = run(echo(1_000) + steady(-10f, 1_000))
        val talkFrom = 1_000L
        assertTrue(at != null && at >= talkFrom + BargeInRules.SPEAKER.sustainMs && at <= talkFrom + BargeInRules.SPEAKER.sustainMs + frame, "at $at")
        assertEquals(null, s.runStartMs) // starts afresh
    }

    @Test
    fun aCoughOrADoorIsTooShort() {
        val (at, _) = run(echo(1_000) + steady(-8f, 160) + echo(1_000))
        assertEquals(null, at)
    }

    @Test
    fun shortDipsBetweenSyllablesDontBreakTheStretchButAPauseDoes() {
        // Meka's voice with a 100 ms dip in the middle still counts as one stretch.
        val dipped = steady(-10f, 200) + steady(-30f, 100) + steady(-10f, 200)
        assertTrue(run(echo(1_000) + dipped).first != null)
        // A 300 ms gap breaks it into two stretches, each too short.
        val broken = steady(-10f, 200) + steady(-30f, 300) + steady(-10f, 200)
        assertEquals(null, run(echo(1_000) + broken).first)
    }

    @Test
    fun theFirstMomentOfMekasAudioOnlyLearns() {
        // MEKA's audio starts loud (-12) after a quiet wait for its clip: no interrupt while it learns its echo.
        val waiting = steady(-60f, 600)
        val (at, _) = run(waiting + steady(-12f, 1_500), playing = { it >= waiting.size })
        assertEquals(null, at)
    }

    @Test
    fun nothingCountsWhileNothingPlays() {
        // A clip still on its way: Meka talking then isn't a barge-in (the recogniser isn't listening either).
        val (at, s) = run(steady(-10f, 1_000), playing = { false })
        assertEquals(null, at)
        assertEquals(null, s.playingSinceMs)
    }

    @Test
    fun aQuietVoiceStaysUnderTheFloorOrTheMinimum() {
        // Over a quiet echo (-55) a voice at -42 is 13 dB up but under the speaker's -40 minimum…
        assertEquals(null, run(steady(-55f, 1_000) + steady(-42f, 1_000)).first)
        // …while in headphones (-46 minimum, 8 dB margin) it stops MEKA.
        assertTrue(run(steady(-55f, 1_000) + steady(-42f, 1_000), BargeInOutput.HEADPHONES).first != null)
        // Only 6 dB over the echo is never enough on the speaker.
        assertEquals(null, run(echo(1_000) + steady(-18f, 1_000)).first)
    }

    @Test
    fun headphonesReactSoonerThanTheSpeaker() {
        val levels = steady(-50f, 1_000) + steady(-15f, 1_000)
        val speaker = run(levels).first!!
        val phones = run(levels, BargeInOutput.HEADPHONES).first!!
        assertTrue(phones < speaker)
        assertEquals(BargeInRules.HEADPHONES, BargeInRules.tuning(BargeInOutput.HEADPHONES))
    }

    @Test
    fun theFloorFollowsMekaUpAtOnceAndComesDownSlowly() {
        val (_, up) = run(steady(-50f, 200) + steady(-20f, 200)) // inside the first 400 ms, while it learns
        assertTrue(up.floorDb > -22f, "${up.floorDb}")
        val (_, down) = run(steady(-60f, 1_000), from = up, startMs = 400L)
        // A second of quiet takes about 3 dB off.
        assertTrue(down.floorDb < up.floorDb - 2.5f && down.floorDb > up.floorDb - 3.5f, "${down.floorDb}")
    }

    @Test
    fun levelsFromTheFoldsSamples() {
        assertEquals(-160f, BargeInRules.dbfs(ShortArray(0)))
        assertEquals(-160f, BargeInRules.dbfs(ShortArray(160)))
        val full = ShortArray(160) { if (it % 2 == 0) 32767 else -32767 }
        assertTrue(BargeInRules.dbfs(full) > -0.1f)
        val tenth = ShortArray(160) { if (it % 2 == 0) 3277 else -3277 }
        assertTrue(BargeInRules.dbfs(tenth) in -20.1f..-19.9f)
        assertEquals(BargeInRules.dbfs(tenth, 80), BargeInRules.dbfs(tenth))
    }

    @Test
    fun theSettingsWordsAndTheOrbsLine() {
        assertTrue(BargeInRules.DEFAULT_ON)
        val on = BargeInRules.section(on = true)
        assertEquals("Talk over MEKA", on.label)
        assertTrue(on.lit)
        assertEquals("Turn off", on.action)
        assertTrue("Tapping the orb" in on.status)
        assertTrue(on.steps.any { "no recording" in it })
        val off = BargeInRules.section(on = false, mac = true)
        assertFalse(off.lit)
        assertEquals("Turn on", off.action)
        assertTrue(off.status.startsWith("Off: click"))
        assertTrue(off.steps.any { "Mac's speakers" in it })
        assertEquals("Talk or tap to interrupt", TalkOrb.label(TalkPhase.SPEAKING, mac = false, talkOver = true))
        assertEquals("Talk or click to interrupt", TalkOrb.label(TalkPhase.SPEAKING, mac = true, talkOver = true))
        assertEquals("Tap to interrupt", TalkOrb.label(TalkPhase.SPEAKING, mac = false, talkOver = false))
        assertEquals("Listening…", TalkOrb.label(TalkPhase.LISTENING, mac = true, talkOver = true))
    }

    @Test
    fun aBargeInStopsMekaAndListensThroughTheFlow() {
        val speaking = TalkSession(TalkPhase.SPEAKING)
        val step = TalkFlow.bargeIn(speaking)
        assertEquals(TalkPhase.LISTENING, step.session.phase)
        assertEquals(listOf(TalkEffect.StopSpeaking, TalkEffect.Listen), step.effects)
    }
}
