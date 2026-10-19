package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/*
 * Money manager, slice 13c (build plan item 13: "business income/expenses tagged (business Gmail invoices + its bank
 * account)"): **the side hustle's money in his business email**. The server reads the emails of the last
 * [MoneyBusinessMail.WINDOW_DAYS] days in the mail account he labelled **Business** (sender, subject and the provider's
 * short preview; never the body, never a newsletter, promo or junk) and keeps the ones whose words say for sure that money
 * came to him ("You've received a payment of £300", "Acme paid invoice INV-12 · £300", "You've been paid £45") or that he
 * paid for something ("Your Canva receipt · £10.99", "Invoice INV-88 from Hostco · total £24.00"), with the amount. Each
 * becomes one `mail_find` (kind INVOICE, `direction` in/out), written once (ADR-008 addendum 2026-10-19, additive).
 *
 * Money → SIDE HUSTLE AND TAX then lists this tax year's under FROM YOUR BUSINESS EMAIL and checks each against his
 * statements (the same amount, the right way, a few days either side): "in your statements · counted" or, when the line
 * there isn't counted as the side hustle's yet, a **Business** chip that marks its payer (the existing Business mark,
 * Undo as before). One the statements should show by now and don't is lit ("not in your statements · paid another
 * way?"). Statements stay the truth: nothing from an email is added to the profit. Every word is someone else's
 * (ADR-006): read only, nothing is paid, filed or sent. Pure, non-AI, unit-tested.
 */

/** Money an email says moved: [direction] [MoneyBusinessMail.IN] (paid to him) or [MoneyBusinessMail.OUT] (a cost). */
data class MailInvoice(val direction: String, val pence: Long, val party: String?)

/** One email's money as FROM YOUR BUSINESS EMAIL shows it. */
data class MoneyBusinessMailRow(
    val id: String,
    /** "Acme · £300". */
    val title: String,
    /** "Paid to you · Fri 3 Oct · in your statements · counted". */
    val line: String,
    /** The email's subject, someone else's words (shown as a quote), or null. */
    val quote: String?,
    val matched: Boolean,
    /** Not in the statements when it should be by now. */
    val lit: Boolean,
    /** The matched statement line, when its payer isn't counted as business yet: the Business chip marks it. */
    val businessTxnId: String?,
    val spoken: String,
)

data class MoneyBusinessMailView(
    val head: String,
    /** "4 this tax year · in £600 · costs £35 · 3 in your statements". */
    val line: String,
    /** Newest first, at most [MoneyBusinessMail.SHOWN]. */
    val rows: List<MoneyBusinessMailRow>,
    val moreLine: String?,
    val foot: String,
) {
    /** Index access for Swift. */
    fun rowAt(i: Int): MoneyBusinessMailRow = rows[i]
}

object MoneyBusinessMail {
    const val KIND_INVOICE = "INVOICE"
    const val IN = "in"
    const val OUT = "out"
    /** The server reads this far back (it keeps 60 days of headers); finds stay once written. */
    const val WINDOW_DAYS = 60
    const val WINDOW_MS = WINDOW_DAYS * 86_400_000L
    /** A statement line this many days before the email, or [LATE_DAYS] after it, can be the same money. */
    const val EARLY_DAYS = 5
    const val LATE_DAYS = 10
    const val SHOWN = 6

    const val HEAD = "FROM YOUR BUSINESS EMAIL"
    const val CHIP = "Business"
    const val FOOT = "Read from your Business mail's subjects and previews. Only your statements count in the profit; Navilife never pays, files or replies from here."

