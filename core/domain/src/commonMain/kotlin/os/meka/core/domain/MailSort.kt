package os.meka.core.domain

/**
 * Inbox autopilot, slice 2a: sorting each email by rules, from its headers only (never a body). Non-AI, pure,
 * unit-tested; MEKA's server runs it over the headers it keeps (slice 1c) and the apps show the counts.
 *
 * The buckets are Meka's (2026-10-12): Needs you · FYI · Newsletter · Receipt/order · Notification · Promo · Junk.
 * Rules decide what headers make plain (Gmail's spam label and categories, List-Id/List-Unsubscribe, known shop and
 * carrier senders with order words, no-reply senders). Anything a person seems to have written lands in Needs you
 * with [MailSorted.sure] false: the safe side (nothing in Needs you is ever tidied away), and the AI looks at those in
 * slice 2b ([afterAi]: Needs you or FYI, from the sender and subject only). Mail from public bodies and schools (gov.uk, nhs, police, sch.uk) is always Needs you, sure.
 * Sorting changes nothing in the mailbox; acting on it is slice 3.
 */
enum class MailSort(val one: String, val many: String) {
    NEEDS_YOU("needs you", "need you"),
    FYI("FYI", "FYI"),
    NEWSLETTER("newsletter", "newsletters"),
    RECEIPT("receipt or order", "receipts and orders"),
    NOTIFICATION("notification", "notifications"),
    PROMO("promo", "promos"),
    JUNK("junk", "junk");

    companion object {
        fun of(stored: String?): MailSort? = entries.firstOrNull { it.name == stored }
    }
}

/** Why a rule sorted it (the server stores [name]; slice 4's "why" line reads [line]). */
enum class MailSortReason(val line: String) {
    SPAM("In Gmail's spam"),
    PUBLIC_BODY("From a public body or school · always yours"),
    ORDER_WORDS("An order, receipt or delivery"),
    CARRIER("From a delivery company"),
    GMAIL_PROMOTIONS("Gmail filed it under Promotions"),
    PROMO_WORDS("A mailing list with sale words"),
    MAILING_LIST("A mailing list (it has an unsubscribe link)"),
    GMAIL_FORUMS("Gmail filed it under Forums"),
    GMAIL_SOCIAL("Gmail filed it under Social"),
    GMAIL_UPDATES("Gmail filed it under Updates"),
    NO_REPLY("Sent by a no-reply address"),
    PERSON("A person writing to you"),
    NO_SENDER("No sender to go by"),
    /** Slice 2b: MEKA's AI read the sender and subject of an unsure one. */
    AI_NEEDS_YOU("MEKA's AI read the sender and subject · it needs you"),
    AI_FYI("MEKA's AI read the sender and subject · just so you know"),
    /** The AI was asked but gave no clear answer: kept as Needs you (the safe side), not asked again. */
    AI_UNCLEAR("MEKA's AI couldn't tell · kept for you");

    companion object {
        fun of(stored: String?): MailSortReason? = entries.firstOrNull { it.name == stored }
    }
}

/** What the rules see of one email: headers only. */
data class MailSortFacts(
    val fromAddress: String?,
    val fromName: String?,
    val subject: String?,
    /** Gmail's label ids (INBOX, SPAM, CATEGORY_PROMOTIONS…); Outlook has none. */
    val labels: List<String>,
    val listUnsubscribe: String?,
    val listId: String?,
)

/** A sorted email. [sure] false: the rules leaned the safe way and the AI should look again (slice 2b). */
data class MailSorted(val sort: MailSort, val reason: MailSortReason, val sure: Boolean)

object MailSortRules {
    /** Domains whose mail is always Needs you, never tidied (with their subdomains). */
    val PUBLIC_SUFFIXES = listOf("gov.uk", "nhs.uk", "nhs.net", "police.uk", "sch.uk", "parliament.uk")

    /** Carriers: anything from them is about a parcel. */
    val CARRIERS = listOf("royalmail.com", "dpd.co.uk", "dpdlocal.co.uk", "evri.com", "myhermes.co.uk", "ups.com", "dhl.com",
        "dhl.co.uk", "parcelforce.com", "yodel.co.uk", "inpost.co.uk", "fedex.com")

    /** Words in a subject that mean an order, a receipt or a delivery (lower case). */
    val ORDER_WORDS = listOf(
        "receipt", "your order", "order confirm", "order #", "order no", "order number", "invoice", "payment received",
        "payment confirmation", "thanks for your payment", "thank you for your payment", "refund", "booking confirm",
        "your booking", "dispatched", "has shipped", "have shipped", "been shipped", "out for delivery", "delivered",
        "your delivery", "your parcel", "tracking number", "e-ticket", "your tickets",
    )

    /** Words in a mailing list's subject that mean selling (lower case). */
    val PROMO_WORDS = listOf(
        "% off", "sale", "deal", "offer", "discount", "voucher", "promo code", "free delivery", "free shipping",
        "black friday", "cyber monday", "limited time", "last chance", "ends tonight", "ends soon", "save £", "save up to",
        "new arrivals", "shop now", "don't miss",
    )

    /** The part before @ of senders no one reads replies to. */
    val NO_REPLY = listOf("noreply", "no-reply", "no_reply", "donotreply", "do-not-reply", "do_not_reply", "notifications",
        "notification", "notify", "alerts", "alert", "mailer-daemon", "postmaster", "automated", "auto-confirm", "bounce")

    fun domain(address: String?): String? = address?.substringAfterLast('@', "")?.trim()?.trimEnd('>')?.lowercase()?.ifEmpty { null }

