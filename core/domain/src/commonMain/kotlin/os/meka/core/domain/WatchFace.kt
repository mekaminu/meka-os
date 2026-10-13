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
 * hands, a thin glowing sweeping second hand (WatchFaceLook), 12 markers with no numerals, the breathing brass rim and
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

    /**
     * The rim's radius on a dial of [sizeDp]: half the dial less half the rim stroke and a [RIM_INSET_DP] inset — room
     * outside the rim for the week's goal ring ([GoalWeekRules.ringRadiusDp], Goals, properly, slice 7).
     */
    fun rimRadiusDp(sizeDp: Int): Float = sizeDp / 2f - rimStrokeDp(sizeDp) / 2f - RIM_INSET_DP
    const val RIM_INSET_DP = 4f

    /**
     * The face's size in Today's header (watch face item, 2026-10-12 18:04): on the closed Fold's cover screen
     * ([cover]) about a fifth smaller than before ([COVER_DP], was [DayRingHeader.COMPACT_DP]) so the greeting, weather
     * and train lines wrap less and Up next sits higher; the open Fold and the Mac keep [DayRingHeader.WIDE_DP].
     */
    fun headerDp(cover: Boolean): Int = if (cover) COVER_DP else DayRingHeader.WIDE_DP
    const val COVER_DP = 78

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

/** One layer of the watch face second hand's glow ([WatchFaceLook.halo]), drawn under it widest first. */
data class WatchHaloLayer(val widthDp: Float, val alpha: Float)

/**
 * How the watch face's bezel and second hand are drawn (watch face item, Meka 2026-10-12 18:04 "the circle's outline is
 * too thick" and 2026-10-13 07:01 "make the moving seconds a very thin glowing proper arm"). Only the watch face reads
 * these — Today's header, the bedside clock and the bedside screen saver on the Fold, Today's header on the Mac; the
 * 24-hour Day ring keeps [DayRingLook]. Everything scales with the face's size ([scale]: 1 at the 150 dp face), so the
 * cover screen's smaller face and a compact clock aren't heavier for their size, and the bedside's large one isn't
 * spindly. Non-AI, pure; both apps only draw what this says.
 *
 * - **Bezel:** one brass line where the old breathing edge sat, [BEZEL_DP] at the 150 dp face — 58 % of the 3 dp track
 *   that, with the 1.5 dp edge beside it, read as a thick outline — breathing 60 % → 100 % with the face once it has
 *   landed (drawing itself round from 12 at [BEZEL_DRAW_ALPHA] on the opening). A fine darker hairline just inside it
 *   gives the depth the weight used to. Its soft outer glow is half the Day ring's: [BLUR_DP] out, [BLUR_PEAK_ALPHA].
 * - **Arcs and habit dots** keep their own track inside the bezel, never on it: a faint [ARC_TRACK_DP] line at the
 *   arcs' radius ([WatchFaceRules.rimRadiusDp]); markers and the hour and minute hands are unchanged.
 * - **Second hand:** a proper sweeping hand instead of the comet tail along the rim — a [NEEDLE_DP] needle from a short
 *   counterweight ([NEEDLE_TAIL] of the radius) through the centre to the markers' outer ends, the full brass, with a
 *   soft glow under it ([halo]: layers up to [HALO_WIDTH] × the needle at [HALO_ALPHA]); a small cap ([CAP_DP]) sits
 *   over all three hands. Smooth, never ticking ([DayRingLive.handDegrees]); quiet hours, Done for the day, power
 *   saving and reduced motion still stop it ([WatchFaceRules.liveMode]).
 */
object WatchFaceLook {
    /** The face size the dp numbers below are for. */
    const val REFERENCE_DP = 150

    const val BEZEL_DP = 1.75f
    const val BEZEL_MIN_DP = 0.6f
    /** The bezel's alpha while it draws itself round on the opening (it breathes once landed). */
    const val BEZEL_DRAW_ALPHA = 0.8f

    const val HAIRLINE_DP = 0.5f
    const val HAIRLINE_MIN_DP = 0.35f
    const val HAIRLINE_ALPHA = 0.35f

    const val ARC_TRACK_DP = 1f
    const val ARC_TRACK_ALPHA = 0.2f

    /** The bezel's glow: half the Day ring's ([DayRingLook.EDGE_BLUR_DP], [DayRingLook.EDGE_BLUR_PEAK_ALPHA]). */
    const val BLUR_DP = 4f
    const val BLUR_PEAK_ALPHA = 0.11f
    const val BLUR_LAYERS = 4

