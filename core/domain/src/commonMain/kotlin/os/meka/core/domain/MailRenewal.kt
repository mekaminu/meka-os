package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/*
 * Promises, receipts and renewals from email, slice 1 (build plan; non-AI, pure): **the renewals radar fills itself,
 * on his tap**. When a connected account receives an email saying a subscription, membership or policy is about to renew
 * — "Your Netflix membership renews on 3 Nov for £10.99", "Your car insurance is due for renewal on 12/12/2026", "Your
 * Spotify Premium plan will auto-renew on Friday" — the server reads the day, the price and who it's from and publishes
 * one *find* (`mail_find`, kind RENEWAL). Both apps show the finds in Needs you as **Renewals from your mail**, each with
 * **Add** (it goes on Lists → Renewals with that day, price, kind and repeat; Undo takes it off) and **Not needed**
 * (gone on both apps; Undo brings it back). Nothing reaches the radar without that tap.
 *
 * Every word of an email is someone else's (ADR-006): a find only proposes a reminder he can clear; nothing is paid,
 * cancelled, sent or opened because of it, and no link is ever taken from the email. Read from the subject and the
 * provider's preview only, never the body. An email saying it has already renewed, was cancelled or won't renew, or
 * a refund, proposes nothing; a renewal day before the email or more than [MailRenewalRules.MAX_DAYS] after it is
 * ignored. Merged with what he typed: a find whose company is already on the radar near that day is not shown.
 */

/** A renewal read from an email: the day it renews, the price if it says, who from, what kind and how often. */
data class MailRenewal(
    val renewDay: Long,
    val pence: Long?,
    val merchant: String?,
    val kind: ObligationKind,
    val repeat: RenewalRepeat,
)

/** What a find became: still to answer, added to the radar, or not needed. */
enum class MailFindState { OPEN, ADDED, DISMISSED }

/** One find as the apps read it from the synced `mail_find` entity. */
data class MailFind(
    val id: String,
    /** "RENEWAL", "PROMISE" (slice 2: [MailPromiseRules]) or "FOLLOW_UP" (Follow-up radar: [MailFollowUpRules]). */
    val kind: String,
    /** The account's label: "Personal", "Business", "Outlook". */
    val account: String,
    val merchant: String?,
    val renewDay: Long,
    val pence: Long?,
    val obligationKind: ObligationKind,
    val repeat: RenewalRepeat,
    val receivedDay: Long,
    /** The email's subject (someone else's words, shown as a quote). */
    val subject: String?,
    val state: MailFindState,
    /** When Add or Not needed was tapped (0 = never). */
    val decidedAtMs: Long,
    /** Slice 2, a promise: what they said they'd do, as Waiting for's title ("Signed contract", "A reply"); else null. */
    val what: String? = null,
    /** Slice 2, a promise: the email named the day ([renewDay] is then that day; else it is the default chase day). */
    val byStated: Boolean = false,
    /** Follow-up radar: when the server saw them answer (or him write again); null while unanswered. */
    val answeredAtMs: Long? = null,
    /** Follow-up radar: [MailFollowUpRules.ANSWERED_REPLIED] or [MailFollowUpRules.ANSWERED_WROTE_AGAIN]. */
    val answeredWhy: String? = null,
    /** Follow-up radar, slice 2: his chase from Time to chase, and what the server did with it ([MailChaseRules]). */
    val chase: ChaseMarks = ChaseMarks(),
    /** Slice 3: when Navilife added it on its own ([MailFindAutoRules]); kept through Undo; null = never. */
    val autoAtMs: Long? = null,
    /** School logistics ([SchoolMailRules]): a day off's last day (else null: [renewDay] alone). */
    val endDay: Long? = null,
    /** School logistics: [SchoolKind.wire] of the School line Add writes ("off" or "day"). */
    val schoolKind: String? = null,
    /** School logistics: the one child it names, else "" / null for all of them. */
    val who: String? = null,
)

