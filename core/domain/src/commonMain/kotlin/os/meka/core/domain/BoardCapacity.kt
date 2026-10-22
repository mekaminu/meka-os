package os.meka.core.domain

/** One row of a card's "How long?" choices: [label] "30m"; [suggested] Navilife's guess; [selected] the one set now. */
data class EstimateChoice(val minutes: Int, val label: String, val suggested: Boolean, val selected: Boolean) {
    /** The menu's line: "✓ 30m" for the one set, "30m · suggested" for Navilife's guess, else "30m". */
    val menuLabel: String get() = when {
        selected -> "✓ $label"
        suggested -> "$label · suggested"
        else -> label
    }
}

/**
 * A Board column's capacity (Week board, slice 3): how long his open tasks there are thought to take against the free
 * time that day. [unsized] counts open tasks with no estimate (they add nothing to [plannedMin]).
 */
data class BoardCapacity(val plannedMin: Int, val freeMin: Int, val unsized: Int) {
    /** More planned than there is free time: the bar and the line turn amber. */
    val over: Boolean get() = plannedMin > freeMin

    /** How full the bar is, 0–1 (full when over, or when there's no free time and something is planned). */
    val fill: Float get() = when {
        plannedMin <= 0 -> 0f
        freeMin <= 0 -> 1f
        else -> (plannedMin.toFloat() / freeMin).coerceAtMost(1f)
    }

    /** "3h planned · 4h free", "5h planned · 1h over", or "7h free" with nothing sized yet. */
    val line: String get() = when {
        plannedMin <= 0 -> "${TaskEstimateRules.label(freeMin)} free"
        over -> "${TaskEstimateRules.label(plannedMin)} planned · ${TaskEstimateRules.label(plannedMin - freeMin)} over"
        else -> "${TaskEstimateRules.label(plannedMin)} planned · ${TaskEstimateRules.label(freeMin)} free"
    }

    /**
     * The same on a compact lane ([BoardFitRules]): "3h of 4h" (planned of free), "5h · 1h over", "7h free". The thin bar under it
     * says the rest.
     */
    val shortLine: String get() = when {
        plannedMin <= 0 -> "${TaskEstimateRules.label(freeMin)} free"
        over -> "${TaskEstimateRules.label(plannedMin)} · ${TaskEstimateRules.label(plannedMin - freeMin)} over"
        else -> "${TaskEstimateRules.label(plannedMin)} of ${TaskEstimateRules.label(freeMin)}"
    }

    /** Screen readers: "3 hours planned, 4 hours free, 2 tasks without a time". */
    val spoken: String get() = buildList {
        add(line.replace(" · ", ", ").replace(Regex("(\\d+)h")) { "${it.groupValues[1]} hour${if (it.groupValues[1] == "1") "" else "s"}" }
            .replace(Regex("(\\d+)m")) { "${it.groupValues[1]} minutes" })
        if (unsized == 1) add("1 task without a time") else if (unsized > 1) add("$unsized tasks without a time")
    }.joinToString(", ")
}

/**
 * Time estimates and day capacity on the Week board (Week board and roll-over, slice 3, item (2); Meka approved
 * 2026-10-19 20:36), non-AI and pure.
 *
 * - A card can carry a time estimate, one of [CHOICES] (15m · 30m · 1h · 2h), set from its menu and synced on the task
 *   ([TaskFields.ESTIMATE_MINUTES], an existing field). A card without one offers Navilife's guess ([suggest]): the
 *   middle estimate of his other tasks sharing the most words with its title, snapped to a choice; nothing is set
 *   until he taps it.
 * - Each column from today on shows "3h planned · 4h free": planned is the sum of its open cards' estimates; free is
 *   the planner's day (09:00–21:00, from now on today) less the day's timed events, his work hours on a work day and,
 *   on an office day, the commute. Over → amber.
 */
object TaskEstimateRules {
    val CHOICES = listOf(15, 30, 60, 120)
    const val MENU_TITLE = "How long?"
    const val CLEAR = "No estimate"
    const val MAX_MIN = 24 * 60

    private val STOP = setOf(
        "the", "and", "for", "with", "from", "into", "about", "my", "our", "his", "her", "this", "that", "some", "get",
        "put", "out", "off", "back", "new", "all", "any",
    )

    /** "15m", "1h", "1h 30m", "0m". */
    fun label(minutes: Int): String {
        val m = minutes.coerceAtLeast(0)
        val h = m / 60
        val r = m % 60
        return when {
            h == 0 -> "${r}m"
            r == 0 -> "${h}h"
            else -> "${h}h ${r}m"
        }
    }

