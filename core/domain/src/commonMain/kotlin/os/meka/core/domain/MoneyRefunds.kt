package os.meka.core.domain

/**
 * Money manager, slice 5 (2026-10-16): **refunds on the way** (board item 11). Every refund on Waiting for ("Sent it
 * back"'s "Refund · Amazon order · £40", a promise's "Refund", or one he typed) is checked against the statements he
 * imported, on the Money screen's current month, behind his fingerprint:
 *
 * - **back**: money in from the same shop on or after the row was made (a week's grace for a late tap), the same amount
 *   when the row names one — "Back in your statement · Refund · Amazon order · £40" · "+£40 from Amazon on Thu 15 Oct ·
 *   Current account" with **Close it** (the row leaves Waiting for as received; Undo puts it back). Nothing closes by
 *   itself: a credit from the shop might be something else.
 * - **late**: not in statements that run [LATE_DAYS] days past the row — lit, "Not in your statements after 16 days ·
 *   since Mon 28 Sep", with a chase drafted for him to copy (he sends it himself; Navilife sends nothing) and **Copy the
 *   chase**, which also marks it chased (next look in [AGAIN_DAYS] days). Once chased it's quiet until then.
 * - **waiting**: "Not in your statements yet · 5 days since Sun 11 Oct".
 *
 * When every account's statements stop before the last few days, the foot says so ("Your statements end Tue 6 Oct ·
 * import a newer one to see if it's come") and nothing is called late. Rules only, no AI; nothing new is stored.
 */
data class MoneyRefundRow(
    /** The Waiting for row's id. */
    val id: String,
    /** "back" · "late" · "waiting". */
    val state: String,
    val title: String,
    val line: String,
    val lit: Boolean,
    /** The chase he can copy and send himself; only when late and not chased lately. */
    val chase: String?,
    val spoken: String,
    /** Layout rules (2026-10-27): the compact row's name ("Refund · Amazon order · £40") and short state ("Back · +£40"). */
    val name: String = title,
    val brief: String = line,
)

data class MoneyRefundsView(val head: String, val rows: List<MoneyRefundRow>, val foot: String?)

object MoneyRefunds {
    const val HEAD = "REFUNDS ON THE WAY"
    const val LATE_DAYS = 14
    const val AGAIN_DAYS = 7
    /** A credit this many days before the row was made can still be its refund (he tapped late). */
    const val EARLY_DAYS = MailRefundRules.EARLY_DAYS
    /** Statements ending more than this many days before today are too old to say a refund hasn't come. */
    const val STALE_DAYS = 3
    const val MAX_ROWS = 6
    const val CLOSE = "Close it"
    const val COPY = "Copy the chase"
    const val BACK = "back"
    const val LATE = "late"
    const val WAITING = "waiting"
    const val NOTE = "Navilife never sends it · paste it into the shop's chat or email"
    /** Layout rules (Money review, Meka 2026-10-26 22:18): Bills shows this many compact rows, then "2 more ›". */
    const val SHOWN = 3
    const val FEWER = "Fewer"

    /** The compact rows shown: the first [SHOWN] until "more" opens them. */
    fun shown(rows: List<MoneyRefundRow>, open: Boolean): List<MoneyRefundRow> = if (open) rows else rows.take(SHOWN)

    /** "2 more ›" folded, [FEWER] open; null when every row already shows. */
    fun moreLabel(rows: Int, open: Boolean): String? = when {
        rows <= SHOWN -> null
        open -> FEWER
        else -> "${rows - SHOWN} more ›"
    }

    private fun fmt(p: Long) = MoneyRules.fmt(p)

