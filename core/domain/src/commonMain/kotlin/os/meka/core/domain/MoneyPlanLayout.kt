package os.meka.core.domain

/**
 * Money's layout rules on **Plan** (Money review, Meka 2026-10-26 22:18: "it's sentences and text, not segmented, boxed,
 * grouped, paginated… make it easy on the eye"), after Spending and Bills. Every Plan section is one card under its small
 * caps label:
 * - **Tips** are compact one-line rows (the fact · ›, a lit one in the accent), [TIPS_SHOWN] then "2 more ›"; a tap opens
 *   the tip's sheet with what it means and Hide tips like this. "Tips hidden" and Show them again sit in the same card.
 * - **The forecast** keeps its pace, what's left and the horizons in the card; the what-ifs (the cut chips, trading at its
 *   pace and the extra-a-month field) move behind one **What if… ›** row ([whatIfBrief] says which are on) opening a sheet,
 *   so no field sits open on the page (rule (e)).
 * - **Challenges** keep the no-spend switch, the days and each bar in the card; setting fun money or a cap is one
 *   **Set a challenge ›** row opening a sheet with the two fields.
 * - **Paying abroad** is one card.
 * - What's worked out how (What if I save's and Clear my cards' sums, the forecast's months, the challenges' and paying
 *   abroad's sources) is said once, behind the tab's foot ⓘ ([MoneyTabs.info]), with "read-only · not financial advice".
 */
object MoneyPlanLayout {
    const val TIPS_HEAD = "TIPS FROM YOUR NUMBERS"
    const val TIPS_SHOWN = 3
    const val TIP_SHEET = "Tip"

    /** The tips shown: the first [TIPS_SHOWN] until "more" opens them. */
    fun tipsShown(tips: List<MoneyTip>, open: Boolean): List<MoneyTip> = if (open) tips else tips.take(TIPS_SHOWN)

    /** "2 more ›" folded, Fewer open; null when every tip already shows. */
    fun tipsMore(tips: Int, open: Boolean): String? = when {
        tips <= TIPS_SHOWN -> null
        open -> MoneyRefunds.FEWER
        else -> "${tips - TIPS_SHOWN} more ›"
    }

    /** The tip whose sheet is open, found again in each view (Hide takes it away, which closes the sheet). */
    fun tipOf(tips: List<MoneyTip>, id: String?): MoneyTip? = id?.let { k -> tips.firstOrNull { it.id == k } }

    const val WHAT_IF_ROW = "What if…"
    const val WHAT_IF_TRY = "Try a cut or extra coming in"
    const val WHAT_IF_SHEET = "What if…"

    /** Whether the forecast has any what-if to offer (cuts, trading at its pace, or the extra field: always, once ready). */
    fun hasWhatIf(f: MoneyForecastView): Boolean = f.notReady == null

    /**
     * The What if… row's short line: the what-ifs that are on ("Halve Transport · +£200 a month"), else [WHAT_IF_TRY].
     * At most two named, then "+N more".
     */
    fun whatIfBrief(f: MoneyForecastView): String {
        val on = (f.cuts + listOfNotNull(f.trading)).filter { it.on }.map { it.label } + listOfNotNull(f.extraLine?.substringBefore(" · "))
        return when {
            on.isEmpty() -> WHAT_IF_TRY
            on.size <= 2 -> on.joinToString(" · ")
            else -> on.take(2).joinToString(" · ") + " · +${on.size - 2} more"
        }
    }

    /** Whether any what-if is on (the row's line then wears the accent). */
    fun whatIfOn(f: MoneyForecastView): Boolean = f.cuts.any { it.on } || f.trading?.on == true || f.extraLine != null

    const val SET_CHALLENGE = "Set a challenge"
    const val SET_CHALLENGE_LINE = "Fun money for the week, or a cap on a category"
    const val SET_CHALLENGE_SHEET = "Set a challenge"
}
