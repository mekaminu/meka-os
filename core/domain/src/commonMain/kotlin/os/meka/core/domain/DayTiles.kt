package os.meka.core.domain

/** What a live tile under the Day ring shows (motion pass 2, the opening moment, part 2). */
enum class DayTileKind { NEXT_EVENT, FAST, HABITS, RENEWALS }

/**
 * One live tile under the Day ring: a number that counts up as Today opens (with the ring's centre) and a short line
 * under it. [value] is what the number counts up to: minutes for [DayTileKind.NEXT_EVENT] and [DayTileKind.FAST],
 * habits done for [DayTileKind.HABITS] (out of [total]) and the count for [DayTileKind.RENEWALS].
 */
data class DayTile(
    val kind: DayTileKind,
    val value: Int,
    /** Habits only: how many are on today's list. */
    val total: Int = 0,
    /** Under the number: "until Standup" · "Fasting · goal 16 h" · "habits today" · "renewals due". */
    val label: String,
) {
    /** The number as shown when it has counted up to [shown]: "25 min" · "1 h 40" · "14 h" · "1 of 3" · "2". */
    fun text(shown: Int = value): String = DayTileRules.valueText(kind, shown, total)

    /** The settled number: [text] of [value]. */
    val valueText: String get() = text(value)

    /** "25 min until Standup" for a screen reader. */
    val spokenLine: String get() = "$valueText ${label.replace(" · ", ", ")}"
}

/**
 * The live tiles under the Day ring (non-AI). Pure, unit-tested. In this order, each only when it has something to say:
 *
 * - **Next event**: the next timed event today that hasn't started ("25 min" · "until Standup"); one already running
 *   isn't counted down (the timeline and Up next say it's on).
 * - **Fast**: a running fast, how long so far ("14 h" · "Fasting · goal 16 h"; "Goal reached" once it is).
 * - **Habits**: habits done today out of today's ("1 of 3" · "habits today"): those done today, due today or behind.
 * - **Renewals**: renewals and bills that need doing ("2" · "renewals due").
 *
 * Today refreshes every minute, so the countdown and the fast move on their own; the apps count the numbers up only
 * while the ring opens (the ring's count-up fraction), then show them as they are.
 */
object DayTileRules {
    const val MAX_TILES = 4
    private const val MIN_MS = 60_000L

    fun build(
        /** Today's visible events (hidden ones and calendars hidden from Today already left out). */
        events: List<CalendarEvent>,
        nowMs: Long,
        today: DayWindow,
        fast: FastNow?,
        habits: List<HabitItem>,
        renewalsDue: Int,
    ): List<DayTile> = build(events, nowMs, today, fast, habitsDone(habits), habitsToday(habits), renewalsDue)

    fun build(
        events: List<CalendarEvent>,
        nowMs: Long,
        today: DayWindow,
        fast: FastNow?,
        habitsDone: Int,
        habitsToday: Int,
        renewalsDue: Int,
    ): List<DayTile> = buildList {
        events.filter { !it.allDay && it.startAtMs > nowMs && it.startAtMs in today }
            .minWithOrNull(compareBy<CalendarEvent> { it.startAtMs }.thenBy { it.id })
            ?.let { e ->
                val minutes = ((e.startAtMs - nowMs + MIN_MS - 1) / MIN_MS).toInt()
                add(DayTile(DayTileKind.NEXT_EVENT, minutes, label = "until ${e.title.trim().ifEmpty { "your next event" }}"))
            }
        fast?.takeIf { nowMs >= it.startedAtMs }?.let { f ->
            val minutes = ((nowMs - f.startedAtMs) / MIN_MS).toInt()
            add(DayTile(DayTileKind.FAST, minutes, label = if (f.reachedGoal) "Goal reached" else f.title))
        }
        if (habitsToday > 0) {
            add(DayTile(DayTileKind.HABITS, habitsDone.coerceIn(0, habitsToday), total = habitsToday, label = "habits today"))
        }
        if (renewalsDue > 0) {
            add(DayTile(DayTileKind.RENEWALS, renewalsDue, label = if (renewalsDue == 1) "renewal due" else "renewals due"))
        }
    }.take(MAX_TILES)

    /** Habits on today's list: done today, due today or behind for the week. */
    fun habitsToday(habits: List<HabitItem>): Int =
        habits.count { it.doneToday || it.pace == HabitPace.DUE || it.pace == HabitPace.BEHIND }

    fun habitsDone(habits: List<HabitItem>): Int = habits.count { it.doneToday }

    /** The tile's number for [shown] (what it has counted up to). */
    fun valueText(kind: DayTileKind, shown: Int, total: Int = 0): String = when (kind) {
        DayTileKind.NEXT_EVENT -> duration(shown, withMinutes = true)
        DayTileKind.FAST -> duration(shown, withMinutes = false)
        DayTileKind.HABITS -> "$shown of $total"
        DayTileKind.RENEWALS -> "$shown"
    }

    /** "25 min" · "1 h 40" · "2 h"; without minutes (a fast) whole hours past the first: "14 h". */
    fun duration(minutes: Int, withMinutes: Boolean): String {
        val m = minutes.coerceAtLeast(0)
        return when {
            m < 60 -> "$m min"
            !withMinutes || m % 60 == 0 -> "${m / 60} h"
            else -> "${m / 60} h ${(m % 60).toString().padStart(2, '0')}"
        }
    }
}
