package os.meka.core.domain

/**
 * School emails first, slice 1b (Meka 2026-11-24, "the school email needs a priority as I miss what it says"), non-AI
 * and pure: the children's school's emails ([MailItem.school], written by the server for [MailSortReason.SCHOOL]) leave
 * Mail to answer for their own **From the school** box, first in Needs you on the Fold and the Mac, open until he
 * closes it, with a count; and each new one is a heads-up straight away ([notices]): through the governor like every
 * notice (quiet hours hold it), never held for Work mode or a digest. Nothing is sent, paid or followed: the box's rows
 * are the same email rows (Reply drafts for his tap, Done, Task), and the notice carries the sender and subject only.
 */
object SchoolNeedsRules {
    /** A school email notifies while it is this fresh; older ones (the 30-day catch-up) wait in the box only. */
    const val NOTIFY_WITHIN_MS = 12 * 60 * 60_000L
    /** How long a school email's heads-up stands before it is dropped unposted (phone off all evening). */
    const val STANDS_MS = 12 * 60 * 60_000L
    const val NOTICE_PREFIX = "schoolmail:"
    const val NOTICE_TITLE = "From the school"
    const val NOTICE_TAIL = "in Needs you"

    /**
     * Mail's card split in two: the school's rows (newest first, as the card had them) and everything else. Either is
     * null when it holds nothing. The rest keeps the card's own lines (snoozed, drafted, held); its count drops the
     * school's emails so the two boxes add up to the card's figure.
     */
    fun split(card: MailNeedsCard?): Pair<MailNeedsCard?, MailNeedsCard?> {
        if (card == null) return null to null
        val school = card.every.filter { it.school }
        if (school.isEmpty()) return null to card
        val rest = card.every.filterNot { it.school }
        val schoolCard = MailNeedsCard(
            title = NOTICE_TITLE, headline = headline(school.size), rows = school.take(MailNeedsRules.SHOWN),
            moreLine = more(school.size), snoozedLine = null, count = school.size, every = school,
        )
        val restCard = if (rest.isEmpty()) null else {
            // The card counts emails (linked ones too), so the school's emails come off with theirs.
            val count = (card.count - school.sumOf { 1 + it.linked.size }).coerceAtLeast(1)
            card.copy(headline = MailNeedsRules.headline(count), rows = rest.take(MailNeedsRules.SHOWN), every = rest,
                count = count, moreLine = more(rest.size))
        }
        return schoolCard to restCard
    }

    /** A Needs you subtitle with the school's emails leading: "1 from the school · 2 to decide"; as it was with none. */
    fun withSchool(subtitle: String, school: Int): String {
        if (school <= 0) return subtitle
        val lead = "$school from the school"
        return if (subtitle == NeedsYouLayoutRules.ALL_CLEAR || subtitle.isBlank()) lead else "$lead · $subtitle"
    }

    /** The school's half of [split] (for Swift, which reads no Pair). */
    fun school(card: MailNeedsCard?): MailNeedsCard? = split(card).first

    /** Everything but the school's ([split]'s second half). */
    fun rest(card: MailNeedsCard?): MailNeedsCard? = split(card).second

    /** "1 email from the school" · "3 emails from the school". */
    fun headline(n: Int): String = if (n == 1) "1 email from the school" else "$n emails from the school"

    private fun more(n: Int): String? = (n - MailNeedsRules.SHOWN).takeIf { it > 0 }?.let { "+ $it more · newest shown first" }

    /** The closed box's peek: the newest subjects, "Christmas fair, Trip letter + 1 more". */
    fun peek(card: MailNeedsCard?): String =
        NeedsYouGroupRules.names(card?.every.orEmpty().map { it.subject.trim() }.filter { it.isNotEmpty() && it != "(no subject)" }.distinct(), 2)

    /**
     * A heads-up for each school email still waiting in Needs you that came in the last [NOTIFY_WITHIN_MS]: "From the
     * school" · "Christmas fair · Oakfield Primary · in Needs you", posted once (its key is the email's), opening Needs
     * you. Its words are the sender's name and subject only (ADR-006): nothing in it is acted on.
     */
    fun notices(mail: List<MailItem>, nowMs: Long): List<Notice> = mail
        .filter { it.school && MailNeedsRules.showing(it, nowMs) && nowMs - it.receivedAtMs in 0 until NOTIFY_WITHIN_MS }
        .sortedBy { it.receivedAtMs }
        .map { i ->
            val subject = i.subject.trim().takeIf { it.isNotEmpty() && it != "(no subject)" }
            Notice(
                key = "$NOTICE_PREFIX${i.id}", source = NoticeSource.SCHOOL_MAIL, tier = NoticeTier.HEADS_UP,
                title = NOTICE_TITLE,
                text = listOfNotNull(subject, i.from.trim().takeIf { it.isNotEmpty() }, NOTICE_TAIL).joinToString(" · "),
                atMs = i.receivedAtMs, target = NoticeTarget.NEEDS_YOU, expiresAtMs = i.receivedAtMs + STANDS_MS,
            )
        }
}
