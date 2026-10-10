package os.meka.core.domain

/**
 * One thing on the watch face's rim (Fold review 2026-10-09 07:26, item 2): something booked in the next 12 hours, at
 * its place on a 12-hour dial (12 at the top). Something already on is drawn from now (the hour hand) to its end, so
 * the rim only ever shows what's ahead.
 */
data class WatchArc(
    /** The Day ring's id: "e-<event>", "t-<task>", "s-<habit>". */
    val id: String,
    val kind: DayArcKind,
    /** Where it starts, clockwise from 12 at the top. */
    val startDegrees: Float,
    /** How far round it runs (at least [WatchFaceRules.MIN_SWEEP_DEGREES]). */
    val sweepDegrees: Float,
    /** On now: glows with the rim's breath. */
    val current: Boolean,
    /** The gym's booked sessions, Barça's fixtures and training: wider and brighter, so they stand out. */
    val highlighted: Boolean,
)

/** Work hours in the next 12 hours: a faint band on the rim (not booked, so not an arc). */
data class WatchBand(val startDegrees: Float, val sweepDegrees: Float, val current: Boolean)

/** The hands' angles, clockwise from 12 at the top. */
data class WatchHands(val hourDegrees: Float, val minuteDegrees: Float, val secondDegrees: Float)

/** One of the 12 hour markers: a longer bar at 12, 3, 6 and 9. */
data class WatchMarker(val hour: Int, val degrees: Float, val major: Boolean)

/**
 * Today's watch face (Fold review 2026-10-09 07:26, item 2: "the ring becomes a real watch face"): the 24-hour dial
 * confused (at 07:21 the now dot sat near "4 o'clock"), so Today's header shows a 12-hour face — gold hour and minute
 * hands, the fine sweeping second hand with its comet tail, 12 markers with no numerals, the breathing brass rim and
 * the hourly shimmer — with the next 12 hours of events as arcs on its rim. Tapping it opens the full 24-hour Day
 * ring as a sheet. Non-AI, pure; both apps only draw what this says.
 */
data class WatchFace(
    val arcs: List<WatchArc>,
    val work: List<WatchBand>,
    /** Things booked in the next 12 hours (events, planned tasks, sessions). */
    val upcoming: Int,
    /** The next thing still to start in the window: "Standup 09:30"; null when nothing is ahead. */
    val next: String?,
) {
    /** What a screen reader says for the face (the time is the system's own). */
    val spokenLine: String get() = WatchFaceRules.spokenLine(this)

    companion object {
        val EMPTY = WatchFace(emptyList(), emptyList(), 0, null)
    }
}

/**
 * Builds the watch face (non-AI). Pure, unit-tested.
 *
 * - The window is now to 12 hours ahead ([WINDOW_MIN]), across midnight: at 20:00 tomorrow morning's 07:00 standup is
 *   on the rim at 7 o'clock. On a 12-hour dial nothing in the window can overlap anything else in it.
 * - Arcs: timed events (all-day ones don't take time), planned open tasks (their estimate, or
 *   [TimelineRules.DEFAULT_TASK_MIN]) and booked sessions, clipped to the window; one already on starts at now.
 * - Highlighted: the gym's booked sessions, the fixtures feed (Barça) and anything called "Training".
 * - Hands: from the local minute of the day and the milliseconds into it, so the hour hand creeps between hours and
 *   the minute hand between minutes; the second hand is the Day ring's sweep ([DayRingLive.handDegrees]).
 */
object WatchFaceRules {
    const val WINDOW_MIN = 12 * 60
    const val MIN_SWEEP_DEGREES = 3f
    private const val MIN_MS = 60_000L

    /** The hour hand: 52 % of the dial's radius, tapering from 4.5 dp at the hub to 2 dp at the tip. */
    const val HOUR_HAND_LENGTH = 0.52f
    const val HOUR_HAND_BASE_DP = 4.5f
    const val HOUR_HAND_TIP_DP = 2f

    /** The minute hand: 78 % of the radius, tapering from 3.5 dp to 1.2 dp. */
    const val MINUTE_HAND_LENGTH = 0.78f
    const val MINUTE_HAND_BASE_DP = 3.5f
    const val MINUTE_HAND_TIP_DP = 1.2f

    /** The hub the hands turn on, and the short counterweight behind them (share of the radius). */
    const val HUB_DP = 3.5f
    const val TAIL_LENGTH = 0.12f

    /** The markers: 12, 3, 6 and 9 are longer and heavier bars; the rest short ones. Share of the radius, inward from the rim. */
    const val MAJOR_MARKER_LENGTH = 0.16f
    const val MINOR_MARKER_LENGTH = 0.08f
    const val MAJOR_MARKER_DP = 2.5f
    const val MINOR_MARKER_DP = 1.2f
    const val MARKER_ALPHA = 0.75f

