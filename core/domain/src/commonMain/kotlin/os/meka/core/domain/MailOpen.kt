package os.meka.core.domain

/*
 * "Archived for you" shows which emails, slice 4 (Meka, 2026-10-15 20:29: "…Open in Gmail/Outlook"): the preview's
 * **Open in Gmail** / **Open in Outlook**, so he can read the whole email in its own app before deciding. Nothing new is
 * stored or synced: the link is made at the moment he taps, from what the tidy already holds (sender's domain, subject,
 * day, where it went) and the account list the server gives the apps (the same list Mail shows). Gmail opens the right
 * account by its address (Google's `authuser`) and searches for the email everywhere (`in:anywhere`, so Trash and Spam
 * too); Outlook on the web opens the folder the email is in now (Archive, Deleted Items, Junk Email or the Inbox), with
 * the subject to look for: Outlook has no stable link that searches. The provider's message ids never reach the apps
 * (ADR-008 addendum 2026-10-16). The link is handed to the browser and never logged or kept. Non-AI, pure, unit-tested.
 */

/** The preview's button: what it says, where it goes, and the quiet line under it. */
data class MailOpenLink(
    /** "Open in Gmail" / "Open in Outlook". */
    val label: String,
    val url: String,
    /** "Opens your Personal Gmail and searches for it" / "Opens Outlook's Archive · look for “…” from Mon 12 Oct". */
    val hint: String,
)

/** Where a tidied email is now. */
enum class MailPlace(val outlookFolder: String, val outlookName: String) {
    INBOX("inbox", "Inbox"),
    ARCHIVE("archive", "Archive"),
    TRASH("deleteditems", "Deleted Items"),
    JUNK("junkemail", "Junk Email"),
}

object MailOpenRules {
    const val GMAIL_BASE = "https://mail.google.com/mail/u/"
    const val OUTLOOK_BASE = "https://outlook.live.com/mail/0/"
    /** The subject words searched for (Gmail): enough to find it, short enough for any link. */
    const val MAX_SUBJECT = 80
    const val NO_ACCOUNT = "Couldn't tell which account this email is in · open it from Mail"
    const val OFFLINE = "Couldn't reach your server to find the account · try again"

    /** The account the tidy belongs to: by its key; an older tidy without one, the first account with its label. */
    fun account(b: MailTidyBatch, accounts: List<MailAccountFacts>): MailAccountFacts? {
        val mail = accounts.filter { MailProviders.isMail(it.provider) }
        b.accountKey?.let { k -> return mail.firstOrNull { MailAccountRules.key(it.provider, it.email) == k } }
        return mail.firstOrNull { MailLabel.of(it.label)?.label == b.account }
    }

    /**
     * The button's label before the account list is known (or when it can't be found): the provider when it can be
     * told, else a plain "Open in your mail".
     */
    fun label(b: MailTidyBatch, accounts: List<MailAccountFacts>?): String {
        val p = accounts?.let { account(b, it) }?.provider ?: if (b.account == MailLabel.OUTLOOK.label) MailProviders.OUTLOOK else null
        return when (p) {
            MailProviders.GMAIL -> "Open in Gmail"
            MailProviders.OUTLOOK -> "Open in Outlook"
            else -> "Open in your mail"
        }
    }

    /** Where [e] is now: put back (one email, or the whole tidy) or where the tidy moved it. */
    fun place(b: MailTidyBatch, e: MailTidiedEmail): MailPlace {
        val junkReview = e.tidy == MailTidy.JUNK_TRASH || e.tidy == MailTidy.RESCUE
        val back = b.undoneAtMs != null || b.emailDone.any {
            it.key == e.key && (it.action == MailTidiedAction.PUT_BACK || it.action == MailTidiedAction.KEEP)
        }
        if (back) return if (junkReview) MailPlace.JUNK else MailPlace.INBOX
        return when (e.tidy) {
            MailTidy.TRASH, MailTidy.JUNK_TRASH -> MailPlace.TRASH
            MailTidy.RESCUE, MailTidy.KEEP -> MailPlace.INBOX
            MailTidy.NEWSLETTER, MailTidy.RECEIPT, MailTidy.ARCHIVE -> MailPlace.ARCHIVE
        }
    }

    private fun ymd(day: Long): String = CivilDate.fromEpochDay(day).let { "${it.year}/${it.month}/${it.day}" }

    /** Words only: no quotes, brackets or ellipsis that would change Gmail's search. */
    private fun words(s: String): String =
        s.replace('…', ' ').map { if (it == '"' || it == '(' || it == ')' || it == '{' || it == '}' || it.code < 0x20) ' ' else it }
            .joinToString("").replace(Regex("""\s+"""), " ").trim().take(MAX_SUBJECT).trim()

    /**
     * Gmail's search for [e]: everywhere, from its domain (else its name), its subject as a phrase, and the day it came
     * with a day either side (Gmail's dates are its own time zone).
     */
    fun gmailQuery(e: MailTidiedEmail, cal: LocalCalendar): String =
        query(e.domain?.let { "from:$it" }, e.from, e.subject, e.receivedAtMs, cal)

