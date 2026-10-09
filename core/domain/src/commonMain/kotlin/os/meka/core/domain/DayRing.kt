package os.meka.core.domain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** What a brass arc on the Day ring stands for. */
enum class DayArcKind { EVENT, TASK, SESSION }

/**
 * One arc of the Day ring: a timed event, a planned task or a booked session, as local minutes of today (clipped to
 * the day: an event that began yesterday starts at 0, one running past midnight ends at 1440).
 */
data class DayArc(
    /** The timeline's id: "e-<event>", "t-<task>", "s-<habit>". */
    val id: String,
    val kind: DayArcKind,
    val startMinute: Int,
    val endMinute: Int,
    /** Over already (drawn dimmer). */
    val past: Boolean,
    /** On now (started, not over): the living ring makes it glow with its breath (Living Today, slice 3). */
    val current: Boolean = false,
) {
    /** Booked sessions (the gym) stand out on the ring: a wider, brighter arc. */
    val highlighted: Boolean get() = kind == DayArcKind.SESSION
    /** Where the arc starts, clockwise from midnight at the top. */
    val startDegrees: Float get() = DayRingRules.degrees(startMinute)

    /** How far round it runs (at least [DayRingRules.MIN_SWEEP_DEGREES], so a 5-minute call still shows). */
    val sweepDegrees: Float get() = maxOf(DayRingRules.degrees(endMinute) - startDegrees, DayRingRules.MIN_SWEEP_DEGREES)
}

/**
 * A faint band on the Day ring's track: today's work hours on a work day ([WorkBlock]), clipped to the day. Not an arc
 * (it isn't booked), so it never counts as a thing to do and can't be tapped.
 */
data class DayBand(val startMinute: Int, val endMinute: Int, /** At work now. */ val current: Boolean = false) {
    val startDegrees: Float get() = DayRingRules.degrees(startMinute)
    val sweepDegrees: Float get() = maxOf(DayRingRules.degrees(endMinute) - startDegrees, 0f)
}

/**
 * A running fast as an inner arc on the Day ring (Living Today, slice 3): drawn from when the fast began round to its
 * goal on the same 24-hour dial (a 16 h fast that began 20:05 runs from 20:05 round past midnight to 12:05), filling
 * as it goes. A fast of 24 h or more (extended) uses the whole inner circle from the top, filled by its progress.
 */
data class DayFastArc(
    val startDegrees: Float,
    /** The whole fast to its goal: at most 360. */
    val sweepDegrees: Float,
    /** Share of the goal done, 0 to 1. */
    val progress: Float,
    val reachedGoal: Boolean,
) {
    /** The filled part of [sweepDegrees]. */
    val filledDegrees: Float get() = sweepDegrees * progress.coerceIn(0f, 1f)
}

/**
 * The Day ring at the top of Today (motion pass 2, the opening moment): a 24-hour dial with midnight at the top, the
 * day's events, planned tasks and booked sessions as brass arcs, free time left dark, a "now" needle, and in the
 * centre "3 h 45 free · 4 to do".
 */
