package os.meka.core.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Obi's face (slice 1): the rules both apps draw the talking face by, so the Fold and the Mac match the approved mockup.
 * The face's files (a film loop and six mouth patches) come from the server ([os.meka.core.wire.FaceCodec]); nothing
 * personal is held here, only the geometry, the gold tint and the lip-sync timing.
 */
enum class MouthShape { REST, M, F, EE, OH, AH }

/**
 * Where things sit, in the film's own pixels. The film is 540×720; the apps draw only its crop (hiding the vendor's HUD
 * text) and fade its edge to black with an ellipse. The mouth patches (360×330 PNGs with their own feathered alpha) sit
 * at [MOUTH] in crop pixels. The mockup drew the crop at 624×900 (×1.5): the patch at 180, 570, 270×247.5.
 */
object FaceGeometry {
    const val FILM_W = 540
    const val FILM_H = 720

    data class Box(val x: Double, val y: Double, val w: Double, val h: Double) {
        fun scaled(k: Double) = Box(x * k, y * k, w * k, h * k)
    }

    /** The crop of the film that is drawn, in film pixels. */
    val CROP = Box(62.0, 10.0, 416.0, 600.0)

    /** The mouth patch, in crop pixels: x 120…300, y 380…545. */
    val MOUTH = Box(120.0, 380.0, 180.0, 165.0)

    /** The edge fade: an ellipse centred at 50 % / 43 % of the crop, clear inside 55 % of its radius, black at 100 %. */
    const val FADE_CX = 0.50
    const val FADE_CY = 0.43
    const val FADE_INNER = 0.55
    const val FADE_OUTER = 1.00
    /** The ellipse's vertical radius is its horizontal one (half the crop's width) × this. */
    const val FADE_TALL = 1.5

    /** The scale that fits the crop inside [w]×[h] (aspect kept). */
    fun fit(w: Double, h: Double): Double = minOf(w / CROP.w, h / CROP.h)

    /** The mouth patch's box in a view [w]×[h] wide, the crop fitted and centred in it. */
    fun mouthIn(w: Double, h: Double): Box {
        val k = fit(w, h)
        val ox = (w - CROP.w * k) / 2
        val oy = (h - CROP.h * k) / 2
        val m = MOUTH.scaled(k)
        return Box(ox + m.x, oy + m.y, m.w, m.h)
    }

    /** How much of the face shows at a point of the crop ([u], [v] in 0…1): 1 in the middle, 0 past the ellipse's edge. */
    fun fadeAlpha(u: Double, v: Double): Double {
        val rx = 0.5
        val ry = rx * FADE_TALL * CROP.w / CROP.h // in v units: the ellipse's height relative to the crop's
        val dx = (u - FADE_CX) / rx
        val dy = (v - FADE_CY) / ry
        val d = kotlin.math.sqrt(dx * dx + dy * dy)
        return when {
            d <= FADE_INNER -> 1.0
            d >= FADE_OUTER -> 0.0
            else -> 1.0 - (d - FADE_INNER) / (FADE_OUTER - FADE_INNER)
        }
    }
}

/**
 * The gold look: the mockup's CSS `grayscale(1) sepia(1) saturate(1.5) hue-rotate(-6deg) brightness(1.55)
 * contrast(1.18)`. Because the chain starts with grayscale, every colour becomes a function of its luminance alone:
 * [lut] gives the exact result (each step clamped to 0…1, as a browser does) for 256 luminance levels (the Mac's
 * Core Image colour cube and the Fold's shader read it); [matrix] is the same chain as one 4×5 colour matrix without the
 * in-between clamps (Android's ColorMatrix layout, offsets in 0…255), for the cheap path; it matches [lut] wherever no
 * step clips, i.e. the darker two-thirds of the face.
 */
object FaceGold {
    const val SATURATE = 1.5
    const val HUE_DEG = -6.0
    const val BRIGHTNESS = 1.55
    const val CONTRAST = 1.18

    private val GRAY = doubleArrayOf(0.2126, 0.7152, 0.0722)
    private val SEPIA = arrayOf(doubleArrayOf(0.393, 0.769, 0.189), doubleArrayOf(0.349, 0.686, 0.168), doubleArrayOf(0.272, 0.534, 0.131))

    private fun saturate(s: Double) = arrayOf(
        doubleArrayOf(0.213 + 0.787 * s, 0.715 - 0.715 * s, 0.072 - 0.072 * s),
        doubleArrayOf(0.213 - 0.213 * s, 0.715 + 0.285 * s, 0.072 - 0.072 * s),
        doubleArrayOf(0.213 - 0.213 * s, 0.715 - 0.715 * s, 0.072 + 0.928 * s),
    )