    private fun query(fromTerm: String?, fromName: String, subject: String, atMs: Long, cal: LocalCalendar): String {
        val day = cal.epochDayOf(atMs)
        val from = fromTerm ?: words(fromName).takeIf { it.isNotEmpty() }?.let { "from:\"$it\"" }
        val s = words(subject).takeIf { it.isNotEmpty() && subject != MailTidiedRules.NO_SUBJECT }?.let { "subject:\"$it\"" }
        return listOfNotNull("in:anywhere", from, s, "after:" + ymd(day - 1), "before:" + ymd(day + 2)).joinToString(" ")
    }

    // ---- Read the full email, slice 3 (2026-10-20): Open in Gmail/Outlook from a "From your mail" card's reader ----

    /** A plain address, safe in Gmail's search (letters, digits and . _ % + - @ only), else null. */
    private fun address(a: String?): String? =
        a?.trim()?.lowercase()?.takeIf { it.length in 3..254 && it.count { c -> c == '@' } == 1 && it.all { c -> c.isLetterOrDigit() || c in "._%+-@" } }

    /**
     * The reader's **Open in Gmail / Open in Outlook** for a "From your mail" card's email: its account by the card's
     * label ("Personal", "Business", "Outlook"), Gmail searched by the sender's address when the reader read it (else
     * the name), the subject and the day; Outlook's Inbox, or its Archive once Done. Null when the account can't be told.
     */
    fun itemLink(
        account: String, from: String, fromAddress: String?, subject: String, receivedAtMs: Long, done: Boolean,
        accounts: List<MailAccountFacts>, cal: LocalCalendar,
    ): MailOpenLink? {
        val acct = accounts.filter { MailProviders.isMail(it.provider) }.firstOrNull { MailLabel.of(it.label)?.label == account }
        val provider = acct?.provider ?: if (account == MailLabel.OUTLOOK.label) MailProviders.OUTLOOK else return null
        val place = if (done) MailPlace.ARCHIVE else MailPlace.INBOX
        return when (provider) {
            MailProviders.GMAIL -> {
                val mine = acct?.email?.trim()?.takeIf { it.contains('@') } ?: return null
                MailOpenLink(
                    label = "Open in Gmail",
                    url = GMAIL_BASE + "?authuser=" + LeavingRules.percentEncode(mine) + "#search/" +
                        LeavingRules.percentEncode(query(address(fromAddress)?.let { "from:$it" }, from, subject, receivedAtMs, cal)),
                    hint = "Opens your $account Gmail and searches for it · it's in ${gmailPlace(place)}",
                )
            }
            MailProviders.OUTLOOK -> {
                val s = if (subject.isBlank() || subject == MailTidiedRules.NO_SUBJECT) "the email" else "“$subject”"
                MailOpenLink(
                    label = "Open in Outlook",
                    url = OUTLOOK_BASE + place.outlookFolder,
                    hint = "Opens Outlook's ${place.outlookName} · look for $s from $from, ${CivilDate.shortLabel(cal.epochDayOf(receivedAtMs))}",
                )
            }
            else -> null
        }
    }

    /**
     * The link for [key] in [b], or null when the email or its account can't be found (the caller says [NO_ACCOUNT]).
     * Gmail needs the account's address to open the right one; Outlook on the web opens the signed-in account.
     */
    fun link(b: MailTidyBatch, key: String, accounts: List<MailAccountFacts>, cal: LocalCalendar): MailOpenLink? {
        val e = b.emails.firstOrNull { it.key == key } ?: return null
        val acct = account(b, accounts)
        val provider = acct?.provider ?: if (b.account == MailLabel.OUTLOOK.label) MailProviders.OUTLOOK else return null
        val place = place(b, e)
        val day = CivilDate.shortLabel(cal.epochDayOf(e.receivedAtMs))
        return when (provider) {
            MailProviders.GMAIL -> {
                val address = acct?.email?.trim()?.takeIf { it.contains('@') } ?: return null
                MailOpenLink(
                    label = "Open in Gmail",
                    url = GMAIL_BASE + "?authuser=" + LeavingRules.percentEncode(address) + "#search/" +
                        LeavingRules.percentEncode(gmailQuery(e, cal)),
                    hint = "Opens your ${b.account} Gmail and searches for it · it's in ${gmailPlace(place)}",
                )
            }
            MailProviders.OUTLOOK -> {
                val subject = if (e.subject == MailTidiedRules.NO_SUBJECT) "the email" else "“${e.subject}”"
                MailOpenLink(
                    label = "Open in Outlook",
                    url = OUTLOOK_BASE + place.outlookFolder,
                    hint = "Opens Outlook's ${place.outlookName} · look for $subject from ${e.from}, $day",
                )
            }
            else -> null
        }
    }

    private fun gmailPlace(p: MailPlace): String = when (p) {
        MailPlace.INBOX -> "the inbox"
        MailPlace.ARCHIVE -> "All Mail"
        MailPlace.TRASH -> "Trash"
        MailPlace.JUNK -> "Spam"
    }
}

/** What a tap on Open in Gmail/Outlook gets: the link, or why there isn't one. */
data class MailOpenResult(val link: MailOpenLink?, val problem: String?)