    /**
     * How the rim's work and arcs read (Meka's 10:48 screenshots, 2026-10-09: "the watch face shows no event arcs" — the
     * work band was a grey line at 16–26 % under the brass track, so it vanished): work is a brass band at 70 % as wide
     * as the rim, so it stands clear of the 3 dp track; events are the full accent; planned tasks 85 %; the gym, Barça
     * and training [HIGHLIGHT_WIDTH] × as wide. Both apps read these, so the Fold and the Mac match in Dark and Light
     * (the accent is the theme's brass).
     */
    const val WORK_BAND_ALPHA = 0.7f
    const val WORK_BAND_WIDTH = 1f
    const val EVENT_ARC_ALPHA = 1f
    const val TASK_ARC_ALPHA = 0.85f
    const val HIGHLIGHT_WIDTH = 1.4f

    /** An arc's alpha (of the accent): events and sessions full, planned tasks a shade under. */
    fun arcAlpha(kind: DayArcKind): Float = if (kind == DayArcKind.TASK) TASK_ARC_ALPHA else EVENT_ARC_ALPHA

    /** The rim's arcs: an outer band this wide (dp), a highlighted one 1.4× as wide. */
    const val RIM_STROKE_DP = 6f
    const val COMPACT_RIM_STROKE_DP = 4f

    /** Where a minute of the day sits on a 12-hour dial: 0 (and 12:00) at the top. */
    fun degrees(minuteOfDay: Int): Float = minuteOfDay.mod(WINDOW_MIN) * 360f / WINDOW_MIN

    /** The hands at [minuteOfDay] (local) and [msIntoMinute]; [secondEpochMs] drives the sweeping second hand. */
    fun hands(minuteOfDay: Int, msIntoMinute: Long, secondEpochMs: Long): WatchHands {
        val frac = msIntoMinute.coerceIn(0L, MIN_MS - 1).toFloat() / MIN_MS
        val m = minuteOfDay.mod(24 * 60)
        val hour = ((m.mod(WINDOW_MIN)) + frac) * 360f / WINDOW_MIN
        val minute = ((m % 60) + frac) * 6f
        return WatchHands(hour, minute, DayRingLive.handDegrees(secondEpochMs))
    }

    /** The hands at wall-clock [epochMs] in a zone [offsetMs] from UTC. */
    fun handsAt(epochMs: Long, offsetMs: Long): WatchHands {
        val local = epochMs + offsetMs
        val minuteOfDay = (local.mod(24 * 60 * MIN_MS) / MIN_MS).toInt()
        return hands(minuteOfDay, local.mod(MIN_MS), epochMs)
    }

    /** The 12 hour markers, 12 o'clock first. */
    fun markers(): List<WatchMarker> = (0 until 12).map { h -> WatchMarker(if (h == 0) 12 else h, h * 30f, h % 3 == 0) }

    /**
     * The rim's stroke on a dial of [sizeDp]: thinner on the closed Fold's compact face, heavier on the bedside clock's
     * large face (from [LARGE_MIN_DP]) so it reads across a dark room.
     */
    fun rimStrokeDp(sizeDp: Int): Float = when {
        sizeDp < DayRingHeader.CENTRE_MIN_DP -> COMPACT_RIM_STROKE_DP
        sizeDp >= LARGE_MIN_DP -> LARGE_RIM_STROKE_DP
        else -> RIM_STROKE_DP
    }

    /** From this size the face is the bedside clock's large one ([FoldModeRules.bedsideRingDp] is 150–260 dp). */
    const val LARGE_MIN_DP = 200
    const val LARGE_RIM_STROKE_DP = 8f

    /**
     * How the face lives (Fold review 2026-10-09 07:26, item 2, slice 2): on Today as the Day ring does; at the bedside
     * as the bedside ring did — in quiet hours nothing moves in a dark bedroom (no second hand, breath or shimmer), the
     * face redrawn once a minute so the hour and minute hands still keep time.
     */
    fun liveMode(reduced: Boolean, powerSave: Boolean, bedside: Boolean, quiet: Boolean, night: Boolean = false): DayRingLiveMode =
        if (bedside || night) DayRingLive.bedsideMode(reduced, powerSave, quiet || night) else DayRingLive.mode(reduced, powerSave)

    /**
     * Done for the day: once the day is shut down Today's face dims to night — the whole face at [NIGHT_ALPHA], no
     * second hand or breath, redrawn each minute so the hands keep time ([liveMode] with `night`). The dim blends in
     * over the `nightFall` choreography token as the shutdown pane drops away; reduced motion: the short cross-fade.
     */
    fun nightAlpha(night: Boolean): Float = if (night) NIGHT_ALPHA else 1f
    const val NIGHT_ALPHA = 0.55f

