package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Live on the speaker: Obi's echo kept out of the microphone, and his voice held a moment so it never stutters. */
class LiveAudioTest {
    private fun obiPlaying(gate: LiveEchoGate, fromMs: Long, toMs: Long, dbfs: Float) {
        var t = fromMs
        while (t < toMs) { gate.played(t, t + 40, dbfs); t += 40 }
    }

    @Test
    fun obisEchoStaysMutedWhileHeSpeaksOutLoud() {
        val gate = LiveEchoGate()
        obiPlaying(gate, 0, 3_000, -18f)
        // His echo through the speaker as loud as -20 dBFS, chunk after chunk: never goes up.
        var t = 0L
        while (t < 3_000) {
            assertFalse(gate.send(BargeInOutput.SPEAKER, -20f, speaking = true, nowMs = t), "echo at $t ms")
            t += 40
        }
        // A fixed loudness (the old rule) would have let this through; the gate learnt the echo instead.
        assertTrue(gate.echoDb < 0f && gate.echoDb > -5f)
    }

    @Test
    fun hisVoiceClearlyOverObisGoesUpAfterAQuarterSecond() {
        val gate = LiveEchoGate()
        obiPlaying(gate, 0, 6_000, -18f)
        // Two seconds of echo at -32 dBFS teach the gate the phone's echo (about -14 dB under what plays).
        var t = 0L
        while (t < 2_000) { gate.send(BargeInOutput.SPEAKER, -32f, true, t); t += 40 }
        val threshold = LiveAudioRules.threshold(-18f, gate.echoDb)
        assertTrue(threshold < -15f, "after learning, his normal voice can reach it ($threshold)")
        // He talks over Obi at -12 dBFS: muted for the first chunks, through once it has lasted ~250 ms.
        val passed = (0 until 10).map { i -> gate.send(BargeInOutput.SPEAKER, -12f, true, 2_000L + i * 40) }
        assertEquals(listOf(false, false, false, false, false, false, true, true, true, true), passed)
        // A clatter (one loud chunk) never goes up.
        assertFalse(gate.send(BargeInOutput.SPEAKER, -32f, true, 2_400))
        assertFalse(gate.send(BargeInOutput.SPEAKER, -5f, true, 2_440))
        assertFalse(gate.send(BargeInOutput.SPEAKER, -32f, true, 2_480))
    }

    @Test
    fun headphonesAndQuietTimesSendEverything() {
        val gate = LiveEchoGate()
        obiPlaying(gate, 0, 1_000, -18f)
        assertTrue(gate.send(BargeInOutput.HEADPHONES, -60f, true, 100))
        assertTrue(gate.send(BargeInOutput.SPEAKER, -60f, speaking = false, nowMs = 100))
        // What played long ago is no longer heard: the floor applies.
        assertEquals(-160f, gate.playedAt(2_000))
        assertEquals(LiveAudioRules.FLOOR_DBFS, LiveAudioRules.threshold(-160f, 0f))
        gate.clear()
        assertEquals(-160f, gate.playedAt(500))
    }

    @Test
    fun aTurnIsHeldUntilAQuarterSecondIsQueuedThenPlaysAsOneStream() {
        val j = LiveJitter<Int>()
        val ms40 = 24_000 * 2 * 40 / 1_000 // 40 ms of 24 kHz
        assertEquals(emptyList(), j.arrive(1, ms40, 0))
        assertEquals(emptyList(), j.arrive(2, ms40, 50))
        assertEquals(0L, j.until, "nothing plays yet")
        // ... until 280 ms is queued: all seven go at once.
        var out = emptyList<Int>()
        for (i in 3..7) out = j.arrive(i, ms40, 50L + i * 10)
        assertEquals((1..7).toList(), out)
        assertEquals(120L + 280, j.until)
        // While playing, each chunk goes straight on.
        assertEquals(listOf(8), j.arrive(8, ms40, 200))
        assertEquals(440L, j.until)
        assertEquals(0, j.underruns)
    }

