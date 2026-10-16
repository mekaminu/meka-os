package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/*
 * Life audit, slice 1 (build plan; non-AI, pure): **your subscriptions from 60 days of mail, and what they cost a
 * month**. The server already keeps 60 days of each connected account's headers (sender, subject, the provider's short
 * preview; never a body). From them it picks out the payments that repeat: the same company charging about the same
 * amount about a month apart ("Your Streamco receipt · £10.99" on 8 Sep and 8 Oct), or one payment whose words say it's a
 * monthly or yearly plan ("Your monthly membership payment of £24.00"). Each becomes one *find* (`mail_find`, kind
 * SUBSCRIPTION) per account and company, with the last payment's day and amount, how many payments were seen and the
 * next one expected. Both apps show them in Needs you's mail finds card ("Streamco · £10.99 a month · paid twice since
 * 8 Sep · next about Sun 8 Nov"), the headline adding up what they cost a month ("3 subscriptions · about £34.97 a
 * month"), each with **Add** (onto Lists → Renewals, monthly or yearly, due the next payment day; Undo) and **Not
 * needed** (gone for good: the server keeps rewriting its own fields, never the answer).
 *
 * Read-only (the audit's rule): nothing in the mail changes, nothing is paid or cancelled and no link is ever taken.
 * Every word is someone else's (ADR-006): a find only proposes a reminder he can clear, and is never added on its own
 * (Renewals and promises from mail's trust switch leaves these alone). A company already on the radar near the next
 * payment, or with a renewal find of its own, isn't offered twice; one that wrote to say it was cancelled, or whose
 * monthly payment has stopped coming, isn't offered at all.
 */

/** One payment read from an email: who was paid, how much (pence), the day, and what its words said about repeating. */
data class AuditPayment(
    val merchant: String,
    val pence: Long,
    val day: Long,
    /** The words name a plan or membership ("subscription", "membership", "your plan"). */
    val planWords: Boolean,
    /** What the words say about how often: [RenewalRepeat.MONTHLY], [RenewalRepeat.YEARLY] or NONE. */
    val stated: RenewalRepeat,
    /** The email's subject, for the find's quote. */
    val subject: String?,
    /** An order from a shop ("Your order", "order confirmation"): only a repeating one counts. */
    val order: Boolean,
)

/** A subscription the payments show. */
data class AuditSubscription(
    val merchant: String,
    /** The last payment's amount. */
    val pence: Long,
    val repeat: RenewalRepeat,
    val lastDay: Long,
    val nextDay: Long,
    /** Payments seen in the window. */
    val seen: Int,
    val subject: String?,
)

object LifeAuditRules {
    const val KIND_SUBSCRIPTION = "SUBSCRIPTION"
    /** The audit looks this far back (what the server keeps). */
    const val WINDOW_DAYS = 60
    const val WINDOW_MS = WINDOW_DAYS * 86_400_000L
    /** Two payments this many days apart are a month apart. */
    val MONTH_GAP = 26..35
    /** A monthly payment this many days late has stopped (cancelled, or moved card): not offered. */
    const val LATE_DAYS = 7
    /** Two amounts this close (pence, or [SAME_PERCENT] of the larger) are the same price. */
    const val SAME_PENCE = 50L
    const val SAME_PERCENT = 5

    const val TITLE = "Subscriptions in your mail"
    const val NO_NAME = "A subscription"

