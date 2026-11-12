package os.meka.core.domain

/** One habit on one day: ticked done, crossed missed, or not marked. */
enum class HabitMark {
    CLEAR, DONE, MISSED;

    /** The circle's cycle: clear → done → missed → clear. */
    fun next(): HabitMark = when (this) {
        CLEAR -> DONE
        DONE -> MISSED
        MISSED -> CLEAR
    }
}

/** A day on the strip: "Thu" over "12", with the share of habits done that day. */
data class HabitBoardDay(
    val day: Long,
    val weekday: String,
    val dayOfMonth: Int,
    val done: Int,
    val total: Int,
    val selected: Boolean,
    val isToday: Boolean,
    /** "Thu 12, 3 of 8 done" for a screen reader. */
    val label: String,
) {
    /** 0–100, the little bar under the date. */
    val pct: Int get() = if (total == 0) 0 else done * 100 / total
}

/** A list chip: All, Morning, All day, Night time, then his own lists. */
data class HabitBoardChip(val name: String, val selected: Boolean)

data class HabitBoardRow(
    val id: String,
    val name: String,
    /** "Do · 9 day streak", "Avoid · start today", "Do · 3 week streak". */
    val meta: String,
    val avoid: Boolean,
    /** The strip's seven days, oldest first: the row's dots. */
    val week: List<HabitMark>,
    /** Where the selected day's dot is in [week]. */
    val selectedIndex: Int,
    /** The selected day's mark: the big circle. */
    val mark: HabitMark,
    /** "Done, tap for missed", "Missed, tap to clear", "Not marked, tap for done". */
    val markLabel: String,
    /** Which list it's in now ("Morning", or his own list's name). */
    val list: String,
    /** Slice 3: the daily reminder, minutes after local midnight; null for none. */
    val remindAt: Int? = null,
    /** Slice 3: "Reminder 07:30" or "No reminder". */
    val reminderLine: String = HabitBoardRules.NO_REMINDER,
    /** Slice 3: the last four weeks, shown when the row unfolds. */
    val history: HabitHistory = HabitHistory.EMPTY,
)

/** One square of a habit's history: a day and its mark; [mark] null for a day before it was added or still to come. */
data class HabitHistoryCell(val day: Long, val dayOfMonth: Int, val mark: HabitMark?, val isToday: Boolean, val label: String)

/**
 * Slice 3 (the design: "tap a habit for its history and reminder"): four weeks Monday to Sunday ending with this
 * week, "Done 18 of the last 28 days · 2 missed" ("Kept off …" for Avoid; "… since you added it" when newer), and
 * "Best run 12 days" (ever) once it is 2 or more.
 */
data class HabitHistory(
    /** Rows Monday to Sunday, oldest first; each has seven cells. */
    val weeks: List<List<HabitHistoryCell>>,
    val doneLine: String,
    val bestLine: String?,
) {
    companion object {
        val EMPTY = HabitHistory(emptyList(), "", null)
    }
}

data class HabitBoardGroup(
    val title: String,
    /** "2 of 3" done on the selected day; empty when none of its habits had started by then. */
    val count: String,
    /** "before work", "any time", "before bed"; empty for his own lists. */
    val whenLine: String,
    val rows: List<HabitBoardRow>,
)

data class HabitBoard(
    val days: List<HabitBoardDay>,
    /** "3 of 8 done today", "5 of 8 done · Tue 10", "No habits yet" (never "0 of 0"). */
    val doneLine: String,
    /** "best streak 9 days"; null when no habit has a streak of 2 yet. */
    val streakLine: String?,
    /** 0–100: the selected day's bar (of the habits shown). */
    val pct: Int,
    val chips: List<HabitBoardChip>,
    val groups: List<HabitBoardGroup>,
    /** Every list a habit can go in (the add sheet's and a row's choices). */
    val lists: List<String>,
    /** The selected day, a local epoch day. */
    val selectedDay: Long,
    val empty: Boolean,
)

/**
 * The Habits screen (approved 2026-11-11 22:19, `docs/design/habits/Habits.dc.html`; non-AI, pure, unit-tested):
 * seven days ending today along the top (tap one to mark an earlier day), the selected day's "3 of 8 done" with a
 * bar and the best streak, list chips, and the habits boxed by list (Morning, All day, Night time, then his own),
 * each Do or Avoid with its streak, the strip's seven dots and one circle that cycles done → missed → clear.
 */
