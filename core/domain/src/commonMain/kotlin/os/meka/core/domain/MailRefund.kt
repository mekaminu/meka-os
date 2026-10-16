package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/*
 * Returns and warranties, completeness slice 2 (2026-10-16): **a refund email closes the Waiting for row**. After
 * "Sent it back" puts "Refund · Amazon order · £40" on Waiting for (or a promise "Refund" from a shop was added), the
 * shop's own "Your refund has been processed" / "We've refunded £40" email is read by the server (sender, subject and
 * kept preview; never the body) and becomes one `mail_find` of kind REFUND. When an open refund row on Waiting for is
 * from the same shop, Needs you's mail finds card shows "Refund from Amazon · refunded £40 Thu 15 Oct · closes “Refund ·
 * Amazon order · £40” on Waiting for" with **Close it** (the row leaves Waiting for as received, Undo puts it back) and
 * **Not this one** (the row stays). Nothing closes without his tap (ADR-006: the email is someone else's words), and a
 * refund email with no open refund row to close shows nothing. Pure, non-AI, unit-tested.
 */

/** A refund an email says has gone back to him: who from, how much (when it says) — not a promise of one. */
data class MailRefund(val merchant: String?, val pence: Long?)

object MailRefundRules {
    const val KIND_REFUND = "REFUND"
    /** How far back the server reads refund emails, and how long a find is offered. */
    const val WINDOW_DAYS = 45
    const val WINDOW_MS = WINDOW_DAYS * 86_400_000L
    /** A refund email this many days before the Waiting for row was made can still close it (he tapped late). */
    const val EARLY_DAYS = 7

    const val TITLE = "Refunds from your mail"
    const val CLOSE = "Close it"
    const val NOT_THIS = "Not this one"
    const val NOT_THIS_LINE = "Not this one · the refund stays on Waiting for"
    const val GONE = "That refund isn't on Waiting for any more"

    /** The refund has happened: processed, issued, sent, credited, "we've refunded", "refunded £40", "refund confirmation". */
    private val DONE = Regex(
        """\b(?:refund(?:\s+of\s+(?:£|GBP\s?)\s?[\d,]+(?:\.\d{2})?)?\s+(?:has\s+been\s+|have\s+been\s+|was\s+|is\s+)?(?:processed|issued|completed|approved|sent|paid|credited|on\s+its\s+way)|(?:we(?:'ve|\s+have)|has\s+been|have\s+been|was|been)\s+refunded|refunded\s+(?:£|GBP|to\s+your|you)|we(?:'ve|\s+have)\s+(?:issued|processed|sent|completed)\s+(?:a|your)\s+refund|refund\s+(?:confirmation|confirmed|complete|completed|processed|issued|notification|receipt))\b""",
        RegexOption.IGNORE_CASE,
    )
    /** Not yet, or not at all: asked for, being processed, will be, declined. */
    private val NOT_YET = Regex(
        """\b(?:refund\s+request|requested\s+a\s+refund|request(?:ed)?\s+(?:for\s+)?(?:a\s+)?refund|being\s+processed|will\s+be\s+(?:refunded|processed|issued|credited)|we(?:'ll|\s+will)\s+(?:refund|process|issue)|once\s+(?:we|it|your)|when\s+we\s+receive|not\s+eligible|unable\s+to\s+(?:refund|process)|refund\s+(?:was\s+|has\s+been\s+)?(?:declined|rejected|denied|refused))\b""",
        RegexOption.IGNORE_CASE,
    )
    private val AMOUNT = Regex("""(?:£|GBP\s?)\s?(\d{1,3}(?:,\d{3})+|\d+)(?:\.(\d{2}))?(?!\d)""", RegexOption.IGNORE_CASE)

    /**
     * The refund an email says was made, or null when its words don't say so for sure (the subject first, then the
     * preview): it must say the money went back and not that it's only asked for, on its way later, or declined.
     */
    fun read(fromName: String?, fromAddress: String?, subject: String?, preview: String?): MailRefund? {
        val texts = listOfNotNull(subject, preview).filter { it.isNotBlank() }
        if (texts.isEmpty() || texts.none { DONE.containsMatchIn(it) } || texts.any { NOT_YET.containsMatchIn(it) }) return null
        return MailRefund(MailReceiptRules.merchant(fromName, fromAddress), amount(texts))
    }

    /** The one £ amount the words name (all the same), else null. */
    private fun amount(texts: List<String>): Long? = texts.flatMap { t ->
        AMOUNT.findAll(t).mapNotNull { m ->
            val pounds = m.groupValues[1].replace(",", "").toLongOrNull() ?: return@mapNotNull null
            (pounds * 100 + (m.groupValues[2].takeIf { it.isNotEmpty() }?.toLong() ?: 0L)).takeIf { it in 1..MailReceiptRules.MAX_PENCE }
        }.toList()
    }.distinct().singleOrNull()

    /** One find per account and email. */
    fun findId(accountId: String, messageId: String): String = "mf" + ActivityRules.fnv64("mailrefund:$accountId:$messageId")