data class DayRing(
    val arcs: List<DayArc>,
    /** Local minute of the day now (0–1439): where the needle points. */
    val nowMinute: Int,
    /** Free minutes left today, from now (or [DayRingRules.DAY_START_MIN]) to [DayRingRules.DAY_END_MIN], outside every arc. */
    val freeMinutes: Int,
    /** Open tasks left today: Needs you, Up next, the timeline's planned tasks and Anytime today. */
    val toDo: Int,
    /** Work hours as a faint band on the track (work days only). */
    val work: List<DayBand> = emptyList(),
    /** A running fast as an inner arc; set by the facade, which holds the fasting view ([DayRingRules.fastArc]). */
    val fast: DayFastArc? = null,
    /**
     * Once the day is shut down (Living Today, item 1), the ring looks ahead: tomorrow's first commitment in the centre
     * and as a hollow brass mark on the track ([DayRingRules.tomorrow]); null until then. Set by the facade.
     */
    val tomorrow: DayRingTomorrow? = null,
    /**
     * Today's rain still to come as a faint blue tint on the track (Weather slice 2; [WeatherRules.rainBands]), brighter
     * while it's raining now. Set by the facade, which holds the forecast.
     */
    val rain: List<DayBand> = emptyList(),
) {
    val nowDegrees: Float get() = DayRingRules.degrees(nowMinute)

    /** "3 h 45 free · 4 to do" (the centre, which counts up to it on open). */
    val line: String get() = DayRingRules.line(freeMinutes, toDo, nowMinute)

    /** The free half of [line], shown large: "3 h 45 free". */
    val freeLine: String get() = DayRingRules.freeLine(freeMinutes, nowMinute)

    /** The to-do half: "4 to do". */
    val toDoLine: String get() = DayRingRules.toDoLine(toDo)

    /** What a screen reader says for the whole ring. */
    val spokenLine: String get() = DayRingRules.spokenLine(this)

    /** The centre's large line: "3 h 45 free", or after Shut down "Tomorrow 09:00". [free] is the counting-up value. */
    fun centreLine(free: Int): String = tomorrow?.headline ?: DayRingRules.freeLine(free, nowMinute)

    /** The centre's small line: "4 to do", or after Shut down tomorrow's first thing ("Standup"). */
    fun centreCaption(toDo: Int): String = tomorrow?.caption ?: DayRingRules.toDoLine(toDo)

    companion object {
        val EMPTY = DayRing(emptyList(), 0, 0, 0)
    }
}

/**
 * Tomorrow's first commitment on the Day ring once the day is shut down (Living Today, item 1): what starts first
 * tomorrow (not an all-day event, not one still running from tonight), or nothing booked yet.
 */
data class DayRingTomorrow(
    /** Local minute of the day it starts (0–1439), where the hollow mark sits on the track; null when nothing is booked. */
    val minute: Int?,
    /** Its title, shortened for the centre; null when nothing is booked. */
    val title: String?,
) {
    /** Where the mark sits, clockwise from midnight at the top; null when nothing is booked. */
    val degrees: Float? get() = minute?.let { DayRingRules.degrees(it) }

    /** "Tomorrow 09:00" · "Tomorrow" when nothing is booked. */
    val headline: String get() = minute?.let { "Tomorrow ${LocalClock.formatMinute(it)}" } ?: "Tomorrow"

    /** "Standup" · "Nothing booked yet". */
    val caption: String get() = title ?: "Nothing booked yet"

    /** "Day shut down. Tomorrow: first thing 09:00 Standup." */
    val spokenLine: String
        get() = "Day shut down. Tomorrow: " +
            (if (minute != null && title != null) "first thing ${LocalClock.formatMinute(minute)} $title." else "nothing booked yet.")
}

/** How the Day ring plays when Today opens ([DayRingRules.play]). */
enum class DayRingPlay {
    /** The first open of the day: the brass mark draws itself (~600 ms), the arcs draw in, the needle sweeps, the centre counts up. */
    FULL,

    /**
     * Later opens (and every return to Today after [DayRingRules.RETURN_REPLAY_MS] away): the mark, arcs, needle and
     * count-up draw in together, about 900 ms in Expressive and 500 ms in Subtle (Living Today, slice 2).
     */
    QUICK,

    /** Motion → Off: drawn at once. */
    STILL,
}

/**
 * Builds the Day ring (non-AI). Pure, unit-tested.
 *
 * - Arcs: timed events touching today (clipped to the day), today's planned open tasks (their estimate, or
 *   [TimelineRules.DEFAULT_TASK_MIN]) and booked sessions; all-day items aren't arcs (they don't take time). Sorted by
 *   start, events first at the same minute, so they draw in clockwise.
 * - Free time counts what's left of the waking day ([DAY_START_MIN]–[DAY_END_MIN]) from now, less every arc's time
 *   (overlaps counted once). After [DAY_END_MIN] the day's free time is used up and the centre says "Evening".
 * - Hidden events and calendars hidden from Today never reach it: it is built from Today's own events.
 * - Work hours on a work day ([WorkHours], Fold review 2026-10-08) aren't free time either: "1 h 50 free" on a work day
 *   counts only the time outside work. Work isn't drawn as an arc (it isn't something booked).
 */
