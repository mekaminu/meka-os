package os.meka.core.domain

/**
 * Inbox autopilot, slice 3a: what MEKA would do with each sorted email (Meka, 2026-10-12: newsletters and receipts
 * auto-archived but shown to him, notifications and promos archived). Non-AI, pure, unit-tested. The server runs it
 * over the headers it keeps; slice 3a only counts the result (a dry run, "would tidy 52"), nothing in the mailbox moves.
 * Slice 3b applies it (archive + label, each with Undo and an off switch).
 *
 * The never-touch rules: only an email still in the inbox, sorted **sure**, and in a tidy bucket is ever planned;
 * Needs you, FYI and Junk (its own daily review later) stay; mail from public bodies and schools, banks and building
 * societies, or an address Meka keeps ([MailTidyFacts.keepAddresses]: his partner, later his contacts) stays whatever
 * it was sorted as.
 */
enum class MailTidy(val one: String, val many: String, val label: String?) {
    /** Left exactly where it is. */
    KEEP("kept", "kept", null),
    /** Archived with the label MEKA/Newsletters. */
    NEWSLETTER("newsletter", "newsletters", "MEKA/Newsletters"),
    /** Archived with the label MEKA/Receipts. */
    RECEIPT("receipt or order", "receipts and orders", "MEKA/Receipts"),
    /** Archived (notifications and promos), no label. */
    ARCHIVE("notification or promo", "notifications and promos", null);

    companion object {
        fun of(stored: String?): MailTidy? = entries.firstOrNull { it.name == stored }
    }
}

/** What the planner sees of one email. */
data class MailTidyFacts(
    val fromAddress: String?,
    val inInbox: Boolean,
    /** Its sort, or null before it's sorted. */
    val sorted: MailSorted?,
    /** Addresses never tidied (lower case): his partner's, later his contacts'. */
    val keepAddresses: Set<String> = emptySet(),
)

object MailTidyRules {
    /** Banks and building societies (generic UK list): their mail is never tidied away, whatever it looks like. */
    val PROTECTED_DOMAINS = listOf(
        "barclays.co.uk", "barclays.com", "hsbc.co.uk", "hsbc.com", "lloydsbank.co.uk", "lloydsbank.com", "halifax.co.uk",
        "bankofscotland.co.uk", "natwest.com", "rbs.co.uk", "santander.co.uk", "nationwide.co.uk", "tsb.co.uk",
        "firstdirect.com", "monzo.com", "starlingbank.com", "revolut.com", "metrobankonline.co.uk", "co-operativebank.co.uk",
        "virginmoney.com", "americanexpress.com", "aexp.com", "chase.co.uk", "coventrybuildingsociety.co.uk",
        "yorkshirebuildingsociety.co.uk", "skipton.co.uk",
    )

    private fun endsWithDomain(domain: String, suffix: String) = domain == suffix || domain.endsWith(".$suffix")

    /** True for a sender MEKA never tidies away: public bodies and schools, banks, an address Meka keeps. */
    fun protectedSender(fromAddress: String?, keepAddresses: Set<String>): Boolean {
        val address = fromAddress?.trim()?.trimStart('<')?.trimEnd('>')?.lowercase()
        if (address != null && address in keepAddresses) return true
        val domain = MailSortRules.domain(fromAddress) ?: return false
        return (MailSortRules.PUBLIC_SUFFIXES + PROTECTED_DOMAINS).any { endsWithDomain(domain, it) }
    }

    fun plan(f: MailTidyFacts): MailTidy {
        val s = f.sorted ?: return MailTidy.KEEP
        if (!f.inInbox || !s.sure || protectedSender(f.fromAddress, f.keepAddresses)) return MailTidy.KEEP
        return when (s.sort) {
            MailSort.NEWSLETTER -> MailTidy.NEWSLETTER
            MailSort.RECEIPT -> MailTidy.RECEIPT
            MailSort.NOTIFICATION, MailSort.PROMO -> MailTidy.ARCHIVE
            MailSort.NEEDS_YOU, MailSort.FYI, MailSort.JUNK -> MailTidy.KEEP
        }
    }

    /**
     * The account's dry-run line in Mail: "Autopilot, dry run · would tidy 52 · 30 newsletters → MEKA/Newsletters ·
     * 12 receipts and orders → MEKA/Receipts · 10 notifications and promos archived · nothing moved yet"; null before
     * the server has planned anything (an older server, or nothing sorted yet). [counts] by [MailTidy] name; KEEP ignored.
     */
    fun dryRunLine(counts: Map<String, Int>?): String? {
        if (counts == null) return null
        val parts = MailTidy.entries.filter { it != MailTidy.KEEP }.mapNotNull { t ->
            counts[t.name]?.takeIf { it > 0 }?.let { n -> n to t }
        }
        val total = parts.sumOf { it.first }
        if (total == 0) return NOTHING_TO_TIDY
        val each = parts.joinToString(" · ") { (n, t) ->
            val what = "$n ${if (n == 1) t.one else t.many}"
            if (t.label != null) "$what → ${t.label}" else "$what archived"
        }
        return "Autopilot, dry run · would tidy $total · $each · nothing moved yet"
    }

    const val NOTHING_TO_TIDY = "Autopilot, dry run · nothing to tidy · nothing moved yet"
}
