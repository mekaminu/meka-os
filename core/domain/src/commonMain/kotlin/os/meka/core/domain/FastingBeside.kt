package os.meka.core.domain

/**
 * Fasting beside its approved mockup (docs/design/fasting/fasting.dc.html; quality gate (2), the design check,
 * 2026-10-30): what the side-by-side render of CI's cover shots showed, shared by the Fold and the Mac so the two
 * can't drift. The Today card (today-card.dc.html) already matched and is unchanged.
 *
 * - The title "Fasting" was the 34 sp greeting; the mockup's is 28 ([TITLE_SP]). The time in the ring was the same
 *   34 sp; the mockup's is 46 bold ([TIME_SP]), the screen's one big number.
 * - Each stage and History row was 64 dp (the 48 dp touch target plus 8 dp above and below) with the list's 16 dp
 *   between rows, so the seven stages ran past two screens; in the mockup a row is about 44 dp with 8 dp between.
 *   A row is now the touch target and no taller ([ROW_DP]), [ROW_GAP_DP] apart, its label 8 dp above it.
 * - A History row stacked the length over the goal word; the mockup keeps one line: span · length · goal, with the
 *   goal as whole hours short ("6h short"; [goalOnRow]) so the line fits the closed Fold. The full words are spoken.
 */
object FastingBesideRules {
    /** The title's size in sp (the mockup's 28 px). */
    const val TITLE_SP = 28

    /** The time in the ring, in sp, bold (the mockup's 46 px, weight 700). */
    const val TIME_SP = 46

    /** A stage or History row's height in dp with one line: the touch target, nothing added. */
    const val ROW_DP = 48

    /** The quality gate's allowance over [ROW_DP] for a closed row. */
    const val ROW_MAX_DP = 52

    /** Between stage rows, between History rows, and from a section's label to its first row, in dp. */
    const val ROW_GAP_DP = 8

    private val SHORT = Regex("""^(\d+)h \d+m short$""")

    /**
     * The goal word on a History row's one line: "on goal", "40m short", and "6h 30m short" as "6h short" (whole hours,
     * as the mockup); anything else unchanged.
     */
    fun goalOnRow(goal: String): String = SHORT.matchEntire(goal)?.let { "${it.groupValues[1]}h short" } ?: goal

    /** What a History row says to TalkBack / VoiceOver: "Tue 20 → Wed 21, 16h 40m, on goal" with the goal in full. */
    fun spoken(row: FastingHistoryRow): String = "${row.span.replace(" → ", " to ")}, ${row.length}, ${row.goal}"
}
