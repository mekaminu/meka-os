package os.meka.core.domain

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Hello, Navilife" (1): the gold particle constellation's sphere, its lines and each state's motion. */
class ConstellationTest {
    private fun spread(f: ConstellationFrame): Float = (0 until f.n).maxOf { sqrt(f.px[it] * f.px[it] + f.py[it] * f.py[it]) }

    private fun frame(n: Int, state: OrbState, level: Float = 0f, angle: Float = 0.3f, ms: Long = 1_000L, reduced: Boolean = false): ConstellationFrame {
        val f = ConstellationFrame(n, Constellation.DUST)
        Constellation.frame(Constellation.geometry(n), f, state, level, angle, ms, reduced)
        return f
    }

    @Test
    fun theSphereIsTheSameEveryTimeWithShortLinesToNearNeighbours() {
        val g = Constellation.geometry(Constellation.POINTS)
        assertEquals(Constellation.POINTS, g.n)
        // On the sphere (a gentle wobble only), and the same on every device: no random numbers.
        for (i in 0 until g.n) {
            val r = sqrt(g.xs[i] * g.xs[i] + g.ys[i] * g.ys[i] + g.zs[i] * g.zs[i])
            assertTrue(r in 0.95f..1.05f, "point $i at $r")
        }
        assertEquals(Constellation.hash(12), Constellation.hash(12))
        assertTrue(Constellation.hash(1) != Constellation.hash(2))
        // Every point joined to something, about two lines each, never one twice, never across the sphere.
        val joined = BooleanArray(g.n)
        val pairs = HashSet<Pair<Int, Int>>()
        for (k in 0 until g.linkCount) {
            val a = g.links[2 * k]; val b = g.links[2 * k + 1]
            assertTrue(a < b)
            assertTrue(pairs.add(a to b), "pair $a-$b twice")
            joined[a] = true; joined[b] = true
            val dx = g.xs[a] - g.xs[b]; val dy = g.ys[a] - g.ys[b]; val dz = g.zs[a] - g.zs[b]
            assertTrue(sqrt(dx * dx + dy * dy + dz * dz) < 0.5f)
        }
        assertTrue(joined.all { it })
        assertTrue(g.linkCount in g.n..(g.n * Constellation.LINKS))
    }

    @Test
    fun theFoldTheMacAndTheSmallOrbsDrawTheirOwnCounts() {
        assertEquals(Constellation.POINTS, Constellation.points(HelloOrb.SIZE, mac = false))
        assertEquals(Constellation.POINTS_MAC, Constellation.points(HelloOrb.SIZE, mac = true))
        assertEquals(Constellation.POINTS_SMALL, Constellation.points(HelloOrb.BEDSIDE_SIZE, mac = false))
        assertEquals(Constellation.POINTS_SMALL, Constellation.points(HelloOrb.SIDEBAR_SIZE, mac = true))
        assertEquals(0, Constellation.dust(HelloOrb.SIDEBAR_SIZE))
        assertEquals(Constellation.DUST, Constellation.dust(HelloOrb.SIZE))
        assertTrue(Constellation.POINTS < Constellation.POINTS_MAC) // the Fold stays battery-light
    }

    @Test
    fun itTurnsSlowlyAtRestFasterThinkingAndNotAtAllWithReducedMotion() {
        val rest = Constellation.advance(0f, 50L, OrbState.IDLE, reduced = false)
        val think = Constellation.advance(0f, 50L, OrbState.THINKING, reduced = false)
        assertTrue(rest > 0f && think > rest * 4)
        assertEquals(1.2f, Constellation.advance(1.2f, 50L, OrbState.SPEAKING, reduced = true))
        // A long gap (the app was away) never jumps more than a tenth of a second's turn.
        assertEquals(Constellation.advance(0f, 100L, OrbState.IDLE, false), Constellation.advance(0f, 60_000L, OrbState.IDLE, false))
    }

    @Test
    fun thinkingTightensSpeakingPushesOutListeningShiversWithHisVoice() {
        val n = Constellation.POINTS
        val idle = spread(frame(n, OrbState.IDLE))
        assertTrue(spread(frame(n, OrbState.THINKING)) < idle)
        assertTrue(spread(frame(n, OrbState.SPEAKING, level = 1f)) > idle)
        val quiet = frame(n, OrbState.LISTENING, level = 0f)
        val loud = frame(n, OrbState.LISTENING, level = 1f)
        assertTrue((0 until n).count { abs(quiet.px[it] - loud.px[it]) > 0.005f } > n / 3)
        // Thinking brightens the lines; the core lights with his voice.
        assertTrue(frame(n, OrbState.THINKING).linkBoost > frame(n, OrbState.IDLE).linkBoost)
        assertTrue(loud.core > quiet.core)
        // Near points are brighter than far ones.
        val f = frame(n, OrbState.IDLE)
        val near = (0 until n).maxBy { f.depth[it] }; val far = (0 until n).minBy { f.depth[it] }
        assertTrue(f.alpha[near] > f.alpha[far] * 2)
        assertTrue(Constellation.linkAlpha(0.9f, 1f, 1f) > Constellation.linkAlpha(0.2f, 1f, 1f))
    }

