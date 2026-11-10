package os.meka.core.domain

/**
 * The approved Needs you look, slice 2 (`docs/design/needs-you/needs-you.dc.html`, MAIL NAVILIFE TIDIED TODAY): the mail
 * as four number tiles — tidied · newsletters · promos · junk & scams — over today's tidies that still stand, then the
 * one unsubscribe question (the busiest sender on the weekly "Not opened in 30 days" offer) and a quiet foot
 * "Still in Gmail and Outlook · Put back from the list" with See all ›, which opens All tidied mail (where every tidy
 * keeps its Put back). It replaces the long log of every tidy Meka found confusing (2026-11-09 22:27).
 * Non-AI and pure; nothing here moves or sends anything.
 */
data class NeedsYouMailTile(
    /** "138". */
    val number: String,
    /** "tidied", "newsletters", "promos", "junk & scams". */
    val label: String,
    /** True on junk & scams: its number wears the soft warning colour. */
    val warn: Boolean,
)

/** The one question: "Unsubscribe from The Weekly Digest?" over "Personal · 6 emails, none opened · last Mon 12 Oct". */
data class NeedsYouUnsubQuestion(
    val senderId: String,
    val question: String,
    val line: String,
    /** "1 of 11" when more senders wait behind this one; else null. */
    val more: String?,
    val spoken: String,
)

data class NeedsYouMailSummary(
    /** Always four, in the mockup's order; empty while nothing was tidied today. */
    val tiles: List<NeedsYouMailTile>,
    val question: NeedsYouUnsubQuestion?,
    val foot: String,
    /** What a screen reader says for the tiles: "Tidied today: 138. 33 newsletters, 71 promos, 33 junk and scams." */
    val spoken: String,
)

object NeedsYouMailRules {
    const val FOOT = "Still in Gmail and Outlook · Put back from the list"
    const val SEE_ALL = "See all ›"
    const val UNSUBSCRIBE = "Unsubscribe"
    const val KEEP = "Keep"
    const val TIDIED = "tidied"
    const val NEWSLETTERS = "newsletters"
    const val PROMOS = "promos"
    const val JUNK = "junk & scams"

    private val JUNK_KINDS = setOf(MailTidy.JUNK_TRASH, MailTidy.SCAM_JUNK, MailTidy.TRASH)

    /**
     * Today's tidies that still stand (not put back or being put back, not the backlog clean-up he approved, which Mail
     * shows), in the phone's own day.
     */
    fun today(batches: List<MailTidyBatch>, nowMs: Long, cal: LocalCalendar): List<MailTidyBatch> {
        val day = cal.epochDayOf(nowMs)
        return batches.filter { it.canUndo && !it.backlog && it.count > 0 && it.atMs <= nowMs && cal.epochDayOf(it.atMs) == day }
    }

    /** The four tiles over [today]'s tidies, or empty when there were none. A tidy from before kinds were kept adds to tidied only. */
    fun tiles(today: List<MailTidyBatch>): List<NeedsYouMailTile> {
        val total = today.sumOf { it.count }
        if (total == 0) return emptyList()
        fun kind(vararg k: MailTidy) = today.sumOf { b -> k.sumOf { b.kinds[it] ?: 0 } }
        return listOf(
            NeedsYouMailTile(total.toString(), TIDIED, false),
            NeedsYouMailTile(kind(MailTidy.NEWSLETTER).toString(), NEWSLETTERS, false),
            NeedsYouMailTile(kind(MailTidy.ARCHIVE).toString(), PROMOS, false),
            NeedsYouMailTile(kind(*JUNK_KINDS.toTypedArray()).toString(), JUNK, true),
        )
    }

    /** The busiest sender on the weekly offer as one question, or null when there's no offer. */
    fun question(offer: MailUnopenedCard?): NeedsYouUnsubQuestion? {
        val row = offer?.rows?.firstOrNull() ?: return null
        val total = offer.ids.size
        val more = if (total > 1) "1 of $total" else null
        val question = "Unsubscribe from ${row.name}?"
        return NeedsYouUnsubQuestion(row.id, question, row.line, more, listOfNotNull(question, row.line, more).joinToString(". ") + ".")
    }

    /**
     * The section, or null when there's nothing to show (no tidy today and no question). The question alone shows when
     * nothing was tidied today but the weekly offer stands.
     */
    fun summary(batches: List<MailTidyBatch>, offer: MailUnopenedCard?, nowMs: Long, cal: LocalCalendar): NeedsYouMailSummary? {
        val tiles = tiles(today(batches, nowMs, cal))
        val q = question(offer)
        if (tiles.isEmpty() && q == null) return null
        val spoken = if (tiles.isEmpty()) "Nothing tidied yet today." else {
            "Tidied today: ${tiles[0].number}. " + tiles.drop(1).joinToString(", ") { "${it.number} ${it.label.replace("&", "and")}" } + "."
        }
        return NeedsYouMailSummary(tiles, q, FOOT, spoken)
    }
}
