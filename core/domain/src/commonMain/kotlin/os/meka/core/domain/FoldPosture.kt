package os.meka.core.domain

/*
 * The bedside clock on the Fold 8 (build plan "Bedside clock doesn't appear on the Fold 8", slice 1; non-AI, pure).
 *
 * Meka opened MEKA on the inner screen, landscape, half folded like a laptop, and Today stayed as it was. What the
 * phone reports is read here from every source the Fold has, so a misreport in one doesn't hide the clock:
 *  - Jetpack WindowManager's folding feature (state, orientation, bounds): half open with the hinge across is the
 *    table stand; half open in a landscape window is the table stand too, whatever orientation is reported (some
 *    devices report it relative to the display's natural orientation; a book-held Fold's window is portrait);
 *  - the hinge angle sensor (`Sensor.TYPE_HINGE_ANGLE`, no permission): 60–130° with the window landscape and the
 *    angle steady for a moment is the table stand, even with no folding feature at all;
 *  - a manual way in (Ask → Daily → Bedside clock, or Talk "bedside clock"), with ‹ Back to leave.
 * The diagnostics line ("Fold: half open, hinge across · hinge 95°") shows in Appearance and Health, and the last
 * [FoldPostureLog.KEEP] changes are kept on the phone, so what the Fold 8 actually reports can be read.
 */

/** What WindowManager says about the fold, as the device read it. */
enum class FoldHinge {
    /** No folding feature in the window (the cover screen, or a device that reports none). */
    NONE,
    FLAT,
    HALF_OPENED,
}

/** One reading of everything the Fold reports about how it is held. */
data class FoldReading(
    val hinge: FoldHinge,
    /** WindowManager's orientation of the fold: true when HORIZONTAL (across the screen); null with no feature. */
    val across: Boolean? = null,
    /** WindowManager's isSeparating; null with no feature. */
    val separating: Boolean? = null,
    /** WindowManager's occlusion type ("NONE", "FULL"); null with no feature. */
    val occlusion: String? = null,
    /** The window's size in px. */
    val windowWidthPx: Int,
    val windowHeightPx: Int,
    /** The hinge angle sensor's last reading in degrees (180 flat open, 0 closed); null without the sensor. */
    val hingeAngle: Float? = null,
    /** The hinge angle has held steady for [FoldPostureRules.STEADY_MS] ([FoldPostureRules.steady]). */
    val hingeSteady: Boolean = false,
) {
    val landscape: Boolean get() = windowWidthPx > windowHeightPx
}

/** Which source put the Fold in its posture, for the diagnostics line. */
enum class PostureSource { WINDOW, LANDSCAPE, HINGE_SENSOR, NONE }

data class PostureCall(val posture: FoldPosture, val source: PostureSource)

object FoldPostureRules {
    /** The hinge angle that reads as a table stand, inclusive. */
    const val TABLE_MIN_DEG = 60f
    const val TABLE_MAX_DEG = 130f

    /** How long the hinge angle must hold before the sensor alone says table stand. */
    const val STEADY_MS = 1_500L

    /** How far the angle may wander in that time and still be steady. */
    const val STEADY_SPREAD_DEG = 6f

    /**
     * How the Fold is held. WindowManager first: half open with the hinge across, or half open in a landscape window,
     * is the table stand; half open in a portrait window with the hinge down the middle is a book. Otherwise the hinge
     * angle sensor: 60–130°, steady, in a landscape window is the table stand. Anything else is flat.
     */
    fun posture(r: FoldReading): PostureCall {
        if (r.hinge == FoldHinge.HALF_OPENED) {
            return when {
                r.across == true -> PostureCall(FoldPosture.TABLETOP, PostureSource.WINDOW)
                r.landscape -> PostureCall(FoldPosture.TABLETOP, PostureSource.LANDSCAPE)
                else -> PostureCall(FoldPosture.BOOK, PostureSource.WINDOW)
            }
        }
        val a = r.hingeAngle
        if (a != null && a >= TABLE_MIN_DEG && a <= TABLE_MAX_DEG && r.landscape && r.hingeSteady) {
            return PostureCall(FoldPosture.TABLETOP, PostureSource.HINGE_SENSOR)
        }
        return PostureCall(FoldPosture.FLAT, PostureSource.NONE)
    }

    /**
     * Whether the hinge angle has held: [samples] are (time ms, degrees), oldest first; steady when they reach back
     * at least [STEADY_MS] before [nowMs] and every one since then is within [STEADY_SPREAD_DEG] of the others.
     */
    fun steady(samples: List<Pair<Long, Float>>, nowMs: Long): Boolean {
        if (samples.isEmpty()) return false
        val from = nowMs - STEADY_MS
        // The newest sample at or before the window's start (the angle then), plus everything after it.
        val startIdx = samples.indexOfLast { it.first <= from }
        if (startIdx < 0) return false
        val window = samples.subList(startIdx, samples.size).map { it.second }
        return (window.max() - window.min()) <= STEADY_SPREAD_DEG
    }