object DayRingRules {
    const val DAY_START_MIN = 7 * 60
    const val DAY_END_MIN = 22 * 60
    const val MINUTES = 24 * 60
    const val MIN_SWEEP_DEGREES = 2f
    private const val MIN_MS = 60_000L

    fun degrees(minute: Int): Float = minute.coerceIn(0, MINUTES) * 360f / MINUTES

    fun build(
        planned: List<Task>,
        events: List<CalendarEvent>,
        sessions: List<BookedSession>,
        toDo: Int,
        nowMs: Long,
        today: DayWindow,
        calendar: LocalCalendar,
        /** Today's work blocks ([WorkHours.blocks]): not free, not arcs. */
        work: List<WorkBlock> = emptyList(),
    ): DayRing {
        fun minute(ms: Long): Int = when {
            ms <= today.startMs -> 0
            ms >= today.endMs -> MINUTES
            else -> calendar.minuteOfDay(ms)
        }
        val now = minute(nowMs).coerceAtMost(MINUTES - 1)
        fun on(start: Long, end: Long) = nowMs in start until end
        data class Item(val arc: DayArc, val event: Boolean)
        val items = buildList {
            events.filter { !it.allDay && it.overlaps(today) }.forEach { e ->
                add(Item(DayArc("e-" + e.id, DayArcKind.EVENT, minute(e.startAtMs), minute(e.endAtMs), e.endAtMs <= nowMs, on(e.startAtMs, e.endAtMs)), true))
            }
            planned.filter { it.scheduledAtMs != null && it.scheduledAtMs in today }.forEach { t ->
                val start = t.scheduledAtMs!!
                val end = start + (t.estimateMinutes ?: TimelineRules.DEFAULT_TASK_MIN) * MIN_MS
                add(Item(DayArc("t-" + t.id, DayArcKind.TASK, minute(start), minute(end), end <= nowMs, on(start, end)), false))
            }
            sessions.filter { it.startMs in today }.forEach { s ->
                add(Item(DayArc("s-" + s.habitId, DayArcKind.SESSION, minute(s.startMs), minute(s.endMs), s.endMs <= nowMs, on(s.startMs, s.endMs)), true))
            }
        }
        val arcs = items.sortedWith(compareBy<Item> { it.arc.startMinute }.thenBy { !it.event }.thenBy { it.arc.id }).map { it.arc }
        val busy = work.map { minute(it.startMs) to minute(it.endMs) }
        val bands = work.filter { it.endMs > today.startMs && it.startMs < today.endMs }
            .map { DayBand(minute(it.startMs), minute(it.endMs), on(it.startMs, it.endMs)) }
            .filter { it.endMinute > it.startMinute }
        return DayRing(arcs, now, freeMinutes(arcs, now, busy), toDo, bands)
    }

    /**
     * A running fast as the ring's inner arc, or null when none is running (or it hasn't started yet). A fast shorter
     * than a day sits at its own clock times; a day or longer fills the whole inner circle from the top.
     */
    fun fastArc(fast: FastNow?, nowMs: Long, calendar: LocalCalendar): DayFastArc? {
        if (fast == null || nowMs < fast.startedAtMs) return null
        val goalMs = maxOf(fast.goalAtMs - fast.startedAtMs, MIN_MS)
        val progress = fast.progress(nowMs).coerceIn(0f, 1f)
        val reached = fast.reachedGoal || nowMs >= fast.goalAtMs
        return if (goalMs >= MINUTES * MIN_MS) {
            DayFastArc(0f, 360f, progress, reached)
        } else {
            DayFastArc(degrees(calendar.minuteOfDay(fast.startedAtMs)), goalMs.toFloat() / (MINUTES * MIN_MS) * 360f, progress, reached)
        }
    }

    /** How far either side of an arc a tap still opens it, in degrees (a 5-minute call is only 2° wide). */
    const val TAP_SLOP_DEGREES = 6f

