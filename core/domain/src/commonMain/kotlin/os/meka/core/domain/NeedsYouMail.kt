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
    /**
     * Meka 2026-11-18 23:34 ("Can't do anything with tidied emails"): which of today's tidied emails a tap on the tile
     * opens in All tidied mail ([TidiedKind.id]).
     */
    val kind: String = TidiedKind.ALL.id,
    /** What a screen reader says for the tile as a button: "38 promos tidied today. Opens them". */
    val spoken: String = "$number $label",
)

/**
 * The four kinds the tiles count, as All tidied mail filters by them (Meka 2026-11-18 23:34): a tile opens today's
 * tidied emails of its kind; [ALL] is every one of them.
 */
enum class TidiedKind(val id: String, val chip: String, val one: String, val many: String) {
    ALL("all", "Today", "email", "emails"),
    NEWSLETTERS("newsletters", "Newsletters", "newsletter", "newsletters"),
    PROMOS("promos", "Promos", "promo", "promos"),
    JUNK("junk", "Junk & scams", "junk or scam email", "junk and scam emails");

    /** The tidies a tile of this kind counts. */
    fun matches(t: MailTidy): Boolean = when (this) {
        ALL -> t != MailTidy.KEEP
        NEWSLETTERS -> t == MailTidy.NEWSLETTER
        PROMOS -> t == MailTidy.ARCHIVE
        JUNK -> t in NeedsYouMailRules.JUNK_KINDS
    }

    companion object {
        fun of(id: String?): TidiedKind? = entries.firstOrNull { it.id == id }
    }
}

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
    /**
     * Found and fixing week 4, A7 ("138 tidied" over 33 + 71 + 33): what the three part tiles leave out, so the tiles add
     * up on screen: "Also 1 receipt or order", "Also 4 receipts and orders · 2 emails rescued from Junk"; null when the
     * three parts make the whole.
     */
    val rest: String?,
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

    internal val JUNK_KINDS = setOf(MailTidy.JUNK_TRASH, MailTidy.SCAM_JUNK, MailTidy.TRASH)

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
        fun tile(n: Int, label: String, warn: Boolean, k: TidiedKind) =
            NeedsYouMailTile(n.toString(), label, warn, k.id, tileSpoken(n, label, k))
        return listOf(
            tile(total, TIDIED, false, TidiedKind.ALL),
            tile(kind(MailTidy.NEWSLETTER), NEWSLETTERS, false, TidiedKind.NEWSLETTERS),
            tile(kind(MailTidy.ARCHIVE), PROMOS, false, TidiedKind.PROMOS),
            tile(kind(*JUNK_KINDS.toTypedArray()), JUNK, true, TidiedKind.JUNK),
        )
    }

    /** "84 emails tidied today. Opens them" / "38 promos tidied today. Opens them" / "None of today's are newsletters". */
    fun tileSpoken(n: Int, label: String, k: TidiedKind): String = when {
        n == 0 && k != TidiedKind.ALL -> "No ${k.many} tidied today. Opens the list"
        k == TidiedKind.ALL -> "$n ${if (n == 1) "email" else "emails"} tidied today. Opens them"
        else -> "$n ${if (n == 1) k.one else k.many} tidied today. Opens ${if (n == 1) "it" else "them"}"
    }

    /** Kinds the three part tiles count; everything else tidied today goes on [rest]'s line. */
    private val PART_KINDS = setOf(MailTidy.NEWSLETTER, MailTidy.ARCHIVE) + JUNK_KINDS

    /**
     * The line under the tiles naming what the three parts leave out (receipts, rescues, a tidy from before kinds were
     * kept as "3 more"), so tidied = newsletters + promos + junk & scams + this; null when nothing is left out.
     */
    fun rest(today: List<MailTidyBatch>): String? {
        val byKind = MailTidy.entries.filter { it !in PART_KINDS && it != MailTidy.KEEP }
            .map { k -> k to today.sumOf { it.kinds[k] ?: 0 } }.filter { it.second > 0 }
        val unknown = today.sumOf { b -> (b.count - b.kinds.values.sum()).coerceAtLeast(0) }
        val parts = byKind.map { (k, n) -> "$n ${if (n == 1) k.one else k.many}" } + listOfNotNull(unknown.takeIf { it > 0 }?.let { "$it more" })
        return if (parts.isEmpty()) null else "Also " + parts.joinToString(" · ")
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
        val today = today(batches, nowMs, cal)
        val tiles = tiles(today)
        val q = question(offer)
        if (tiles.isEmpty() && q == null) return null
        val rest = if (tiles.isEmpty()) null else rest(today)
        val spoken = if (tiles.isEmpty()) "Nothing tidied yet today." else {
            "Tidied today: ${tiles[0].number}. " + tiles.drop(1).joinToString(", ") { "${it.number} ${it.label.replace("&", "and")}" } + "." +
                (rest?.let { " ${it.replace(" · ", ", ")}." } ?: "")
        }
        return NeedsYouMailSummary(tiles, q, FOOT, spoken, rest)
    }
}
