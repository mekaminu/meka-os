package os.meka.core.domain

/** What a small chip on a Board card says (Week board by hand, slice 2: the look). */
enum class CardChipKind {
    /** "14:30", a task planned at a time. */
    TIME,
    /** "30m", its estimate. */
    ESTIMATE,
    /** "Carried 2 days" · "From last week", in amber. */
    CARRIED,
    /** "↻ Every weekday". */
    REPEAT,
    /** "◎ Run 5k", in gold. */
    GOAL,
    /** Anything else on its line ("Idea" on a Someday card). */
    NOTE,
}

/** One chip under a card's title. */
data class CardChip(val kind: CardChipKind, val label: String)

/** "2 of 5 done" under a day's header with a thin bar that fills gold. */
data class DayProgress(val done: Int, val total: Int) {
    val line: String get() = "$done of $total done"
    val fill: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
    val complete: Boolean get() = total > 0 && done >= total
}

/** A day's free time as one quiet line ("19:00–21:00 · 2h free · +1 more"), never as boxes. */
data class FreeLine(val label: String, val spoken: String)

/**
 * The Week board's look (by hand, slice 2; Meka 2026-10-21 19:00, the approved design `docs/design/week-board`), non-AI
 * and pure, the same on the Fold and the Mac:
 * - each lane's header is a big day number with its weekday ("21" over "Wed"), a small tag ("Today · Office"), and
 *   "2 of 5 done" over a thin bar that fills gold ([progress]); a past day shows only what was done ([pastLine]);
 * - a card is a raised tile: the tick, the title (up to two lines), then small chips ([chips]: time, estimate, carried
 *   in amber, repeat, goal in gold); a done card has none;
 * - events are slim one-line chips ("09:30 · Dentist", [eventLine]);
 * - free time is one quiet line ([freeLine]): the longest stretch and how many more; the stretches only show as drop
 *   targets while a card is held.
 */
object BoardLookRules {
    const val NOTHING_DONE = "Nothing done"

    /** The chips under a card's title, in order: time, estimate, its line (carried, repeat or a note), goal. None once done. */
    fun chips(card: BoardCard): List<CardChip> {
        if (card.done) return emptyList()
        return buildList {
            card.time?.let { add(CardChip(CardChipKind.TIME, it)) }
            card.estimate?.let { add(CardChip(CardChipKind.ESTIMATE, it)) }
            card.line?.let { line ->
                val kind = when {
                    card.carried -> CardChipKind.CARRIED
                    line.startsWith("↻") -> CardChipKind.REPEAT
                    else -> CardChipKind.NOTE
                }
                add(CardChip(kind, line))
            }
            card.goalTag?.let { add(CardChip(CardChipKind.GOAL, it)) }
        }
    }

    /** "2 of 5 done" for a day with cards; null for an empty one. */
    fun progress(day: BoardDay): DayProgress? {
        if (day.cards.isEmpty()) return null
        return DayProgress(day.cards.count { it.done }, day.cards.size)
    }

    /** The small tag beside the day number: "Today · Office", "Home", "Today"; null on an ordinary day off. */
    fun tag(day: BoardDay): String? = listOfNotNull(if (day.isToday) "Today" else null, day.placeLabel)
        .joinToString(" · ").ifEmpty { null }

    /** A past day, collapsed: "3 done" or "Nothing done". */
    fun pastLine(day: BoardDay): String {
        val done = day.cards.count { it.done }
        return if (done == 0) NOTHING_DONE else "$done done"
    }

    /** An event as one slim line: "09:30 · Dentist", "All day · Half term". */
    fun eventLine(chip: BoardChip): String = "${chip.time} · ${chip.title}"

    /** The longest free stretch (the earliest of equals) and how many more; null when the day has none. */
    fun freeLine(free: List<BoardFreeTime>): FreeLine? {
        val longest = free.maxWithOrNull(compareBy<BoardFreeTime> { it.minutes }.thenByDescending { it.startMinute }) ?: return null
        val more = free.size - 1
        val label = "${longest.label} · ${longest.lengthLabel}" + if (more > 0) " · +$more more" else ""
        val spoken = "Free from ${LocalClock.formatMinute(longest.startMinute)} to ${LocalClock.formatMinute(longest.endMinute)}" +
            when (more) {
                0 -> ""
                1 -> ", and 1 more free time"
                else -> ", and $more more free times"
            }
        return FreeLine(label, spoken)
    }
}