    /** Samples older than the steady window (bar the one that opens it) are dropped. */
    fun trim(samples: List<Pair<Long, Float>>, nowMs: Long): List<Pair<Long, Float>> {
        val from = nowMs - STEADY_MS
        val startIdx = samples.indexOfLast { it.first <= from }
        return if (startIdx <= 0) samples else samples.subList(startIdx, samples.size)
    }

    /**
     * The diagnostics line: "Fold: flat", "Fold: half open, hinge across", "Fold: half open, hinge down the middle",
     * "Fold: no fold reported", then the hinge sensor's angle when there is one ("· hinge 95°"), then why it is the
     * bedside clock when that isn't WindowManager's own word ("· bedside from the landscape window" / "… from the
     * hinge sensor").
     */
    fun line(r: FoldReading): String {
        val base = when (r.hinge) {
            FoldHinge.NONE -> "Fold: no fold reported"
            FoldHinge.FLAT -> "Fold: flat"
            FoldHinge.HALF_OPENED -> if (r.across == true) "Fold: half open, hinge across" else "Fold: half open, hinge down the middle"
        }
        val parts = mutableListOf(base)
        r.hingeAngle?.let { parts += "hinge ${it.toInt()}°" }
        when (posture(r).source) {
            PostureSource.LANDSCAPE -> parts += "bedside from the landscape window"
            PostureSource.HINGE_SENSOR -> parts += "bedside from the hinge sensor"
            else -> {}
        }
        return parts.joinToString(" · ")
    }

    /**
     * One reading as a log entry: every raw fact, short ("HALF_OPENED across=false sep=false occ=NONE 2176x1812
     * angle=95 steady → TABLETOP/LANDSCAPE"). Nothing personal: only what the hinge and the window say.
     */
    fun entry(r: FoldReading): String {
        val call = posture(r)
        val angle = r.hingeAngle?.let { " angle=${it.toInt()}${if (r.hingeSteady) " steady" else ""}" } ?: ""
        return "${r.hinge.name} across=${r.across} sep=${r.separating} occ=${r.occlusion} ${r.windowWidthPx}x${r.windowHeightPx}$angle → ${call.posture.name}/${call.source.name}"
    }
}

/** The bedside clock's manual way in and the Back that leaves it ([FoldPostureRules]; pure). */
object BedsideEntryRules {
    /** Ask → Daily's row. */
    const val ROW_LABEL = "Bedside clock"
    const val ROW_LINE = "A big clock for the nightstand · half fold it on a table"

    /** What MEKA says before the clock opens from Talk. */
    const val OPENING = "Here's the bedside clock."
    const val OPENED = "Opened the bedside clock."

    /**
     * Whether the bedside clock shows: the Fold stands on a table ([auto]) and Meka hasn't stepped back out of it
     * since, or he opened it himself ([manual]).
     */
    fun shows(auto: Boolean, manual: Boolean, dismissed: Boolean): Boolean = manual || (auto && !dismissed)

    /** ‹ Back leaves a clock opened by hand; on a table stand it stays away until the Fold stops standing. */
    fun dismissedAfterBack(auto: Boolean): Boolean = auto

    /** A table stand that ends clears the step-back, so the next one shows the clock again. */
    fun dismissedAfterPosture(auto: Boolean, dismissed: Boolean): Boolean = dismissed && auto
}

/** The last posture changes on this phone, newest first, at most [KEEP] (pure; the device keeps the list). */
object FoldPostureLog {
    const val KEEP = 20

    /** One change: when, and [FoldPostureRules.entry]. */
    data class Change(val atMs: Long, val entry: String)

    /**
     * [log] with [reading] added at [atMs] when its posture or source differs from the newest entry's (a window
     * resize alone isn't a change); newest first, cut to [KEEP].
     */
    fun add(log: List<Change>, reading: FoldReading, atMs: Long): List<Change> {
        val entry = FoldPostureRules.entry(reading)
        val outcome = entry.substringAfter("→ ")
        if (log.firstOrNull()?.entry?.substringAfter("→ ") == outcome) return log
        return (listOf(Change(atMs, entry)) + log).take(KEEP)
    }

    /** "12 Oct 18:14 · HALF_OPENED … → TABLETOP/WINDOW" for Health, in [cal]'s time. */
    fun line(c: Change, cal: LocalCalendar): String =
        "${CivilDate.shortLabel(cal.epochDayOf(c.atMs))} ${LocalClock.formatMinute(cal.minuteOfDay(c.atMs))} · ${c.entry}"

    /** One change per line, for the phone's own storage: "atMs|entry". */
    fun encode(log: List<Change>): String = log.joinToString("\n") { "${it.atMs}|${it.entry.replace("\n", " ")}" }

    fun decode(text: String?): List<Change> = text.orEmpty().lines().mapNotNull { l ->
        val bar = l.indexOf('|')
        if (bar <= 0) return@mapNotNull null
        val at = l.substring(0, bar).toLongOrNull() ?: return@mapNotNull null
        Change(at, l.substring(bar + 1))
    }.take(KEEP)
}
