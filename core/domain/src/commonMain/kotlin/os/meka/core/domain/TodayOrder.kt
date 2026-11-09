package os.meka.core.domain

/** The parts of Today under its header, in the order [TodayOrderRules.ORDER] puts them. */
enum class TodaySlot {
    /** "You're clear." / "Nothing else timed today". */
    CLEAR,
    /** "From yesterday": open tasks planned for an earlier day (roll-over, Week board slice 1; [RollOverRules]). */
    FROM_YESTERDAY,
    /** Up next's card (or the cover screen's "now" card), the next event within the hour, today's gym session and quick alarms. */
    UP_NEXT,
    /** "This evening · 1 of 3": the evening's one card, right under Now from 17:00 ([EveningCardRules]). */
    EVENING,
    /** The day: the All day group, "3 earlier", events and planned tasks with the now line and free gaps. */
    TIMELINE,
    /** The moment cards: the motion check, an app update, the morning brief, the weekly review, the evening shutdown. */
    CARDS,
    /** Tasks that need a decision, when Today lists them (the closed Fold; the command centre has its own column). */
    NEEDS_YOU,
    /** "Anytime today": tasks with no time. */
    ANYTIME,
    /** "3 done today". */
    DONE,
}

/**
 * Calm Today, slice 3 (Meka approved 2026-10-09: "Up next and the timeline lead"): under the header, what to do now and
 * the shape of the day come first; the moment cards (brief, review, shutdown, update) follow them instead of pushing
 * the day down the screen. Non-AI, pure; the Fold and the Mac lay Today out in this order.
 *
 * - [ORDER]: clear line · From yesterday (roll-over) · Up next · This evening · Needs you · Today's tasks · done today ·
 *   the schedule (timeline) · the cards. The Today rebuild (Meka approved 2026-11-08 22:58, slice 2a) moved the day's
 *   tasks up under Now and the evening card, and the schedule under them (it was above, and the tasks at the very bottom).
 * - [stagger]: each part's place in the opening stagger (the header is 0), so the sections arrive in the order they sit.
 * - [leads]: the parts that come before any card.
 */
object TodayOrderRules {
    val ORDER: List<TodaySlot> = listOf(
        TodaySlot.CLEAR, TodaySlot.FROM_YESTERDAY, TodaySlot.UP_NEXT, TodaySlot.EVENING, TodaySlot.NEEDS_YOU,
        TodaySlot.ANYTIME, TodaySlot.DONE, TodaySlot.TIMELINE, TodaySlot.CARDS,
    )

    /** How many steps the opening stagger runs under the header (the Mac waits this long before settling). */
    const val SECTIONS = 7

    /** The timeline's rows arrive one step after its label and All day group. */
    const val TIMELINE_ROWS_STEP = 5

    /** The step of the opening stagger [slot] arrives on (header 0; the timeline's rows on [TIMELINE_ROWS_STEP]). */
    fun stagger(slot: TodaySlot): Int = when (slot) {
        TodaySlot.CLEAR, TodaySlot.FROM_YESTERDAY, TodaySlot.UP_NEXT -> 1
        TodaySlot.EVENING, TodaySlot.NEEDS_YOU -> 2
        TodaySlot.ANYTIME, TodaySlot.DONE -> 3
        TodaySlot.TIMELINE -> 4
        TodaySlot.CARDS -> 6
    }

    /** True for the parts that sit above every card: everything but the cards, which come last. */
    fun leads(slot: TodaySlot): Boolean = ORDER.indexOf(slot) < ORDER.indexOf(TodaySlot.CARDS)
}
