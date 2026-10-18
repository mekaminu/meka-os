package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/*
 * "Archived for you" shows which emails (Meka, 2026-10-15 20:29: "these emails I can't even look at to decide if
 * they're good to archive; I don't see the sender etc"), slice 1. Each tidy the server does (Autopilot's and the daily
 * Junk review's; not the approved backlog or Empty Junk clean-ups, which can run to thousands) now carries the list of
 * the emails it moved in one Text field on its `mail_tidy` entity ([MailTidyBatchFields.EMAILS], ADR-008 addendum
 * 2026-10-16): per email the sender's name (else the domain's name), the sender's domain (never the whole address),
 * the subject, when it came, what was done with it and, for a priced receipt, what it cost. It lives exactly as long
 * as the tidy record (Delete mail data removes both). Every word of it is the sender's (ADR-006): shown as plain
 * text, never acted on. Non-AI, pure, unit-tested.
 */

/** One email a tidy moved, as the apps read it. */
data class MailTidiedEmail(
    /** A stable key for the email ("te" + a hash of the account and the provider's id), for Put back per email later. */
    val key: String,
    val tidy: MailTidy,
    /** For a notification or promo, which of the two ([MailSort] name); null otherwise or when not known. */
    val sort: MailSort?,
    val receivedAtMs: Long,
    /** "Amazon": the sender's name, else the domain's name. */
    val from: String,
    /** "amazon.co.uk"; null when the sender had no address. */
    val domain: String?,
    /** As sent; "(no subject)" when it had none. */
    val subject: String,
    /** A priced receipt's amount, else null. */
    val pence: Long?,
    /** Slice 2: the email offered a way to unsubscribe (a `List-Unsubscribe` header). */
    val unsubscribe: Boolean = false,
    /** Slice 2: the provider's own short preview (Gmail's snippet, Outlook's bodyPreview), cut; never the body. */
    val preview: String? = null,
)

/** One email under a sender in the card. */
data class ArchivedEmailRow(
    val key: String,
    /** "Your order has shipped". */
    val subject: String,
    /** "Promo", "Newsletter", "Receipt £12.99", "Junk · Trash", "Rescued from Junk". */
    val chip: String,
    /** "14:02" today, else "Mon 12 Oct · 14:02". */
    val time: String,
    /** Slice 2: "Putting back at the next check…", the server's "Back in the inbox", …; null while nothing was asked. */
    val state: String? = null,
    /** The tidy it belongs to (slice 2: a tap opens [MailTidiedAskRules.preview] with both). */
    val batchId: String = "",
)

/** A sender's emails in one tidy: "Deliveroo · 4 promos" when several, else the sender alone. */
data class ArchivedEmailGroup(
    val id: String,
    /** "Deliveroo · 4 promos" / "Amazon". */
    val title: String,
    /** "amazon.co.uk · Personal" (the account always; the domain when known). */
    val meta: String,
    /** Newest first, at most [MailTidiedRules.ROWS_SHOWN]. */
    val rows: List<ArchivedEmailRow>,
    /** "+ 2 more from Deliveroo" when the sender has more than [MailTidiedRules.ROWS_SHOWN]; else null. */
    val moreLine: String?,
    /** What a screen reader says for the group. */
    val spoken: String,
)

object MailTidiedRules {
    /** Fits inside the sync kernel's 20,000-character Text limit with room to spare. */
    const val MAX_CHARS = 18_000
    const val MAX_FROM = 60
    const val MAX_DOMAIN = 80
    const val MAX_SUBJECT = 140
    /** Slice 2: the preview's first lines, cut to this. */
    const val MAX_PREVIEW = 120
    const val NO_SUBJECT = "(no subject)"
    /** Senders listed under one tidy in the card; the rest are counted. */
    const val GROUPS_SHOWN = 6
    /** Emails listed under one sender. */
    const val ROWS_SHOWN = 3

    fun key(accountId: String, messageId: String): String = "te" + ActivityRules.fnv64("mailtidied:$accountId:$messageId")

    private fun clean(s: String?, max: Int): String =
        s.orEmpty().map { if (it == '\t' || it == '\n' || it == '\r' || it.code < 0x20) ' ' else it }.joinToString("")
            .replace(Regex("""\s+"""), " ").trim().let { if (it.length <= max) it else it.take(max - 1).trimEnd() + "…" }

