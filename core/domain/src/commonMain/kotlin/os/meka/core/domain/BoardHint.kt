package os.meka.core.domain

/** What one of Obi's ✦ thoughts on a Board card is about ([BoardHintRules]), most useful first. */
enum class BoardHintKind {
    /** Its day is over its free time and this card fits whole on a lighter day: move it there. */
    OVER,
    /** It has carried for days and he kept it when asked: put it on Someday for now. */
    SLIPPED,
    /** Its estimate is well under what his tasks like it usually take: make it the usual. */
    LONGER,
    /** A task from an email with no estimate: give it 15 minutes for the reply. */
    REPLY,
}

/**
 * One of Obi's quiet thoughts on a Board card (Ada plans with you, part 2): [line] "Thu is 1h over · move it to Sat?",
 * [action] the one-tap pill ("Move to Sat"). [toDay] is where OVER moves it, [minutes] what LONGER and REPLY set.
 * [key] names the thought on the task, so Not now puts away only this one ([BoardHintRules.dismissed]).
 */
data class BoardHint(
    val taskId: String,
    val kind: BoardHintKind,
    val key: String,
    val line: String,
    val action: String,
    val toDay: Long = -1L,
    val minutes: Int = 0,
) {
    /** "✦ Thu is 1h over · move it to Sat?" */
    val shown: String get() = "${BoardHintRules.MARK} $line"

    /** Screen readers: "Obi suggests: Thu is 1h over, move it to Sat?". */
    val spoken: String get() = "${BoardNudgeRules.SPEAKER} suggests: " + line.replace(" · ", ", ")
}

/**
 * Obi's suggestions on the Week board (Ada plans with you and Ada's errands, part 2; Meka 2026-10-21 19:54), non-AI
 * and pure: read only from the board itself, so the Fold and the Mac show the same and nothing is sent anywhere.
 *
 * For each week, from today on, a card may carry one thought, the first that holds:
 * - **OVER**: its day's planned time runs over its free time, and the card is the one [BoardNudgeRules.overFix] would
 *   move: "Thu is 1h over · move it to Sat?" → Move to Sat (a Board move with Undo).
 * - **SLIPPED**: a one-off card carried [SLIP_DAYS]+ days that he chose to Keep when Today asked (so it still slips):
 *   "Slipped 5 days · put it on Someday for now?" → Someday.
 * - **LONGER**: its estimate is under half of what his tasks sharing its words usually take
 *   ([TaskEstimateRules.suggest] with its own estimate left out): "Usually about 1h for you, not 15m" → Make it 1h.
 * - **REPLY**: from his mail with no estimate: "From an email · give it 15m for the reply?" → 15m.
 *
 * Never on a done or repeating card, never one he put away ([dismissed]: [Task.boardHintNo] holds the key), and never
 * more than [MAX] in a week, most useful first, then by day and his order. One tap applies (with Undo); a swipe or Not
 * now puts that thought away on both devices.
 */
object BoardHintRules {
    const val MARK = "✦"
    const val MAX = 3
    const val SLIP_DAYS = 4L
    const val REPLY_MIN = 15
    const val NOT_NOW = "Not now"
    const val PUT_AWAY = "Put away · Obi won't suggest it again"
    private const val SEP = ";"

