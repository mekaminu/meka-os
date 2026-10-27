package os.meka.core.domain

/**
 * Money review (Meka 2026-10-26 22:12: "Worth a look" was five tall cards each with only "That's fine"). Spending now
 * shows payments worth a look ([MoneyWatch]) as compact one-line rows: what it is, the amount and a chevron. A tap opens
 * the row in place (when and where, That's fine, and that Navilife never acts); a swipe either way on the Fold is That's
 * fine. At most [SHOWN] rows show, then "2 more ›". Both apps draw them from here.
 */
data class MoneyLookRow(
    val id: String,
    /** "Possibly charged twice · Amazon", "Bigger than usual · Currys", "Foreign transaction fees". */
    val name: String,
    /** "£23.40"; empty when the alert names no amount. */
    val amount: String,
    /** When and where ("£23.40 on Tue 13 Oct and again on Wed 14 Oct · Current account"). */
    val line: String,
    val lit: Boolean,
    val spoken: String,
)

object MoneyLook {
    const val SHOWN = 3
    const val FEWER = "Fewer"
    const val FINE_SPOKEN = "That's fine, put it away"

    private val AMOUNT = Regex("£[0-9][0-9,]*(\\.[0-9]{2})?")

    /**
     * One alert as a row: the amount comes off the end of the title ("Bigger than usual · Currys £480" → "Bigger than
     * usual · Currys", "£480"; "Foreign transaction fees · £4.12" → "Foreign transaction fees", "£4.12"), else it is the
     * first amount in the line (a double charge's "£23.40 twice on …").
     */
    fun row(a: MoneyAlert): MoneyLookRow {
        val inTitle = AMOUNT.findAll(a.title).lastOrNull()?.takeIf { m -> a.title.substring(m.range.last + 1).isBlank() }
        val amount: String
        val name: String
        if (inTitle != null) {
            amount = inTitle.value
            name = a.title.substring(0, inTitle.range.first).trimEnd().removeSuffix("·").trimEnd()
        } else {
            amount = AMOUNT.find(a.line)?.value.orEmpty()
            name = a.title
        }
        return MoneyLookRow(a.id, name.ifBlank { a.title }, amount, a.line, a.lit, a.spoken)
    }

    fun rows(alerts: List<MoneyAlert>): List<MoneyLookRow> = alerts.map { row(it) }

    /** The rows shown: the first [SHOWN] until "more" opens them. */
    fun shown(rows: List<MoneyLookRow>, open: Boolean): List<MoneyLookRow> = if (open) rows else rows.take(SHOWN)

    /** "2 more ›" folded, [FEWER] open; null when every row already shows. */
    fun moreLabel(rows: Int, open: Boolean): String? = when {
        rows <= SHOWN -> null
        open -> FEWER
        else -> "${rows - SHOWN} more ›"
    }
}
