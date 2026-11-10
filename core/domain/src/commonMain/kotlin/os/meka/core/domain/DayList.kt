package os.meka.core.domain

/**
 * One line typed (or pasted) into the Day list's add bar, read by [DayListRules.parse]: the title without the bits
 * Navilife understood, how long it takes ("30m", "1h", "1h30", "45 min"), a start time ("9:00", "14:30") and an area
 * ("#work", "#family", "#house", "#money", "#side"). Anything not understood stays in the title.
 */
data class DayListEntry(val title: String, val minutes: Int?, val startMinute: Int?, val area: BoardArea?)

/** One row of the Day list: tick · time · title · area dot and name · length chip. */
data class DayListRow(
    val id: String,
    val title: String,
    /** "09:00": its own time, or (Times on) where it falls if the day runs in order; null with Times off and no time. */
    val time: String?,
    /** True when [time] is the task's own planned time; false for a projected start (drawn quieter). */
    val timeIsSet: Boolean,
    val area: BoardArea?,
    /** "Work"; null with no area. */
    val areaLabel: String?,
    /** "30m": its estimate, else Navilife's guess; null when neither. */
    val length: String?,
    /** True when [length] is a guess, not his estimate (drawn quieter). */
    val lengthGuessed: Boolean,
    val done: Boolean,
    val card: BoardCard,
) {
    /** Screen readers: "Book MOT, 09:00, 30 minutes, Work, done". */
    val spoken: String
        get() = listOfNotNull(title, time, length?.let { "$it long" }, areaLabel, if (done) "done" else null).joinToString(", ")
}

/** The Day list for one day (the approved `docs/design/day-list/Main.dc.html`). */
data class DayListView(
    val epochDay: Long,
    /** "Sunday 8 November" */
    val title: String,
    val isToday: Boolean,
    /** "2h 30m" planned in all (open and done); "Nothing yet" with no tasks. */
    val total: String,
    /** "2 of 5 done" */
    val doneLine: String,
    /** "ends about 11:15" (open tasks run from now on today, from 9:00 on another day); null when all are done. */
    val endsLine: String?,
    /** 0..1: the done share of the day's planned minutes. */
    val progress: Float,
    val rows: List<DayListRow>,
    val timesOn: Boolean,
) {
    val isEmpty: Boolean get() = rows.isEmpty()
    val openIds: List<String> get() = rows.filter { !it.done }.map { it.id }
}

/** What the add bar did: the tasks made and the one line said under it ("Added 3 tasks · 1h 15m"). */
data class DayListAdded(val ids: List<String>, val line: String)

/** What Fit times did: the line ("Fitted 4 tasks from 09:00 · ends about 11:15") and what Undo puts back. */
data class DayListFit(val line: String, val before: List<Pair<String, Int?>>, val day: Long)

/**
 * The Day list (Meka approved 2026-11-08 22:04): one screen per day where a long list of tasks goes in fast. Non-AI and
 * pure, the same on the Fold and the Mac; the tasks are the Week board's own (nothing new to sync).
 *
 * - **Rapid add**: Enter adds the line and clears the bar for the next; "30m"/"1h", "9:00" and "#work" are read out of
 *   the text ([parse]); anything else stays in the title.
 * - **Paste a list**: one task per line, bullets, numbers and "[ ]" boxes stripped ([pasted]).
 * - **Fit times from 9:00**: the open tasks get start times one after another in the list's order ([fit]); a task
 *   without a length counts [DEFAULT_MINUTES].
 */
object DayListRules {
    const val DEFAULT_MINUTES = 30
    const val DAY_START = 9 * 60
    const val MAX_PASTE = 50
    const val HINT = "Type one after another · “30m”, “9:00”, “#work” work in the text · paste a list for many at once"
    const val PLACEHOLDER = "New task"
    const val PASTE = "Paste a list"
    const val EMPTY = "Nothing planned yet. Type a task and press Enter, then the next."
    /** The way in beside Today's "Anytime today" heading, and under Today when nothing is unplanned (both apps). */
    const val LINK = "Day list ›"
    const val EMPTY_LINK = "Plan today’s tasks · Day list ›"
    const val TYPE_FIRST = "Type a task first"
    const val COPY_FIRST = "Copy a list first, one task per line"

