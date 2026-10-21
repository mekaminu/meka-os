package os.meka.core.domain

/** "How are you feeling?" at the end of the day (Week board, item (11)). */
enum class Feeling(val label: String) {
    GOOD("Good"),
    TIRED("Tired"),
    STRETCHED("Stretched"),
}

/** One task Navilife offers to take off tomorrow: to [toDay] ("Fri"), or to Someday when [toDay] is null. */
data class FeelingMove(val taskId: String, val title: String, val toDay: Long?, val dayLabel: String)

/**
 * What Navilife says after he picks how he feels, and what it offers. Nothing moves until he taps: [moves] go with
 * "Lighten tomorrow" (one Undo puts them all back); Stretched's [drop] is its own tap ("Someday for “Garage”").
 */
data class FeelingOffer(
    val feeling: Feeling,
    /** "Let's go easy tomorrow: move “Gym” to Fri and “Trip form” to Sat?" */
    val line: String,
    val moves: List<FeelingMove>,
    /** Stretched: one task that could wait on Someday; null otherwise. */
    val drop: FeelingMove? = null,
    /** Stretched, part 2: "Hold alerts till tomorrow 21:30" ([AlertHoldRules]); null otherwise or while one is on. */
    val holdLabel: String? = null,
    /** Stretched, part 2: "I can hold mail and call alerts that can wait till tomorrow 21:30." */
    val holdLine: String? = null,
) {
    val lightenLabel: String? get() = if (moves.isEmpty()) null else FeelingRules.LIGHTEN
    val dropLabel: String? get() = drop?.let { "Someday for “${it.title}”" }
}

/**
 * "How are you feeling?" (Week board and roll-over, item (11); Meka 2026-10-20 19:56), non-AI and pure.
 *
 * - **Good · Tired · Stretched** on Shut down the day. Good changes nothing.
 * - **Tired** offers to lighten tomorrow: up to [MAX_MOVES] movable tasks from tomorrow's Board column (one-offs, not
 *   due tomorrow, not done) — those planned from [LATE_MIN] first, so nothing is left after 20:00, then carried ones,
 *   then the lowest priority, then the last in his order — each to the lightest of the [AHEAD_DAYS] days after it that
 *   isn't stacking up and isn't past its due day (fewest open cards, counting the ones already offered; then the
 *   earliest). A proposal: nothing moves until he taps, with Undo.
 * - **Stretched** offers the same, and also names one task that could wait on Someday (no due date, lowest priority).
 * - **Kept on this device only**, never synced or sent anywhere, and forgotten after [KEEP_DAYS] days ([encode]).
 */
object FeelingRules {
    const val QUESTION = "How are you feeling?"
    const val LIGHTEN = "Lighten tomorrow"
    const val LEAVE = "Leave it"
    const val MAX_MOVES = 2
    const val LATE_MIN = 20 * 60
    const val AHEAD_DAYS = 6
    const val KEEP_DAYS = 7
    /** The device's own store (never synced). */
    const val KEY = "feeling.log"

    val CHOICES: List<Feeling> = Feeling.entries

    /**
     * [nowMs] given (part 2), Stretched also offers to hold the alerts that can wait for a day ([AlertHoldRules]),
     * unless [holding] already.
     */
    fun offer(
        board: WeekBoard, feeling: Feeling, today: Long, calendar: LocalCalendar,
        nowMs: Long? = null, holding: Boolean = false,
    ): FeelingOffer {
        if (feeling == Feeling.GOOD) return FeelingOffer(feeling, "Good to hear. Tomorrow stays as it is.", emptyList())
        val tomorrow = today + 1
        val column = board.day(tomorrow)
        val candidates = column?.let { candidates(it, tomorrow, calendar) }.orEmpty()
        val moves = mutableListOf<FeelingMove>()
        val added = HashMap<Long, Int>()
        for (c in candidates) {
            if (moves.size == MAX_MOVES) break
            val due = c.task.dueAtMs?.let(calendar::epochDayOf)
            val target = (tomorrow + 1..tomorrow + AHEAD_DAYS)
                .filter { due == null || it <= due }
                .mapNotNull { board.day(it) }
                .filter { it.pressure == null && it.canDrop }
                .minWithOrNull(compareBy<BoardDay>({ it.openCards.size + (added[it.epochDay] ?: 0) }, { it.epochDay }))
                ?: continue
            added[target.epochDay] = (added[target.epochDay] ?: 0) + 1
            moves += FeelingMove(c.id, c.title, target.epochDay, PressureRules.shortDay(target.epochDay, today))
        }
        val drop = if (feeling == Feeling.STRETCHED) {
            val moved = moves.map { it.taskId }.toSet()
            candidates.asSequence()
                .filter { it.id !in moved && it.task.dueAtMs == null }
                .sortedWith(compareBy<BoardCard>({ it.task.priority }, { -(column?.openCards?.indexOf(it) ?: 0) }))
                .firstOrNull()
                ?.let { FeelingMove(it.id, it.title, null, "Someday") }
        } else null
        val hold = feeling == Feeling.STRETCHED && nowMs != null && !holding
        return FeelingOffer(
            feeling, line(feeling, moves, drop, column != null && column.openCards.isNotEmpty()), moves, drop,
            holdLabel = if (hold) AlertHoldRules.offerLabel(nowMs!!, calendar) else null,
            holdLine = if (hold) AlertHoldRules.offerLine(nowMs!!, calendar) else null,
        )
    }