    /**
     * Where on the dial a tap landed, as degrees clockwise from the top, or null when it's off the ring: [dx], [dy] are
     * from the dial's centre (y down) and [radius] is the track's; the ring answers from 60 % to 125 % of it, so the
     * centre's text and the space outside stay clear.
     */
    fun tapDegrees(dx: Float, dy: Float, radius: Float): Float? {
        if (radius <= 0f) return null
        val d = kotlin.math.sqrt(dx * dx + dy * dy)
        if (d < radius * 0.6f || d > radius * 1.25f) return null
        val deg = (kotlin.math.atan2(dx.toDouble(), -dy.toDouble()) * 180.0 / PI).toFloat()
        return if (deg < 0f) deg + 360f else deg
    }

    /**
     * The arc a tap at [degrees] opens (Living Today, slice 3: tap an arc to open it): the arcs whose span, widened by
     * [TAP_SLOP_DEGREES] each side, holds the tap; one on now first, then one still to come, then the one whose middle
     * is nearest. Null: free time (nothing opens).
     */
    fun arcAt(ring: DayRing, degrees: Float): DayArc? {
        fun gap(a: Float, b: Float): Float { val d = ((a - b) % 360f + 360f) % 360f; return minOf(d, 360f - d) }
        return ring.arcs.filter { arc ->
            val into = ((degrees - arc.startDegrees) % 360f + 360f) % 360f
            into <= arc.sweepDegrees + TAP_SLOP_DEGREES || 360f - into <= TAP_SLOP_DEGREES
        }.minWithOrNull(
            compareBy<DayArc> { !it.current }.thenBy { it.past }.thenBy { gap(degrees, it.startDegrees + it.sweepDegrees / 2) },
        )
    }

    /** Minutes of the waking day left from [nowMinute] that no arc (and no [busy] stretch, such as work) covers. */
    fun freeMinutes(arcs: List<DayArc>, nowMinute: Int, busy: List<Pair<Int, Int>> = emptyList()): Int {
        val from = maxOf(nowMinute, DAY_START_MIN)
        if (from >= DAY_END_MIN) return 0
        var free = 0
        var cursor = from
        (arcs.map { it.startMinute to it.endMinute } + busy).map { maxOf(it.first, from) to minOf(it.second, DAY_END_MIN) }
            .filter { it.second > it.first }
            .sortedBy { it.first }
            .forEach { (s, e) ->
                if (s > cursor) free += s - cursor
                cursor = maxOf(cursor, e)
            }
        if (DAY_END_MIN > cursor) free += DAY_END_MIN - cursor
        return free
    }

    /** "3 h 45 free" · "45 min free" · "No free time" · "Evening" (once the waking day is over). */
    fun freeLine(freeMinutes: Int, nowMinute: Int = 0): String = when {
        nowMinute >= DAY_END_MIN -> "Evening"
        freeMinutes < 5 -> "No free time"
        else -> TimelineRules.freeLabel(freeMinutes)
    }

    /** "4 to do" · "1 to do" · "Nothing to do". */
    fun toDoLine(toDo: Int): String = if (toDo <= 0) "Nothing to do" else "$toDo to do"

    /**
     * The centre's whole line for [freeMinutes] and [toDo]; the apps call it with the counting-up numbers on open, so it
     * reads "1 h 50 free · 2 to do" on the way to "3 h 45 free · 4 to do".
     */
    fun line(freeMinutes: Int, toDo: Int, nowMinute: Int = 0): String = "${freeLine(freeMinutes, nowMinute)} · ${toDoLine(toDo)}"

    /**
     * After Shut down (Living Today, item 1): the ring looks ahead to tomorrow's first commitment ([TomorrowPreview.first],
     * the same first thing the shutdown pane shows). Null while the day isn't shut down, so the ring shows today.
     */
    fun tomorrow(shutdown: ShutdownView): DayRingTomorrow? {
        if (!shutdown.doneToday) return null
        val first = shutdown.tomorrow.first ?: return DayRingTomorrow(null, null)
        val minute = clockMinute(first.time) ?: return DayRingTomorrow(null, null)
        return DayRingTomorrow(minute, ShutdownRules.shorten(first.title))
    }

