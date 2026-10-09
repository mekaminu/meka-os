package os.meka.core.domain

/** A stretch when MEKA didn't run on the phone at all during waking hours: likely put to sleep or stopped by the battery manager. */
data class BatteryGap(val startMs: Long, val endMs: Long)

/** What the phone reports about its battery handling of MEKA, read on every open. */
data class BatteryFacts(
    /** `PowerManager.isIgnoringBatteryOptimizations` for MEKA. */
    val exempt: Boolean,
    /** A Samsung phone (One UI's own "sleeping apps" lists sit on top of Android's). */
    val samsung: Boolean,
)

enum class BatteryCareStatus {
    /** Nothing to show. */
    OK,

    /** Android may still restrict MEKA in the background. */
    NOT_EXEMPT,

    /** MEKA didn't run for a while during the day: it was most likely stopped. */
    STOPPED,
}

/**
 * Today's line about battery care (Reliability first, slice 1) and the steps it unfolds. [line] null: nothing shows.
 * [allowInAndroid]: offer Android's own "let MEKA run in the background" prompt. [openSamsung]: offer the Samsung
 * battery screen (the "Never sleeping apps" list).
 */
data class BatteryCareView(
    val status: BatteryCareStatus,
    val line: String?,
    val critical: Boolean,
    val steps: List<String>,
    val allowInAndroid: Boolean,
    val openSamsung: Boolean,
    /** The stop shown, so "Got it" can put it away for good. */
    val gap: BatteryGap?,
)

/**
 * Samsung battery care (build plan, Reliability first (1); non-AI, pure). The phone records a heartbeat whenever MEKA
 * runs (an open, a background sync, a notification read); a long silence during waking hours means Android or
 * Samsung's battery manager stopped it, and Today says so with a one-tap way to keep MEKA awake.
 *
 * Overnight silences don't count: an idle phone in Doze defers background work for hours even for exempt apps, so
 * only time between [WAKING_START_MIN] and [WAKING_END_MIN] (local) is measured.
 */
object BatteryCareRules {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    /** Beats closer than this to the last kept one are dropped (the list stays small). */
    const val BEAT_SPACING_MS = 5 * MINUTE

    /** Beats older than this are forgotten. */
    const val KEEP_MS = 48 * HOUR

    /** A silence counts as a stop when at least this much of it fell in waking hours (sync runs every 15 min). */
    const val STOP_WAKING_MS = 2 * HOUR

    /** Only a stop that ended within this long ago is shown. */
    const val SHOW_FOR_MS = 24 * HOUR

    /** Waking hours, minutes after local midnight: 07:00–22:30. */
    const val WAKING_START_MIN = 7 * 60
    const val WAKING_END_MIN = 22 * 60 + 30

    /** Adds a beat at [nowMs] (sorted, spaced, pruned). Unchanged when the last beat is under [BEAT_SPACING_MS] old. */
    fun record(beats: List<Long>, nowMs: Long): List<Long> {
        val kept = beats.filter { it in (nowMs - KEEP_MS)..nowMs }.sorted()
        val last = kept.lastOrNull()
        return if (last != null && nowMs - last < BEAT_SPACING_MS) kept else kept + nowMs
    }

    /** "1715,1716" ↔ beats, for the phone's preferences; anything unreadable is skipped. */
    fun encode(beats: List<Long>): String = beats.joinToString(",")
    fun decode(text: String?): List<Long> =
        text.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }.filter { it > 0 }.sorted()

    /**
     * How much of [startMs]..[endMs] fell in waking hours. [minuteOfDay] gives the local minute of the day (0..1439)
     * for an instant; the span is walked a minute-of-day boundary at a time, so a change of clocks costs at most an hour.
     */
    fun wakingMs(startMs: Long, endMs: Long, minuteOfDay: (Long) -> Int): Long {
        var t = startMs
        var total = 0L
        while (t < endMs) {
            val m = minuteOfDay(t).coerceIn(0, 24 * 60 - 1)
            val waking = m in WAKING_START_MIN until WAKING_END_MIN
            // The next boundary: the waking start or end, or midnight.
            val nextMin = when {
                m < WAKING_START_MIN -> WAKING_START_MIN
                m < WAKING_END_MIN -> WAKING_END_MIN
                else -> 24 * 60
            }
            val step = ((nextMin - m) * MINUTE).coerceAtLeast(MINUTE)
            val next = minOf(endMs, t + step)
            if (waking) total += next - t
            t = next
        }
        return total
    }

    /** The latest stop (two beats with [STOP_WAKING_MS] of waking time between them) that ended in the last day, else null. */
    fun lastStop(beats: List<Long>, nowMs: Long, minuteOfDay: (Long) -> Int): BatteryGap? {
        val sorted = beats.filter { it <= nowMs }.sorted()
        for (i in sorted.size - 1 downTo 1) {
            val start = sorted[i - 1]
            val end = sorted[i]
            if (nowMs - end > SHOW_FOR_MS) return null
            if (wakingMs(start, end, minuteOfDay) >= STOP_WAKING_MS) return BatteryGap(start, end)
        }
        return null
    }

    /** "14:05" from a minute of the day. */
    fun clock(minuteOfDay: Int): String {
        val m = minuteOfDay.coerceIn(0, 24 * 60 - 1)
        return "${(m / 60).toString().padStart(2, '0')}:${(m % 60).toString().padStart(2, '0')}"
    }

    /** The steps on a Samsung phone, in One UI's words. */
    val SAMSUNG_STEPS = listOf(
        "Settings → Battery → Background usage limits → Never sleeping apps → + → MEKA",
        "Make sure MEKA isn't in Sleeping apps or Deep sleeping apps",
        "Settings → Apps → MEKA → Battery → Unrestricted",
    )

    /** Any other phone. */
    val ANDROID_STEPS = listOf(
        "Settings → Apps → MEKA → Battery → Unrestricted",
    )

    /**
     * Today's line. A stop wins (it is what actually went wrong), unless Meka put that one away ([dismissedEndMs]
     * at or after its end); then a phone that isn't exempt yet gets the quieter accent line.
     */
    fun view(facts: BatteryFacts, beats: List<Long>, nowMs: Long, minuteOfDay: (Long) -> Int, dismissedEndMs: Long?): BatteryCareView {
        val steps = if (facts.samsung) SAMSUNG_STEPS else ANDROID_STEPS
        val gap = lastStop(beats, nowMs, minuteOfDay)?.takeIf { dismissedEndMs == null || it.endMs > dismissedEndMs }
        if (gap != null) {
            val from = clock(minuteOfDay(gap.startMs))
            val to = clock(minuteOfDay(gap.endMs))
            val who = if (facts.samsung) "Samsung's battery saver" else "Android's battery saver"
            return BatteryCareView(
                BatteryCareStatus.STOPPED,
                "MEKA was stopped $from–$to, likely by $who · Keep MEKA awake",
                critical = true, steps = steps, allowInAndroid = !facts.exempt, openSamsung = facts.samsung, gap = gap,
            )
        }
        if (!facts.exempt) {
            val line = if (facts.samsung) "Samsung may put MEKA to sleep · Keep MEKA awake" else "Android may pause MEKA · Keep MEKA awake"
            return BatteryCareView(
                BatteryCareStatus.NOT_EXEMPT, line, critical = false, steps = steps,
                allowInAndroid = true, openSamsung = facts.samsung, gap = null,
            )
        }
        return BatteryCareView(BatteryCareStatus.OK, null, false, steps, allowInAndroid = false, openSamsung = facts.samsung, gap = null)
    }
}
