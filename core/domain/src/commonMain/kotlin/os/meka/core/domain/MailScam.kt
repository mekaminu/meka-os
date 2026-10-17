package os.meka.core.domain

/**
 * "From your mail · 13 emails need you" was mostly junk (Meka, 2026-10-17 03:56: a club platform's activity digest, a
 * lottery "you've won a prize!" marketing email, his own email to himself, a "Notification via PayPal: your account will
 * be closed" email and a "4 HOURS LEFT" prize draw). These rules, headers only (sender, display name, subject), pure and
 * unit-tested, take them out of Needs you:
 *
 * - **Possible scam** ([check]): an email that names a company (in its display name or subject) and threatens or promises
 *   something ("your account will be closed", "verify your account", "you've won") but isn't sent from that company's
 *   domain stays in Needs you, sure, with "⚠ Possible scam · sent from mail-x.com, not paypal.com". The same words from
 *   the company's **real** domain read "From PayPal's real address · log in at paypal.com yourself, not through the
 *   email's link". Neither is ever drafted a reply or tidied away.
 * - **Prize draws and competitions** ([prize]) are Promo, **account digests** ([digest]) a Notification, unless they come
 *   from someone he has written to ([MailSortRules.known]).
 * - **His own address** writing to him ([MailSortRules.mine]) is FYI: a note to self, never "needs you".
 */
enum class MailScamKind { SCAM, REAL }

/** What [MailScamRules.check] found: the company named, its real domain and the domain the email came from. */
data class MailScamFind(val kind: MailScamKind, val brand: String, val realDomain: String, val fromDomain: String?)

object MailScamRules {
    /**
     * Words of a threat or a promise that phishing uses (lower case, in the subject or display name). Only together with
     * a company named that isn't the sender do they make a possible scam.
     */
    val THREATS = listOf(
        "account will be closed", "account will be suspended", "account will be locked", "account will be limited",
        "account has been suspended", "account has been limited", "account has been locked", "account is suspended",
        "account is limited", "account is locked", "account is on hold", "account on hold", "account suspended",
        "account locked", "account limited", "will be closed", "will be deactivated", "will be terminated",
        "verify your account", "verify your identity", "verify your details", "verify your information",
        "confirm your identity", "confirm your account", "confirm your details", "update your details",
        "update your payment", "update your billing", "payment failed", "payment declined", "payment was declined",
        "unable to process your payment", "final notice", "final warning", "last warning", "action required",
        "immediate action", "urgent action", "unusual activity", "suspicious activity", "unusual sign-in", "unauthorized",
        "unauthorised", "you have won", "you've won", "claim your prize", "claim your reward", "claim your refund",
        "refund pending", "you are owed", "tax refund", "parcel is held", "parcel held", "delivery failed", "redelivery fee",
        "customs fee", "subscription has expired", "subscription expired", "membership has expired", "mailbox is full",
        "storage is full", "password expires", "password will expire",
    )

    /** Words of a prize draw, lottery or competition email (lower case, in the subject). */
    val PRIZE_WORDS = listOf(
        "you've won", "you have won", "prize draw", "prize", "lottery", "jackpot", "chance to win", "win a ",
        "win an ", "win £", "win up to", "to be won", "sweepstake", "giveaway", "competition", "winner", "hours left",
        "hours to go", "last chance to enter", "enter now", "enter today", "free entry", "free entries", "free spins",
        "draw closes", "draw ends", "tickets left",
    )

    /** Words of an account digest or activity summary (lower case, in the subject). */
    val DIGEST_WORDS = listOf(
        "digest", "activity summary", "summary of activity", "summary of your", "weekly summary", "monthly summary",
        "daily summary", "weekly round-up", "weekly roundup", "monthly round-up", "monthly roundup", "your weekly",
        "your monthly", "your daily", "activity report", "account activity", "recent activity", "what you missed",
        "this week on", "this week at", "this month on", "this month at", "week in review", "month in review",
        "your stats", "new activity", "highlights from",
    )

    /**
     * Companies phishing pretends to be, with how they're written and their real domains: the Junk review's list
     * ([MailJunkRules.CLAIMED_SENDERS]) plus the account-security providers ([MailSecurityRules.SENDERS]). Names under
     * three letters ("X", "O2", "EE") are matched only from the Junk list's own tokens.
     */
    private val BRANDS: List<Triple<String, String, List<String>>> by lazy {
        val junk = MailJunkRules.CLAIMED_SENDERS.map { (k, ds) -> Triple(k.trim(), display(k.trim()), ds) }
        val security = MailSecurityRules.SENDERS.filter { it.first.length >= 3 }.map { (n, ds) -> Triple(n.lowercase(), n, ds) }
        (junk + security).groupBy { it.first }.map { (k, all) ->
            Triple(k, all.last().second, all.flatMap { it.third }.distinct().filter { it !in FREE_MAIL })
        }.filter { it.third.isNotEmpty() }
    }