    /** Lower-case words of three letters or more, without the filler words. */
    fun words(title: String): Set<String> =
        title.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in STOP }.toSet()

    /** The [CHOICES] entry nearest [minutes] (the smaller on a tie). */
    fun snap(minutes: Int): Int = CHOICES.minWith(compareBy<Int> { kotlin.math.abs(it - minutes) }.thenBy { it })

    /**
     * Navilife's guess for [task]: of his other tasks with an estimate, those sharing the most title words (at least
     * one); the middle of their estimates (the lower middle for an even count), snapped to a choice. Null when none
     * share a word, or when [task] already has an estimate.
     */
    fun suggest(task: Task, others: List<Task>): Int? = suggest(task, Sized.of(others))

    internal fun suggest(task: Task, sized: Sized): Int? {
        if (task.estimateMinutes != null) return null
        val mine = words(task.title)
        if (mine.isEmpty()) return null
        var best = 0
        val hits = mutableListOf<Int>()
        for ((id, ws, min) in sized.rows) {
            if (id == task.id) continue
            val shared = ws.count { it in mine }
            if (shared == 0 || shared < best) continue
            if (shared > best) { best = shared; hits.clear() }
            hits += min
        }
        if (hits.isEmpty()) return null
        val sorted = hits.sorted()
        return snap(sorted[(sorted.size - 1) / 2])
    }

    /** The menu rows for a card: the four choices, one ticked if set, else Navilife's guess marked. */
    fun choices(estimate: Int?, suggested: Int?): List<EstimateChoice> =
        CHOICES.map { EstimateChoice(it, label(it), suggested == it && estimate == null, estimate == it) }

    /** Valid to store: 1 minute to 24 hours, or null to clear. */
    fun valid(minutes: Int?): Boolean = minutes == null || minutes in 1..MAX_MIN

    /**
     * Day [d]'s capacity, or null before today. [open] are the column's open cards' tasks; [events] the board's
     * (merged, hidden ones out).
     */
    fun capacity(
        d: Long, today: Long, nowMs: Long, open: List<Task>, events: List<CalendarEvent>, calendar: LocalCalendar,
        work: WorkHours?, prefs: DayPlanner.Prefs = DayPlanner.Prefs(),
    ): BoardCapacity? {
        if (d < today) return null
        val planned = open.sumOf { it.estimateMinutes ?: 0 }
        val unsized = open.count { it.estimateMinutes == null }
        return BoardCapacity(planned, freeMinutes(d, today, nowMs, events, calendar, work, prefs), unsized)
    }

    /** Free minutes on day [d] (see the object's note), rounded down to the quarter hour. */
    fun freeMinutes(
        d: Long, today: Long, nowMs: Long, events: List<CalendarEvent>, calendar: LocalCalendar, work: WorkHours?,
        prefs: DayPlanner.Prefs = DayPlanner.Prefs(),
    ): Int {
        val free = freeGaps(d, today, nowMs, events, calendar, work, prefs).sumOf { it.endMs - it.startMs }
        val minutes = (free / 60_000L).toInt()
        return minutes - minutes % 15
    }

    /**
     * Day [d]'s free stretches (see the object's note): the planner's day, from now on today, less timed events, work
     * hours and the commute. Also where Block time offers its starts ([BoardBlockRules], slice 6).
     */
    fun freeGaps(
        d: Long, today: Long, nowMs: Long, events: List<CalendarEvent>, calendar: LocalCalendar, work: WorkHours?,
        prefs: DayPlanner.Prefs = DayPlanner.Prefs(),
    ): List<DayPlanner.Slot> {
        val start = calendar.toEpochMs(d, prefs.dayStartMin).let { if (d == today) maxOf(it, nowMs) else it }
        val end = calendar.toEpochMs(d, prefs.dayEndMin)
        if (end <= start) return emptyList()
        val day = CalendarAgenda.window(d, calendar)
        val busy = buildList {
            events.filter { !it.allDay && it.overlaps(day) }.forEach { add(DayPlanner.Slot(it.startAtMs, it.endAtMs)) }
            work?.let { w ->
                w.blocks(d, calendar).forEach { add(DayPlanner.Slot(it.startMs, it.endMs)) }
                CommuteRules.bands(w, d, calendar).forEach { add(DayPlanner.Slot(it.startMs, it.endMs)) }
            }
        }
        return DayPlanner.subtract(DayPlanner.Slot(start, end), busy)
    }

    /** His tasks that carry an estimate, with their title words, worked out once per board. */
    internal class Sized private constructor(val rows: List<Triple<String, Set<String>, Int>>) {
        companion object {
            fun of(tasks: List<Task>): Sized = Sized(
                tasks.mapNotNull { t -> t.estimateMinutes?.takeIf { it > 0 }?.let { Triple(t.id, words(t.title), it) } }
                    .filter { it.second.isNotEmpty() },
            )
        }
    }
}