    private fun key(s: String?): String = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }

    /** Words that name no shop, so a statement line's first word alone never matches on them. */
    private val GENERIC = setOf("the", "www", "card", "payment", "refund", "paypal", "sumup", "zettle", "visa", "debit", "credit", "direct", "bank", "faster", "transfer", "online")

    /**
     * Whether the money-in line [merchantKey] (a statement's merchant key, "amazon marketplace") is from the shop the
     * Waiting for row [w] waits on: its "who" is the same shop, or with no "who" its title names the shop.
     */
    fun sameShop(w: WaitingItem, merchantKey: String): Boolean {
        val label = MoneyCategoryRules.merchantLabel(merchantKey)
        val first = merchantKey.split(' ').firstOrNull { it.isNotBlank() }.orEmpty()
        val firstOk = key(first).length >= 3 && first.lowercase() !in GENERIC
        val who = w.who?.takeIf { it.isNotBlank() }
        if (who != null) return MailRefundRules.sameShop(who, label) || (firstOk && MailRefundRules.sameShop(who, first))
        if (!firstOk) return false
        // "Refund · Amazon order · £40": a word of the title is the shop's first word.
        return Regex("[A-Za-z0-9&']+").findAll(w.title).any { m -> key(m.value).length >= 3 && key(m.value) !in GENERIC && MailRefundRules.sameShop(m.value, first) }
    }

    /** The chase he can send himself. */
    fun chase(w: WaitingItem, pence: Long?, sinceDay: Long): String {
        val what = pence?.let { "the refund of ${fmt(it)}" } ?: "my refund"
        return "Hello, I'm still waiting for $what, which I've been expecting since ${CivilDate.shortLabel(sinceDay)}, and it hasn't reached my account. " +
            "Could you check where it is and let me know when it will arrive? Thank you."
    }

    /** "Chased · next look Fri 23 Oct" after Copy the chase. */
    fun chasedSaid(today: Long): String = "Chase copied · paste it to the shop · next look ${CivilDate.shortLabel(today + AGAIN_DAYS)}"

    /** "Closed Refund · Amazon order · £40 · it's back in your statement". */
    fun closedSaid(title: String): String = "Closed $title · it's back in your statement"

    /**
     * The refunds section for [today], or null with no refund on Waiting for. [lines] are every account's statement
     * lines (merchant keys already worked out); [names] the accounts' names.
     */
    fun view(waits: List<WaitingItem>, lines: List<MoneyWatchLine>, today: Long, cal: LocalCalendar, names: Map<String, String>): MoneyRefundsView? {
        val refunds = waits.filter { MailRefundRules.isRefundWait(it) }.sortedWith(compareBy<WaitingItem> { it.sinceMs }.thenBy { it.id })
        if (refunds.isEmpty()) return null
        val credits = lines.filter { it.pence > 0 }.sortedWith(compareBy<MoneyWatchLine> { it.day }.thenBy { it.id })
        val lastDay = lines.maxOfOrNull { it.day }
        val stale = lastDay == null || lastDay < today - STALE_DAYS
        val used = HashSet<String>()
        val rows = refunds.map { w ->
            val since = cal.epochDayOf(w.sinceMs)
            val want = MailRefundRules.penceOf(w.title)
            val hit = credits.firstOrNull { c ->
                c.id !in used && c.day >= since - EARLY_DAYS && (want == null || c.pence == want) && sameShop(w, c.merchant)
            }
            if (hit != null) {
                used += hit.id
                val title = "Back in your statement · ${w.title}"
                val line = listOf(
                    "+${fmt(hit.pence)} from ${MoneyCategoryRules.merchantLabel(hit.merchant)} on ${CivilDate.shortLabel(hit.day)}",
                    names[hit.account].orEmpty(),
                ).filter { it.isNotEmpty() }.joinToString(" · ")
                MoneyRefundRow(w.id, BACK, title, line, lit = false, chase = null, spoken = "$title. $line",
                    name = w.title, brief = "Back · +${fmt(hit.pence)}")
            } else {
                val days = (today - since).coerceAtLeast(0)
                val dayWord = "$days day${if (days == 1L) "" else "s"}"
                val chasedDay = w.lastChasedMs?.let { cal.epochDayOf(it) }
                val chasedLately = chasedDay != null && chasedDay > today - AGAIN_DAYS
                // Not stale: the statements run to within a few days of today, so past the row's first two weeks.
                val late = !stale && days >= LATE_DAYS
                val state = if (late) LATE else WAITING
                val line = (if (late) "Not in your statements after $dayWord · since ${CivilDate.shortLabel(since)}"
                    else "Not in your statements yet · $dayWord since ${CivilDate.shortLabel(since)}") +
                    (chasedDay?.let { " · chased ${CivilDate.shortLabel(it)}" } ?: "")
                val lit = late && !chasedLately
                val ago = if (days == 0L) "since today" else dayWord
                val brief = when {
                    lit -> "Late · $dayWord · chase it"
                    chasedDay != null -> "Chased ${CivilDate.shortLabel(chasedDay)} · $ago"
                    late -> "Late · $dayWord"
                    else -> "Waiting · $ago"
                }
                MoneyRefundRow(w.id, state, w.title, line, lit, chase = if (lit) chase(w, want, since) else null, spoken = "${w.title}. $line",
                    name = w.title, brief = brief)
            }
        }.sortedBy { when (it.state) { BACK -> 0; LATE -> 1; else -> 2 } }.take(MAX_ROWS)
        val foot = if (stale && rows.any { it.state != BACK }) (lastDay?.let { "Your statements end ${CivilDate.shortLabel(it)} · import a newer one to see if it's come" }) else null
        return MoneyRefundsView("$HEAD · ${rows.size}", rows, foot)
    }
}