    /** Domains anyone can get a mailbox on: never a company's "real address". */
    val FREE_MAIL = setOf("gmail.com", "googlemail.com", "outlook.com", "hotmail.com", "hotmail.co.uk", "live.com",
        "live.co.uk", "icloud.com", "me.com", "yahoo.com", "yahoo.co.uk", "aol.com")

    private val UPPER = setOf("hmrc", "dvla", "nhs", "ee", "dpd", "dhl", "hsbc", "o2")

    private fun display(token: String): String = when {
        token in UPPER -> token.uppercase()
        token == "paypal" -> "PayPal"
        token == "tv licensing" -> "TV Licensing"
        else -> token.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
    }

    private fun endsWithDomain(domain: String, suffix: String) = domain == suffix || domain.endsWith(".$suffix")

    private fun word(text: String, token: String): Boolean =
        Regex("(^|[^a-z0-9])" + Regex.escape(token) + "([^a-z0-9]|$)").containsMatchIn(text)

    private fun hasPhrase(text: String, words: List<String>): Boolean = words.any { w ->
        var i = text.indexOf(w)
        while (i >= 0) {
            val before = i == 0 || !text[i - 1].isLetter()
            val after = i + w.length >= text.length || !text[i + w.length].isLetter() || !w.last().isLetter()
            if (before && after) return@any true
            i = text.indexOf(w, i + 1)
        }
        false
    }

    private fun clean(s: String?): String = s.orEmpty().lowercase().replace('’', '\'').replace('‑', '-').replace('‐', '-')

    /**
     * A possible scam or a real company's warning, or null. Needs a threat ([THREATS]) and a company named in the
     * display name or the subject: from a domain that isn't the company's it's [MailScamKind.SCAM], from the company's
     * own (or a subdomain) [MailScamKind.REAL].
     */
    fun check(fromAddress: String?, fromName: String?, subject: String?): MailScamFind? {
        val text = clean(subject) + " " + clean(fromName)
        if (!hasPhrase(text, THREATS)) return null
        val domain = MailSortRules.domain(fromAddress)
        val name = clean(fromName)
        val subj = clean(subject)
        // The display name's claim wins over one only in the subject ("Notification via PayPal").
        val brand = BRANDS.firstOrNull { (k, _, _) -> word(name, k) } ?: BRANDS.firstOrNull { (k, _, _) -> word(subj, k) } ?: return null
        val (_, shown, domains) = brand
        val real = domain != null && domains.any { endsWithDomain(domain, it) }
        return MailScamFind(if (real) MailScamKind.REAL else MailScamKind.SCAM, shown, domains.first(), domain)
    }

    /** True for a prize draw, lottery or competition subject. */
    fun prize(subject: String?): Boolean = hasPhrase(clean(subject), PRIZE_WORDS)

    /** True for an account digest or activity-summary subject. */
    fun digest(subject: String?): Boolean = hasPhrase(clean(subject), DIGEST_WORDS)

    /** "⚠ Possible scam · sent from mail-x.com, not paypal.com" / "From PayPal's real address · log in at paypal.com yourself…". */
    fun line(f: MailScamFind): String = when (f.kind) {
        MailScamKind.SCAM -> "⚠ Possible scam · sent from ${f.fromDomain ?: "an unknown address"}, not ${f.realDomain}"
        MailScamKind.REAL -> "From ${f.brand}'s real address · log in at ${f.realDomain} yourself, not through the email's link"
    }

    /**
     * What the server writes on the card's `mail_item` ([MailItemFields.WARNING]): the line for a scam or a real company's
     * warning sorted by these rules, else "".
     */
    fun itemWarning(reason: MailSortReason?, fromAddress: String?, fromName: String?, subject: String?): String {
        // A real company's line only where the sort kept it as one (its marketing sorts as usual).
        if (reason != MailSortReason.SCAM && reason != MailSortReason.BRAND_NOTICE) return ""
        return check(fromAddress, fromName, subject)?.let(::line).orEmpty()
    }

    /** True for a warning line that is a possible scam (shown in the critical colour). */
    fun scamLine(line: String?): Boolean = line?.startsWith("⚠") == true

    /**
     * Inbox junk fix: an email already in the inbox sorted Needs you before these rules is sorted again when the rules
     * now say something sure and different (a Promo, a Notification, FYI, a scam or a company's warning). His
     * correspondents' mail ([MailSortReason.KNOWN_PERSON]), public bodies and account-security mail stay as they are.
     */
    fun resort(current: MailSorted, again: MailSorted): MailSorted? {
        if (current.sort != MailSort.NEEDS_YOU) return null
        if (current.reason in STAYS) return null
        if (!again.sure || again == current) return null
        if (again.sort == MailSort.NEEDS_YOU && again.reason != MailSortReason.SCAM && again.reason != MailSortReason.BRAND_NOTICE &&
            again.reason != MailSortReason.SECURITY) return null
        return again
    }

    private val STAYS = setOf(
        MailSortReason.KNOWN_PERSON, MailSortReason.PUBLIC_BODY, MailSortReason.SECURITY, MailSortReason.SECURITY_CODE,
        MailSortReason.SCAM, MailSortReason.BRAND_NOTICE, MailSortReason.SPAM,
    )
}
