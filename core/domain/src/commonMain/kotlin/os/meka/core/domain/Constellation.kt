package os.meka.core.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * "Hello, Navilife" (1), Meka 2026-10-19 19:19 and 20:14: the Hello orb as a **gold particle constellation** — points on
 * a slowly turning 3D sphere, each joined to its nearest neighbours by faint lines, depth-shaded, with a soft gold core
 * and a halo of drifting dust. The numbers live here so the Fold (Compose Canvas) and the Mac (SwiftUI Canvas) draw the
 * same thing frame for frame; the apps only paint what [frame] works out. (Non-AI, pure; no allocation per frame beyond
 * the caller's [ConstellationFrame].)
 *
 * States ([OrbState]): idle turns slowly and breathes; listening makes the points shiver with his voice; thinking
 * tightens the sphere, spins it faster, brightens the lines and adds three tilted orbit rings with bright comets
 * ([ringPoint], [comet]); speaking pushes the points outward with the voice's amplitude, streams the dust out, lights a
 * voice-reactive ring round the equator and sends a soft shockwave on each strong beat ([shock]); needs-you adds two
 * amber comets. Reduced motion: the constellation held still (no turning), a calm glow, and the apps' level bar.
 */
object Constellation {
    /** Points on the Fold's orb (battery-light), the Mac's, and the small orbs (the stand's 64 dp, the sidebar's 40 pt). */
    const val POINTS = 300
    const val POINTS_MAC = 360
    const val POINTS_SMALL = 80
    /** Dust motes drifting out round the orb (none on the small orbs). */
    const val DUST = 48
    /** Each point joins at most this many of its nearest neighbours. */
    const val LINKS = 3
    /** One turn of the sphere at rest, and while thinking. */
    const val TURN_MS = 26_000L
    const val THINK_TURN_MS = 5_200L
    /** The sphere's tilt towards him (radians), so the turn reads as 3D. */
    const val TILT = 0.42f
    /** One breath at rest (the sphere's radius 0.96 → 1). */
    const val BREATH_MS = 4_200L
    /** Thinking draws the sphere in to this radius; speaking pushes out by up to [PUSH]. */
    const val TIGHT = 0.86f
    const val PUSH = 0.14f
    /** Listening: how far a point shivers at full voice (of the radius). */
    const val SHIVER = 0.07f
    /** One lap of an orbit ring's comet while thinking, and of a needs-you amber comet. */
    const val COMET_MS = 2_400L
    const val AMBER_COMET_MS = 6_000L
    /** A dust mote's drift from the rim to gone, at rest and while speaking (it streams out). */
    const val DUST_MS = 7_000L
    const val DUST_SPEAK_MS = 2_200L
    /** A strong beat while speaking: one soft shockwave every [BEAT_MS], out to [SHOCK_REACH]× the radius. */
    const val BEAT_MS = 1_150L
    const val SHOCK_REACH = 1.45f
    /** How far anything reaches (dust, shockwave), of the sphere's radius: the apps size the sphere to fit its box. */
    const val EXTENT = 1.45f
    /** The orbit rings while thinking: three, each tilted its own way (screen angle, squash). */
    val RINGS: List<Pair<Float, Float>> = listOf(0.35f to 0.30f, 2.25f to 0.42f, 4.10f to 0.24f)
    /** The equator ring while speaking: the sphere's own tilt, a little outside it. */
    const val EQUATOR_SQUASH = 0.36f
    const val EQUATOR_R = 1.14f

    /** How many points an orb of [sizeDp] dp/pt draws on the Mac or the Fold. */
    fun points(sizeDp: Int, mac: Boolean): Int = when {
        sizeDp < 96 -> POINTS_SMALL
        mac -> POINTS_MAC
        else -> POINTS
    }

    /** The dust motes for an orb of [sizeDp]: none on the small orbs (they'd only be noise at 40 pt). */
    fun dust(sizeDp: Int): Int = if (sizeDp < 96) 0 else DUST

    private val cache = mutableMapOf<Int, ConstellationGeometry>()

    /** The sphere of [n] points, worked out once per size (a Fibonacci sphere, gently jittered so it doesn't look ruled). */
    fun geometry(n: Int): ConstellationGeometry = cache.getOrPut(n) { build(n) }

    private fun build(n: Int): ConstellationGeometry {
        val xs = FloatArray(n); val ys = FloatArray(n); val zs = FloatArray(n); val seeds = FloatArray(n)
        val golden = PI * (3 - sqrt(5.0))
        for (i in 0 until n) {
            val s = hash(i)
            val y = 1 - 2 * (i + 0.5) / n
            val r = sqrt(1 - y * y)
            val th = golden * i + (s - 0.5) * 0.35
            val wobble = 1 + (hash(i + 7_919) - 0.5) * 0.06
            xs[i] = (cos(th) * r * wobble).toFloat(); ys[i] = (y * wobble).toFloat(); zs[i] = (sin(th) * r * wobble).toFloat()
            seeds[i] = s.toFloat()
        }
        // Each point to its LINKS nearest, kept once per pair, and never across the sphere.
        val limit = 6f / sqrt(n.toFloat()) // about 1.7× the spacing between neighbours
        val pairs = mutableListOf<Int>()
        val seen = HashSet<Long>()
        val best = IntArray(LINKS); val bestD = FloatArray(LINKS)
        for (i in 0 until n) {
            best.fill(-1); bestD.fill(Float.MAX_VALUE)
            for (j in 0 until n) {
                if (j == i) continue
                val dx = xs[i] - xs[j]; val dy = ys[i] - ys[j]; val dz = zs[i] - zs[j]
                val d = dx * dx + dy * dy + dz * dz
                var k = LINKS - 1
                if (d >= bestD[k]) continue
                while (k > 0 && d < bestD[k - 1]) { bestD[k] = bestD[k - 1]; best[k] = best[k - 1]; k-- }
                bestD[k] = d; best[k] = j
            }
            for (k in 0 until LINKS) {
                val j = best[k]
                if (j < 0 || (k > 0 && sqrt(bestD[k]) > limit)) continue // the nearest always, so no point is left alone
                val key = if (i < j) i.toLong() * 100_000 + j else j.toLong() * 100_000 + i
                if (seen.add(key)) { pairs += minOf(i, j); pairs += maxOf(i, j) }
            }
        }
        return ConstellationGeometry(n, xs, ys, zs, seeds, pairs.toIntArray())
    }

    /** A steady 0…1 per index, the same on every device (no random numbers, so the Fold and the Mac match). */
    internal fun hash(i: Int): Double {
        var h = i * 374_761_393 + 668_265_263
        h = (h xor (h ushr 13)) * 1_274_126_177
        h = h xor (h ushr 16)
        return (h.toLong() and 0x7fffffffL).toDouble() / 0x7fffffffL.toDouble()
    }

    /**
     * The sphere's turn after [dtMs] more of [state]: the apps keep the angle between frames so a change of state
     * speeds the turn up or down without a jump. Reduced motion: it doesn't turn.
     */
    fun advance(angle: Float, dtMs: Long, state: OrbState, reduced: Boolean): Float {
        if (reduced) return angle
        val period = if (state == OrbState.THINKING) THINK_TURN_MS else TURN_MS
        val next = angle + (2 * PI).toFloat() * dtMs.coerceIn(0L, 100L) / period
        return next % (2 * PI).toFloat()
    }

    /** The resting breath at [elapsedMs], 0 → 1 → 0 eased (a still 1 with reduced motion). */
    fun breath(elapsedMs: Long, reduced: Boolean): Float {
        if (reduced) return 1f
        val p = (elapsedMs.coerceAtLeast(0L) % BREATH_MS).toFloat() / BREATH_MS
        return 0.5f - 0.5f * cos(2 * PI.toFloat() * p)
    }

    /**
     * The voice's amplitude while speaking: the playing audio's [level] when the app has one (above 0), else a
     * syllable-like swell from [elapsedMs] so the orb still talks (0…1). Reduced motion: held at 0.4.
     */
    fun speech(level: Float, elapsedMs: Long, reduced: Boolean): Float {
        if (reduced) return 0.4f
        if (level > 0f) return level.coerceIn(0f, 1f)
        val t = elapsedMs.coerceAtLeast(0L) / 1000f
        val syll = abs(sin(t * 6.3f)) * 0.55f + abs(sin(t * 2.1f + 0.7f)) * 0.35f
        val beat = shockStrength(elapsedMs)
        return (0.15f + syll * 0.7f + beat * 0.25f).coerceIn(0f, 1f)
    }

    private fun shockStrength(elapsedMs: Long): Float {
        val p = (elapsedMs.coerceAtLeast(0L) % BEAT_MS).toFloat() / BEAT_MS
        return if (p < 0.12f) 1f - p / 0.12f else 0f
    }

    /** The shockwave while speaking: how far out (1 → [SHOCK_REACH]× the radius) and how bright (fading); null at rest. */
    fun shock(state: OrbState, elapsedMs: Long, reduced: Boolean): Pair<Float, Float>? {
        if (state != OrbState.SPEAKING || reduced) return null
        val p = (elapsedMs.coerceAtLeast(0L) % BEAT_MS).toFloat() / BEAT_MS
        val eased = 1 - (1 - p) * (1 - p)
        return (1f + (SHOCK_REACH - 1f) * eased) to (1 - p) * (1 - p) * 0.55f
    }

    /**
     * Works one frame out into [out]: every point's place on screen (−1…1 of the radius, before the app scales it), its
     * depth (0 far … 1 near) and brightness; the lines' brightness; the dust; and the core's glow.
     */
    fun frame(geo: ConstellationGeometry, out: ConstellationFrame, state: OrbState, level: Float, angle: Float, elapsedMs: Long, reduced: Boolean) {
        val n = geo.n
        val ca = cos(angle); val sa = sin(angle); val ct = cos(TILT); val st = sin(TILT)
        val t = elapsedMs.coerceAtLeast(0L)
        val breath = breath(t, reduced)
        val voice = level.coerceIn(0f, 1f)
        val amp = if (state == OrbState.SPEAKING) speech(level, t, reduced) else 0f
        val base = when (state) {
            OrbState.THINKING -> TIGHT
            OrbState.LISTENING -> 0.98f + 0.04f * voice
            OrbState.SPEAKING -> 1f
            else -> 0.96f + 0.04f * breath
        }
        val tsec = t / 1000f
        for (i in 0 until n) {
            val s = geo.seeds[i]
            var r = base
            if (!reduced) when (state) {
                OrbState.LISTENING -> r += SHIVER * voice * sin(tsec * 31f + s * 47f) * (0.4f + 0.6f * s)
                OrbState.SPEAKING -> r += PUSH * amp * (0.55f + 0.45f * sin(tsec * 3.1f + s * 23f))
                OrbState.THINKING -> r += 0.015f * sin(tsec * 9f + s * 31f)
                else -> {}
            }
            val x0 = geo.xs[i] * r; val y0 = geo.ys[i] * r; val z0 = geo.zs[i] * r
            // Turn about the vertical, then tilt towards him.
            val x1 = x0 * ca + z0 * sa
            val z1 = -x0 * sa + z0 * ca
            val y2 = y0 * ct - z1 * st
            val z2 = y0 * st + z1 * ct
            val persp = 1f + 0.16f * z2
            out.px[i] = x1 * persp
            out.py[i] = y2 * persp
            val depth = ((z2 / r.coerceAtLeast(0.5f)) + 1f) / 2f
            out.depth[i] = depth.coerceIn(0f, 1f)
            val twinkle = if (reduced) 1f else 0.85f + 0.15f * sin(tsec * (1.3f + s * 2.2f) + s * 40f)
            out.alpha[i] = ((0.18f + 0.82f * depth * depth) * twinkle * pointBoost(state, voice, amp)).coerceIn(0f, 1f)
        }
        out.linkBoost = when (state) {
            OrbState.THINKING -> 1.7f
            OrbState.LISTENING -> 1f + 0.5f * voice
            OrbState.SPEAKING -> 1.1f + 0.4f * amp
            else -> 0.8f + 0.2f * breath
        }
        out.core = when (state) {
            OrbState.LISTENING -> 0.45f + 0.5f * voice
            OrbState.THINKING -> 0.7f
            OrbState.SPEAKING -> 0.55f + 0.4f * amp
            OrbState.NEEDS_YOU -> 0.35f + 0.25f * HelloOrb.pulse(t, reduced)
            OrbState.IDLE -> 0.3f + 0.15f * breath
        }
        out.amp = amp
        // Dust: motes drifting out from the rim and fading; streaming out while it speaks.
        val period = if (state == OrbState.SPEAKING) DUST_SPEAK_MS else DUST_MS
        for (k in 0 until out.dustN) {
            val s = hash(k + 31_337).toFloat()
            val dir = (hash(k + 4_099) * 2 * PI).toFloat()
            val f = if (reduced) s else ((t.toFloat() / period) + s) % 1f
            val reach = 1.04f + f * (if (state == OrbState.SPEAKING) 0.4f else 0.3f)
            val swirl = if (reduced) 0f else angle * 0.25f
            out.dx[k] = cos(dir + swirl) * reach
            out.dy[k] = sin(dir + swirl) * reach * 0.92f
            out.da[k] = (1f - f) * (if (reduced) 0.25f else 0.45f) * (0.5f + 0.5f * s)
        }
    }

    private fun pointBoost(state: OrbState, voice: Float, amp: Float): Float = when (state) {
        OrbState.THINKING -> 1.1f
        OrbState.LISTENING -> 0.9f + 0.3f * voice
        OrbState.SPEAKING -> 0.95f + 0.25f * amp
        else -> 0.9f
    }

    /** A line between two points: how bright, from the nearer-looking end's depth (0 far … 1 near) and [boost]. */
    fun linkAlpha(depthA: Float, depthB: Float, boost: Float): Float =
        (minOf(depthA, depthB) * minOf(depthA, depthB) * 0.32f * boost).coerceIn(0f, 0.6f)

    /** A point's dot radius, as a share of the orb's radius: bigger as it comes nearer. */
    fun dotRadius(depth: Float, n: Int): Float = (if (n <= POINTS_SMALL) 0.03f else 0.011f) * (0.55f + 0.9f * depth)

    /**
     * A point on a tilted ring (a circle seen at an angle, so an ellipse): [phi] its screen angle, [squash] how flat
     * (0 a line, 1 a circle), [theta] where round it, [radius] of the orb's radius. Returns x, y in −…+ radius.
     */
    fun ringPoint(phi: Float, squash: Float, theta: Float, radius: Float): Pair<Float, Float> {
        val ex = cos(theta) * radius
        val ey = sin(theta) * radius * squash
        return (ex * cos(phi) - ey * sin(phi)) to (ex * sin(phi) + ey * cos(phi))
    }

    /** The orbit rings while thinking: ring [k]'s comet's angle round its ring at [elapsedMs] (each its own pace). */
    fun comet(k: Int, elapsedMs: Long, lapMs: Long = COMET_MS): Float {
        val lap = lapMs + k * 430L
        val p = ((elapsedMs.coerceAtLeast(0L) + k * 700L) % lap).toFloat() / lap
        return (if (k % 2 == 0) p else 1 - p) * (2 * PI).toFloat()
    }

    /** The orbit rings' radius (of the orb's) while thinking, and the amber comets' rings while something needs him. */
    const val RING_R = 1.22f
    val AMBER_RINGS: List<Pair<Float, Float>> = listOf(0.9f to 0.34f, 3.6f to 0.28f)
    const val AMBER_R = 1.12f

    /** The equator ring's stroke and brightness while speaking, from the voice's amplitude. */
    fun equator(amp: Float): Pair<Float, Float> = (0.6f + 1.6f * amp) to (0.25f + 0.55f * amp)
}

/** The sphere's points (unit, before turning) with a steady seed each, and the joined pairs (i, j, i, j, …). */
class ConstellationGeometry(val n: Int, val xs: FloatArray, val ys: FloatArray, val zs: FloatArray, val seeds: FloatArray, val links: IntArray) {
    /** How many lines. */
    val linkCount: Int get() = links.size / 2
}

/** One frame worked out by [Constellation.frame]; the app keeps one and reuses it (no allocation per frame). */
class ConstellationFrame(val n: Int, val dustN: Int) {
    val px = FloatArray(n)
    val py = FloatArray(n)
    val depth = FloatArray(n)
    val alpha = FloatArray(n)
    val dx = FloatArray(dustN)
    val dy = FloatArray(dustN)
    val da = FloatArray(dustN)
    var linkBoost = 1f
    var core = 0.3f
    /** The voice's amplitude this frame while speaking (0 otherwise): drives the equator ring. */
    var amp = 0f
}

/**
 * "Hello, Navilife", slice 6: the orb speaks with Navilife's **real voice**. While a clip of Navilife's voice plays, the
 * apps read its loudness a few times a second (the Fold from Android's `Visualizer` on its own player's session, peak
 * RMS in millibels; the Mac from `AVAudioPlayer`'s meters, average power in dB) and this turns it into the level the
 * constellation pushes out with ([Constellation.speech]). Only a loudness number is read, never the audio itself, and only
 * of Navilife's own player. While a metered clip plays the level never drops to 0 ([FLOOR]), so a pause in the voice
 * calms the orb instead of handing it back to the made-up syllable swell; when nothing metered plays (the device's own
 * voice, or the meter isn't available) it is 0 and the swell takes over as before. (Non-AI, pure.)
 */
object VoiceOut {
    /** The quietest and loudest a spoken clip gets, in dB full scale. */
    const val QUIET_DBFS = -45f
    const val LOUD_DBFS = -9f
    /** The least a playing, metered clip reads as (a pause): the orb stays with the real voice. */
    const val FLOOR = 0.02f
    /** How often the apps read the meter while Navilife speaks. */
    const val POLL_MS = 40L

    /** A clip's loudness [dbfs] as the orb's level; 0 when nothing metered is [playing]. */
    fun level(dbfs: Float, playing: Boolean): Float {
        if (!playing || dbfs.isNaN()) return 0f
        val l = ((dbfs - QUIET_DBFS) / (LOUD_DBFS - QUIET_DBFS)).coerceIn(0f, 1f)
        return l.coerceAtLeast(FLOOR)
    }

    /** Android's `Visualizer.MeasurementPeakRms` reading (millibels, -9600 silence … 0) as dB full scale. */
    fun dbfsOfMillibels(mb: Int): Float = (mb / 100f).coerceIn(-160f, 0f)

    /**
     * The shown level follows [target]: up fast with each syllable, down a little slower so it doesn't flicker; straight
     * to 0 when the clip stops (the swell or rest takes over), and never below [FLOOR] while one plays.
     */
    fun follow(shown: Float, target: Float): Float {
        if (target <= 0f) return 0f
        val k = if (target > shown) 0.7f else 0.3f
        return (shown + (target - shown) * k).coerceIn(FLOOR, 1f)
    }
}