/** The `mail_find` entity's fields (ADR-008 addendum 2026-10-15). */
object MailFindFields {
    // Written by the server, once.
    const val KIND = "kind"
    const val ACCOUNT = "account"
    const val MERCHANT = "merchant"
    const val DAY = "day"
    const val PENCE = "pence"
    const val OBLIGATION_KIND = "obligationKind"
    const val REPEAT = "repeat"
    const val RECEIVED_DAY = "receivedDay"
    const val SUBJECT = "subject"
    const val TRUST = "trust"
    /** Slice 2 (promises): Waiting for's title. */
    const val WHAT = "what"
    /** Slice 2 (promises): whether the email named the day. */
    const val BY_STATED = "byStated"
    /** Follow-up radar (server-written later): when they answered or he wrote again, and which ([MailFollowUpRules]). */
    const val ANSWERED_AT = "answeredAtMs"
    const val ANSWERED_WHY = "answeredWhy"
    /** Follow-up radar, slice 2: the chase, named as on `mail_item` (the apps write the first four, the server the rest). */
    const val CHASE_TEXT = MailItemFields.CHASE_TEXT
    const val CHASE_ASKED_AT = MailItemFields.CHASE_ASKED_AT
    const val CHASE_UNDO_AT = MailItemFields.CHASE_UNDO_AT
    const val CHASE_SEEN_AT = MailItemFields.CHASE_SEEN_AT
    const val CHASE_SENT_AT = MailItemFields.CHASE_SENT_AT
    const val CHASE_FAILED_AT = MailItemFields.CHASE_FAILED_AT
    const val CHASE_FAILED_WHY = MailItemFields.CHASE_FAILED_WHY
    // Written by the apps.
    const val STATE = "state"
    const val DECIDED_AT = "decidedAtMs"
    /** Slice 3: when a device added it on its own ([MailFindAutoRules]); never cleared, so an undone one stays a card. */
    const val AUTO_AT = "autoAtMs"
    /** School logistics from email, slice 1 (server-written, once): a day off's last day, the School kind, the child. */
    const val END_DAY = "endDay"
    const val SCHOOL_KIND = "schoolKind"
    const val WHO = "who"
}

data class MailFindRow(
    val id: String,
    /** "Netflix" (or "A renewal from your mail"). */
    val title: String,
    /** "Renews Tue 3 Nov · £10.99 a month · Subscription". */
    val line: String,
    /** "Personal mail · “Your membership renews soon”". */
    val from: String,
    val business: Boolean,
    val spoken: String,
    /** What Add says to a screen reader: "Add Netflix to Renewals", "Add Sam Taylor's promise to Waiting for". */
    val addSpoken: String = "Add $title to Renewals",
    /** What Not needed says: "Netflix isn't needed on Renewals". */
    val notNeededSpoken: String = "$title isn't needed on Renewals",
    /** Follow-up radar, slice 3 (follow-ups only, else null/empty): "Wait 3 days ▾", what it says, the chips' heading and chips. */
    val waitPill: String? = null,
    val waitSpoken: String? = null,
    val waitHeading: String? = null,
    val waits: List<WaitChoice> = emptyList(),
)

data class MailFindCard(
    /** "Renewals from your mail". */
    val title: String,
    /** "2 to add to Renewals · nothing goes on until you tap Add". */
    val headline: String,
    /** Soonest first, at most [MailRenewalRules.SHOWN]. */
    val rows: List<MailFindRow>,
    val moreLine: String?,
)

object MailRenewalRules {
    /** Emails this many days back are read each check. */
    const val WINDOW_DAYS = 45
    const val WINDOW_MS = WINDOW_DAYS * 86_400_000L
    /** A renewal further than this after the email is a misread (or a contract's end years away). */
    const val MAX_DAYS = 120
    /** A radar item for the same company due this close is the same renewal. */
    const val SAME_DAYS = 31
    const val KIND_RENEWAL = "RENEWAL"
    const val SHOWN = 5
    /** Provenance of the reminders an Add writes. */
    const val SOURCE = "meka:mail"

    const val TITLE = "Renewals from your mail"
    const val ADD = "Add"
    const val NOT_NEEDED = "Not needed"
    const val NOT_NEEDED_LINE = "Not needed · it won't be offered again"
    const val UNKNOWN = "Couldn't find that renewal"
    const val NO_NAME = "A renewal from your mail"