    private fun endsWithDomain(domain: String, suffix: String) = domain == suffix || domain.endsWith(".$suffix")

    private fun local(address: String?): String = address?.substringBefore('@')?.trim()?.trimStart('<')?.lowercase().orEmpty()

    /** True for a no-reply style sender ("no-reply@", "notifications@", "noreply.alerts@"…). */
    fun isAutomated(address: String?): Boolean {
        val l = local(address)
        if (l.isEmpty()) return false
        return NO_REPLY.any { l == it || l.startsWith("$it.") || l.startsWith("$it-") || l.startsWith("${it}_") || l.startsWith("$it+") || l.endsWith(".$it") || l.endsWith("-$it") }
    }

    private fun hasWord(text: String, words: List<String>): Boolean = words.any { w ->
        var i = text.indexOf(w)
        while (i >= 0) {
            // Whole words only for short words ("sale" not "wholesale", "deal" not "dealer"): letters must not touch.
            val before = i == 0 || !text[i - 1].isLetter()
            val after = i + w.length >= text.length || !text[i + w.length].isLetter() || !w.last().isLetter()
            if (before && after) return@any true
            i = text.indexOf(w, i + 1)
        }
        false
    }

    fun sort(f: MailSortFacts): MailSorted {
        val labels = f.labels.toSet()
        val domain = domain(f.fromAddress)
        val subject = f.subject?.lowercase().orEmpty()
        val list = !f.listId.isNullOrBlank() || !f.listUnsubscribe.isNullOrBlank()
        fun sure(s: MailSort, r: MailSortReason) = MailSorted(s, r, true)
        return when {
            "SPAM" in labels -> sure(MailSort.JUNK, MailSortReason.SPAM)
            domain != null && PUBLIC_SUFFIXES.any { endsWithDomain(domain, it) } -> sure(MailSort.NEEDS_YOU, MailSortReason.PUBLIC_BODY)
            domain != null && CARRIERS.any { endsWithDomain(domain, it) } -> sure(MailSort.RECEIPT, MailSortReason.CARRIER)
            hasWord(subject, ORDER_WORDS) && (list || isAutomated(f.fromAddress) || "CATEGORY_UPDATES" in labels || "CATEGORY_PURCHASES" in labels) ->
                sure(MailSort.RECEIPT, MailSortReason.ORDER_WORDS)
            "CATEGORY_PROMOTIONS" in labels -> sure(MailSort.PROMO, MailSortReason.GMAIL_PROMOTIONS)
            list && hasWord(subject, PROMO_WORDS) -> sure(MailSort.PROMO, MailSortReason.PROMO_WORDS)
            "CATEGORY_FORUMS" in labels -> sure(MailSort.NEWSLETTER, MailSortReason.GMAIL_FORUMS)
            "CATEGORY_SOCIAL" in labels -> sure(MailSort.NOTIFICATION, MailSortReason.GMAIL_SOCIAL)
            list -> sure(MailSort.NEWSLETTER, MailSortReason.MAILING_LIST)
            "CATEGORY_UPDATES" in labels -> sure(MailSort.NOTIFICATION, MailSortReason.GMAIL_UPDATES)
            isAutomated(f.fromAddress) -> sure(MailSort.NOTIFICATION, MailSortReason.NO_REPLY)
            domain == null -> MailSorted(MailSort.NEEDS_YOU, MailSortReason.NO_SENDER, false)
            else -> MailSorted(MailSort.NEEDS_YOU, MailSortReason.PERSON, false)
        }
    }

    /**
     * The account's line in Mail: "In the inbox · 6 need you · 30 newsletters · 12 receipts and orders …" in
     * [MailSort] order, zeros left out; "Inbox clear" when nothing is there; null before the server has sorted
     * anything (an older server, or headers still being read).
     */
    fun summary(counts: Map<String, Int>?): String? {
        if (counts == null) return null
        val parts = MailSort.entries.mapNotNull { s -> counts[s.name]?.takeIf { it > 0 }?.let { n -> "$n ${if (n == 1) s.one else s.many}" } }
        return if (parts.isEmpty()) INBOX_CLEAR else "In the inbox · " + parts.joinToString(" · ")
    }

    /** The AI's two answers (slice 2b). It only ever chooses between these: nothing it says can make mail Junk or Promo. */
    const val AI_NEEDS_YOU = "needs_you"
    const val AI_FYI = "fyi"

    /**
     * Slice 2b: what an unsure email becomes after the AI looked. Only an unsure Needs you is ever asked, and the
     * answer can only keep it Needs you or make it FYI; anything else (no answer, an unknown word) keeps it Needs you.
     * Every result is sure, so the same email is never asked about twice (until its headers change).
     */
    fun afterAi(lane: String?): MailSorted = when (lane?.trim()?.lowercase()) {
        AI_FYI -> MailSorted(MailSort.FYI, MailSortReason.AI_FYI, true)
        AI_NEEDS_YOU -> MailSorted(MailSort.NEEDS_YOU, MailSortReason.AI_NEEDS_YOU, true)
        else -> MailSorted(MailSort.NEEDS_YOU, MailSortReason.AI_UNCLEAR, true)
    }

    /** True for a sort the AI should look at: the rules leaned the safe way (a person, or no sender). */
    fun wantsAi(s: MailSorted): Boolean = !s.sure && s.sort == MailSort.NEEDS_YOU

    const val INBOX_CLEAR = "Inbox clear · nothing from the last 60 days waiting"
}
