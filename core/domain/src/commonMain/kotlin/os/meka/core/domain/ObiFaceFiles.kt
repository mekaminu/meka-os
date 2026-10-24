package os.meka.core.domain

/**
 * Obi's face, slice 3: how a device keeps the face's seven files (the film loop and six mouth patches) in its own
 * storage. It asks the server's list at most about weekly ([RECHECK_MS]; after a failed or empty look, again in
 * [RETRY_MS]), fetches only the files whose tag changed, lets go of any the server no longer has, and draws the face only
 * when all seven are kept. Until then (or with Face off) the orb stays, with no error shown. The names here are the
 * wire's allow-list ([os.meka.core.wire.FaceCodec.FILES]); this file holds nothing personal.
 */
object FaceFileRules {
    const val RECHECK_MS = 7L * 24 * 60 * 60 * 1000
    /** A look that failed, or found the folder empty or half there (he may be uploading): look again sooner. */
    const val RETRY_MS = 6L * 60 * 60 * 1000

    /**
     * Slice 4: the seven names for the Mac (Swift sees the domain, not the wire): the same list as
     * [os.meka.core.wire.FaceCodec.FILES] (a facade test keeps them equal), the film first.
     */
    const val LOOP = "face-loop.mp4"
    val FILES: List<String> = listOf(LOOP) + MouthShape.entries.map { MouthMix.file(it) }
    fun allowed(name: String): Boolean = name in FILES

    /** The cache's index: which files are kept, by the tag the server gave them, and when the list was last read. */
    data class Index(val kept: Map<String, String> = emptyMap(), val checkedAtMs: Long = 0L, val complete: Boolean = false)

    /** True when the list should be asked for now. A clock that went backwards asks again. */
    fun due(index: Index, nowMs: Long): Boolean {
        if (index.checkedAtMs <= 0L || nowMs < index.checkedAtMs) return true
        val wait = if (index.complete) RECHECK_MS else RETRY_MS
        return nowMs - index.checkedAtMs >= wait
    }

    /** What to do with the server's list ([offered]: name → tag): fetch the new or changed, drop the ones it no longer has. */
    data class Plan(val fetch: List<String>, val drop: List<String>)

    fun plan(index: Index, offered: Map<String, String>, allowed: Set<String>): Plan {
        val wanted = offered.filterKeys { it in allowed }
        val fetch = wanted.filter { (name, tag) -> index.kept[name] != tag }.keys.sorted()
        val drop = index.kept.keys.filter { it !in wanted }.sorted()
        return Plan(fetch, drop)
    }

    /** After a look: [fetched] (name → tag) kept, [plan]'s drops gone; complete when every allowed name is kept. */
    fun after(index: Index, plan: Plan, fetched: Map<String, String>, allowed: Set<String>, nowMs: Long): Index {
        val kept = (index.kept - plan.drop.toSet()) + fetched.filterKeys { it in allowed }
        return Index(kept, nowMs, ready(kept.keys, allowed))
    }

    /** A failed look keeps what's kept and tries again in [RETRY_MS]. */
    fun failed(index: Index, nowMs: Long): Index = index.copy(checkedAtMs = nowMs, complete = false)

    /** The face shows only with every allowed file kept. */
    fun ready(kept: Set<String>, allowed: Set<String>): Boolean = allowed.isNotEmpty() && kept.containsAll(allowed)

    /** The face replaces the orb on the big stages when the files are there and Face is on (it is by default). */
    fun shows(ready: Boolean, faceOn: Boolean): Boolean = ready && faceOn

    /** The index as text for the device's storage: `checkedAtMs`, then `name\ttag` per line. Unreadable lines are dropped. */
    fun encode(index: Index): String = buildString {
        append(index.checkedAtMs).append('\n')
        index.kept.toSortedMap().forEach { (n, t) -> append(n).append('\t').append(t).append('\n') }
    }

    fun decode(text: String?, allowed: Set<String>): Index {
        if (text.isNullOrBlank()) return Index()
        val lines = text.lines()
        val at = lines.first().trim().toLongOrNull() ?: return Index()
        val kept = lines.drop(1).mapNotNull { l ->
            val parts = l.split('\t')
            if (parts.size != 2 || parts[0] !in allowed || parts[1].isEmpty() || parts[1].length > 128) null else parts[0] to parts[1]
        }.toMap()
        return Index(kept, at, ready(kept.keys, allowed))
    }
}

/**
 * Obi's face, slice 3: how the face looks moment to moment on the big stages. It dissolves in from the orb over
 * [DISSOLVE_MS] once the stage has settled (the orb's points fading as the face comes up); listening it is a touch
 * brighter; thinking, a slow gold glint passes across it; reduced motion: no glint and an instant swap (a plain fade).
 */