    @Test
    fun aNetworkGapWaitsAgainInsteadOfStutteringAndNothingHeldIsDropped() {
        val j = LiveJitter<Int>()
        val ms300 = 24_000 * 2 * 300 / 1_000
        assertEquals(listOf(1), j.arrive(1, ms300, 0))
        // The next chunk comes after the speaker ran dry: held again, counted.
        val ms40 = 24_000 * 2 * 40 / 1_000
        assertEquals(emptyList(), j.arrive(2, ms40, 500))
        assertEquals(1, j.underruns)
        // A held short answer starts after the longest wait even if the turn's end is slow.
        assertEquals(emptyList(), j.tick(600))
        assertEquals(listOf(2), j.tick(500 + LiveAudioRules.MAX_WAIT_MS))
        // Quiet from when the speaker ran dry (300) until it played again (950): what Health shows as the longest wait.
        assertEquals(650L, j.longestGapMs)
    }

    @Test
    fun theTurnsEndPlaysWhatIsHeldAndTalkingOverDropsIt() {
        val j = LiveJitter<Int>()
        val ms40 = 24_000 * 2 * 40 / 1_000
        j.arrive(1, ms40, 0)
        assertEquals(listOf(1), j.turnComplete(10))
        // The next turn after a pause isn't an underrun: it's held afresh.
        assertEquals(emptyList(), j.arrive(2, ms40, 5_000))
        assertEquals(0, j.underruns)
        j.interrupted()
        assertEquals(0L, j.until)
        assertEquals(emptyList(), j.turnComplete(5_100), "dropped on talking over")
        assertTrue(LiveAudioRules.start(300, 0, false))
        assertFalse(LiveAudioRules.start(100, 100, false))
        assertTrue(LiveAudioRules.start(0, 0, true), "even a sliver plays at the turn's end")
        assertEquals(48_000, LiveAudioRules.trackBytes(24_000, 4_000))
        assertEquals(9_600, LiveAudioRules.trackBytes(4_800, 9_600))
    }

    @Test
    fun aCutOffKeepsTheLevelsTheGateSawAndHealthSaysTheModeCutOffsAndWaits() {
        val gate = LiveEchoGate()
        obiPlaying(gate, 0, 2_000, -18f)
        val j = LiveJitter<Int>()
        val ms300 = 24_000 * 2 * 300 / 1_000
        j.arrive(1, ms300, 0)
        // Dry from 300 ms; 300 ms more arrives at 500 and plays at once (enough is queued): 200 ms quiet.
        j.arrive(2, ms300, 500)
        val cuts = LiveCutOffs()
        // Google stopped Obi while only silence was going up (the echo stayed muted): it stopped on its own.
        assertFalse(gate.send(BargeInOutput.SPEAKER, -24f, true, 100))
        assertFalse(gate.lastSent)
        cuts.record(gate)
        val a = cuts.stats(BargeInOutput.SPEAKER, j)
        assertEquals(LiveAudioStats(BargeInOutput.SPEAKER, 1, 1, 200, -24f, -18f, false), a)
        assertEquals(
            "Speaker: the mic waits while Obi talks · stopped once when he heard you (last: mic -24 dB, Obi -18 dB, only silence was going up) · waited once for the network (longest 200 ms)",
            LiveAudioLines.line(a),
        )
        assertEquals("Headphones: you can talk over Obi · no cut-offs or waits", LiveAudioLines.line(LiveAudioStats(BargeInOutput.HEADPHONES)))
        assertEquals(
            "Speaker: the mic waits while Obi talks · stopped 3 times when he heard you (last: mic -9 dB, Obi quiet)",
            LiveAudioLines.line(LiveAudioStats(BargeInOutput.SPEAKER, cutOffs = 3, lastMicDbfs = -9.4f, lastPlayedDbfs = -160f, lastLetThrough = true)),
        )
        assertEquals(a, LiveAudioLines.decode(LiveAudioLines.encode(a)))
        assertEquals(LiveAudioStats(BargeInOutput.HEADPHONES, 2), LiveAudioLines.decode(LiveAudioLines.encode(LiveAudioStats(BargeInOutput.HEADPHONES, 2))))
        assertEquals(null, LiveAudioLines.decode(listOf("NOPE", "1", "2", "3")))
        // Talking over Obi mid-wait drops the wait (nothing more to play).
        val k = LiveJitter<Int>()
        k.arrive(1, ms300, 0)
        k.arrive(2, 24_000 * 2 * 40 / 1_000, 500)
        k.interrupted()
        assertEquals(0L, k.longestGapMs)
    }
}