    /** "09:30" → 570; anything else (null, "All day", "Until 01:00") → null. */
    fun clockMinute(time: String?): Int? {
        val m = time?.let { Regex("^(\\d{1,2}):(\\d{2})$").find(it.trim()) } ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) h * 60 + min else null
    }

    /** "Your day: 4 things booked, 1 done. Now 14:32. 3 h 45 free · 4 to do." (after Shut down: tomorrow's first thing). */
    fun spokenLine(ring: DayRing): String {
        ring.tomorrow?.let { return it.spokenLine }
        val booked = ring.arcs.size
        val past = ring.arcs.count { it.past }
        val things = when (booked) {
            0 -> "nothing booked"
            1 -> "1 thing booked"
            else -> "$booked things booked"
        }
        val over = if (past > 0) ", $past over" else ""
        val rain = ring.rain.firstOrNull()?.let {
            if (it.current) " Raining now." else " Rain from ${LocalClock.formatMinute(it.startMinute)}."
        }.orEmpty()
        return "Your day: $things$over. Now ${LocalClock.formatMinute(ring.nowMinute)}. ${line(ring.freeMinutes, ring.toDo, ring.nowMinute)}.$rain"
    }

    /**
     * How the ring plays as Today opens: [DayRingPlay.FULL] the first time today on this device ([lastFullEpochDay] is
     * the local day it last played in full, kept per device), [DayRingPlay.QUICK] after that, [DayRingPlay.STILL] with
     * Motion → Off ([reduced]). Off doesn't count as having played, so turning motion on later still gets the full one.
     */
    fun play(lastFullEpochDay: Long?, todayEpochDay: Long, reduced: Boolean): DayRingPlay = when {
        reduced -> DayRingPlay.STILL
        lastFullEpochDay == todayEpochDay -> DayRingPlay.QUICK
        else -> DayRingPlay.FULL
    }

    /**
     * How long MEKA must have been away (another app in front, the screen off, the window behind another) before
     * coming back to Today plays the opening again (Living Today, slice 2: "every open is a moment"). Shorter trips —
     * a fingerprint prompt, a permission dialog, a glance at a notification — leave Today as it was.
     */
    const val RETURN_REPLAY_MS = 15_000L

    /**
     * What coming back to Today plays after [awayMs] away: the first return on a new day plays in full whatever the
     * gap, a return after [RETURN_REPLAY_MS] or more draws the ring in again ([DayRingPlay.QUICK]) with Today's
     * stagger, and a shorter trip plays nothing (null: Today stays as it was). Motion → Off: null, nothing replays.
     */
    fun onReturn(lastFullEpochDay: Long?, todayEpochDay: Long, awayMs: Long, reduced: Boolean): DayRingPlay? {
        if (reduced) return null
        val play = play(lastFullEpochDay, todayEpochDay, reduced = false)
        return when {
            play == DayRingPlay.FULL -> DayRingPlay.FULL
            awayMs >= RETURN_REPLAY_MS -> DayRingPlay.QUICK
            else -> null
        }
    }
}

/** How the Day ring lives once the opening has landed ([DayRingLive.mode]). */
enum class DayRingLiveMode {
    /** The gold second hand sweeps smoothly round, the edge breathes and the hour's shimmer runs (60 fps while visible). */
    SWEEP,

    /** Power saving: no hand and no breath; the ring is redrawn once a minute so the now needle still moves. */
    MINUTE,

    /** Motion → Off: a still ring with no hand. */
    STILL,
}