    /** Tomorrow's movable cards, the first to go first. */
    fun candidates(day: BoardDay, tomorrow: Long, calendar: LocalCalendar): List<BoardCard> {
        val open = day.openCards
        return open.withIndex()
            .filter { (_, c) ->
                val due = c.task.dueAtMs?.let(calendar::epochDayOf)
                c.canMove && !c.task.isRepeating && (due == null || due > tomorrow)
            }
            .sortedWith(
                compareBy<IndexedValue<BoardCard>>(
                    { if (late(it.value, tomorrow, calendar)) 0 else 1 },
                    { if (it.value.carried) 0 else 1 },
                    { it.value.task.priority },
                    { -it.index },
                ),
            )
            .map { it.value }
    }

    private fun late(c: BoardCard, day: Long, calendar: LocalCalendar): Boolean {
        val at = c.task.scheduledAtMs?.takeIf { calendar.epochDayOf(it) == day } ?: return false
        return calendar.minuteOfDay(at) >= LATE_MIN
    }

    private fun line(feeling: Feeling, moves: List<FeelingMove>, drop: FeelingMove?, anything: Boolean): String {
        val waitLine = drop?.let { "“${it.title}” could wait on Someday." }
        if (moves.isEmpty()) {
            val base = if (anything) "Tomorrow has nothing that can move." else "Tomorrow is light already."
            return listOfNotNull(base, waitLine ?: "Rest well.").joinToString(" ")
        }
        val lead = if (feeling == Feeling.TIRED) "Let's go easy tomorrow" else "Let's take some off tomorrow"
        return listOfNotNull("$lead: move ${moveList(moves)}?", waitLine?.let { "And $it" }).joinToString(" ")
    }

    /** "“Gym” to Fri" · "“Gym” to Fri and “Trip form” to Sat". */
    fun moveList(moves: List<FeelingMove>): String = moves.joinToString(" and ") { "“${it.title}” to ${it.dayLabel}" }

    /** The undo bar after Lighten tomorrow: "Tomorrow's lighter · “Gym” to Fri and “Trip form” to Sat". */
    fun lightenedLine(moves: List<FeelingMove>): String = "Tomorrow's lighter · ${moveList(moves)}"

    /** What picking a feeling says when there's no offer to make: the pane's quiet line under the choices. */
    fun pickedLine(f: Feeling): String = "${f.label} · kept on this device for a week"

    /** The log on this device: "20381:TIRED;20382:GOOD". Unknown or broken parts are skipped. */
    fun decode(text: String?): Map<Long, Feeling> {
        if (text.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<Long, Feeling>()
        for (part in text.split(';')) {
            val day = part.substringBefore(':').toLongOrNull() ?: continue
            val f = Feeling.entries.firstOrNull { it.name == part.substringAfter(':', "") } ?: continue
            out[day] = f
        }
        return out
    }

    /** [log] with [feeling] for [today], days older than [KEEP_DAYS] forgotten. */
    fun record(log: Map<Long, Feeling>, today: Long, feeling: Feeling): Map<Long, Feeling> =
        prune(log + (today to feeling), today)

    fun prune(log: Map<Long, Feeling>, today: Long): Map<Long, Feeling> =
        log.filterKeys { it > today - KEEP_DAYS && it <= today }

    fun encode(log: Map<Long, Feeling>): String = log.entries.sortedBy { it.key }.joinToString(";") { "${it.key}:${it.value.name}" }
}