    /** The rim's breath now: Today's 5 s, the bedside clock's calmer 8 s ([DayRingLive.bedsideGlow]). */
    fun breath(epochMs: Long, bedside: Boolean): Float =
        if (bedside) DayRingLive.bedsideGlow(epochMs) else DayRingLive.glow(epochMs)

    /** How bright the whole bedside face is: dimmed with the clock's colours in quiet hours. */
    fun bedsideAlpha(quiet: Boolean): Float = if (quiet) DayRingLive.BEDSIDE_QUIET_ALPHA else 1f

    /** The bedside face's tap opens the whole day over the clock, titled so (the pane's back line returns to it). */
    const val BEDSIDE_SHEET_BACK = "‹ Clock"

    /** The rim's radius on a dial of [sizeDp]: half the dial less half the rim stroke and a 2 dp inset. */
    fun rimRadiusDp(sizeDp: Int): Float = sizeDp / 2f - rimStrokeDp(sizeDp) / 2f - 2f

    /** "Training", "Training - 3G", "U12 training": something to stand out on the rim. */
    fun isTraining(title: String): Boolean = Regex("\\btraining\\b", RegexOption.IGNORE_CASE).containsMatchIn(title)

    fun build(
        events: List<CalendarEvent>,
        planned: List<Task>,
        sessions: List<BookedSession>,
        nowMs: Long,
        calendar: LocalCalendar,
        /** Work blocks for today and tomorrow ([WorkHours.blocks]). */
        work: List<WorkBlock> = emptyList(),
    ): WatchFace {
        val windowEnd = nowMs + WINDOW_MIN * MIN_MS
        data class Item(val id: String, val kind: DayArcKind, val start: Long, val end: Long, val title: String, val highlighted: Boolean)
        val items = buildList {
            events.filter { !it.allDay && it.endAtMs > nowMs && it.startAtMs < windowEnd && it.endAtMs > it.startAtMs }.forEach { e ->
                add(Item("e-" + e.id, DayArcKind.EVENT, e.startAtMs, e.endAtMs, e.title, e.isFixture || isTraining(e.title)))
            }
            planned.filter { (it.lifecycle == Lifecycle.ACTIVE || it.lifecycle == Lifecycle.INBOX) && it.scheduledAtMs != null }.forEach { t ->
                val start = t.scheduledAtMs!!
                val end = start + (t.estimateMinutes ?: TimelineRules.DEFAULT_TASK_MIN) * MIN_MS
                if (end > nowMs && start < windowEnd) add(Item("t-" + t.id, DayArcKind.TASK, start, end, t.title, false))
            }
            sessions.filter { it.endMs > nowMs && it.startMs < windowEnd }.forEach { s ->
                add(Item("s-" + s.habitId, DayArcKind.SESSION, s.startMs, s.endMs, s.title, true))
            }
        }.sortedWith(compareBy<Item> { it.start }.thenBy { it.kind != DayArcKind.EVENT }.thenBy { it.id })
        fun arcOf(start: Long, end: Long): Pair<Float, Float> {
            val s = maxOf(start, nowMs)
            val e = minOf(end, windowEnd)
            val sweep = (e - s).toFloat() / (WINDOW_MIN * MIN_MS) * 360f
            return degrees(calendar.minuteOfDay(s)) to maxOf(sweep, MIN_SWEEP_DEGREES)
        }
        val arcs = items.map { i ->
            val (from, sweep) = arcOf(i.start, i.end)
            WatchArc(i.id, i.kind, from, sweep, current = i.start <= nowMs, highlighted = i.highlighted)
        }
        val bands = work.filter { it.endMs > nowMs && it.startMs < windowEnd && it.endMs > it.startMs }
            .distinctBy { it.startMs to it.endMs }
            .map { b ->
                val s = maxOf(b.startMs, nowMs)
                val e = minOf(b.endMs, windowEnd)
                WatchBand(degrees(calendar.minuteOfDay(s)), (e - s).toFloat() / (WINDOW_MIN * MIN_MS) * 360f, b.startMs <= nowMs)
            }
        val next = items.firstOrNull { it.start > nowMs }?.let { "${shorten(it.title)} ${LocalClock.formatMinute(calendar.minuteOfDay(it.start))}" }
        return WatchFace(arcs, bands, items.size, next)
    }

    private fun shorten(title: String): String = title.trim().let { if (it.length <= 28) it else it.take(27).trimEnd() + "…" }

    /** "Watch face. Next 12 hours: 3 things. Next: Standup 09:30. Tap for your whole day." */
    fun spokenLine(face: WatchFace): String {
        val things = when (face.upcoming) {
            0 -> "nothing booked"
            1 -> "1 thing"
            else -> "${face.upcoming} things"
        }
        val next = face.next?.let { " Next: $it." }.orEmpty()
        return "Watch face. Next 12 hours: $things.$next Tap for your whole day."
    }
}
