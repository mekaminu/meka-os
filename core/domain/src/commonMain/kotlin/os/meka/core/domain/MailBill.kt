package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/*
 * Inbox autopilot, slice 3h / Bills due, slice 2 (build plan; non-AI, pure): **bills due, from bill emails**. When an
 * email of the last [MailBillRules.WINDOW_DAYS] days (sorted as a receipt, notification, FYI or one that needs him; never
 * a newsletter, promo or junk) is a bill — "Your Octopus Energy bill is ready · £84.20 due 28 Oct", "Your statement:
 * minimum payment due 3 Nov", "Your Direct Debit of £45.00 will be collected on 1 November" — the server puts one
 * reminder on the Renewals radar: "Octopus Energy bill", kind BILL, due 09:00 on the day, the amount, so Bills due's
 * heads-up comes three days before ("Octopus Energy bill due Wed 28 Oct · £84.20").
 *
 * Every word of an email is someone else's (ADR-006): it only sets a reminder he can clear. **Navilife never pays**, and
 * no link is taken from the email (a scam bill would bring its own): no pay link is set, and the note says to pay on the
 * company's own site or app. Read from the subject and the provider's preview only, never the body. A payment
 * confirmation or a refund sets nothing; a day before the email or more than [MAX_DAYS] after it is ignored; a bill he
 * already has on the radar (same payee, due within [SAME_BILL_DAYS]) is left to that one. One reminder per payee and due
 * day, written once, so a "reminder: your bill is due" email adds nothing and his Done or Stop tracking stands.
 */

/** A bill read from an email: the day it's due, what it says is owed, who to, and whether a Direct Debit takes it. */
data class MailBill(val dueDay: Long, val pence: Long?, val payee: String?, val directDebit: Boolean)

object MailBillRules {
    /** Emails this many days back are read each check. */
    const val WINDOW_DAYS = 45
    const val WINDOW_MS = WINDOW_DAYS * 86_400_000L
    /** A due day further than this after the email is a misread (or a contract end), not a bill. */
    const val MAX_DAYS = 90
    /** A radar bill for the same payee due this close is the same bill. */
    const val SAME_BILL_DAYS = 7
    /** Provenance of the reminders this writes. */
    const val SOURCE = "meka:mail"

