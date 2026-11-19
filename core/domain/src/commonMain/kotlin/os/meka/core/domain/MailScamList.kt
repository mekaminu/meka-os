package os.meka.core.domain

/**
 * A possible scam isn't mail to answer (Meka 2026-11-18 23:34, Needs you screenshot: "Possible scam · CMC Invest" sat
 * under Mail to answer; and 2026-11-19 00:52, the same email had become a task). An email Navilife flags as a possible
 * scam ([MailScamRules.scamLine] on its warning) leaves Mail to answer, the ten-minute inbox and every "n emails need
 * you" count, and waits instead in the Mail tidied today box as **Possible scams to check**, counted with junk & scams.
 * Tapped, its row opens on **Junk it & block** (Junk, and the sender's later emails to Trash) · **Not a scam** · Done.
 * Not a scam puts it back with the mail to answer. Non-AI and pure; nothing here moves or sends anything.
 */
object MailScamListRules {
    const val TITLE = "Possible scams to check"

    /** The box's lead line over the rows: why they're here and not under Mail to answer. */
    const val LEAD = "Not answered here · Junk it & block, or Not a scam if it's real"

    /** True when [i] is a possible scam he hasn't said is real: out of Mail to answer, into the tidied box. */
    fun possibleScam(i: MailItem): Boolean = MailScamRules.scamLine(i.warning) && !MailNotScamRules.notScam(i)

    /** [items] less the possible scams: what Mail to answer, the ten-minute inbox and its counts go through. */
    fun toAnswer(items: List<MailItem>): List<MailItem> =
        if (items.none(::possibleScam)) items else items.filterNot(::possibleScam)

    /** "1 possible scam to check" / "3 possible scams to check". */
    fun headline(n: Int): String = if (n == 1) "1 possible scam to check" else "$n possible scams to check"

    /** "Possible scam · CMC Invest" / "2 possible scams · CMC Invest, PayPal" for the closed box's peek. */
    fun peek(card: MailNeedsCard?): String? {
        val rows = card?.every.orEmpty()
        if (rows.isEmpty()) return null
        val names = rows.map { it.from }.distinct()
        val shown = names.take(2).joinToString(", ") + if (names.size > 2) " +${names.size - 2}" else ""
        return if (rows.size == 1) "${NeedsYouGroupRules.SCAM_FLAG} · $shown" else "${rows.size} possible scams · $shown"
    }

    /**
     * The possible scams waiting, as rows the Mail to answer box already knows how to draw (each with "Possible scam"
     * and its scam answers), newest first; null when there are none.
     */
    fun card(items: List<MailItem>, nowMs: Long, cal: LocalCalendar): MailNeedsCard? {
        val scams = items.filter(::possibleScam)
        if (scams.isEmpty()) return null
        val card = MailNeedsRules.card(scams, nowMs, cal) ?: return null
        return card.copy(title = TITLE, headline = headline(card.count), replyLine = null, moreLine = null)
    }

    /** The tidied box's count with the possible scams in it: today's tidies plus the scams to check. */
    fun tidiedCount(tidiedToday: Int, scams: MailNeedsCard?): Int = tidiedToday + (scams?.count ?: 0)

    /** Junk & scams' tile with the possible scams waiting added to its number (and said aloud). */
    fun withScams(tiles: List<NeedsYouMailTile>, scams: MailNeedsCard?): List<NeedsYouMailTile> {
        val n = scams?.count ?: 0
        if (n == 0) return tiles
        return tiles.map { t ->
            if (t.kind != TidiedKind.JUNK.id) t else {
                val total = (t.number.toIntOrNull() ?: 0) + n
                t.copy(number = total.toString(), spoken = "${t.spoken}. ${headline(n)}, shown below")
            }
        }
    }
}