    private val AMOUNT = Regex("""(?:£|GBP\s?)\s?(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{2}))?(?!\d)""", RegexOption.IGNORE_CASE)
    /** Money to him: a customer paid, a payment or payout reached him, a sale. */
    private val MONEY_IN = Regex(
        """\b(?:you(?:'ve|\s+have)?\s+received\s+(?:a\s+)?(?:new\s+)?(?:payment|£|money|GBP)|you(?:'ve|\s+have)\s+been\s+paid|you\s+got\s+paid|(?:has\s+)?paid\s+(?:your\s+|an?\s+|the\s+)?invoice|(?<!your\s)invoice\s+[A-Z0-9#][\w#/-]*\s+(?:has\s+been\s+|was\s+)?paid\b|sent\s+you\s+(?:a\s+payment|£|money|GBP)|payout\s+(?:of\s+(?:£|GBP\s?)\s?[\d,]+(?:\.\d{2})?\s+)?(?:is\s+on\s+its\s+way|has\s+been\s+sent|sent|initiated|paid)|you(?:'ve|\s+have)?\s+(?:made|got)\s+a\s+(?:new\s+)?sale|new\s+sale)""",
        RegexOption.IGNORE_CASE,
    )
    /** Words that put the money the other way round ("we've received your payment"): never money in. */
    private val HIS_PAYMENT = Regex(
        """\b(received\s+your\s+payment|thanks?\s+(?:you\s+)?for\s+your\s+(?:payment|order|purchase)|your\s+payment\s+(?:was|has\s+been)\s+(?:received|successful))\b""",
        RegexOption.IGNORE_CASE,
    )
    /** A cost: a receipt, an invoice or bill to him, an order, a charge. */
    private val COST = Regex(
        """\b(receipt|invoice|your\s+order|order\s+confirm(?:ation|ed)|payment\s+confirm(?:ation|ed)|you\s+paid|you've\s+paid|billed|charged|your\s+bill|subscription|renewed)\b""",
        RegexOption.IGNORE_CASE,
    )
    /** Not money moving: refunds, failures, quotes, reminders of one still to pay. */
    private val NOT_MONEY = Regex(
        """\b(refund|refunded|refunding|credit\s+note|failed|declined|unsuccessful|couldn'?t\s+(?:take|process)|quote|estimate|overdue|reminder|due\s+(?:on|by|date)|payment\s+due|cancell?ed)\b""",
        RegexOption.IGNORE_CASE,
    )
    /** "Acme Ltd paid invoice …", "Sam Taylor sent you £40": the payer, before the verb. */
    private val PAYER = Regex("""^\s*(.{2,40}?)\s+(?:has\s+)?(?:paid|sent\s+you)\b""", RegexOption.IGNORE_CASE)
    private val NOT_A_PAYER = Regex("""^(?:you|you've|you\s+have|we|we've|your|invoice|payment|a\s+payment|re|fwd?)\b""", RegexOption.IGNORE_CASE)

    /**
     * The money an email moved, or null when its words don't say so for sure: money in or a cost (money in first), with
     * one £ amount it names ([MailReceiptRules.amount]'s total first); never a refund, a failed payment, a quote, an
     * overdue reminder or a bill still to pay.
     */
    fun read(fromName: String?, fromAddress: String?, subject: String?, preview: String?): MailInvoice? {
        val texts = listOfNotNull(subject, preview).filter { it.isNotBlank() }
        if (texts.isEmpty() || texts.any { NOT_MONEY.containsMatchIn(it) }) return null
        val pence = MailReceiptRules.amount(subject, preview) ?: return null
        val sender = MailReceiptRules.merchant(fromName, fromAddress)
        val moneyIn = texts.any { MONEY_IN.containsMatchIn(it) } && texts.none { HIS_PAYMENT.containsMatchIn(it) }
        if (moneyIn) {
            val payer = texts.firstNotNullOfOrNull { t ->
                PAYER.find(t)?.groupValues?.get(1)?.trim()?.trim('"', '\'', ':', '-')?.takeIf { p ->
                    p.length >= 2 && p.any { it.isLetter() } && !NOT_A_PAYER.containsMatchIn(p) && AMOUNT.find(p) == null
                }
            }
            return MailInvoice(IN, pence, payer ?: sender)
        }
        if (texts.none { COST.containsMatchIn(it) }) return null
        return MailInvoice(OUT, pence, sender)
    }

    /** One find per account and email. */
    fun findId(accountId: String, messageId: String): String = "mf" + ActivityRules.fnv64("mailinvoice:$accountId:$messageId")

    /** The find's fields, written once by the server: `day` is the day the email came. */
    fun serverFields(inv: MailInvoice, account: String, subject: String?, receivedDay: Long): Map<String, FieldValue> = linkedMapOf(
        MailFindFields.KIND to KIND_INVOICE.fv(),
        MailFindFields.ACCOUNT to account.fv(),
        MailFindFields.MERCHANT to (inv.party?.fv() ?: FieldValue.Null),
        MailFindFields.DAY to receivedDay.fv(),
        MailFindFields.PENCE to inv.pence.fv(),
        MailFindFields.DIRECTION to inv.direction.fv(),
        MailFindFields.RECEIVED_DAY to receivedDay.fv(),
        MailFindFields.SUBJECT to (subject?.trim()?.take(MailRenewalRules.MAX_SUBJECT)?.takeIf { it.isNotEmpty() }?.fv() ?: FieldValue.Null),
        MailFindFields.TRUST to Trust.UNTRUSTED_EXTERNAL.name.fv(),
    )