    /** What makes it a bill at all. */
    private val BILL = Regex(
        """\b(bill|bills|statement|invoice|direct debit|payment due|amount due|balance due|amount to pay|minimum payment|payment reminder|due for payment|council tax|instalment|installment)\b""",
        RegexOption.IGNORE_CASE,
    )
    /** Already paid, or money back: no reminder. */
    private val PAID = Regex(
        """\b(payment received|received your payment|thanks? (?:you )?for your payment|has been paid|have been paid|paid in full|payment (?:confirmation|confirmed|successful)|successfully paid|refund|refunded|refunding|credited|credit note)\b""",
        RegexOption.IGNORE_CASE,
    )
    /** A Direct Debit takes it: nothing to pay by hand. */
    private val DIRECT_DEBIT = Regex(
        """\b(direct debit|will be (?:collected|taken|debited)|to be (?:collected|taken|debited))\b""",
        RegexOption.IGNORE_CASE,
    )
    /** The words a due day follows: "due 28 Oct", "payment due date: 28/10/2026", "pay by Friday", "collected on 1 Nov". */
    private val DUE = Regex(
        """\b(?:(?:payment\s+)?due(?:\s+date)?(?:\s+(?:on|by|is))?|pay(?:\s+it)?\s+by|to\s+be\s+paid\s+by|payment\s+date(?:\s+is)?|(?:collected|taken|debited)(?:\s+from\s+your\s+account)?\s+(?:on\s+or\s+(?:just\s+)?after|on|from))\b""",
        RegexOption.IGNORE_CASE,
    )
    /** "28/10/2026", "28/10/26", "28.10.2026": day first, as in the UK. */
    private val NUMERIC = Regex("""^\W{0,6}?(\d{1,2})[/.](\d{1,2})[/.](\d{2}|\d{4})(?!\d)""")
    /** Bill words an amount follows first ("Amount due: £84.20", "your bill is £84.20", "minimum payment of £25"). */
    private val BILL_AMOUNT_WORDS = Regex(
        """\b(amount due|total due|balance due|amount to pay|to pay|minimum payment(?:\s+of)?|bill(?:\s+is|\s+of|\s+for)?|statement balance|balance|direct debit(?:\s+of)?|payment of)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val AMOUNT = Regex("""(?:£|GBP\s?)\s?(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{2}))?(?!\d)""", RegexOption.IGNORE_CASE)

    /**
     * The bill an email received on [receivedDay] is, or null when its words don't say for sure: it must name a bill and
     * a due day after the email (the subject first, then the preview), and not be a payment confirmation or refund.
     */
    fun read(fromName: String?, fromAddress: String?, subject: String?, preview: String?, receivedDay: Long): MailBill? {
        val texts = listOfNotNull(subject, preview).filter { it.isNotBlank() }
        if (texts.isEmpty() || texts.none { BILL.containsMatchIn(it) } || texts.any { PAID.containsMatchIn(it) }) return null
        val due = dueDay(texts, receivedDay) ?: return null
        return MailBill(
            dueDay = due,
            pence = amount(texts) ?: MailReceiptRules.amount(subject, preview),
            payee = MailReceiptRules.merchant(fromName, fromAddress),
            directDebit = texts.any { DIRECT_DEBIT.containsMatchIn(it) },
        )
    }

    private fun dueDay(texts: List<String>, receivedDay: Long): Long? {
        for (t in texts) {
            for (m in DUE.findAll(t)) {
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

    /** The first £ amount right after a bill word, in the subject then the preview; null when none follows one closely. */
    private fun amount(texts: List<String>): Long? {
        for (t in texts) {
            for (w in BILL_AMOUNT_WORDS.findAll(t)) {
                val after = t.substring(w.range.last + 1).take(24)
                val m = AMOUNT.find(after) ?: continue
                if (after.substring(0, m.range.first).any { it.isDigit() }) continue
                val pounds = m.groupValues[1].replace(",", "").toLongOrNull() ?: continue
                val pence = pounds * 100 + (m.groupValues[2].takeIf { it.isNotEmpty() }?.toLong() ?: 0L)
                if (pence in 1..MailReceiptRules.MAX_PENCE) return pence
            }
        }
        return null
    }

    /** One reminder per account, payee and due day (else the email), so the bill and its "reminder" email land on one. */
    fun obligationId(accountId: String, messageId: String, bill: MailBill): String =
        "mb" + ActivityRules.fnv64("mailbill:$accountId:${bill.payee?.lowercase() ?: "msg:$messageId"}:${bill.dueDay}")

    /** "Octopus Energy bill"; "Octopus Energy direct debit"; "A bill from your mail" when no payee was read. */
    fun title(bill: MailBill): String {
        val what = if (bill.directDebit) "direct debit" else "bill"
        return bill.payee?.let { "$it $what" } ?: if (bill.directDebit) "A direct debit from your mail" else "A bill from your mail"
    }

    /** Whether a reminder's title says a Direct Debit takes it (Bills due's heads-up then has nothing to pay). */
    fun isDirectDebit(title: String): Boolean = title.trimEnd().endsWith("direct debit", ignoreCase = true)

    /**
     * Whether the radar already has this bill: an open BILL (or one with a pay link) whose title or subject names the
     * payee, due within [SAME_BILL_DAYS] of it. [obligations] are the household's `obligation` entities' fields.
     */
    fun alreadyOnRadar(bill: MailBill, obligations: Collection<Map<String, FieldValue>>, calendar: LocalCalendar): Boolean {
        val payee = bill.payee?.lowercase()?.takeIf { it.length >= 3 } ?: return false
        return obligations.any { f ->
            val kind = f[ObligationFields.KIND]?.textOrNull?.let { enumOrNull<ObligationKind>(it) }
            val billish = kind == ObligationKind.BILL || f[ObligationFields.PAY_LINK]?.textOrNull != null
            val open = f[ActionableFields.LIFECYCLE]?.textOrNull?.let { enumOrNull<Lifecycle>(it) }?.isTerminal != true
            val named = listOfNotNull(f[ActionableFields.TITLE]?.textOrNull, f[ObligationFields.SUBJECT_LABEL]?.textOrNull)
                .any { payee in it.lowercase() }
            val due = f[ActionableFields.DUE_AT]?.longOrNull?.let(calendar::epochDayOf)
            billish && open && named && due != null && kotlin.math.abs(due - bill.dueDay) <= SAME_BILL_DAYS
        }
    }

    /**
     * The reminder's fields, written once by the server: a one-off [ObligationKind.BILL] due at 09:00 on the due day in
     * [calendar], shown [BillRules.REMIND_DAYS] before, the amount as its cost, the account as its subject, no pay link,
     * and a note saying where it came from and that Navilife never pays.
     */
    fun fields(bill: MailBill, account: String, receivedDay: Long, nowMs: Long, calendar: LocalCalendar): Map<String, FieldValue> {
        val from = "From a bill email in your $account mail on ${CivilDate.shortLabel(receivedDay)}."
        val how = if (bill.directDebit) {
            "Taken by Direct Debit, so nothing to pay · tap Done once it's gone out."
        } else {
            "Navilife never pays: pay on the company's own site or app, not through a link in an email · tap Done once it's paid."
        }
        val out = linkedMapOf<String, FieldValue>(
            ActionableFields.TITLE to title(bill).take(Lists.MAX_TITLE).fv(),
            ObligationFields.KIND to ObligationKind.BILL.name.fv(),
            ObligationFields.SUBJECT_LABEL to "$account mail".fv(),
            ActionableFields.NOTES to "$from $how".fv(),
            ActionableFields.DUE_AT to calendar.toEpochMs(bill.dueDay, Lists.DATE_MINUTE).fv(),
            ObligationFields.LEAD_DAYS to BillRules.REMIND_DAYS.fv(),
            ObligationFields.RECURRENCE to FieldValue.Null,
            ActionableFields.LIFECYCLE to Lifecycle.ACTIVE.name.fv(),
            ActionableFields.CREATED_AT to nowMs.fv(),
            ActionableFields.VISIBILITY to Visibility.PRIVATE.name.fv(),
            ActionableFields.PROVENANCE_SOURCE to SOURCE.fv(),
            ActionableFields.PROVENANCE_TRUST to Trust.UNTRUSTED_EXTERNAL.name.fv(),
        )
        bill.pence?.let {
            out[ObligationFields.COST_MINOR] = it.fv()
            out[ObligationFields.CURRENCY] = "GBP".fv()
        }
        return out
    }
}