    /** Obi's thoughts for the week starting [weekStart] on [board], at most [MAX], or none for a week gone. */
    fun of(board: WeekBoard, weekStart: Long, calendar: LocalCalendar): List<BoardHint> {
        val week = board.week(weekStart) ?: return emptyList()
        val today = board.todayEpochDay
        val sized = TaskEstimateRules.Sized.of(allTasks(board))
        val out = mutableListOf<Pair<BoardHint, Int>>()
        val seen = mutableSetOf<String>()
        var order = 0
        fun add(h: BoardHint?) {
            if (h == null || h.taskId in seen || dismissed(board.find(h.taskId)?.card?.task, h.key)) return
            seen += h.taskId
            out += h to order++
        }
        val ahead = week.days.filter { !it.isPast && it.epochDay >= today }
        // OVER first, the soonest day first.
        ahead.forEach { d ->
            val c = d.capacity ?: return@forEach
            if (!c.over || c.plannedMin <= 0 || c.freeMin <= 0) return@forEach
            val over = c.plannedMin - c.freeMin
            val fix = BoardNudgeRules.overFix(board, d, over, today, calendar) ?: return@forEach
            val name = BoardNudgeRules.dayName(d.epochDay, today)
            val to = PressureRules.shortDay(fix.toDay, today)
            add(BoardHint(fix.taskId, BoardHintKind.OVER, "over:${d.epochDay}", "$name is ${TaskEstimateRules.label(over)} over · move it to $to?", "Move to $to", toDay = fix.toDay))
        }
        val cards = ahead.flatMap { it.openCards } + week.lane.filter { !it.done }
        cards.forEach { add(slipped(it, today, calendar)) }
        cards.forEach { add(longer(it, sized)) }
        cards.forEach { add(reply(it)) }
        return out.sortedWith(compareBy({ it.first.kind.ordinal }, { it.second })).take(MAX).map { it.first }
    }

    /** SLIPPED for [card] (see the object's note), or null. */
    fun slipped(card: BoardCard, today: Long, calendar: LocalCalendar): BoardHint? {
        val t = card.task
        if (card.done || t.isRepeating || t.carryAskedDay == null || !t.checklist.isEmpty()) return null
        val at = t.scheduledAtMs ?: return null
        val days = today - calendar.epochDayOf(at)
        if (days < SLIP_DAYS) return null
        return BoardHint(t.id, BoardHintKind.SLIPPED, "slipped", "Slipped $days days · put it on Someday for now?", WeekBoardRules.SOMEDAY)
    }

    /** LONGER for [card] (see the object's note), or null. */
    internal fun longer(card: BoardCard, sized: TaskEstimateRules.Sized): BoardHint? {
        val t = card.task
        val e = t.estimateMinutes ?: return null
        if (card.done || t.isRepeating || e <= 0) return null
        val usual = TaskEstimateRules.suggest(t.copy(estimateMinutes = null), sized) ?: return null
        if (usual < 2 * e) return null
        val u = TaskEstimateRules.label(usual)
        return BoardHint(t.id, BoardHintKind.LONGER, "longer:$e", "Usually about $u for you, not ${TaskEstimateRules.label(e)}", "Make it $u", minutes = usual)
    }

    /** LONGER for [task] against [others] (tests and the facade). */
    fun longer(card: BoardCard, others: List<Task>): BoardHint? = longer(card, TaskEstimateRules.Sized.of(others))

    /** REPLY for [card] (see the object's note), or null. */
    fun reply(card: BoardCard): BoardHint? {
        val t = card.task
        if (card.done || t.isRepeating || t.estimateMinutes != null || card.source != CardSource.MAIL) return null
        return BoardHint(t.id, BoardHintKind.REPLY, "reply", "From an email · give it ${REPLY_MIN}m for the reply?", "${REPLY_MIN}m", minutes = REPLY_MIN)
    }

    /** True when he put away the thought [key] on [task] ([Task.boardHintNo]). */
    fun dismissed(task: Task?, key: String): Boolean = task?.boardHintNo?.split(SEP)?.contains(key) == true

    /** What [Task.boardHintNo] becomes once [key] is put away (the newest few kept). */
    fun withDismissed(before: String?, key: String): String =
        (before?.split(SEP)?.filter { it.isNotBlank() && it != key }.orEmpty() + key).takeLast(8).joinToString(SEP)

    /** The undo bar's line after a tap on [hint]'s pill for [title]. */
    fun appliedLine(hint: BoardHint, title: String): String {
        val t = TaskTitleRules.display(title)
        return when (hint.kind) {
            BoardHintKind.OVER -> "Moved “$t” ${hint.action.removePrefix("Move ")}"
            BoardHintKind.SLIPPED -> WeekBoardRules.somedayLine(title)
            BoardHintKind.LONGER, BoardHintKind.REPLY -> "“$t” · about ${TaskEstimateRules.label(hint.minutes)}"
        }
    }

    private fun allTasks(board: WeekBoard): List<Task> =
        (board.weeks.flatMap { it.allCards } + board.someday).map { it.task }.distinctBy { it.id }
}
