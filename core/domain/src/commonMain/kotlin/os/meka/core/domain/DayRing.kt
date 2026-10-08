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
) {
    /** Where the arc starts, clockwise from midnight at the top. */
    val startDegrees: Float get() = DayRingRules.degrees(startMinute)

    /** How far round it runs (at least [DayRingRules.MIN_SWEEP_DEGREES], so a 5-minute call still shows). */
    val sweepDegrees: Float get() = maxOf(DayRingRules.degrees(endMinute) - startDegrees, DayRingRules.MIN_SWEEP_DEGREES)
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

    companion object {
        val EMPTY = DayRing(emptyList(), 0, 0, 0)
    }
}

/** How the Day ring plays when Today opens ([DayRingRules.play]). */
enum class DayRingPlay {
    /** The first open of the day: the brass mark draws itself (~600 ms), the arcs draw in, the needle sweeps, the centre counts up. */
    FULL,

    /** Later opens: the same, all in about 300 ms. */
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
        data class Item(val arc: DayArc, val event: Boolean)
        val items = buildList {
            events.filter { !it.allDay && it.overlaps(today) }.forEach { e ->
                add(Item(DayArc("e-" + e.id, DayArcKind.EVENT, minute(e.startAtMs), minute(e.endAtMs), e.endAtMs <= nowMs), true))
            }
            planned.filter { it.scheduledAtMs != null && it.scheduledAtMs in today }.forEach { t ->
                val start = t.scheduledAtMs!!
                val end = start + (t.estimateMinutes ?: TimelineRules.DEFAULT_TASK_MIN) * MIN_MS
                add(Item(DayArc("t-" + t.id, DayArcKind.TASK, minute(start), minute(end), end <= nowMs), false))
            }
            sessions.filter { it.startMs in today }.forEach { s ->
                add(Item(DayArc("s-" + s.habitId, DayArcKind.SESSION, minute(s.startMs), minute(s.endMs), s.endMs <= nowMs), true))
            }
        }
        val arcs = items.sortedWith(compareBy<Item> { it.arc.startMinute }.thenBy { !it.event }.thenBy { it.arc.id }).map { it.arc }
        val busy = work.map { minute(it.startMs) to minute(it.endMs) }
        return DayRing(arcs, now, freeMinutes(arcs, now, busy), toDo)
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

    /** "Your day: 4 things booked, 1 done. Now 14:32. 3 h 45 free · 4 to do." */
    fun spokenLine(ring: DayRing): String {
        val booked = ring.arcs.size
        val past = ring.arcs.count { it.past }
        val things = when (booked) {
            0 -> "nothing booked"
            1 -> "1 thing booked"
            else -> "$booked things booked"
        }
        val over = if (past > 0) ", $past over" else ""
        return "Your day: $things$over. Now ${LocalClock.formatMinute(ring.nowMinute)}. ${line(ring.freeMinutes, ring.toDo, ring.nowMinute)}."
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

    private const val HOUR_MS = 3_600_000L

    /** The damped sine's own peak, so the dot swells by exactly [NOW_POP_SCALE]. */
    private const val POP_PEAK = 0.4636

    fun mode(reduced: Boolean, powerSave: Boolean): DayRingLiveMode = when {
        reduced -> DayRingLiveMode.STILL
        powerSave -> DayRingLiveMode.MINUTE
        else -> DayRingLiveMode.SWEEP
    }

    /**
     * Where the second hand points, degrees clockwise from the top: the milliseconds into the current minute, swept
     * smoothly ([epochMs] is the wall clock; every time zone's minutes start on UTC minutes).
     */
    fun handDegrees(epochMs: Long): Float = (epochMs).mod(SWEEP_MS).toFloat() * 360f / SWEEP_MS

    /** How bright the brass edge is now: [GLOW_LOW] at the bottom of the breath, [GLOW_HIGH] at its top (a cosine, so it eases both ways). */
    fun glow(epochMs: Long): Float {
        val phase = (epochMs).mod(GLOW_PERIOD_MS).toDouble() / GLOW_PERIOD_MS
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
