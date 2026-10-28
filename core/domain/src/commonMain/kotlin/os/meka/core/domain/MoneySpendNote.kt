package os.meka.core.domain

/**
 * Money's mockup check, item 8 (the approved Spending mockup): each category row's note says how the category compares
 * with his usual month ("£20 under usual", "as usual", "£110 over usual") wherever the months allow it, rather than
 * "3 payments". Both apps draw [MoneySpendRow.note] from here.
 *
 * - No month before the one shown: the payment count, as before (nothing to compare with).
 * - Other: "14 without a category" (what the row is, and the Sort them line under the rows sorts them).
 * - A finished month compares with the usual month whole.
 * - This month compares with the usual month's pace by today (as the ring's middle does), from day [EARLIEST_DAY] on,
 *   before which it's too early to say and the count shows. Bills, subscriptions and fees go out in lumps, so they
 *   compare with the whole usual month: over only once past it, else "as usual".
 * - Within [MoneyRules.OVER_MIN_PENCE] (and, for over, within [MoneyRules.OVER_SHARE] of usual) is "as usual".
 * [usual] is "usual", or the month's name when only one month came before ("£20 under September").
 */
object MoneySpendNote {
    /** This month's notes compare from this day of the month on. */
    const val EARLIEST_DAY = 7

    /** Categories that go out in lumps on a day, so this month's pace says nothing about them. */
    private val LUMPY = setOf(MoneyCategory.BILLS, MoneyCategory.SUBSCRIPTIONS, MoneyCategory.FEES)

    data class Note(val text: String, val tone: MoneyTone)

    fun of(
        category: MoneyCategory, own: Boolean, pence: Long, payments: Int, avg: Long?, usual: String,
        partial: Boolean, dayOfMonth: Int, daysInMonth: Int,
    ): Note {
        val count = Note("$payments payment${if (payments == 1) "" else "s"}", MoneyTone.QUIET)
        if (!own && category == MoneyCategory.OTHER) return Note("$payments without a category", MoneyTone.QUIET)
        if (avg == null) return count
        val one = usual != "usual"
        val same = if (one) "about the same as $usual" else "as usual"
        if (avg <= 0L) return Note(if (one) "none in $usual" else "new this month", MoneyTone.QUIET)
        if (partial && dayOfMonth < EARLIEST_DAY) return count
        val lumpy = !own && category in LUMPY
        val against = if (partial && !lumpy) avg * dayOfMonth.coerceIn(1, daysInMonth) / daysInMonth.coerceAtLeast(1) else avg
        val diff = pence - against
        return when {
            diff >= MoneyRules.OVER_MIN_PENCE && pence > against * (1 + MoneyRules.OVER_SHARE) ->
                Note("${MoneySpending.whole(diff)} over $usual", MoneyTone.OVER)
            // A lump not yet out this month isn't "under".
            partial && lumpy -> Note(same, MoneyTone.QUIET)
            -diff >= MoneyRules.OVER_MIN_PENCE -> Note("${MoneySpending.whole(-diff)} under $usual", MoneyTone.GOOD)
            else -> Note(same, MoneyTone.QUIET)
        }
    }
}
