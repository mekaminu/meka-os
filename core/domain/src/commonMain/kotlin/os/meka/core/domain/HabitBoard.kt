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
)

data class HabitBoardGroup(
    val title: String,
    /** "2 of 3" done on the selected day. */
    val count: String,
    /** "before work", "any time", "before bed"; empty for his own lists. */
    val whenLine: String,
    val rows: List<HabitBoardRow>,
)

data class HabitBoard(
    val days: List<HabitBoardDay>,
    /** "3 of 8 done today", "5 of 8 done · Tue 10". */
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
    const val EMPTY_LINE = "Add a habit to do (Drink water) or one to keep off (No biscuits). Tap its circle each day."
    private val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

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
            HabitBoardDay(d, wd, dom, done, total, i == sel, i == DAYS - 1, "$wd $dom, $done of $total done")
        }
        val shown = habits.filter { chosen == ALL || listName(it) == chosen }
        val counted = shown.filter { countsOn(it, sel) }
        val done = counted.count { markOn(it, sel) == HabitMark.DONE }
        val selDay = days[sel]
        val doneLine = "$done of ${counted.size} done" + if (sel == DAYS - 1) " today" else " · ${selDay.weekday} ${selDay.dayOfMonth}"
        val best = shown.maxByOrNull { it.streak }?.takeIf { it.streak >= 2 }
        val groups = lists.filter { chosen == ALL || it == chosen }.mapNotNull { name ->
            val rows = shown.filter { listName(it) == name }
                .sortedWith(compareBy<HabitItem> { it.createdDay }.thenBy { it.title.lowercase() }.thenBy { it.id })
            if (rows.isEmpty()) return@mapNotNull null
            val d = rows.count { countsOn(it, sel) && markOn(it, sel) == HabitMark.DONE }
            HabitBoardGroup(
                title = name,
                count = "$d of ${rows.count { countsOn(it, sel) }}",
                whenLine = whenLine(name),
                rows = rows.map { h ->
                    val m = markOn(h, sel)
                    HabitBoardRow(
                        id = h.id, name = h.title, meta = meta(h), avoid = h.avoid,
                        week = (0 until DAYS).map { markOn(h, it) }, selectedIndex = sel,
                        mark = m, markLabel = markLabel(m), list = name,
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
}
