package os.meka.core.domain

/**
 * The Today rebuild, Meka's 08:09 point (i), and the audit's point 8: **gold means "act here"**. On Today's face the
 * accent colour is kept for things you tap (Done, Open list ›, a fix, a link) and for the one "now" mark the approved
 * mockup (`docs/design/day-list/TodayNew.dc.html`) draws in gold: the NOW label, the now line's time, dot and hairline,
 * the lit bar of a band you're in. Words that only tell you something are never gold:
 *
 * - [LIVE]: what's on right now ("Now · until 17:30", "Now · at work about 09:00", "1 h free until Work") in the
 *   secondary grey, a step brighter than other lines so it still stands out;
 * - [ATTENTION]: a line that wants a look but isn't a button ("Since 09:15 · 30 min" on a late task, "Carried 2 days",
 *   "Clashes with work · who's taking Sam?") in amber, the colour of the mockup's "waiting 17 days" badge;
 * - [QUIET]: everything else in the tertiary grey.
 *
 * Non-AI and pure, shared by the Fold and the Mac (the Mac's Today follows in the rebuild's slice 2d).
 */
enum class TodayInk { TAP, NOW_MARK, LIVE, ATTENTION, QUIET }

object TodayInkRules {
    /** An event's, session's, work band's or commute's line under its title: lit (not gold) while it's on. */
    fun detail(running: Boolean): TodayInk = if (running) TodayInk.LIVE else TodayInk.QUIET

    /** A clash with work under an event: amber until the event is over. */
    fun clash(past: Boolean): TodayInk = if (past) TodayInk.QUIET else TodayInk.ATTENTION

    /** The bar of a band (work, the commute): the now mark while you're in it. */
    fun bar(running: Boolean): TodayInk = if (running) TodayInk.NOW_MARK else TodayInk.QUIET

    /** The words on the now line ("1 h free until Work"); its time, dot and hairline are the now mark. */
    val nowWords: TodayInk = TodayInk.LIVE

    /**
     * A task row's line: a late planned task's or a carried one's in amber; a conflict or overdue stays critical red
     * (drawn by the row, null here); otherwise the row's usual grey.
     */
    fun taskLine(late: Boolean, carried: Boolean, flagged: Boolean): TodayInk? = when {
        flagged -> null
        carried || late -> TodayInk.ATTENTION
        else -> TodayInk.LIVE
    }

    /** True for the inks drawn in the accent: only taps and the now mark. */
    fun isGold(ink: TodayInk): Boolean = ink == TodayInk.TAP || ink == TodayInk.NOW_MARK
}