object HabitBoardRules {
    const val DAYS = 7
    const val DO = "do"
    const val AVOID = "avoid"
    const val ALL = "All"
    const val MORNING = "Morning"
    const val ALL_DAY = "All day"
    const val NIGHT = "Night time"
    val DEFAULT_LISTS = listOf(MORNING, ALL_DAY, NIGHT)
    const val MAX_LIST = 24
    const val HINT = "Tap the circle: done, then missed, then clear · tap a day to mark an earlier day"
    const val NO_HABITS = "No habits yet"
    const val EMPTY_LINE = "Add a habit to do (Drink water) or one to keep off (No biscuits). Tap its circle each day."
    private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    /** Slice 3: the history kept on each habit (four weeks back to a Monday always fit). */
    const val HISTORY_DAYS = 28
    const val HISTORY_WEEKS = 4
    const val NO_REMINDER = "No reminder"
    /** The weekday letters over the history's columns. */
    val HISTORY_HEADS = listOf("M", "T", "W", "T", "F", "S", "S")
    const val REMINDER_TEXT_DO = "Time for it · tap to open Habits and tick it"
    const val REMINDER_TEXT_AVOID = "Still keeping off it? Tick it in Habits tonight"

    /** A list's name trimmed, spaces folded, at most [MAX_LIST]; a default name in any case reads as the default; null when blank. */
    fun cleanList(raw: String?): String? {
        val t = raw?.trim()?.replace(Regex("\\s+"), " ")?.take(MAX_LIST)?.trim() ?: return null
        if (t.isEmpty()) return null
        return DEFAULT_LISTS.firstOrNull { it.equals(t, ignoreCase = true) } ?: t.takeUnless { it.equals(ALL, ignoreCase = true) }
    }

    /** Morning → MORNING, Night time → EVENING, All day → ANYTIME; null for his own list. */
    fun timingOf(list: String): HabitTiming? = when (list) {
        MORNING -> HabitTiming.MORNING
        ALL_DAY -> HabitTiming.ANYTIME
        NIGHT -> HabitTiming.EVENING
        else -> null
    }

    /** The list a habit sits in: his own list, else its part of the day (an afternoon habit is All day). */
    fun listName(h: HabitItem): String = h.list ?: when (h.timing) {
        HabitTiming.MORNING -> MORNING
        HabitTiming.EVENING -> NIGHT
        HabitTiming.AFTERNOON, HabitTiming.ANYTIME -> ALL_DAY
    }

    fun whenLine(list: String): String = when (list) {
        MORNING -> "before work"
        ALL_DAY -> "any time"
        NIGHT -> "before bed"
        else -> ""
    }

    fun markLabel(m: HabitMark): String = when (m) {
        HabitMark.DONE -> "Done, tap for missed"
        HabitMark.MISSED -> "Missed, tap to clear"
        HabitMark.CLEAR -> "Not marked, tap for done"
    }

    /** What the undo-free toast says after a tap: "Done · nice", "Marked missed", or nothing. */
    fun markedLine(m: HabitMark): String? = when (m) {
        HabitMark.DONE -> "Done · nice"
        HabitMark.MISSED -> "Marked missed"
        HabitMark.CLEAR -> null
    }

    fun meta(h: HabitItem): String {
        val kind = if (h.avoid) "Avoid" else "Do"
        return if (h.streak >= 1) "$kind · ${h.streak} ${h.streakUnit} streak" else "$kind · start today"
    }