    /** What makes it a renewal at all. */
    private val RENEWAL = Regex(
        """\b(renew|renews|renewal|renewing|auto-?renew(?:s|al)?|due for renewal|membership (?:expires|ends)|subscription (?:expires|ends)|policy (?:expires|ends)|cover (?:expires|ends))\b""",
        RegexOption.IGNORE_CASE,
    )
    /** Already done, stopped, or money back: nothing to add. */
    private val NOT_COMING = Regex(
        """\b(has (?:been )?renewed|have renewed|was renewed|successfully renewed|renewal (?:confirmation|confirmed|complete|successful)|thanks? (?:you )?for renewing|(?:has been|was|is) cancell?ed|cancell?ation (?:confirmed|confirmation)|will not (?:auto-?)?renew|won'?t (?:auto-?)?renew|not be renewed|turned off auto-?renew|auto-?renew(?:al)? (?:is )?(?:off|turned off|disabled)|refund|refunded|refunding|credited)\b""",
        RegexOption.IGNORE_CASE,
    )
    /** The words a renewal day follows: "renews on 3 Nov", "renewal date: 12/12/2026", "expires on Friday". */
    private val DAY_WORDS = Regex(
        """\b(?:(?:will\s+)?(?:auto-?)?renews?(?:\s+automatically)?(?:\s+on)?|renewing\s+on|(?:renewal|expiry|end)\s+date(?:\s+is)?|due\s+for\s+renewal(?:\s+on)?|(?:expires|ends|runs\s+out)(?:\s+on)?)\b""",
        RegexOption.IGNORE_CASE,
    )
    /** "12/12/2026", "12/12/26", "12.12.2026": day first, as in the UK. */
    private val NUMERIC = Regex("""^\W{0,6}?(\d{1,2})[/.](\d{1,2})[/.](\d{2}|\d{4})(?!\d)""")
    /** Words a renewal price follows first ("for £10.99", "renewal price: £412", "premium of £380.20"). */
    private val PRICE_WORDS = Regex(
        """\b(for|at|price(?:\s+(?:is|of))?|premium(?:\s+(?:is|of|will be))?|cost(?:s)?|charged|charge of|renewal (?:price|quote|premium)(?:\s+is)?|amount(?:\s+of)?|of)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val AMOUNT = Regex("""(?:£|GBP\s?)\s?(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{2}))?(?!\d)""", RegexOption.IGNORE_CASE)
    private val INSURANCE = Regex("""\b(insurance|insurer|policy|cover|premium|breakdown)\b""", RegexOption.IGNORE_CASE)
    private val LICENCE = Regex("""\b(tv licen[cs]e|licen[cs]e|passport|domain)\b""", RegexOption.IGNORE_CASE)
    private val MONTHLY = Regex("""\b(monthly|a month|per month|each month|every month|/\s?month|/\s?mo|pcm)\b""", RegexOption.IGNORE_CASE)
    private val YEARLY = Regex("""\b(annual|annually|yearly|a year|per year|each year|every year|/\s?year|/\s?yr|12 months)\b""", RegexOption.IGNORE_CASE)

    /**
     * The renewal an email received on [receivedDay] announces, or null when its words don't say for sure: it must
     * name a renewal and a day after the email (the subject first, then the preview), and not say it already renewed,
     * was cancelled, won't renew or is a refund.
     */
    fun read(fromName: String?, fromAddress: String?, subject: String?, preview: String?, receivedDay: Long): MailRenewal? {
        val texts = listOfNotNull(subject, preview).filter { it.isNotBlank() }
        if (texts.isEmpty() || texts.none { RENEWAL.containsMatchIn(it) } || texts.any { NOT_COMING.containsMatchIn(it) }) return null
        val day = renewDay(texts, receivedDay) ?: return null
        val all = texts.joinToString(" ")
        val kind = when {
            INSURANCE.containsMatchIn(all) -> ObligationKind.INSURANCE
            LICENCE.containsMatchIn(all) -> ObligationKind.LICENCE
            else -> ObligationKind.SUBSCRIPTION
        }
        val repeat = when {
            MONTHLY.containsMatchIn(all) -> RenewalRepeat.MONTHLY
            YEARLY.containsMatchIn(all) -> RenewalRepeat.YEARLY
            kind == ObligationKind.SUBSCRIPTION -> RenewalRepeat.YEARLY.takeIf { day - receivedDay > 31 } ?: RenewalRepeat.MONTHLY
            else -> RenewalRepeat.YEARLY
        }
        return MailRenewal(
            renewDay = day,
            pence = price(texts) ?: MailReceiptRules.amount(subject, preview),
            merchant = MailReceiptRules.merchant(fromName, fromAddress),
            kind = kind,
            repeat = repeat,
        )
    }

    private fun renewDay(texts: List<String>, receivedDay: Long): Long? {
        for (t in texts) {
            for (m in DAY_WORDS.findAll(t)) {
                val after = t.substring(m.range.last + 1).take(40)
                val day = numeric(after) ?: MailWeekRules.dayOf(after, receivedDay)
                if (day != null && day >= receivedDay && day - receivedDay <= MAX_DAYS) return day
            }
        }
        return null
    }

    private fun numeric(text: String): Long? {
        val m = NUMERIC.find(text) ?: return null
        val d = m.groupValues[1].toInt()
        val mo = m.groupValues[2].toInt()
        val y = m.groupValues[3].toInt().let { if (it < 100) 2000 + it else it }
        if (mo !in 1..12 || d < 1 || d > CivilDate.lengthOfMonth(y, mo)) return null
        return CivilDate.toEpochDay(y, mo, d)
    }

    /** The first £ amount right after a price word, in the subject then the preview. */
    private fun price(texts: List<String>): Long? {
        for (t in texts) {
            for (w in PRICE_WORDS.findAll(t)) {
                val after = t.substring(w.range.last + 1).take(20)
                val m = AMOUNT.find(after) ?: continue
                if (after.substring(0, m.range.first).any { it.isLetterOrDigit() }) continue
                val pounds = m.groupValues[1].replace(",", "").toLongOrNull() ?: continue
                val pence = pounds * 100 + (m.groupValues[2].takeIf { it.isNotEmpty() }?.toLong() ?: 0L)
                if (pence in 1..MailReceiptRules.MAX_PENCE) return pence
            }
        }
        return null
    }

    /** One find per account, company and renewal day (else the email), so a reminder email lands on the same one. */
    fun findId(accountId: String, messageId: String, r: MailRenewal): String =
        "mf" + ActivityRules.fnv64("mailrenewal:$accountId:${r.merchant?.lowercase() ?: "msg:$messageId"}:${r.renewDay}")

    /** The radar item an Add writes: one per find, so Add on both apps lands on the same reminder. */
    fun obligationId(findId: String): String = "mo" + ActivityRules.fnv64("mailfind:$findId")

    /**
     * Whether the radar already has this renewal: an open item whose title or subject names the company, due within
     * [SAME_DAYS] of it. [obligations] are the household's `obligation` entities' fields.
     */
    fun alreadyOnRadar(merchant: String?, day: Long, obligations: Collection<Map<String, FieldValue>>, calendar: LocalCalendar): Boolean {
        val name = merchant?.lowercase()?.takeIf { it.length >= 3 } ?: return false
        return obligations.any { f ->
            val open = f[ActionableFields.LIFECYCLE]?.textOrNull?.let { enumOrNull<Lifecycle>(it) }?.isTerminal != true
            val named = listOfNotNull(f[ActionableFields.TITLE]?.textOrNull, f[ObligationFields.SUBJECT_LABEL]?.textOrNull)
                .any { name in it.lowercase() }
            val due = f[ActionableFields.DUE_AT]?.longOrNull?.let(calendar::epochDayOf)
            open && named && due != null && kotlin.math.abs(due - day) <= SAME_DAYS
        }
    }

    /** The find's fields, written once by the server. */
    fun serverFields(r: MailRenewal, account: String, subject: String?, receivedDay: Long): Map<String, FieldValue> = linkedMapOf(
        MailFindFields.KIND to KIND_RENEWAL.fv(),
        MailFindFields.ACCOUNT to account.fv(),
        MailFindFields.MERCHANT to (r.merchant?.fv() ?: FieldValue.Null),
        MailFindFields.DAY to r.renewDay.fv(),
        MailFindFields.PENCE to (r.pence?.fv() ?: FieldValue.Null),
        MailFindFields.OBLIGATION_KIND to r.kind.name.fv(),
        MailFindFields.REPEAT to r.repeat.name.fv(),
        MailFindFields.RECEIVED_DAY to receivedDay.fv(),
        MailFindFields.SUBJECT to (subject?.trim()?.take(MAX_SUBJECT)?.takeIf { it.isNotEmpty() }?.fv() ?: FieldValue.Null),
        MailFindFields.TRUST to Trust.UNTRUSTED_EXTERNAL.name.fv(),
    )

    const val MAX_SUBJECT = 120

    /** A find read from its fields; null when the server's half is missing or unreadable. */
    fun read(id: String, f: Map<String, FieldValue>): MailFind? {
        val kind = f[MailFindFields.KIND]?.textOrNull ?: return null
        val day = f[MailFindFields.DAY]?.longOrNull ?: return null
        return MailFind(
            id = id,
            kind = kind,
            account = f[MailFindFields.ACCOUNT]?.textOrNull ?: "Mail",
            merchant = f[MailFindFields.MERCHANT]?.textOrNull?.takeIf { it.isNotBlank() },
            renewDay = day,
            pence = f[MailFindFields.PENCE]?.longOrNull,
            obligationKind = f[MailFindFields.OBLIGATION_KIND]?.textOrNull?.let { enumOrNull<ObligationKind>(it) } ?: ObligationKind.SUBSCRIPTION,
            repeat = f[MailFindFields.REPEAT]?.textOrNull?.let { enumOrNull<RenewalRepeat>(it) } ?: RenewalRepeat.NONE,
            receivedDay = f[MailFindFields.RECEIVED_DAY]?.longOrNull ?: day,
            subject = f[MailFindFields.SUBJECT]?.textOrNull,
            state = f[MailFindFields.STATE]?.textOrNull?.let { enumOrNull<MailFindState>(it) } ?: MailFindState.OPEN,
            decidedAtMs = f[MailFindFields.DECIDED_AT]?.longOrNull ?: 0L,
            what = f[MailFindFields.WHAT]?.textOrNull?.takeIf { it.isNotBlank() },
            byStated = f[MailFindFields.BY_STATED]?.boolOrNull ?: false,
            answeredAtMs = f[MailFindFields.ANSWERED_AT]?.longOrNull,
            answeredWhy = f[MailFindFields.ANSWERED_WHY]?.textOrNull,
            chase = ChaseMarks(
                text = f[MailFindFields.CHASE_TEXT]?.textOrNull?.takeIf { it.isNotBlank() },
                askedAtMs = f[MailFindFields.CHASE_ASKED_AT]?.longOrNull,
                undoAtMs = f[MailFindFields.CHASE_UNDO_AT]?.longOrNull,
                seenAtMs = f[MailFindFields.CHASE_SEEN_AT]?.longOrNull,
                sentAtMs = f[MailFindFields.CHASE_SENT_AT]?.longOrNull,
                failedAtMs = f[MailFindFields.CHASE_FAILED_AT]?.longOrNull,
                failedWhy = f[MailFindFields.CHASE_FAILED_WHY]?.textOrNull,
            ),
            autoAtMs = f[MailFindFields.AUTO_AT]?.longOrNull?.takeIf { it > 0 },
            endDay = f[MailFindFields.END_DAY]?.longOrNull,
            schoolKind = f[MailFindFields.SCHOOL_KIND]?.textOrNull,
            who = f[MailFindFields.WHO]?.textOrNull,
        )
    }

    fun stateFields(state: MailFindState, nowMs: Long): Map<String, FieldValue> =
        mapOf(MailFindFields.STATE to state.name.fv(), MailFindFields.DECIDED_AT to nowMs.fv())

    /** "Netflix"; [NO_NAME] when no company was read. */
    fun title(f: MailFind): String = f.merchant ?: NO_NAME

    /** "£10.99 a month", "£412 a year", "£10.99"; null when no price was read. */
    fun priceLabel(pence: Long?, repeat: RenewalRepeat, day: Long): String? =
        pence?.let { RenewalRules.costLabel(it, RenewalRules.ruleFor(repeat, day)) }

    /** "Renews Tue 3 Nov · £10.99 a month · Subscription" ("Renews tomorrow", "Renews today"). */
    fun line(f: MailFind, today: Long): String = listOfNotNull(
        "Renews " + RenewalRules.dayWord(f.renewDay, today),
        priceLabel(f.pence, f.repeat, f.renewDay),
        RenewalRules.kindLabel(f.obligationKind),
    ).joinToString(" · ")

    /** "Personal mail · “Your membership renews soon”" (the subject when there is one). */
    fun fromLine(f: MailFind): String = "${f.account} mail" + (f.subject?.let { " · “$it”" } ?: "")

    fun headline(n: Int): String = (if (n == 1) "1 to add to Renewals" else "$n to add to Renewals") + " · nothing goes on until you tap Add"

    /** Slice 2: "2 to add to Renewals · 1 to Waiting for · nothing goes on until you tap Add" (either part alone too). */
    fun headline(renewals: Int, promises: Int): String = when {
        promises == 0 -> headline(renewals)
        renewals == 0 -> "$promises to add to Waiting for · nothing goes on until you tap Add"
        else -> "$renewals to add to Renewals · $promises to Waiting for · nothing goes on until you tap Add"
    }

    /** Follow-up radar: emails with no reply go to Waiting for too ("1 with no reply · nothing goes on until you tap Add"). */
    fun headline(renewals: Int, promises: Int, followUps: Int): String = when {
        followUps == 0 -> headline(renewals, promises)
        renewals == 0 && promises == 0 ->
            (if (followUps == 1) "1 email with no reply" else "$followUps emails with no reply") + " · nothing goes on Waiting for until you tap Add"
        else -> "${renewals + promises + followUps} to add · nothing goes on until you tap Add"
    }

    /** Slice 2: the card's title for what it holds. */
    fun title(renewals: Int, promises: Int): String = when {
        promises == 0 -> TITLE
        renewals == 0 -> MailPromiseRules.TITLE
        else -> "Renewals and promises from your mail"
    }

    /** Follow-up radar: "No reply yet" alone; mixed with the others, "To add from your mail". */
    fun title(renewals: Int, promises: Int, followUps: Int): String = when {
        followUps == 0 -> title(renewals, promises)
        renewals == 0 && promises == 0 -> MailFollowUpRules.TITLE
        else -> "To add from your mail"
    }

    /** School logistics: the school's finds join the card ("2 to add to School…" alone, "To add from your mail" mixed). */
    fun headline(renewals: Int, promises: Int, followUps: Int, school: Int, asks: Int = 0): String = when {
        school == 0 -> headline(renewals, promises, followUps)
        // Slice 2 of school logistics: an ask becomes a task, not a School line.
        renewals == 0 && promises == 0 && followUps == 0 && asks > 0 -> "$school from the school · nothing is added until you tap Add"
        renewals == 0 && promises == 0 && followUps == 0 -> "$school to add to School · nothing goes in until you tap Add"
        else -> "${renewals + promises + followUps + school} to add · nothing goes on until you tap Add"
    }

    fun title(renewals: Int, promises: Int, followUps: Int, school: Int): String = when {
        school == 0 -> title(renewals, promises, followUps)
        renewals == 0 && promises == 0 && followUps == 0 -> SchoolMailRules.TITLE
        else -> "To add from your mail"
    }

    /** The bar's line after Add: "On Renewals: Netflix · renews Tue 3 Nov". */
    fun addedLine(f: MailFind, today: Long): String = "On Renewals: ${title(f)} · renews ${RenewalRules.dayWord(f.renewDay, today)}"

    /**
     * The card: open renewal finds still to come (renewing today or later), not already on the radar (his own entry or
     * an earlier Add), and (slice 2) open promises still worth waiting for and not already on Waiting for
     * ([MailPromiseRules.showable]), and (Follow-up radar) his emails with no reply yet ([MailFollowUpRules.showable]),
     * soonest first; null when there are none.
     */
    fun card(
        finds: List<MailFind>, obligations: Collection<Map<String, FieldValue>>, nowMs: Long, cal: LocalCalendar,
        waiting: List<WaitingItem> = emptyList(), waits: List<FollowUpWait> = emptyList(),
        schoolItems: List<SchoolItem> = emptyList(),
    ): MailFindCard? {
        val today = cal.epochDayOf(nowMs)
        val renewals = finds.filter { f ->
            f.kind == KIND_RENEWAL && f.state == MailFindState.OPEN && f.renewDay >= today &&
                !alreadyOnRadar(f.merchant, f.renewDay, obligations, cal)
        }
        val promises = finds.filter { MailPromiseRules.showable(it, waiting, today) }
        val followUps = finds.filter { MailFollowUpRules.showable(it, waiting, today, waits) }
        val school = finds.filter { SchoolMailRules.showable(it, schoolItems, today) }
        val open = (renewals + promises + followUps + school).sortedWith(compareBy<MailFind> { it.renewDay }.thenBy { it.id })
        if (open.isEmpty()) return null
        val rows = open.take(SHOWN).map { f ->
            if (f.kind == MailPromiseRules.KIND_PROMISE) return@map MailPromiseRules.row(f, today)
            if (f.kind == MailFollowUpRules.KIND_FOLLOW_UP) return@map MailFollowUpRules.row(f, today, waits)
            if (f.kind == SchoolMailRules.KIND_SCHOOL) return@map SchoolMailRules.row(f, today)
            val title = title(f)
            val line = line(f, today)
            MailFindRow(
                id = f.id, title = title, line = line, from = fromLine(f),
                business = f.account == MailLabel.BUSINESS.label,
                spoken = "$title. $line. From your ${f.account} mail.",
            )
        }
        val more = open.size - rows.size
        return MailFindCard(
            title(renewals.size, promises.size, followUps.size, school.size), headline(renewals.size, promises.size, followUps.size, school.size, school.count(SchoolMailRules::isAsk)), rows,
            if (more > 0) "+ $more more · answer these first" else null,
        )
    }

    /**
     * The radar item Add writes: [MailFind.obligationKind] due at 09:00 on the renewal day in [calendar], the kind's lead
     * time, the repeat read from the email (none for a licence), the price, the account as its subject, and a note
     * saying where it came from. Navilife never pays or cancels; the note says so.
     */
    fun obligationFields(f: MailFind, nowMs: Long, calendar: LocalCalendar): Map<String, FieldValue> {
        val out = linkedMapOf<String, FieldValue>(
            ActionableFields.TITLE to title(f).take(Lists.MAX_TITLE).fv(),
            ObligationFields.KIND to f.obligationKind.name.fv(),
            ObligationFields.SUBJECT_LABEL to "${f.account} mail".fv(),
            ActionableFields.NOTES to (
                "From a renewal email in your ${f.account} mail on ${CivilDate.shortLabel(f.receivedDay)}, added on your tap. " +
                    "Navilife never pays or cancels: do that on the company's own site or app."
                ).fv(),
            ActionableFields.DUE_AT to calendar.toEpochMs(f.renewDay, Lists.DATE_MINUTE).fv(),
            ObligationFields.LEAD_DAYS to RenewalRules.defaultLeadDays(f.obligationKind).fv(),
            ObligationFields.RECURRENCE to (RenewalRules.ruleFor(f.repeat, f.renewDay)?.encode()?.fv() ?: FieldValue.Null),
            ActionableFields.LIFECYCLE to Lifecycle.ACTIVE.name.fv(),
            ActionableFields.CREATED_AT to nowMs.fv(),
            ActionableFields.VISIBILITY to Visibility.PRIVATE.name.fv(),
            ActionableFields.PROVENANCE_SOURCE to SOURCE.fv(),
            ActionableFields.PROVENANCE_TRUST to Trust.UNTRUSTED_EXTERNAL.name.fv(),
        )
        f.pence?.let {
            out[ObligationFields.COST_MINOR] = it.fv()
            out[ObligationFields.CURRENCY] = "GBP".fv()
        }
        return out
    }
}