    private fun hue(deg: Double): Array<DoubleArray> {
        val a = deg * PI / 180
        val c = cos(a)
        val s = sin(a)
        return arrayOf(
            doubleArrayOf(0.213 + c * 0.787 - s * 0.213, 0.715 - c * 0.715 - s * 0.715, 0.072 - c * 0.072 + s * 0.928),
            doubleArrayOf(0.213 - c * 0.213 + s * 0.143, 0.715 + c * 0.285 + s * 0.140, 0.072 - c * 0.072 - s * 0.283),
            doubleArrayOf(0.213 - c * 0.213 - s * 0.787, 0.715 - c * 0.715 + s * 0.715, 0.072 + c * 0.928 + s * 0.072),
        )
    }

    private fun apply(m: Array<DoubleArray>, v: DoubleArray, clamp: Boolean) = DoubleArray(3) { r ->
        val x = m[r][0] * v[0] + m[r][1] * v[1] + m[r][2] * v[2]
        if (clamp) x.coerceIn(0.0, 1.0) else x
    }

    /** One colour (0…1 each) through the chain, clamped after every step as CSS does. */
    fun tint(r: Double, g: Double, b: Double): DoubleArray {
        val y = (GRAY[0] * r + GRAY[1] * g + GRAY[2] * b).coerceIn(0.0, 1.0)
        var v = doubleArrayOf(y, y, y)
        v = apply(SEPIA, v, true)
        v = apply(saturate(SATURATE), v, true)
        v = apply(hue(HUE_DEG), v, true)
        v = DoubleArray(3) { (v[it] * BRIGHTNESS).coerceIn(0.0, 1.0) }
        v = DoubleArray(3) { ((v[it] - 0.5) * CONTRAST + 0.5).coerceIn(0.0, 1.0) }
        return v
    }

    /** 256 luminance levels → packed 0xRRGGBB. */
    val lut: IntArray by lazy {
        IntArray(256) { i ->
            val y = i / 255.0
            val c = tint(y, y, y)
            (c[0] * 255).roundToInt() shl 16 or ((c[1] * 255).roundToInt() shl 8) or (c[2] * 255).roundToInt()
        }
    }

    private fun mul(a: Array<DoubleArray>, b: Array<DoubleArray>) = Array(3) { r -> DoubleArray(3) { c -> (0..2).sumOf { a[r][it] * b[it][c] } } }

    /** The 20 numbers of an Android ColorMatrix (rows R, G, B, A; columns r, g, b, a, offset in 0…255). */
    val matrix: FloatArray by lazy {
        val gray = Array(3) { GRAY.copyOf() }
        val m = mul(hue(HUE_DEG), mul(saturate(SATURATE), mul(SEPIA, gray)))
        val k = BRIGHTNESS * CONTRAST
        val off = (0.5 - 0.5 * CONTRAST) * 255
        FloatArray(20).also { out ->
            for (r in 0..2) {
                for (c in 0..2) out[r * 5 + c] = (m[r][c] * k).toFloat()
                out[r * 5 + 4] = off.toFloat()
            }
            out[18] = 1f
        }
    }
}

/**
 * Lips that move with the words. A voice that tells when each sound starts (Polly's viseme speech marks; ElevenLabs'
 * character timings) becomes a list of [Cue]s; the apps ask [at] each frame which patch to draw and how far through the
 * 90 ms cross-fade from the previous one they are. A shape is held at least [HOLD_MS] (a sound shorter than that is
 * skipped for the next one), pauses close the mouth, and before the first sound and after the last there is no patch:
 * the film's own face shows.
 */
object LipSync {
    const val HOLD_MS = 120L
    const val FADE_MS = 90L
    /** After the last sound, the closed mouth stays this long before the patch goes and the film's own face is back. */
    const val TAIL_MS = 150L

    /** One sound from the voice: when it starts in the clip, and the voice's own name for it. */
    data class Mark(val atMs: Long, val viseme: String)

    /** From [atMs] the patch is [shape]; null = no patch (the film's face). */
    data class Cue(val atMs: Long, val shape: MouthShape?)

    /** Polly's English visemes → the six shapes (p→m; f→f; a/@→ah; o/u→oh; i/e/E→ee; everything else and sil→rest). */
    fun shapeForPolly(viseme: String): MouthShape = when (viseme) {
        "p" -> MouthShape.M
        "f" -> MouthShape.F
        "a", "@" -> MouthShape.AH
        "o", "u" -> MouthShape.OH
        "i", "e", "E" -> MouthShape.EE
        else -> MouthShape.REST
    }