    @Test
    fun speakingHasAShockwaveOnTheBeatAndStreamsTheDust() {
        assertNull(Constellation.shock(OrbState.IDLE, 100L, reduced = false))
        assertNull(Constellation.shock(OrbState.SPEAKING, 100L, reduced = true))
        val early = Constellation.shock(OrbState.SPEAKING, 50L, reduced = false)!!
        val late = Constellation.shock(OrbState.SPEAKING, Constellation.BEAT_MS - 50L, reduced = false)!!
        assertTrue(late.first > early.first && late.second < early.second)
        // The playing audio's level drives the voice when there is one.
        assertEquals(0.8f, Constellation.speech(0.8f, 1_234L, reduced = false))
        assertTrue(Constellation.speech(0f, 1_234L, reduced = false) in 0f..1f)
        val speaking = frame(Constellation.POINTS, OrbState.SPEAKING, level = 0.9f)
        assertEquals(0.9f, speaking.amp)
        assertTrue(Constellation.equator(1f).second > Constellation.equator(0f).second)
        assertTrue(speaking.da.any { it > 0f })
    }

    @Test
    fun reducedMotionHoldsTheConstellationStill() {
        val a = frame(Constellation.POINTS, OrbState.IDLE, ms = 1_000L, reduced = true)
        val b = frame(Constellation.POINTS, OrbState.IDLE, ms = 9_000L, reduced = true)
        for (i in 0 until a.n) { assertEquals(a.px[i], b.px[i]); assertEquals(a.alpha[i], b.alpha[i]) }
        for (k in 0 until a.dustN) assertEquals(a.dx[k], b.dx[k])
    }

    @Test
    fun ringsAreTiltedEllipsesAndTheirCometsGoRound() {
        val (x, y) = Constellation.ringPoint(0f, 0.3f, 0f, 1f)
        assertEquals(1f, x); assertEquals(0f, y)
        val (_, top) = Constellation.ringPoint(0f, 0.3f, (kotlin.math.PI / 2).toFloat(), 1f)
        assertTrue(abs(top - 0.3f) < 1e-4f)
        assertTrue(Constellation.comet(0, 0L) != Constellation.comet(0, 600L))
        assertEquals(3, Constellation.RINGS.size)
        assertEquals(2, Constellation.AMBER_RINGS.size)
    }
}

/** "Hello, Navilife", slice 6: the orb's speaking level from Navilife's real voice. */
class VoiceOutTest {
    @Test
    fun aPlayingClipsLoudnessBecomesTheOrbsLevel() {
        assertEquals(0f, VoiceOut.level(-20f, playing = false))
        assertEquals(1f, VoiceOut.level(-3f, playing = true))
        assertEquals(VoiceOut.FLOOR, VoiceOut.level(-80f, playing = true)) // a pause: calm, but still the real voice
        assertEquals(0f, VoiceOut.level(Float.NaN, playing = true))
        val mid = VoiceOut.level(-27f, playing = true)
        assertTrue(mid in 0.45f..0.55f, "mid $mid")
        assertTrue(VoiceOut.level(-15f, true) > VoiceOut.level(-30f, true))
    }

    @Test
    fun millibelsReadAsDecibels() {
        assertEquals(-20f, VoiceOut.dbfsOfMillibels(-2000))
        assertEquals(-96f, VoiceOut.dbfsOfMillibels(-9600))
        assertEquals(0f, VoiceOut.dbfsOfMillibels(250))
    }

    @Test
    fun itRisesFastFallsSlowerAndLetsGoWhenTheClipStops() {
        val up = VoiceOut.follow(0.1f, 0.9f) - 0.1f
        val down = 0.9f - VoiceOut.follow(0.9f, 0.1f)
        assertTrue(up > down, "up $up down $down")
        assertEquals(0f, VoiceOut.follow(0.8f, 0f))
        assertTrue(VoiceOut.follow(0f, VoiceOut.FLOOR) >= VoiceOut.FLOOR)
    }

    @Test
    fun theRealVoiceReplacesTheMadeUpSwell() {
        // Metered (even in a pause) the constellation follows the voice, the same at every moment; unmetered it swells.
        assertEquals(VoiceOut.FLOOR, Constellation.speech(VoiceOut.FLOOR, 100L, reduced = false))
        assertEquals(VoiceOut.FLOOR, Constellation.speech(VoiceOut.FLOOR, 350L, reduced = false))
        assertTrue(Constellation.speech(0f, 100L, reduced = false) != Constellation.speech(0f, 350L, reduced = false))
        assertEquals(0.4f, Constellation.speech(0.9f, 100L, reduced = true))
    }
}