    private val LENGTH = Regex("""(?i)(?<![A-Za-z0-9_:])(\d{1,2}(?:\.5)?)\s*h(?:rs?|ours?)?\s*(\d{1,2})?\s*(?:m(?:ins?)?)?(?![A-Za-z0-9_:])|(?<![A-Za-z0-9_:])(\d{1,3})\s*m(?:ins?|inutes?)?(?![A-Za-z0-9_:])""")
    private val TIME = Regex("""(?<![A-Za-z0-9_:])([01]?\d|2[0-3])[:.]([0-5]\d)(?![A-Za-z0-9_:])""")
    private val TAG = Regex("""(?<!\S)#([A-Za-z][A-Za-z-]*)""")
    private val BULLET = Regex("""^\s*(?:[-*•–·]|\d{1,3}[.)]|\[\s?[xX]?\s?])\s*""")
    private val SPACE = Regex("""\s+""")

    /** "#work" and friends, by the start of the word (so "#fam", "#side", "#home" work too). */
    fun areaOf(tag: String): BoardArea? {
        val t = tag.lowercase().replace("-", "")
        return when {
            t.startsWith("work") || t == "job" || t == "office" -> BoardArea.WORK
            t.startsWith("fam") || t == "kids" || t == "family" -> BoardArea.FAMILY
            t.startsWith("side") || t == "hustle" || t == "biz" -> BoardArea.SIDE_HUSTLE
            t.startsWith("money") || t == "bills" || t == "finance" -> BoardArea.MONEY
            t.startsWith("house") || t == "home" -> BoardArea.HOUSE
            else -> null
        }
    }

    /** Reads one typed line; null when nothing is left to be a title. */
    fun parse(text: String): DayListEntry? {
        var s = " " + text.replace(SPACE, " ").trim() + " "
        var minutes: Int? = null
        LENGTH.find(s)?.let { m ->
            val mins = if (m.groupValues[3].isNotEmpty()) m.groupValues[3].toInt()
            else ((m.groupValues[1].toDouble() * 60).toInt() + (m.groupValues[2].toIntOrNull() ?: 0))
            if (mins in 1..TaskEstimateRules.MAX_MIN) {
                minutes = mins
                s = s.removeRange(m.range).let { " $it " }
            }
        }
        var start: Int? = null
        TIME.find(s)?.let { m ->
            start = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
            s = s.removeRange(m.range)
        }
        var area: BoardArea? = null
        for (m in TAG.findAll(s).toList().reversed()) {
            val a = areaOf(m.groupValues[1]) ?: continue
            if (area == null) area = a
            s = s.removeRange(m.range)
        }
        val title = s.replace(SPACE, " ").trim().trim(',', ';', '·', '-').trim()
        if (title.isEmpty()) return null
        return DayListEntry(title.take(QuickCapture.MAX_TITLE), minutes, start, area)
    }

    /** Paste a list: one entry per non-empty line (at most [MAX_PASTE]), bullets and numbering stripped. */
    fun pasted(text: String): List<DayListEntry> =
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            .map { it.replace(BULLET, "") }
            .mapNotNull { parse(it) }
            .take(MAX_PASTE)

    /** True when the field holds more than one line (the add bar then offers "Add N tasks"). */
    fun isList(text: String): Boolean = pasted(text).size > 1

    /** "Added “Book MOT” · 30m" · "Added 3 tasks · 1h 15m". */
    fun addedLine(entries: List<DayListEntry>): String {
        val mins = entries.sumOf { it.minutes ?: 0 }
        val head = if (entries.size == 1) "Added “${entries[0].title}”" else "Added ${entries.size} tasks"
        return if (mins > 0) "$head · ${TaskEstimateRules.label(mins)}" else head
    }

