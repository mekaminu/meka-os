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

/**
 * What [MailScamRules.check] (or [MailScamRules.bodyCheck]) found: the company named, its real domain and the domain the
 * email came from. [why] is set for a scam found in the body ([MailScamRules.BODY_BRAND], [MailScamRules.BODY_GREETING],
 * [MailScamRules.BODY_AUTH]), which reads "⚠ Possible scam · says Microsoft, sent from formlab.it".
 */
data class MailScamFind(
    val kind: MailScamKind, val brand: String, val realDomain: String, val fromDomain: String?, val why: String? = null,
)

/**
 * The body of an email, for [MailScamRules.bodyCheck]: its plain [text], its (sanitised) [html] for the links' real
 * hosts and logos' alt text, the addresses it was sent to ([ownAddresses], for "Hi alex@hotmail.com,"), and the
 * `Authentication-Results` header when the server has it.
 */
data class MailBodyFacts(
    val fromAddress: String?,
    val fromName: String?,
    val subject: String?,
    val text: String?,
    val html: String? = null,
    val ownAddresses: Collection<String> = emptyList(),
    val authResults: String? = null,
)

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
        // Account checks (Meka, 2026-11-08: "Is Your Account Still Active?" from "Outlook Session" at an unrelated domain).
        "account still active", "is your account active", "account is still active", "confirm account", "verify account",
        "keep your account", "reactivate", "deactivation", "account deactivation", "account closure", "account will be deleted",
        "will be deleted", "session expired", "session has expired", "mailbox will be", "storage quota", "quota exceeded",
        "sign-in required", "sign in required", "re-validate", "revalidate", "validate your account", "account verification",
    )

    /**
     * Subject words about the account itself: a display name naming a company from a domain that isn't the company's
     * makes a possible scam with one of these even without a threat ("Outlook Session" · "Your mailbox").
     */
    val ACCOUNT_WORDS = listOf(
        "account", "accounts", "password", "mailbox", "sign-in", "sign in", "login", "log in", "verify", "verification",
        "security", "billing", "payment details", "storage", "session", "suspended", "suspension", "deactivate",
        "deactivated", "expire", "expired", "expires", "expiring", "subscription",
    )

    /** Words in an email's body that press for action now (with a company named and its links elsewhere: a scam). */
    val BODY_URGENCY = listOf(
        "within 24 hours", "within 48 hours", "within 72 hours", "within 7 days", "within seven days", "within 3 days",
        "within three days", "within 14 days", "or your account", "your account will", "will be closed", "will be deleted",
        "will be suspended", "will be deactivated", "will be terminated", "will be permanently", "avoid suspension",
        "avoid losing", "to avoid interruption", "to keep your account", "to continue using", "failure to", "click below",
        "click here to", "confirm now", "verify now", "act now", "immediately",
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
        val companies = (junk + security).groupBy { it.first }.map { (k, all) ->
            Triple(k, all.last().second, (all.flatMap { it.third } + EXTRA_DOMAINS[k].orEmpty()).distinct().filter { it !in FREE_MAIL })
        }.filter { it.third.isNotEmpty() }
        // Products count as their company: "Outlook Session" claims to be Microsoft.
        val products = PRODUCTS.mapNotNull { (p, k) -> companies.firstOrNull { it.first == k }?.let { Triple(p, it.second, it.third) } }
        companies + products
    }

    /** Products phishing names instead of the company (lower case), with the company's token in [BRANDS]. */
    val PRODUCTS: List<Pair<String, String>> = listOf(
        "outlook" to "microsoft", "hotmail" to "microsoft", "office 365" to "microsoft", "office365" to "microsoft",
        "microsoft 365" to "microsoft", "onedrive" to "microsoft", "one drive" to "microsoft", "sharepoint" to "microsoft",
        "microsoft teams" to "microsoft", "windows live" to "microsoft", "msn" to "microsoft",
        "gmail" to "google", "google drive" to "google", "google account" to "google", "google workspace" to "google",
        "icloud" to "apple", "apple id" to "apple", "app store" to "apple", "itunes" to "apple",
        "amazon prime" to "amazon", "prime video" to "amazon",
    )

    /** Company domains beyond the Junk and security lists (Microsoft's sign-in and Office mail). */
    private val EXTRA_DOMAINS: Map<String, List<String>> = mapOf(
        "microsoft" to listOf("microsoftonline.com", "office.com", "office365.com", "sharepointonline.com", "microsoft365.com"),
        "google" to listOf("googlemail.com"),
        "apple" to listOf("itunes.com", "apple.co.uk"),
    )

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
        val domain = MailSortRules.domain(fromAddress)
        val name = clean(fromName)
        val subj = clean(subject)
        if (!hasPhrase(text, THREATS)) {
            // A display name claiming a company, from a domain that isn't the company's, about the account: a scam.
            if (!hasPhrase(subj, ACCOUNT_WORDS)) return null
            val (_, shown, domains) = BRANDS.firstOrNull { (k, _, _) -> word(name, k) } ?: return null
            if (domain != null && domains.any { endsWithDomain(domain, it) }) return null
            return MailScamFind(MailScamKind.SCAM, shown, domains.first(), domain)
        }
        // The display name's claim wins over one only in the subject ("Notification via PayPal").
        val brand = BRANDS.firstOrNull { (k, _, _) -> word(name, k) } ?: BRANDS.firstOrNull { (k, _, _) -> word(subj, k) } ?: return null
        val (_, shown, domains) = brand
        val real = domain != null && domains.any { endsWithDomain(domain, it) }
        return MailScamFind(if (real) MailScamKind.REAL else MailScamKind.SCAM, shown, domains.first(), domain)
    }

    /**
     * Phishing in the body, slice 3: the display name itself claims a company ("Outlook Session", "PayPal Service")
     * and the email isn't sent from that company's domain (or a subdomain of it).
     */
    fun nameClaim(fromAddress: String?, fromName: String?): Boolean {
        val name = clean(fromName)
        val (_, _, domains) = BRANDS.firstOrNull { (k, _, _) -> word(name, k) } ?: return false
        val domain = MailSortRules.domain(fromAddress) ?: return true
        return domains.none { endsWithDomain(domain, it) }
    }

    const val BODY_BRAND = "brand"
    const val BODY_GREETING = "greeting"
    const val BODY_AUTH = "auth"

    private val HREF = Regex("""href\s*=\s*["']?([^"'\s>]+)""", RegexOption.IGNORE_CASE)
    private val ALT = Regex("""alt\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
    private val BARE = Regex("""https?://[^\s"'<>)]+""", RegexOption.IGNORE_CASE)

    /** The real hosts of the email's web links (href and bare https://…), without "www.", lower case. */
    fun linkHosts(html: String?, text: String?): List<String> {
        val urls = HREF.findAll(html.orEmpty()).map { it.groupValues[1] } + BARE.findAll(text.orEmpty()).map { it.value }
        return urls.mapNotNull { u ->
            val c = u.trim().lowercase()
            if (!c.startsWith("http://") && !c.startsWith("https://")) return@mapNotNull null
            c.substringAfter("//").substringBefore('/').substringBefore('?').substringBefore('#')
                .substringAfterLast('@').substringBefore(':').removePrefix("www.").takeIf { it.contains('.') }
        }.distinct().toList()
    }

    /** True when SPF, DKIM or DMARC failed outright in an `Authentication-Results` header (dmarc=fail, or spf and dkim both). */
    fun authFailed(results: String?): Boolean {
        val r = results?.lowercase() ?: return false
        return "dmarc=fail" in r || ("spf=fail" in r && ("dkim=fail" in r || "dkim=none" in r))
    }

    /**
     * Phishing that hides its threat in the body (Meka, 2026-11-08). The headers first ([check]); else a possible scam
     * when, from a sender that isn't the company's own domain:
     * - **the company is named** (in the text, the display name or a logo's alt text) **and its links go elsewhere**
     *   (any web link whose host isn't the company's) **and it presses** ([THREATS] or [BODY_URGENCY]);
     * - **it greets him by his email address** ("Hi alex@hotmail.com,", "Dear alex@…");
     * - **the sender check failed** ([authFailed]), even from the company's own domain (a spoof).
     * Real company mail from its own domain (account-security mail included) is never a body scam unless spoofed.
     */
    fun bodyCheck(f: MailBodyFacts): MailScamFind? {
        check(f.fromAddress, f.fromName, f.subject)?.let { if (it.kind == MailScamKind.SCAM) return it }
        val domain = MailSortRules.domain(f.fromAddress)
        val html = f.html.orEmpty()
        val body = clean(f.text?.takeIf { it.isNotBlank() } ?: MailHtmlSanitizer.text(html)).replace(Regex("\\s+"), " ")
        val alts = ALT.findAll(html).joinToString(" ") { clean(it.groupValues[1]) }
        val named = clean(f.fromName) + " " + clean(f.subject) + " " + alts + " " + body
        val brand = BRANDS.firstOrNull { (k, _, _) -> word(clean(f.fromName), k) } ?: BRANDS.firstOrNull { (k, _, _) -> word(named, k) }
        val own = brand != null && domain != null && brand.third.any { endsWithDomain(domain, it) }
        fun find(why: String) = MailScamFind(MailScamKind.SCAM, brand?.second.orEmpty(), brand?.third?.first().orEmpty(), domain, why)
        if (authFailed(f.authResults)) return find(BODY_AUTH)
        if (own) return null
        if (brand != null) {
            val elsewhere = linkHosts(html, f.text).any { h -> brand.third.none { endsWithDomain(h, it) } }
            if (elsewhere && (hasPhrase(body, THREATS) || hasPhrase(body, BODY_URGENCY))) return find(BODY_BRAND)
        }
        val greeting = Regex("(^|\\s)(hi|hello|dear|hey|attention|attn|greetings)[ ,:]+(\\S+@\\S+)")
            .find(body.take(400))?.groupValues?.get(3)?.trim(',', '.', ':', '!')
        return if (greeting != null && f.ownAddresses.any { it.trim().lowercase() == greeting }) find(BODY_GREETING) else null
    }

    /** True for a prize draw, lottery or competition subject. */
    fun prize(subject: String?): Boolean = hasPhrase(clean(subject), PRIZE_WORDS)

    /** True for an account digest or activity-summary subject. */
    fun digest(subject: String?): Boolean = hasPhrase(clean(subject), DIGEST_WORDS)

    /** "⚠ Possible scam · sent from mail-x.com, not paypal.com" / "From PayPal's real address · log in at paypal.com yourself…". */
    fun line(f: MailScamFind): String = when {
        f.kind == MailScamKind.SCAM && f.why != null -> {
            val from = "sent from ${f.fromDomain ?: "an unknown address"}"
            "⚠ Possible scam · " + when (f.why) {
                BODY_GREETING -> "greets you by your email address, $from"
                BODY_AUTH -> "failed the sender check" + (if (f.brand.isNotEmpty()) ", says ${f.brand}" else "") + ", $from"
                else -> "says ${f.brand}, $from"
            }
        }
        f.kind == MailScamKind.SCAM -> "⚠ Possible scam · sent from ${f.fromDomain ?: "an unknown address"}, not ${f.realDomain}"
        else -> "From ${f.brand}'s real address · log in at ${f.realDomain} yourself, not through the email's link"
    }

    /**
     * What the server writes on the card's `mail_item` ([MailItemFields.WARNING]): the line for a scam or a real company's
     * warning sorted by these rules, else "".
     */
    fun itemWarning(reason: MailSortReason?, fromAddress: String?, fromName: String?, subject: String?, bodyLine: String? = null): String {
        // A real company's line only where the sort kept it as one (its marketing sorts as usual).
        if (reason != MailSortReason.SCAM && reason != MailSortReason.BRAND_NOTICE) return ""
        val headers = check(fromAddress, fromName, subject)
        if (reason == MailSortReason.BRAND_NOTICE) return headers?.let(::line).orEmpty()
        // A scam: the headers' line, else what the server's body check said ([sortedBodyLine]), else a plain warning
        // (a scam found in the body before a restart, its line not kept).
        if (headers?.kind == MailScamKind.SCAM) return line(headers)
        return bodyLine?.takeIf { scamLine(it) } ?: "⚠ Possible scam · sent from ${MailSortRules.domain(fromAddress) ?: "an unknown address"}"
    }

    /** How many emails' bodies the server reads per account per check for [sortedBodyLine] (newest first). */
    const val BODY_PER_CHECK = 10

    /**
     * Phishing in the body, slice 2: whether the server reads this sorted email's body when it sorts mail. Only mail in
     * Needs you, and never his correspondents' ([MailSortReason.KNOWN_PERSON]), his own, public bodies', account
     * security mail or one already a scam by its headers.
     */
    fun bodyDue(s: MailSorted): Boolean = s.sort == MailSort.NEEDS_YOU && s.reason !in BODY_SKIPS

    private val BODY_SKIPS = setOf(
        MailSortReason.KNOWN_PERSON, MailSortReason.OWN, MailSortReason.PUBLIC_BODY, MailSortReason.SECURITY,
        MailSortReason.SECURITY_CODE, MailSortReason.SCAM,
    )

    /**
     * Phishing in the body, slice 2: the server's body check of one sorted email ([bodyDue]) read whole from the
     * provider: [bodyCheck] over its text, HTML and `Authentication-Results`, with the addresses it was sent to and the
     * household's own ([own]) for the greeting. The scam's line ("⚠ Possible scam · says Microsoft, sent from
     * formlab.it"), or null when it isn't one. The body is never kept.
     */
    fun sortedBodyLine(fromAddress: String?, fromName: String?, subject: String?, facts: MailMessageFacts, own: Collection<String>): String? {
        val sentTo = (facts.to + facts.cc).mapNotNull { it.address }
        val f = bodyCheck(MailBodyFacts(fromAddress, fromName, subject, facts.text, facts.html, (sentTo + own).distinct(), facts.authResults))
        return f?.takeIf { it.kind == MailScamKind.SCAM }?.let(::line)
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
