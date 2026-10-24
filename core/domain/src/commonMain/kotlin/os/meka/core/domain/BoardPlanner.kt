package os.meka.core.domain

/**
 * The top line of a planner card: a time chip ("12:30", gold) when it is planned at a time, and its duration on the
 * right ("0:45") when it has an estimate. Either may be missing; a card with neither has no top line.
 */
data class PlannerCardTop(val time: String?, val duration: String?)

/**
 * A day's planned-against-budget pill under its header: "planned" · "4h 05 / 3h", amber when [over]. [value] is empty
 * when there is nothing to put on the right (no free time and nothing planned).
 */
data class PlannerBudget(val label: String, val value: String, val over: Boolean, val fill: Float, val spoken: String)

/**
 * The Week board redesign, Option B (Sunsama-style planner cards; Meka approved the mockup and its three refinements
 * 2026-10-24 08:30), slice 1: the cards and the day's budget. Non-AI and pure, the same on the Fold and the Mac.
 *
 * - A card is a borderless tile on a slightly lighter surface: a top line with the time chip and the duration
 *   ([top]), the title across the card's whole width (the tick no longer takes a column beside it), then a foot with
 *   the tick on the left and the card's small chips and where it came from on the right ([footChips]).
 * - A done card fades ([DONE_ALPHA]) and keeps its words plain: nothing is struck through (refinement 3).
 * - Each day from today shows "planned · 4h 05 / 3h" in a slim pill ([budget]), amber when more is planned than the
 *   day has free; never "0h" or "0m" (the quality gate's zero-length check), never a lone dash.
 */
object BoardPlannerRules {
    /** How much a done card fades (the mockup's 0.55); its title is never struck through. */
    const val DONE_ALPHA = 0.55f

    /** A done card's title is struck through: never on the planner cards (refinement 3, "fade instead of striking"). */
    const val STRIKE_DONE = false

    const val PLANNED = "planned"
    const val FREE = "free"
    const val NO_FREE_TIME = "no free time"

    /** A duration as the mockup draws it: 15 → "0:15", 60 → "1:00", 150 → "2:30". */
    fun duration(minutes: Int): String {
        val m = minutes.coerceAtLeast(0)
        return "${m / 60}:${(m % 60).toString().padStart(2, '0')}"
    }

    /** The budget's hours: 45 → "45m", 60 → "1h", 245 → "4h 05". Never "0h": under an hour is minutes. */
    fun hours(minutes: Int): String {
        val m = minutes.coerceAtLeast(0)
        val h = m / 60
        val r = m % 60
        return when {
            h == 0 -> "${r}m"
            r == 0 -> "${h}h"
            else -> "${h}h ${r.toString().padStart(2, '0')}"
        }
    }

    /** The card's top line, or null when it has neither a time nor an estimate. */
    fun top(card: BoardCard): PlannerCardTop? {
        val duration = card.task.estimateMinutes?.takeIf { it > 0 }?.let(::duration)
        if (card.time == null && duration == null) return null
        return PlannerCardTop(card.time, duration)
    }

    /** The time chip's words ("12:30"), or null (Swift reads this rather than the data class's nullable fields). */
    fun timeChip(card: BoardCard): String? = top(card)?.time

    /** The duration on the right ("0:45"), or null. */
    fun durationLabel(card: BoardCard): String? = top(card)?.duration

    /** The chips in the card's foot: carried, repeat, a note and the goal (time and estimate are on the top line). */
    fun footChips(card: BoardCard): List<CardChip> =
        BoardLookRules.chips(card).filter { it.kind != CardChipKind.TIME && it.kind != CardChipKind.ESTIMATE }

    /** Where it came from, short, beside its dot in the foot: "Mail", "Needs you", "Discord", "School". */
    fun sourceLabel(source: CardSource): String = when (source) {
        CardSource.MAIL -> "Mail"
        CardSource.NEEDS -> "Needs you"
        CardSource.DISCORD -> "Discord"
        CardSource.SCHOOL -> "School"
    }

    /** How opaque a card is drawn: faded once done. */
    fun alpha(card: BoardCard): Float = if (card.done) DONE_ALPHA else 1f

    /**
     * The pill as one line on a compact lane (under [BoardFitRules.COMPACT_LANE_DP], where "planned" and its value
     * don't fit side by side): "4h 05 / 3h", "nothing yet / 7h".
     */
    fun compactLine(b: PlannerBudget): String = b.value

    /** Nothing planned on the left of the pill's value ("nothing yet / 7h"): never "0h" or "0m". */
    const val NOTHING_YET = "nothing yet"

    /**
     * The day's budget pill from its capacity, always "planned" against what is free, as the mockup draws it (Meka
     * 2026-10-24 14:39: no "free 6h 15" pill): "4h 05 / 3h", "nothing yet / 7h", "1h / no free time".
     */
    fun budget(c: BoardCapacity): PlannerBudget {
        val planned = if (c.plannedMin <= 0) NOTHING_YET else hours(c.plannedMin)
        val free = if (c.freeMin <= 0) NO_FREE_TIME else hours(c.freeMin)
        val over = c.plannedMin > 0 && c.plannedMin > c.freeMin
        val fill = when {
            c.plannedMin <= 0 -> 0f
            c.freeMin <= 0 -> 1f
            else -> c.fill
        }
        return PlannerBudget(PLANNED, "$planned / $free", over, fill, c.spoken)
    }
}