object FaceLook {
    const val DISSOLVE_MS = 600L
    const val GLINT_MS = 2400L
    const val LISTEN_BRIGHT = 1.08f

    /** How far the face has come in (0…1), [msSinceSettled] after the stage settled; negative = not yet. */
    fun faceIn(msSinceSettled: Long, reduced: Boolean): Float = when {
        msSinceSettled < 0 -> 0f
        reduced -> if (msSinceSettled >= HelloEntrance.FADE_MS) 1f else msSinceSettled.toFloat() / HelloEntrance.FADE_MS
        else -> (msSinceSettled.toFloat() / DISSOLVE_MS).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }
    }

    /** The face's brightness multiplier for the orb's state. */
    fun brightness(state: OrbState): Float = if (state == OrbState.LISTENING) LISTEN_BRIGHT else 1f

    /** Where the thinking glint is across the face (0…1, left to right), or null when there is none. */
    fun glint(state: OrbState, tMs: Long, reduced: Boolean): Float? =
        if (state != OrbState.THINKING || reduced) null else (tMs % GLINT_MS).toFloat() / GLINT_MS

    /**
     * The mouth for a voice with no timings: from the loudness while speaking, nothing otherwise (the film's own
     * blinking face). Reduced motion keeps the shapes (only the mouth moves, as the board asks).
     */
    fun mouthFromLevel(state: OrbState, level: Float, tMs: Long): MouthShape? =
        if (state != OrbState.SPEAKING) null else LipSync.shapeForLoudness(level.toDouble(), LipSync.beat(tMs))

    /**
     * Slice 3c, the docked orb (the bottom bar's small gold orb stays an orb; a face at 54 dp reads poorly): while Obi
     * speaks with the face shown, its points hint at a face, faintly. [hint] is how much (1 speaking with the face, 0
     * otherwise; the app eases between them over [HINT_MS], reduced motion at once).
     */
    const val HINT_MS = 400L
    const val HINT_STRENGTH = 0.55f

    fun hint(state: OrbState, faceShown: Boolean): Float = if (faceShown && state == OrbState.SPEAKING) 1f else 0f

    /**
     * The face's silhouette in the orb's own units (the sphere's radius is 1, y down): 1 on the face (an oval a little
     * taller than wide), 0 off it and in the eyes and mouth, so the points there stay dark and the features read.
     */
    fun onFace(x: Float, y: Float): Boolean {
        fun inEllipse(cx: Float, cy: Float, rx: Float, ry: Float): Boolean {
            val dx = (x - cx) / rx
            val dy = (y - cy) / ry
            return dx * dx + dy * dy <= 1f
        }
        if (!inEllipse(0f, -0.04f, 0.70f, 0.90f)) return false
        if (inEllipse(-0.29f, -0.20f, 0.15f, 0.10f) || inEllipse(0.29f, -0.20f, 0.15f, 0.10f)) return false // the eyes
        if (inEllipse(0f, 0.44f, 0.24f, 0.09f)) return false // the mouth
        return true
    }

    /** A point's brightness multiplier at ([x], [y]) for [hint]: brighter on the face, dimmer off it; 1 with no hint. */
    fun pointShade(x: Float, y: Float, hint: Float): Float {
        val h = hint.coerceIn(0f, 1f) * HINT_STRENGTH
        return if (onFace(x, y)) 1f + h else 1f - h
    }

    /** The Face setting in Ask → Settings → Obi's voice. */
    const val SETTING_TITLE = "Face"
    const val ON = "On"
    const val OFF = "Off"
    /** Under the chips while the face's files aren't on this device yet. */
    const val WAITING_LINE = "Shows once the face's files are on Navilife's server; the orb until then"
}

/**
 * The mouth for a shape that comes and goes live (the loudness path): a new shape waits until the one showing has had
 * [LipSync.HOLD_MS], then cross-fades in over [LipSync.FADE_MS], the same timing as the marked path ([LipSync.at]).
 * One per face on screen; [step] is called each frame.
 */
class MouthFollower {
    private var from: MouthShape? = null
    private var to: MouthShape? = null
    private var changedAt = Long.MIN_VALUE / 2

    fun step(wanted: MouthShape?, tMs: Long): LipSync.Frame {
        if (wanted != to && tMs - changedAt >= LipSync.HOLD_MS) {
            from = to
            to = wanted
            changedAt = tMs
        }
        val mix = ((tMs - changedAt).toDouble() / LipSync.FADE_MS).coerceIn(0.0, 1.0)
        return if (mix >= 1.0) LipSync.Frame(to, to, 1.0) else LipSync.Frame(from, to, mix)
    }
}
