package os.meka.core.domain

/**
 * Today beside its approved calmer mockup (docs/design/today/today-calmer.dc.html; quality gate (2), the design check,
 * 2026-10-29): what the side-by-side render showed, shared by the Fold and the Mac so the two can't drift.
 *
 * - The mockup counts the open tasks in the section label ("ANYTIME TODAY · 4").
 * - A task row is the 48 dp touch target and no taller (the Fold's were 60 dp: the 44 dp tick plus 8 dp above and
 *   below, then the list's 8 dp gap), with a hairline between rows.
 * - Up next's quiet pills (Break it down, Tomorrow, Open) sit a step lighter than the card, not darker.
 */
object TodayBesideRules {
    /** The label over Today's tasks with no time: "Anytime today · 4", or just "Anytime today" with none open. */
    fun anytimeLabel(openCount: Int): String = if (openCount > 0) "$ANYTIME · $openCount" else ANYTIME

    /** A hairline under every Anytime row but the last (the mockup's 1 px rule between rows). */
    fun dividerAfter(index: Int, count: Int): Boolean = index in 0 until count - 1

    /** A task row's height in dp with a one-line title: the touch target, nothing added. */
    const val ROW_DP = 48

    /** The quality gate's allowance over [ROW_DP] for a one-line title with its meta line under it. */
    const val ROW_WITH_LINE_MAX_DP = 52

    const val ANYTIME = "Anytime today"
}