/**
 * The living Day ring (Living Today, slice 1; Meka, 2026-10-08: "a constant animation every time I open the app… don't
 * make it look cheap"): after the opening lands, the ring stays alive like a mechanical watch. Non-AI, pure, the same
 * numbers on the Fold and the Mac (both apps only draw what this says).
 *
 * - **Second hand**: a fine brass hand with a soft comet tail orbits the dial once a minute, a smooth sweep (never a
 *   tick), its angle from the wall clock so every screen agrees: :00 at the top, :15 at three o'clock.
 * - **Now pop**: on each new minute the now needle's brass dot swells a touch and settles ([nowPop], a damped spring).
 * - **Breath**: the ring's brass edge brightens and dims, [GLOW_LOW] → [GLOW_HIGH] → [GLOW_LOW] over [GLOW_PERIOD_MS]
 *   (a sleeping laptop's light: calm, never a flash).
 * - **Hour shimmer**: in the first [SHIMMER_MS] of each local hour a single band of light runs once round the ring.
 * - Off: still, no hand. Power saving: no hand, no breath, the ring redrawn each minute ([DayRingLiveMode.MINUTE]).
 */
object DayRingLive {
    /** One orbit of the second hand. */
    const val SWEEP_MS = 60_000L

    /** One breath of the brass edge. */
    const val GLOW_PERIOD_MS = 5_000L
    const val GLOW_LOW = 0.6f
    const val GLOW_HIGH = 1f

    /** How long the top-of-the-hour shimmer takes to run once round. */
    const val SHIMMER_MS = 1_800L

    /** How far round the shimmer's band of light reaches (degrees, behind its head). */
    const val SHIMMER_BAND_DEGREES = 50f

    /** How long the comet tail behind the hand reaches (degrees of the dial). */
    const val TAIL_DEGREES = 42f

    /** Segments the tail is drawn in, each fainter than the one before. */
    const val TAIL_SEGMENTS = 14

    /** How long the now dot's pop lasts after the minute turns, and how far it swells at most. */
    const val NOW_POP_MS = 600L
    const val NOW_POP_SCALE = 0.45f

    /** How long the hand takes to fade in once the opening has landed. */
    const val HAND_FADE_MS = 500L

    /** The bedside clock's slower breath (Living Today, slice 5): a sleeping room wants a calmer light. */
    const val BEDSIDE_GLOW_PERIOD_MS = 8_000L

    /** How bright the whole bedside ring is in quiet hours, so it quietens with the clock's colours. */
    const val BEDSIDE_QUIET_ALPHA = 0.45f

    private const val HOUR_MS = 3_600_000L

    /** The damped sine's own peak, so the dot swells by exactly [NOW_POP_SCALE]. */
    private const val POP_PEAK = 0.4636

    fun mode(reduced: Boolean, powerSave: Boolean): DayRingLiveMode = when {
        reduced -> DayRingLiveMode.STILL
        powerSave -> DayRingLiveMode.MINUTE
        else -> DayRingLiveMode.SWEEP
    }

    /**
     * The bedside clock's ring (Living Today, slice 5): as on Today, except that in quiet hours nothing moves in a dark
     * bedroom — no sweeping hand or breath, the ring redrawn once a minute so the now needle still keeps time.
     */
    fun bedsideMode(reduced: Boolean, powerSave: Boolean, quiet: Boolean): DayRingLiveMode =
        if (quiet && !reduced) DayRingLiveMode.MINUTE else mode(reduced, powerSave)

    /**
     * Where the second hand points, degrees clockwise from the top: the milliseconds into the current minute, swept
     * smoothly ([epochMs] is the wall clock; every time zone's minutes start on UTC minutes).
     */
    fun handDegrees(epochMs: Long): Float = (epochMs).mod(SWEEP_MS).toFloat() * 360f / SWEEP_MS

    /** How bright the brass edge is now: [GLOW_LOW] at the bottom of the breath, [GLOW_HIGH] at its top (a cosine, so it eases both ways). */
    fun glow(epochMs: Long): Float = glowOver(epochMs, GLOW_PERIOD_MS)

    /** The bedside clock's breath: the same 60 % → 100 % → 60 %, over [BEDSIDE_GLOW_PERIOD_MS]. */
    fun bedsideGlow(epochMs: Long): Float = glowOver(epochMs, BEDSIDE_GLOW_PERIOD_MS)