    /**
     * The email as one line of the field (tab-separated, version 1). Slice 2 adds two trailing columns (a reader from
     * slice 1 ignores them): "u" when it offered an unsubscribe, and the cut preview ([withPreview]).
     */
    fun line(e: MailTidiedEmail, withPreview: Boolean = true): String = listOf(
        "1", e.key, e.tidy.name, e.sort?.name.orEmpty(), e.receivedAtMs.toString(), clean(e.from, MAX_FROM),
        clean(e.domain, MAX_DOMAIN), clean(e.subject, MAX_SUBJECT), e.pence?.toString().orEmpty(),
        if (e.unsubscribe) "u" else "", if (withPreview) clean(e.preview, MAX_PREVIEW) else "",
    ).joinToString("\t")

    /**
     * The field's value: newest first, as many as fit in [MAX_CHARS]; null for none. When the previews would leave
     * emails out, the list keeps every email it can without them (who and what matter more than the first lines).
     */
    fun encode(emails: List<MailTidiedEmail>): String? {
        val sorted = emails.sortedByDescending { it.receivedAtMs }
        fun build(withPreview: Boolean): Pair<String, Int> {
            val out = StringBuilder()
            var n = 0
            for (e in sorted) {
                val l = line(e, withPreview)
                if (out.length + l.length + 1 > MAX_CHARS) break
                if (out.isNotEmpty()) out.append('\n')
                out.append(l)
                n++
            }
            return out.toString() to n
        }
        val (full, n) = build(true)
        val text = if (n < sorted.size) build(false).first else full
        return text.takeIf { it.isNotEmpty() }
    }

    fun fields(emails: List<MailTidiedEmail>): Map<String, FieldValue> =
        encode(emails)?.let { mapOf(MailTidyBatchFields.EMAILS to it.fv()) } ?: emptyMap()

    /** Reads the field back; a line it can't read (a later version) is skipped. */
    fun decode(text: String?): List<MailTidiedEmail> = text.orEmpty().split('\n').mapNotNull { l ->
        val p = l.split('\t')
        if (p.size < 9 || p[0] != "1") return@mapNotNull null
        val tidy = MailTidy.of(p[2])?.takeIf { it != MailTidy.KEEP } ?: return@mapNotNull null
        MailTidiedEmail(
            key = p[1], tidy = tidy, sort = MailSort.entries.firstOrNull { it.name == p[3] },
            receivedAtMs = p[4].toLongOrNull() ?: return@mapNotNull null,
            from = p[5].ifBlank { "Unknown sender" }, domain = p[6].ifBlank { null },
            subject = p[7].ifBlank { NO_SUBJECT }, pence = p[8].toLongOrNull()?.takeIf { it > 0 },
            unsubscribe = p.getOrNull(9) == "u", preview = p.getOrNull(10)?.ifBlank { null },
        )
    }

    /** The reason chip: "Promo", "Newsletter", "Receipt £12.99", "Junk · Trash", "Rescued from Junk". */
    fun chip(e: MailTidiedEmail): String = when (e.tidy) {
        MailTidy.NEWSLETTER -> "Newsletter"
        MailTidy.RECEIPT -> "Receipt" + (e.pence?.let { " " + MailReceiptRules.format(it) } ?: "")
        MailTidy.ARCHIVE -> when (e.sort) {
            MailSort.PROMO -> "Promo"
            MailSort.NOTIFICATION -> "Notification"
            else -> "Notification or promo"
        }
        MailTidy.TRASH -> "Unsubscribed · Trash"
        MailTidy.JUNK_TRASH -> "Junk · Trash"
        MailTidy.RESCUE -> "Rescued from Junk"
        MailTidy.KEEP -> "Kept"
    }

    /** "4 promos", "2 receipts", "3 emails" (mixed). */
    private fun count(es: List<MailTidiedEmail>): String {
        val n = es.size
        val kinds = es.map { noun(it) }.distinct()
        val k = kinds.singleOrNull() ?: ("email" to "emails")
        return "$n ${if (n == 1) k.first else k.second}"
    }