    /**
     * The board for [view] (its `today` and each habit's [HabitItem.recent]), with [selected] 0–6 along the strip (6 =
     * today) and [list] "All" or a list's name. A habit counts on a day only from the day it was added.
     */
    fun board(view: GoalsView, selected: Int = DAYS - 1, list: String = ALL): HabitBoard {
        val today = view.today
        val sel = selected.coerceIn(0, DAYS - 1)
        val first = today - (DAYS - 1)
        val habits = view.habits
        fun markOn(h: HabitItem, i: Int): HabitMark = h.recent.getOrNull(i) ?: HabitMark.CLEAR
        fun countsOn(h: HabitItem, i: Int): Boolean = h.createdDay <= first + i
        val own = habits.mapNotNull { it.list }.distinct().sortedBy { it.lowercase() }
        val lists = DEFAULT_LISTS + own
        val chosen = if (list == ALL || list in lists) list else ALL
        val days = (0 until DAYS).map { i ->
            val d = first + i
            val total = habits.count { countsOn(it, i) }
            val done = habits.count { countsOn(it, i) && markOn(it, i) == HabitMark.DONE }
            val wd = WEEKDAYS[CivilDate.isoDayOfWeek(d) - 1]
            val dom = CivilDate.fromEpochDay(d).day
            HabitBoardDay(d, wd, dom, done, total, i == sel, i == DAYS - 1, if (total == 0) "$wd $dom, no habits yet" else "$wd $dom, $done of $total done")
        }
        val shown = habits.filter { chosen == ALL || listName(it) == chosen }
        val counted = shown.filter { countsOn(it, sel) }
        val done = counted.count { markOn(it, sel) == HabitMark.DONE }
        val selDay = days[sel]
        val on = if (sel == DAYS - 1) " today" else " · ${selDay.weekday} ${selDay.dayOfMonth}"
        // Never "0 of 0": no habits at all, or none yet on that day (all added later).
        val doneLine = when {
            habits.isEmpty() -> NO_HABITS
            counted.isEmpty() -> "No habits yet$on"
            else -> "$done of ${counted.size} done$on"
        }
        val best = shown.maxByOrNull { it.streak }?.takeIf { it.streak >= 2 }
        val groups = lists.filter { chosen == ALL || it == chosen }.mapNotNull { name ->
            val rows = shown.filter { listName(it) == name }
                .sortedWith(compareBy<HabitItem> { it.createdDay }.thenBy { it.title.lowercase() }.thenBy { it.id })
            if (rows.isEmpty()) return@mapNotNull null
            val d = rows.count { countsOn(it, sel) && markOn(it, sel) == HabitMark.DONE }
            val n = rows.count { countsOn(it, sel) }
            HabitBoardGroup(
                title = name,
                count = if (n == 0) "" else "$d of $n",
                whenLine = whenLine(name),
                rows = rows.map { h ->
                    val m = markOn(h, sel)
                    HabitBoardRow(
                        id = h.id, name = h.title, meta = meta(h), avoid = h.avoid,
                        week = (0 until DAYS).map { markOn(h, it) }, selectedIndex = sel,
                        mark = m, markLabel = markLabel(m), list = name,
                        remindAt = h.remindAt, reminderLine = reminderLine(h.remindAt), history = history(h, today),
                    )
                },
            )
        }
        return HabitBoard(
            days = days,
            doneLine = doneLine,
            streakLine = best?.let { "best streak ${it.streak} ${it.streakUnit}s" },
            pct = if (counted.isEmpty()) 0 else done * 100 / counted.size,
            chips = (listOf(ALL) + lists).map { HabitBoardChip(it, it == chosen) },
            groups = groups,
            lists = lists,
            selectedDay = selDay.day,
            empty = habits.isEmpty(),
        )
    }

    /**
     * A tap's mark shown at once, before the core's view comes back (taps feel instant): [pending] maps
     * "<habitId>@<day>" to the mark tapped; each habit's [HabitItem.recent] takes it.
     */
    fun shown(view: GoalsView, pending: Map<String, HabitMark>): GoalsView {
        if (pending.isEmpty()) return view
        val first = view.today - (DAYS - 1)
        var changed = false
        val habits = view.habits.map { h ->
            if (h.recent.size != DAYS) return@map h
            var r = h.recent
            for (i in 0 until DAYS) {
                val m = pending[key(h.id, first + i)] ?: continue
                if (r[i] != m) { r = r.toMutableList().also { it[i] = m }; changed = true }
            }
            if (r === h.recent) h else h.copy(recent = r)
        }
        return if (changed) view.copy(habits = habits) else view
    }

    fun key(habitId: String, day: Long): String = "$habitId@$day"

    // ---- Slice 3: history and the reminder ----