    private fun glowOver(epochMs: Long, periodMs: Long): Float {
        val phase = (epochMs).mod(periodMs).toDouble() / periodMs
        val rise = (0.5 - 0.5 * cos(phase * 2 * PI)).toFloat()
        return GLOW_LOW + (GLOW_HIGH - GLOW_LOW) * rise
    }

    /**
     * The hour's shimmer: how far round its head has run (0–1, eased) during the first [SHIMMER_MS] of a local hour,
     * else null. [offsetMs] is the local zone's offset from UTC (half-hour zones' hours start on the half hour).
     */
    fun shimmer(epochMs: Long, offsetMs: Long): Float? {
        val into = (epochMs + offsetMs).mod(HOUR_MS)
        if (into >= SHIMMER_MS) return null
        val t = into.toFloat() / SHIMMER_MS
        return 1f - (1f - t) * (1f - t) * (1f - t)
    }

    /**
     * The now dot's size after the minute turns: 1 at rest; swells to about 1 + [NOW_POP_SCALE] and settles back with
     * a small overshoot below 1 (a damped spring) over [NOW_POP_MS].
     */
    fun nowPop(epochMs: Long): Float {
        val into = (epochMs).mod(SWEEP_MS)
        if (into >= NOW_POP_MS) return 1f
        val t = into.toDouble() / NOW_POP_MS
        return (1.0 + NOW_POP_SCALE * exp(-3.0 * t) * sin(t * 1.6 * PI) / POP_PEAK).toFloat()
    }

    /** The hand's opacity [sinceLandedMs] after the opening landed (fades in over [HAND_FADE_MS]). */
    fun handFade(sinceLandedMs: Long): Float = (sinceLandedMs.toFloat() / HAND_FADE_MS).coerceIn(0f, 1f)

    /** Opacity of tail segment [i] (0 = next to the hand, brightest), fading to nothing at the tail's end. */
    fun tailAlpha(i: Int): Float {
        val f = 1f - i.toFloat() / TAIL_SEGMENTS
        return (f * f * 0.55f).coerceIn(0f, 1f)
    }

    /** How long until the ring next needs drawing in [mode]: the next frame while sweeping, the next minute else. */
    fun nextDrawInMs(mode: DayRingLiveMode, epochMs: Long): Long? = when (mode) {
        DayRingLiveMode.SWEEP -> 16L
        DayRingLiveMode.MINUTE -> SWEEP_MS - (epochMs).mod(SWEEP_MS)
        DayRingLiveMode.STILL -> null
    }
}

/**
 * Where the Day ring sits on Today (Fold review 2026-10-09, item 1): in the header, beside the greeting — not a block
 * between the ticker and Up next. The open Fold and the Mac show a [WIDE_DP] dial with its centre line; the closed
 * Fold a compact [COMPACT_DP] dial (ring, hand, now dot, arcs) with no centre text — free time stays in the
 * timeline's now line. The live tiles leave the dial and sit under the header as a slim row. The track's stroke
 * thins on the compact dial so the arcs still read at that size.
 */
object DayRingHeader {
    const val WIDE_DP = 150
    const val COMPACT_DP = 96
    /** Below this the centre's "3 h 45 free" · "4 to do" doesn't fit inside the arcs. */
    const val CENTRE_MIN_DP = 120
    const val STROKE_DP = 10f
    const val COMPACT_STROKE_DP = 6f

    /** The dial's size in the header: compact on a narrow screen (the closed Fold's cover screen). */
    fun sizeDp(compact: Boolean): Int = if (compact) COMPACT_DP else WIDE_DP

    /** Whether a dial of [sizeDp] shows its centre line and caption. */
    fun showsCentre(sizeDp: Int): Boolean = sizeDp >= CENTRE_MIN_DP

    /** The arcs' stroke on a dial of [sizeDp]. */
    fun strokeDp(sizeDp: Int): Float = if (sizeDp < CENTRE_MIN_DP) COMPACT_STROKE_DP else STROKE_DP

    /** The track's radius (what taps are measured against): half the dial, less half the stroke and a 2 dp inset. */
    fun trackRadiusDp(sizeDp: Int): Float = sizeDp / 2f - strokeDp(sizeDp) / 2f - 2f
}