    private fun minuteOf(time: String?): Int? = time?.let { TIME.matchEntire(it) }?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }

    /** A task's minutes on the Day list: its estimate, else the suggested length, else [DEFAULT_MINUTES]. */
    fun minutesOf(card: BoardCard): Int = card.task.estimateMinutes ?: card.suggestedMinutes ?: DEFAULT_MINUTES

    private fun lengthOf(card: BoardCard): Int = minutesOf(card)

    /** The clock a day's open tasks run from: now (to the next 5 minutes) on today, else [DAY_START]. */
    fun startOf(isToday: Boolean, nowMinute: Int): Int =
        if (isToday) ((nowMinute + 4) / 5 * 5).coerceAtMost(24 * 60 - 5) else DAY_START

    /** Where "Fit times" starts: today from now (to the next 5 minutes, never before [DAY_START]); another day [DAY_START]. */
    fun fitFrom(isToday: Boolean, nowMinute: Int): Int = if (isToday) maxOf(DAY_START, startOf(true, nowMinute)) else DAY_START

    /** "Fit times from 9:00". */
    fun fitLabel(fromMinute: Int): String = "Fit times from ${TaskWhenRules.timeLabel(fromMinute)}"

    /**
     * The day's plan from [fromMinute]: open tasks with their own time at or after [holdFrom] hold it; the rest, in
     * the list's order, start one after another in the gaps around them (a task never overlaps a held one). A task that
     * would run past midnight gets no start. Returns each open task's start (null when it doesn't fit) and the end.
     */
    internal fun flow(cards: List<BoardCard>, fromMinute: Int, holdFrom: Int = fromMinute): Pair<Map<String, Int?>, Int> {
        val open = cards.filter { !it.done }
        val held = open.mapNotNull { c -> minuteOf(c.time)?.takeIf { it >= holdFrom }?.let { c.id to (it until it + lengthOf(c)) } }.toMap()
        val starts = mutableMapOf<String, Int?>()
        var end = fromMinute
        held.forEach { (id, r) -> starts[id] = r.first; end = maxOf(end, r.last + 1) }
        var clock = fromMinute
        for (c in open) {
            if (c.id in held) continue
            val len = lengthOf(c)
            var at = clock
            while (true) {
                val clash = held.values.firstOrNull { at < it.last + 1 && it.first < at + len } ?: break
                at = clash.last + 1
            }
            if (at + len > 24 * 60) { starts[c.id] = null; continue }
            starts[c.id] = at
            clock = at + len
            end = maxOf(end, clock)
        }
        return starts to end
    }

    fun view(day: BoardDay, nowMinute: Int, timesOn: Boolean): DayListView {
        val cards = day.cards
        // Another day keeps every planned time (a 7:00 gym is real); today, a time already gone runs from now.
        val from = startOf(day.isToday, nowMinute)
        val (starts, end) = flow(cards, from, holdFrom = if (day.isToday) from else 0)
        val built = cards.map { c ->
            val set = if (c.done) null else minuteOf(c.time)
            val time = when {
                c.done -> null
                set != null -> TaskWhenRules.timeLabel(set)
                timesOn -> starts[c.id]?.let(TaskWhenRules::timeLabel)
                else -> null
            }
            val est = c.task.estimateMinutes
            DayListRow(
                id = c.id, title = c.title, time = time, timeIsSet = set != null,
                area = BoardAreaRules.of(c), areaLabel = BoardAreaRules.cardLabel(c),
                length = (est ?: c.suggestedMinutes)?.let(TaskEstimateRules::label), lengthGuessed = est == null,
                done = c.done, card = c,
            )
        }
        // With times showing, the open rows read as the day runs (stable for ties); done ones stay at the foot.
        val rows = if (!timesOn) built else built.filter { !it.done }
            .sortedBy { r -> minuteOf(r.time) ?: Int.MAX_VALUE } + built.filter { it.done }
        val total = cards.sumOf(::lengthOf)
        val doneMin = cards.filter { it.done }.sumOf(::lengthOf)
        val open = cards.count { !it.done }
        return DayListView(
            epochDay = day.epochDay,
            title = if (day.isToday) "Today · ${CivilDate.longLabel(day.epochDay)}" else CivilDate.longLabel(day.epochDay),
            isToday = day.isToday,
            total = if (cards.isEmpty()) "Nothing yet" else TaskEstimateRules.label(total),
            doneLine = "${cards.size - open} of ${cards.size} done",
            endsLine = if (open > 0) "ends about ${TaskWhenRules.timeLabel(end.coerceAtMost(24 * 60 - 1))}" else null,
            progress = if (total > 0) doneMin.toFloat() / total else 0f,
            rows = rows,
            timesOn = timesOn,
        )
    }

    /**
     * Fit times from [fromMinute]: the plan [flow] works out, written down. Times at or after [fromMinute] hold; every
     * other open task gets its start in the list's order, around them. Returns (task, start) for each that changes.
     */
    fun fit(view: DayListView, fromMinute: Int): List<Pair<String, Int>> {
        val (starts, _) = flow(view.rows.map { it.card }, fromMinute)
        return view.rows.filter { !it.done }.mapNotNull { r ->
            val at = starts[r.id] ?: return@mapNotNull null
            if (r.timeIsSet && minuteOf(r.time) == at) null else r.id to at
        }
    }

    /**
     * Drag to reorder (slice 1c): whether [r] has a grip. Open rows only; with Times on a row with its own time is placed
     * by that time (a 14:00 dentist stays at 14:00), so only the rest drag. With Times off every open row drags.
     */
    fun canDrag(r: DayListRow, timesOn: Boolean): Boolean = !r.done && !(timesOn && r.timeIsSet)

    /** The open rows' indexes a screen reader's Move up / Move down step to: the nearest draggable row above or below. */
    fun stepTo(view: DayListView, taskId: String, up: Boolean): Int? {
        val open = view.rows.filter { !it.done }
        val from = open.indexOfFirst { it.id == taskId }
        if (from < 0 || !canDrag(open[from], view.timesOn)) return null
        val range = if (up) (from - 1 downTo 0) else (from + 1 until open.size)
        return range.firstOrNull { canDrag(open[it], view.timesOn) }
    }

    /**
     * Drag to reorder: [taskId] dropped at row [toRow] among the view's open rows (0 = top). Returns its place among the
     * day's other open cards in the board's order ([openOrder], the Week board's `openCards`) for
     * `WeekBoardRules.move`, or null when it can't move or nothing changes. Times on, the open rows read as the day runs,
     * so the task goes right after the nearest draggable row above where it lands (or before the first draggable one):
     * the untimed tasks then run in the order he dropped them, and the timed ones keep their times.
     */
    fun boardIndex(view: DayListView, openOrder: List<String>, taskId: String, toRow: Int): Int? {
        val open = view.rows.filter { !it.done }
        val from = open.indexOfFirst { it.id == taskId }
        if (from < 0 || !canDrag(open[from], view.timesOn)) return null
        val to = toRow.coerceIn(0, open.size - 1)
        if (to == from) return null
        val moved = open.map { it.id }.toMutableList().apply { removeAt(from); add(to, taskId) }
        val free = open.filter { canDrag(it, view.timesOn) }.map { it.id }.toSet()
        val others = openOrder.filter { it != taskId }
        val before = moved.subList(0, to).lastOrNull { it in free && it in others }
        return if (before != null) others.indexOf(before) + 1
        else others.indexOfFirst { it in free }.let { if (it < 0) others.size else it }
    }

    /** "Fitted 4 tasks from 09:00 · ends about 11:15"; "Times already fit" when nothing changes. */
    fun fitLine(view: DayListView, fromMinute: Int, changed: Int): String {
        if (changed == 0) return "Times already fit"
        val (_, end) = flow(view.rows.map { it.card }, fromMinute)
        val n = if (changed == 1) "1 task" else "$changed tasks"
        return "Fitted $n from ${TaskWhenRules.timeLabel(fromMinute)} · ends about ${TaskWhenRules.timeLabel(end.coerceAtMost(24 * 60 - 1))}"
    }
}