    private fun noun(e: MailTidiedEmail): Pair<String, String> = when (e.tidy) {
        MailTidy.NEWSLETTER -> "newsletter" to "newsletters"
        MailTidy.RECEIPT -> "receipt" to "receipts"
        MailTidy.ARCHIVE -> when (e.sort) {
            MailSort.PROMO -> "promo" to "promos"
            MailSort.NOTIFICATION -> "notification" to "notifications"
            else -> "notification or promo" to "notifications and promos"
        }
        MailTidy.TRASH -> "email to Trash" to "emails to Trash"
        MailTidy.JUNK_TRASH -> "junk email" to "junk emails"
        MailTidy.RESCUE -> "email rescued from Junk" to "emails rescued from Junk"
        MailTidy.KEEP -> "email" to "emails"
    }

    private fun time(ms: Long, today: Long, cal: LocalCalendar): String {
        val day = cal.epochDayOf(ms)
        val clock = LocalClock.formatMinute(cal.minuteOfDay(ms))
        return if (day == today) clock else "${CivilDate.shortLabel(day)} · $clock"
    }

    /** A repeat's subject on the card's Came again line, cut to this. */
    const val MAX_REPEAT_SUBJECT = 48

    /**
     * 2026-10-18 (the inbox item: "six GitHub “Run failed: ci” emails … group them"): what makes two subjects the same
     * email again — lower case, words only, and any word holding a digit (a run number, a commit, an order or a date)
     * counts as one "#". Null when too little is left to tell (no word of three letters or more), so short or numeric
     * subjects never collapse.
     */
    fun repeatKey(subject: String): String? {
        val words = subject.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
            .map { w -> if (w.any { it.isDigit() }) "#" else w }
        if (words.none { it.length >= 3 && it != "#" }) return null
        return words.joinToString(" ")
    }

    /** One sender's emails (newest first) in runs of the same email again; each run newest first, in order of its newest. */
    fun repeatRuns(es: List<MailTidiedEmail>): List<List<MailTidiedEmail>> {
        val runs = LinkedHashMap<String, MutableList<MailTidiedEmail>>()
        es.forEach { e -> runs.getOrPut(repeatKey(e.subject) ?: ("\u0000" + e.key)) { mutableListOf() }.add(e) }
        return runs.values.toList()
    }

    /**
     * A tidy's emails as the card shows them: by sender (newest sender first), each sender's emails newest first.
     * Returns the groups shown (at most [GROUPS_SHOWN]) and, when the tidy moved more than it lists, "+ 12 more in
     * this tidy" (more senders than shown, or emails the field couldn't hold).
     */
    fun groups(batch: MailTidyBatch, emails: List<MailTidiedEmail>, nowMs: Long, cal: LocalCalendar): Pair<List<ArchivedEmailGroup>, String?> {
        if (emails.isEmpty()) return emptyList<ArchivedEmailGroup>() to null
        val today = cal.epochDayOf(nowMs)
        val bySender = emails.groupBy { it.from.lowercase() }.values
            .map { es -> es.sortedWith(compareByDescending<MailTidiedEmail> { it.receivedAtMs }.thenBy { it.key }) }
            .sortedWith(compareByDescending<List<MailTidiedEmail>> { it.first().receivedAtMs }.thenBy { it.first().from.lowercase() })
        val shown = bySender.take(GROUPS_SHOWN).map { es ->
            val first = es.first()
            val title = if (es.size == 1) first.from else "${first.from} · ${count(es)}"
            val meta = listOfNotNull(first.domain, batch.account).joinToString(" · ")
            // 2026-10-18: the same email again and again ("Run failed: ci" for each commit) is one row, "· 6 times".
            val runs = repeatRuns(es).take(ROWS_SHOWN)
            val rows = runs.map { r ->
                val e = r.first()
                val again = if (r.size > 1) " · ${r.size} times" else ""
                ArchivedEmailRow(e.key, e.subject, chip(e) + again, time(e.receivedAtMs, today, cal), MailTidiedAskRules.state(batch, e.key), batch.id)
            }
            val more = es.size - runs.sumOf { it.size }
            ArchivedEmailGroup(
                id = batch.id + ":" + first.key,
                title = title, meta = meta, rows = rows,
                moreLine = if (more > 0) "+ $more more from ${first.from}" else null,
                spoken = "$title, $meta. " + rows.joinToString(". ") { r -> listOfNotNull(r.subject, r.chip, r.time, r.state).joinToString(", ") } + ".",
            )
        }
        val listedInGroups = bySender.take(GROUPS_SHOWN).sumOf { it.size }
        val more = maxOf(batch.count, emails.size) - listedInGroups
        return shown to (if (more > 0) "+ $more more in this tidy" else null)
    }
}