    /** The longest run of days in a row among [days] (each a local epoch day); 0 for none. */
    fun bestRun(days: Collection<Long>): Int {
        if (days.isEmpty()) return 0
        val sorted = days.toSortedSet().toList()
        var best = 1
        var run = 1
        for (i in 1 until sorted.size) {
            run = if (sorted[i] == sorted[i - 1] + 1) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    fun reminderLine(minute: Int?): String = minute?.let { "Reminder ${LocalClock.formatMinute(it)}" } ?: NO_REMINDER

    /** The time a reminder starts at when he turns it on: Morning 07:30, All day 12:30, Night time 21:30, his own 09:00. */
    fun defaultReminder(list: String): Int = when (list) {
        MORNING -> 7 * 60 + 30
        ALL_DAY -> 12 * 60 + 30
        NIGHT -> 21 * 60 + 30
        else -> 9 * 60
    }

    /** A habit's four weeks ([HabitItem.history] ending on [today]). */
    fun history(h: HabitItem, today: Long): HabitHistory {
        if (h.history.size != HISTORY_DAYS) return HabitHistory.EMPTY
        val first = today - (HISTORY_DAYS - 1)
        val start = GoalRules.weekStart(today) - 7L * (HISTORY_WEEKS - 1)
        fun markOn(d: Long): HabitMark? = when {
            d > today || d < h.createdDay || d < first -> null
            else -> h.history[(d - first).toInt()]
        }
        val weeks = (0 until HISTORY_WEEKS).map { w ->
            (0 until 7).map { i ->
                val d = start + 7L * w + i
                val m = markOn(d)
                val date = CivilDate.fromEpochDay(d)
                val name = "${WEEKDAYS[i]} ${date.day}"
                val label = when (m) {
                    null -> if (d > today) "$name, still to come" else "$name, before you added it"
                    HabitMark.DONE -> if (h.avoid) "$name, kept off" else "$name, done"
                    HabitMark.MISSED -> "$name, missed"
                    HabitMark.CLEAR -> "$name, not marked"
                }
                HabitHistoryCell(d, date.day, m, d == today, label)
            }
        }
        val from = maxOf(first, h.createdDay)
        val counted = if (from > today) 0 else (today - from + 1).toInt()
        val done = (from..today).count { markOn(it) == HabitMark.DONE }
        val missed = (from..today).count { markOn(it) == HabitMark.MISSED }
        val verb = if (h.avoid) "Kept off" else "Done"
        val span = when {
            counted == 0 -> ""
            h.createdDay > first -> "$counted days since you added it"
            else -> "the last $HISTORY_DAYS days"
        }
        val doneLine = if (counted <= 1) "Added today · its days build up here" else buildString {
            append("$verb $done of $span")
            if (missed > 0) append(" · $missed missed")
        }
        return HabitHistory(
            weeks = weeks,
            doneLine = doneLine,
            bestLine = h.bestRun.takeIf { it >= 2 }?.let { "Best run $it days" },
        )
    }

    /**
     * Slice 3: the day's reminder for each habit that has one, at its time today, while today is still unmarked (a
     * tick or a cross stops it) and the habit has started; an N-a-week habit whose week is already met isn't nagged.
     * Gone at the end of the day. Never on a day it doesn't count.
     */
    fun reminderNotices(view: GoalsView, nowMs: Long, cal: LocalCalendar): List<Notice> {
        val today = cal.epochDayOf(nowMs)
        if (view.today != today) return emptyList()
        return view.habits.mapNotNull { h ->
            val at = h.remindAt ?: return@mapNotNull null
            if (h.createdDay > today) return@mapNotNull null
            if ((h.recent.lastOrNull() ?: HabitMark.CLEAR) != HabitMark.CLEAR) return@mapNotNull null
            if (h.weekly && h.doneThisWeek >= h.weekTarget) return@mapNotNull null
            Notice(
                key = "habit-remind:${h.id}:$today", source = NoticeSource.HABITS, tier = NoticeTier.HEADS_UP,
                title = h.title,
                text = if (h.avoid) REMINDER_TEXT_AVOID else REMINDER_TEXT_DO,
                atMs = cal.toEpochMs(today, at), target = NoticeTarget.GOALS,
                expiresAtMs = cal.toEpochMs(today, 24 * 60 - 1),
                precision = NoticePrecision.CLOCK,
            )
        }
    }
}