    fun isInvoice(f: MailFind): Boolean =
        f.kind == KIND_INVOICE && (f.direction == IN || f.direction == OUT) && (f.pence ?: 0L) > 0L && f.state != MailFindState.DISMISSED

    private fun fmt(p: Long) = MoneyRules.fmt(p)

    /**
     * FROM YOUR BUSINESS EMAIL: this tax year's invoice finds, each matched to at most one statement line (the same
     * amount the right way, from [EARLY_DAYS] before the email to [LATE_DAYS] after; the closest day first, oldest
     * emails first), newest first. Null when there are none.
     */
    fun view(
        finds: List<MailFind>, lines: List<MoneyWatchLine>, businessAccounts: Set<String>, merchants: Map<String, Boolean>, today: Long,
    ): MoneyBusinessMailView? {
        val start = MoneyBusiness.yearStart(MoneyBusiness.taxYearOf(today))
        val mine = finds.filter { isInvoice(it) && it.receivedDay in start..today }
        if (mine.isEmpty()) return null
        val lastLine = lines.maxOfOrNull { it.day }
        val used = HashSet<String>()
        val match = HashMap<String, MoneyWatchLine>()
        for (f in mine.sortedWith(compareBy<MailFind> { it.receivedDay }.thenBy { it.id })) {
            val signed = if (f.direction == IN) f.pence!! else -f.pence!!
            val l = lines.filter { it.id !in used && it.pence == signed && it.day in (f.receivedDay - EARLY_DAYS)..(f.receivedDay + LATE_DAYS) }
                .minWithOrNull(compareBy<MoneyWatchLine> { kotlin.math.abs(it.day - f.receivedDay) }.thenBy { it.id })
            if (l != null) { used += l.id; match[f.id] = l }
        }
        val sorted = mine.sortedWith(compareByDescending<MailFind> { it.receivedDay }.thenBy { it.id })
        val rows = sorted.take(SHOWN).map { f ->
            val p = f.pence!!
            val who = f.merchant ?: if (f.direction == IN) "A payment" else "A cost"
            val title = "$who · ${fmt(p)}"
            val way = if (f.direction == IN) "Paid to you" else "A cost"
            val l = match[f.id]
            val counted = l != null && MoneyBusiness.counts(MoneyBusinessLine(l.account, l.day, l.pence, l.merchant, l.category), businessAccounts, merchants)
            val due = f.receivedDay + LATE_DAYS
            val lit = l == null && lastLine != null && lastLine >= due && today >= due
            val state = when {
                l != null && counted -> "in your statements · counted"
                l != null -> "in your statements · not counted as business yet"
                lines.isEmpty() -> "add a statement to check it"
                lit -> "not in your statements · paid another way?"
                else -> "not in your statements yet"
            }
            val line = "$way · ${CivilDate.shortLabel(f.receivedDay)} · $state"
            MoneyBusinessMailRow(
                id = f.id, title = title, line = line,
                quote = f.subject?.trim()?.takeIf { it.isNotEmpty() }?.let { "“$it”" },
                matched = l != null, lit = lit,
                businessTxnId = if (l != null && !counted && l.category != MoneyCategory.TRANSFERS) l.id else null,
                spoken = "$who, ${fmt(p)}. $line.",
            )
        }
        val inP = mine.filter { it.direction == IN }.sumOf { it.pence!! }
        val outP = mine.filter { it.direction == OUT }.sumOf { it.pence!! }
        val n = mine.size
        val parts = mutableListOf("$n this tax year")
        if (inP > 0) parts += "in ${fmt(inP)}"
        if (outP > 0) parts += "costs ${fmt(outP)}"
        if (lines.isNotEmpty()) parts += "${match.size} in your statements"
        val more = (n - rows.size).takeIf { it > 0 }?.let { "and $it more this tax year" }
        return MoneyBusinessMailView(HEAD, parts.joinToString(" · "), rows, more, FOOT)
    }

    /** What the Business chip says to a screen reader. */
    fun chipSpoken(row: MoneyBusinessMailRow): String = "Count ${row.title.substringBefore(" · ")} as business"
}