    /**
     * A letter of an ElevenLabs character timing → a shape, by the sound it most often makes in English (b/m/p closed;
     * f/v; a; o/u/w rounded; e/i/y spread; anything else, spaces and punctuation, rest).
     */
    fun shapeForLetter(ch: Char): MouthShape = when (ch.lowercaseChar()) {
        'b', 'm', 'p' -> MouthShape.M
        'f', 'v' -> MouthShape.F
        'a' -> MouthShape.AH
        'o', 'u', 'w' -> MouthShape.OH
        'e', 'i', 'y' -> MouthShape.EE
        else -> MouthShape.REST
    }

    /** ElevenLabs' with-timestamps alignment (characters and their start times in seconds) → marks named by shape. */
    fun marksFromCharacters(chars: List<String>, startsSec: List<Double>): List<Pair<Long, MouthShape>> =
        chars.zip(startsSec).mapNotNull { (c, t) ->
            val ch = c.firstOrNull() ?: return@mapNotNull null
            (t * 1000).roundToInt().toLong() to shapeForLetter(ch)
        }

    /** Polly's marks for a clip [clipMs] long → cues. */
    fun fromPolly(marks: List<Mark>, clipMs: Long): List<Cue> = cues(marks.map { it.atMs to shapeForPolly(it.viseme) }, clipMs)

    /**
     * Timed shapes → cues: in time order; a change sooner than [HOLD_MS] after the last one waits until the hold is up,
     * and if a newer sound arrives in the meantime the waiting one is dropped for it; a repeat of the shape showing is no
     * change. Ends closed at the last sound's end (or [clipMs]), then no patch [TAIL_MS] later.
     */
    fun cues(timed: List<Pair<Long, MouthShape>>, clipMs: Long): List<Cue> {
        val sorted = timed.filter { it.first >= 0 }.sortedBy { it.first }
        if (sorted.isEmpty()) return emptyList()
        val out = mutableListOf<Cue>()
        var i = 0
        while (i < sorted.size) {
            val (t, shape) = sorted[i]
            val last = out.lastOrNull()
            if (last == null) {
                out += Cue(t, shape)
                i++
                continue
            }
            val earliest = last.atMs + HOLD_MS
            if (t >= earliest) {
                if (shape != last.shape) out += Cue(t, shape)
                i++
                continue
            }
            // Too soon: the newest sound that starts before the hold ends wins, shown when the hold ends.
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1].first < earliest) j++
            val winner = sorted[j].second
            if (winner != last.shape) out += Cue(earliest, winner)
            i = j + 1
        }
        val end = maxOf(clipMs, out.last().atMs)
        if (out.last().shape != MouthShape.REST) out += Cue(maxOf(end, out.last().atMs + HOLD_MS), MouthShape.REST)
        out += Cue(out.last().atMs + TAIL_MS, null)
        return out
    }

    /** What to draw at [tMs]: [to] faded in [mix] (0…1) over [from]; both null = the film's own face. */
    data class Frame(val from: MouthShape?, val to: MouthShape?, val mix: Double)

    fun at(cues: List<Cue>, tMs: Long): Frame {
        val idx = cues.indexOfLast { it.atMs <= tMs }
        if (idx < 0) return Frame(null, null, 1.0)
        val cur = cues[idx]
        val prev = if (idx > 0) cues[idx - 1].shape else null
        val mix = ((tMs - cur.atMs).toDouble() / FADE_MS).coerceIn(0.0, 1.0)
        return if (mix >= 1.0) Frame(cur.shape, cur.shape, 1.0) else Frame(prev, cur.shape, mix)
    }

    /**
     * A voice with no timings (Gemini Live, the phone's own voice): the shape from the live loudness (0…1) and a
     * syllable beat that flips about every 150 ms. Silence → no patch (the film's closed mouth); quiet → rest; middling
     * → rest and ee alternating with the beat; loud → ah.
     */
    fun shapeForLoudness(level: Double, beat: Boolean): MouthShape? = when {
        level < 0.04 -> null
        level < 0.18 -> MouthShape.REST
        level < 0.55 -> if (beat) MouthShape.EE else MouthShape.REST
        else -> MouthShape.AH
    }

    /** The beat for [tMs]: on for one [BEAT_MS], off for the next. */
    const val BEAT_MS = 150L
    fun beat(tMs: Long): Boolean = (tMs / BEAT_MS) % 2 == 0L
}