    const val NEEDLE_DP = 1f
    const val NEEDLE_MIN_DP = 0.6f
    const val NEEDLE_TAIL = 0.18f
    const val HALO_WIDTH = 3.5f
    const val HALO_ALPHA = 0.35f
    const val CAP_DP = 2.6f

    /** How much bigger or smaller than the 150 dp face a face of [sizeDp] is. */
    fun scale(sizeDp: Int): Float = sizeDp.toFloat() / REFERENCE_DP

    fun bezelDp(sizeDp: Int): Float = maxOf(BEZEL_DP * scale(sizeDp), BEZEL_MIN_DP)

    /**
     * The bezel's centre line: its inner edge a quarter-dp outside the arcs' outer edge, so the arcs never sit on it,
     * and its outer edge clear of the week's goal ring hugging the face's edge ([GoalWeekRules.ringRadiusDp]).
     */
    fun bezelRadiusDp(sizeDp: Int): Float =
        WatchFaceRules.rimRadiusDp(sizeDp) + WatchFaceRules.rimStrokeDp(sizeDp) / 2f + BEZEL_GAP_DP + bezelDp(sizeDp) / 2f
    const val BEZEL_GAP_DP = 0.25f

    fun hairlineDp(sizeDp: Int): Float = maxOf(HAIRLINE_DP * scale(sizeDp), HAIRLINE_MIN_DP)

    /** The hairline's centre line: just inside the bezel, a quarter-dp gap at the 150 dp face. */
    fun hairlineRadiusDp(sizeDp: Int): Float =
        bezelRadiusDp(sizeDp) - bezelDp(sizeDp) / 2f - 0.25f * scale(sizeDp) - hairlineDp(sizeDp) / 2f

    /**
     * The bezel's alpha once landed: [BEZEL_DRAW_ALPHA] (what the opening drew it at) blending into the breath [glow]
     * (0.6 → 1.0) as [shown] goes 0 → 1, so it never blinks when the live layer takes over.
     */
    fun bezelAlpha(glow: Float, shown: Float): Float =
        (BEZEL_DRAW_ALPHA + (glow - BEZEL_DRAW_ALPHA) * shown.coerceIn(0f, 1f)).coerceIn(0f, 1f)

    /** How far outside the bezel glow layer [i] sits (dp, scaled), evenly across [BLUR_DP]. */
    fun blurOffsetDp(i: Int, sizeDp: Int): Float = BLUR_DP * scale(sizeDp) * (i + 1) / BLUR_LAYERS

    /** Glow layer [i]'s alpha for a breath of [glow]: brightest next to the bezel, nothing past [BLUR_DP]. */
    fun blurAlpha(i: Int, glow: Float): Float {
        if (i < 0 || i >= BLUR_LAYERS) return 0f
        val f = 1f - i.toFloat() / BLUR_LAYERS
        return (f * f * BLUR_PEAK_ALPHA * glow).coerceIn(0f, 1f)
    }

    /** Each glow layer's stroke: wide enough that neighbours overlap into one soft glow. */
    fun blurStrokeDp(sizeDp: Int): Float = BLUR_DP * scale(sizeDp) / BLUR_LAYERS * 1.6f

    fun needleDp(sizeDp: Int): Float = maxOf(NEEDLE_DP * scale(sizeDp), NEEDLE_MIN_DP)

    /** The needle's tip: the markers' outer ends, just inside the arcs. */
    fun needleReachDp(sizeDp: Int): Float = WatchFaceRules.rimRadiusDp(sizeDp) - WatchFaceRules.rimStrokeDp(sizeDp) / 2f - 2f

    /** The counterweight behind the centre. */
    fun needleTailDp(sizeDp: Int): Float = WatchFaceRules.rimRadiusDp(sizeDp) * NEEDLE_TAIL

    /** The cap over all three hands' centre. */
    fun capDp(sizeDp: Int): Float = CAP_DP * scale(sizeDp).coerceIn(0.7f, 2f)

    /** The needle's soft glow: three layers widening to [HALO_WIDTH] × the needle, fainter as they widen. */
    fun halo(sizeDp: Int): List<WatchHaloLayer> {
        val n = needleDp(sizeDp)
        return listOf(
            WatchHaloLayer(n * HALO_WIDTH, HALO_ALPHA * 0.35f),
            WatchHaloLayer(n * 2.4f, HALO_ALPHA * 0.6f),
            WatchHaloLayer(n * 1.6f, HALO_ALPHA),
        )
    }
}
