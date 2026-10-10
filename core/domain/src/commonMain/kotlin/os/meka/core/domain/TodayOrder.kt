package os.meka.core.domain

/** The parts of Today under its header, in the order [TodayOrderRules.ORDER] puts them. */
enum class TodaySlot {
    /** "You're clear." / "Nothing else timed today". */
    CLEAR,
    /** Up next's card (or the cover screen's "now" card), the next event within the hour, today's gym session and quick alarms. */
    UP_NEXT,
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
 * - [ORDER]: clear line · Up next · the timeline · the cards · Needs you · Anytime today · done today.
 * - [stagger]: each part's place in the opening stagger (the header is 0), so the sections arrive in the order they sit.
 * - [leads]: the parts that come before any card.
 */
object TodayOrderRules {
    val ORDER: List<TodaySlot> = listOf(
        TodaySlot.CLEAR, TodaySlot.UP_NEXT, TodaySlot.TIMELINE, TodaySlot.CARDS,
        TodaySlot.NEEDS_YOU, TodaySlot.ANYTIME, TodaySlot.DONE,
    )

    /** How many steps the opening stagger runs under the header (the Mac waits this long before settling). */
    const val SECTIONS = 7

    /** The timeline's rows arrive one step after its label and All day group. */
    const val TIMELINE_ROWS_STEP = 3

    /** The step of the opening stagger [slot] arrives on (header 0; the timeline's rows on [TIMELINE_ROWS_STEP]). */
    fun stagger(slot: TodaySlot): Int = when (slot) {
        TodaySlot.CLEAR, TodaySlot.UP_NEXT -> 1
        TodaySlot.TIMELINE -> 2
        TodaySlot.CARDS -> 4
        TodaySlot.NEEDS_YOU -> 4
        TodaySlot.ANYTIME -> 5
        TodaySlot.DONE -> 6
    }

    /** True for the parts that sit above every card: the clear line, Up next and the timeline. */
    fun leads(slot: TodaySlot): Boolean = ORDER.indexOf(slot) < ORDER.indexOf(TodaySlot.CARDS)
}