    /** What makes it a payment at all. */
    private val PAYMENT = Regex(
        """\b(receipt|invoice|payment|paid|charged|billed|billing|your bill|direct debit|subscription|membership|renewed)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val PLAN = Regex(
        """\b(subscription|subscribed|membership|member|your plan|premium plan|monthly plan|annual plan|plan renewed|has renewed|renewed)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val ORDER = Regex("""\b(your order|order confirm(?:ation|ed)|order #|order no|order number|has shipped|dispatched)\b""", RegexOption.IGNORE_CASE)
    /** Stopped, failed or money back: not a live subscription. */
    private val STOPPED = Regex(
        """\b(cancell?ed|cancell?ation|will not (?:auto-?)?renew|won'?t (?:auto-?)?renew|not be renewed|has ended|subscription ended|membership ended|refund|refunded|credited|payment failed|failed payment|declined|unsuccessful)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val MONTHLY = Regex("""\b(monthly|a month|per month|each month|every month|/\s?month|/\s?mo|pcm)\b""", RegexOption.IGNORE_CASE)
    private val YEARLY = Regex("""\b(annual|annually|yearly|a year|per year|each year|every year|/\s?year|/\s?yr|12 months)\b""", RegexOption.IGNORE_CASE)

    /**
     * The payment an email shows, or null: it must read as a payment with one sure £ amount ([MailReceiptRules.amount])
     * and a company, and must not say it was cancelled, failed or refunded, nor announce a renewal still to come.
     */
    fun payment(fromName: String?, fromAddress: String?, subject: String?, preview: String?, day: Long): AuditPayment? {
        val texts = listOfNotNull(subject, preview).filter { it.isNotBlank() }
        if (texts.isEmpty() || texts.none { PAYMENT.containsMatchIn(it) } || texts.any { STOPPED.containsMatchIn(it) }) return null
        // A notice of a renewal still to come is Renewals from your mail's, not a payment.
        if (MailRenewalRules.read(fromName, fromAddress, subject, preview, day) != null) return null
        val pence = MailReceiptRules.amount(subject, preview) ?: return null
        val merchant = MailReceiptRules.merchant(fromName, fromAddress) ?: return null
        val all = texts.joinToString(" ")
        val stated = when {
            MONTHLY.containsMatchIn(all) -> RenewalRepeat.MONTHLY
            YEARLY.containsMatchIn(all) -> RenewalRepeat.YEARLY
            else -> RenewalRepeat.NONE
        }
        return AuditPayment(merchant, pence, day, PLAN.containsMatchIn(all), stated, subject?.trim()?.takeIf { it.isNotEmpty() }, ORDER.containsMatchIn(all))
    }

    /** Whether an email says this company's subscription stopped (a cancellation or a failed payment). */
    fun stopped(subject: String?, preview: String?): Boolean =
        listOfNotNull(subject, preview).any { STOPPED.containsMatchIn(it) }

    fun samePrice(a: Long, b: Long): Boolean = kotlin.math.abs(a - b) <= maxOf(SAME_PENCE, maxOf(a, b) * SAME_PERCENT / 100)

    /**
     * The subscriptions [payments] show as of [today], one per company (by name, any case): a monthly one from two
     * payments of the same price a month apart (or one whose words say monthly plan), a yearly one from a payment whose
     * words say a yearly plan; next = a month (a year) after the last, rolled on to today or later while it is no more
     * than [LATE_DAYS] late. [stops] are the companies (lowercase) that wrote to say it stopped, with the day of their
     * newest such email: none of theirs unless a payment came after it.
     */
    fun subscriptions(payments: List<AuditPayment>, today: Long, stops: Map<String, Long> = emptyMap()): List<AuditSubscription> =
        payments.groupBy { it.merchant.lowercase() }.mapNotNull { (key, ps) ->
            val sorted = ps.sortedBy { it.day }.distinctBy { it.day }
            val last = sorted.last()
            if ((stops[key] ?: Long.MIN_VALUE) >= last.day) return@mapNotNull null
            val monthlyPair = sorted.zipWithNext().any { (a, b) -> (b.day - a.day).toInt() in MONTH_GAP && samePrice(a.pence, b.pence) }
            val plan = sorted.any { it.planWords }
            val stated = sorted.lastOrNull { it.stated != RenewalRepeat.NONE }?.stated ?: RenewalRepeat.NONE
            val repeat = when {
                // A shop's repeating orders count only with two at the same price a month apart.
                monthlyPair && (plan || sorted.none { it.order }) -> RenewalRepeat.MONTHLY
                plan && !last.order && stated == RenewalRepeat.MONTHLY -> RenewalRepeat.MONTHLY
                plan && !last.order && stated == RenewalRepeat.YEARLY -> RenewalRepeat.YEARLY
                else -> return@mapNotNull null
            }
            val months = if (repeat == RenewalRepeat.YEARLY) 12 else 1
            var next = MailWarrantyRules.plusMonths(last.day, months)
            if (next < today) {
                if (repeat == RenewalRepeat.YEARLY || today - next > LATE_DAYS) return@mapNotNull null
                // A few days late (a weekend, a slow receipt): expect it on the next round.
                while (next < today) next = MailWarrantyRules.plusMonths(next, months)
            }
            AuditSubscription(last.merchant, last.pence, repeat, last.day, next, sorted.size, last.subject)
        }.sortedBy { it.merchant.lowercase() }

    /** What [s] costs a month (a yearly one's twelfth, rounded). */
    fun monthlyPence(pence: Long, repeat: RenewalRepeat): Long =
        if (repeat == RenewalRepeat.YEARLY) (pence + 6) / 12 else pence

    /** One find per account and company, so the next payment updates the same find (and his answer stands). */
    fun findId(accountId: String, merchant: String): String =
        "mf" + ActivityRules.fnv64("mailsub:$accountId:${merchant.lowercase()}")

    /** The find's fields, rewritten by the server each check (unchanged ones write nothing; never the answer). */
    fun serverFields(s: AuditSubscription, account: String): Map<String, FieldValue> = linkedMapOf(
        MailFindFields.KIND to KIND_SUBSCRIPTION.fv(),
        MailFindFields.ACCOUNT to account.fv(),
        MailFindFields.MERCHANT to s.merchant.fv(),
        MailFindFields.DAY to s.nextDay.fv(),
        MailFindFields.PENCE to s.pence.fv(),
        MailFindFields.OBLIGATION_KIND to ObligationKind.SUBSCRIPTION.name.fv(),
        MailFindFields.REPEAT to s.repeat.name.fv(),
        MailFindFields.RECEIVED_DAY to s.lastDay.fv(),
        MailFindFields.SUBJECT to (s.subject?.take(MailRenewalRules.MAX_SUBJECT)?.fv() ?: FieldValue.Null),
        MailFindFields.TRUST to Trust.UNTRUSTED_EXTERNAL.name.fv(),
        MailFindFields.SEEN to s.seen.toLong().fv(),
    )

    fun isSubscription(f: MailFind): Boolean = f.kind == KIND_SUBSCRIPTION

    /**
     * Whether [f] is an open subscription to show: unanswered, its next payment today or later, not on the radar near
     * that day, and no renewal find of the same company that he hasn't turned down ([finds]).
     */
    fun showable(f: MailFind, finds: List<MailFind>, obligations: Collection<Map<String, FieldValue>>, today: Long, cal: LocalCalendar): Boolean {
        if (!isSubscription(f) || f.state != MailFindState.OPEN || f.renewDay < today) return false
        if (MailRenewalRules.alreadyOnRadar(f.merchant, f.renewDay, obligations, cal)) return false
        val name = f.merchant?.lowercase() ?: return true
        return finds.none { it.kind == MailRenewalRules.KIND_RENEWAL && it.state != MailFindState.DISMISSED && it.merchant?.lowercase() == name }
    }

    fun title(f: MailFind): String = f.merchant ?: NO_NAME

    private fun times(n: Int): String = when (n) { 1 -> "once"; 2 -> "twice"; else -> "$n times" }

    /** "£10.99 a month · paid twice since Tue 8 Sep · next about Sun 8 Nov" (one payment: "paid Thu 8 Oct"). */
    fun line(f: MailFind, today: Long): String {
        val price = MailRenewalRules.priceLabel(f.pence, f.repeat, f.renewDay)
        val seen = f.seen ?: 1
        val paid = if (seen > 1) "paid ${times(seen)} in 60 days" else "paid ${CivilDate.shortLabel(f.receivedDay)}"
        return listOfNotNull(price, paid, "next about " + RenewalRules.dayWord(f.renewDay, today)).joinToString(" · ")
    }

    fun row(f: MailFind, today: Long): MailFindRow {
        val title = title(f)
        val line = line(f, today)
        return MailFindRow(
            id = f.id, title = title, line = line, from = MailRenewalRules.fromLine(f),
            business = f.account == MailLabel.BUSINESS.label,
            spoken = "$title. Subscription. $line. From your ${f.account} mail.",
            addSpoken = "Add $title to Renewals",
            notNeededSpoken = "$title isn't needed on Renewals",
        )
    }

    /** "about £34.97 a month" for the subscriptions shown. */
    fun monthlyLabel(subs: List<MailFind>): String =
        "about " + RenewalRules.formatPence(subs.sumOf { monthlyPence(it.pence ?: 0L, it.repeat) }) + " a month"

    /** "3 subscriptions · about £34.97 a month · nothing goes on Renewals until you tap Add". */
    fun headline(subs: List<MailFind>): String =
        (if (subs.size == 1) "1 subscription" else "${subs.size} subscriptions") + " · " + monthlyLabel(subs) +
            " · nothing goes on Renewals until you tap Add"

    /** The bar's line after Add: "On Renewals: Streamco · next payment about Sun 8 Nov". */
    fun addedLine(f: MailFind, today: Long): String =
        "On Renewals: ${title(f)} · next payment about ${RenewalRules.dayWord(f.renewDay, today)}"

    /** The radar item's note: where it came from, and that Navilife never pays or cancels. */
    fun notes(f: MailFind): String {
        val seen = f.seen ?: 1
        val paid = if (seen > 1) "${seen} payments in 60 days, the last on ${CivilDate.shortLabel(f.receivedDay)}" else "a payment on ${CivilDate.shortLabel(f.receivedDay)}"
        return "Found by the life audit in your ${f.account} mail ($paid), added on your tap. " +
            "Navilife never pays or cancels: do that on the company's own site or app."
    }
}