    /** The find's fields, written once by the server: `day` is the day the email came. */
    fun serverFields(r: MailRefund, account: String, subject: String?, receivedDay: Long): Map<String, FieldValue> = linkedMapOf(
        MailFindFields.KIND to KIND_REFUND.fv(),
        MailFindFields.ACCOUNT to account.fv(),
        MailFindFields.MERCHANT to (r.merchant?.fv() ?: FieldValue.Null),
        MailFindFields.DAY to receivedDay.fv(),
        MailFindFields.PENCE to (r.pence?.fv() ?: FieldValue.Null),
        MailFindFields.RECEIVED_DAY to receivedDay.fv(),
        MailFindFields.SUBJECT to (subject?.trim()?.take(MailRenewalRules.MAX_SUBJECT)?.takeIf { it.isNotEmpty() }?.fv() ?: FieldValue.Null),
        MailFindFields.TRUST to Trust.UNTRUSTED_EXTERNAL.name.fv(),
    )

    /** A Waiting for row that waits for a refund: "Sent it back"'s "Refund · Amazon order · £40", or a promise's "Refund". */
    fun isRefundWait(w: WaitingItem): Boolean {
        val t = w.title.trim()
        return t.equals("Refund", ignoreCase = true) || t.startsWith("Refund ·", ignoreCase = true) ||
            t.startsWith("Refund from", ignoreCase = true) || t.startsWith("Refund for", ignoreCase = true)
    }

    private fun key(s: String?): String = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }

    /** The same shop: "Amazon" and "Amazon UK", "Next" and "next" — at least 3 letters, one the start of the other. */
    fun sameShop(a: String?, b: String?): Boolean {
        val x = key(a)
        val y = key(b)
        if (x.length < 3 || y.length < 3) return false
        return x.startsWith(y) || y.startsWith(x)
    }

    /** "£40" in "Refund · Amazon order · £40"; null when the row names no amount. */
    fun penceOf(title: String): Long? = AMOUNT.findAll(title).toList().singleOrNull()?.let { m ->
        val pounds = m.groupValues[1].replace(",", "").toLongOrNull() ?: return null
        pounds * 100 + (m.groupValues[2].takeIf { it.isNotEmpty() }?.toLong() ?: 0L)
    }

    /**
     * The open Waiting for row [f] closes: a refund row from the same shop, made no more than [EARLY_DAYS] after the
     * email, the one with the same amount first, else the oldest. Null when there's none (nothing shows then).
     */
    fun match(f: MailFind, waiting: List<WaitingItem>, cal: LocalCalendar): WaitingItem? {
        if (f.kind != KIND_REFUND || f.merchant == null) return null
        val shops = waiting.filter { w ->
            isRefundWait(w) && (sameShop(w.who, f.merchant) || (w.who == null && key(f.merchant).length >= 3 && key(f.merchant) in key(w.title))) &&
                f.receivedDay + EARLY_DAYS >= cal.epochDayOf(w.sinceMs)
        }
        if (shops.isEmpty()) return null
        return shops.sortedWith(compareBy<WaitingItem> { if (f.pence != null && penceOf(it.title) == f.pence) 0 else 1 }.thenBy { it.sinceMs }.thenBy { it.id }).first()
    }

    /** Whether [f] is an open refund to show: not answered, its email inside the window, and a row on Waiting for to close. */
    fun showable(f: MailFind, waiting: List<WaitingItem>, today: Long, cal: LocalCalendar): Boolean =
        f.kind == KIND_REFUND && f.state == MailFindState.OPEN && f.receivedDay + WINDOW_DAYS >= today && match(f, waiting, cal) != null

    /** "Refund from Amazon". */
    fun title(f: MailFind): String = "Refund from ${f.merchant ?: "a shop"}"

    /** "Refunded £40 Thu 15 Oct · closes “Refund · Amazon order · £40” on Waiting for" ("Refunded today", no amount). */
    fun line(f: MailFind, w: WaitingItem, today: Long): String =
        "Refunded " + (f.pence?.let { RenewalRules.formatPence(it) + " " } ?: "") + RenewalRules.dayWord(f.receivedDay, today) +
            " · closes “${w.title}” on Waiting for"

    fun row(f: MailFind, w: WaitingItem, today: Long): MailFindRow {
        val title = title(f)
        val line = line(f, w, today)
        return MailFindRow(
            id = f.id, title = title, line = line, from = MailRenewalRules.fromLine(f),
            business = f.account == MailLabel.BUSINESS.label,
            spoken = "$title. $line. From your ${f.account} mail.",
            addSpoken = "Close ${w.title} on Waiting for",
            notNeededSpoken = "Keep waiting for ${w.title}",
            addLabel = CLOSE,
            notNeededLabel = NOT_THIS,
        )
    }

    /** "1 refund came · close it on Waiting for with a tap" (alone on the card). */
    fun headline(n: Int): String = (if (n == 1) "1 refund came" else "$n refunds came") + " · nothing closes until you tap Close it"

    /** The bar's line after Close it: "Closed Refund · Amazon order · £40 · Amazon refunded it Thu 15 Oct". */
    fun closedLine(f: MailFind, w: WaitingItem, today: Long): String =
        "Closed ${w.title} · ${f.merchant ?: "the shop"} refunded it ${RenewalRules.dayWord(f.receivedDay, today)}"

    /** Whether a refund promise from the same shop on the same day is covered by a refund find (one card, not two). */
    fun covers(refunds: List<MailFind>, promise: MailFind): Boolean =
        promise.kind == MailPromiseRules.KIND_PROMISE && promise.what?.trim()?.equals("Refund", ignoreCase = true) == true &&
            refunds.any { it.kind == KIND_REFUND && it.receivedDay == promise.receivedDay && sameShop(it.merchant, promise.merchant) }
}
