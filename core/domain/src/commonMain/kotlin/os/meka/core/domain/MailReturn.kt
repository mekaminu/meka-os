package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/*
 * Inbox autopilot, slice 3e (build plan; non-AI, pure): **return-by dates from receipts**. When Autopilot tidies a
 * receipt or order email whose subject or kept preview says how long there is to send it back — "Return by 12 Nov",
 * "returns accepted until 30 November 2026", "Return window closes Friday", "30-day returns", "free returns within
 * 28 days" — the server puts a one-off reminder on the Renewals radar: "Last day to return · Amazon order", due that
 * day, shown 5 days before, with the order's price. Done (returned it or keeping it) or Stop tracking clears it, like
 * any renewal; the server writes it once and never again, so his edit or removal stands.
 *
 * Every word of an email is someone else's (ADR-006): the date only sets a reminder he can clear; nothing is sent,
 * paid or moved because of it, and a date that isn't after the email (or is more than [MAX_DAYS] after it) is ignored.
 * Read from the subject and the provider's preview only, never the body. A refund email sets nothing.
 */

/** A return window read from a receipt: the last local day to send it back. */
data class MailReturn(val lastDay: Long, val merchant: String?, val pence: Long?)

object MailReturnRules {
    /** The reminder shows this many days before the last day. */
    const val LEAD_DAYS = 5
    /** A window longer than this (or a misread year) is ignored. */
    const val MAX_DAYS = 400
    /** "N-day returns" shorter than this is a misread ("1-day delivery"), not a return window. */
    const val MIN_DAYS = 7
    /** Provenance of the reminders this writes. */
    const val SOURCE = "meka:mail"

    /** An explicit last day: "return by", "returns accepted until", "return window closes on", "last day to return is". */
    private val BY_DATE = Regex(
        """\b(?:returns?\s+(?:it\s+|items?\s+|them\s+)?(?:by|before|until|till)|returns?\s+(?:are\s+)?(?:accepted|open)\s+(?:until|till)|returnable\s+(?:until|till|by)|return\s+(?:window|period)\s+(?:closes|ends)(?:\s+on)?|last\s+day\s+(?:to|for)\s+returns?(?:\s+is)?)\b""",
        RegexOption.IGNORE_CASE,
    )
    /** A window in days: "30-day returns", "30 day return policy", "return within 28 days", "28 days to return". */
    private val IN_DAYS = listOf(
        Regex("""\b(\d{1,3})[\s-]?days?\s+(?:free\s+)?returns?\b""", RegexOption.IGNORE_CASE),
        Regex("""\breturns?\s+(?:it\s+|items?\s+|them\s+)?(?:for\s+free\s+|free\s+)?(?:with)?in\s+(\d{1,3})\s+days\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(\d{1,3})\s+days\s+to\s+return\b""", RegexOption.IGNORE_CASE),
    )
    /** "12/11/2026", "12/11/26", "12.11.2026": day first, as in the UK. */
    private val NUMERIC = Regex("""^\D{0,6}?(\d{1,2})[/.](\d{1,2})[/.](\d{2}|\d{4})(?!\d)""")
    private val REFUND = Regex("""\b(refund|refunded|refunding|credited|credit note)\b""", RegexOption.IGNORE_CASE)

    /**
     * The last local day to return what an email received on [receivedDay] bought, or null when its words don't say:
     * an explicit date after a return phrase (the subject first, then the preview) wins over a window in days.
     */
    fun lastDay(subject: String?, preview: String?, receivedDay: Long): Long? {
        val texts = listOfNotNull(subject, preview).filter { it.isNotBlank() }
        if (texts.isEmpty() || texts.any { REFUND.containsMatchIn(it) }) return null
        for (t in texts) {
            for (m in BY_DATE.findAll(t)) {
                val after = t.substring(m.range.last + 1).take(40)
                val day = numeric(after) ?: MailWeekRules.dayOf(after, receivedDay)
                if (day != null && fits(day, receivedDay)) return day
            }
        }
        for (t in texts) {
            for (r in IN_DAYS) {
                for (m in r.findAll(t)) {
                    val n = m.groupValues[1].toIntOrNull() ?: continue
                    if (n < MIN_DAYS) continue
                    val day = receivedDay + n
                    if (fits(day, receivedDay)) return day
                }
            }
        }
        return null
    }

    private fun fits(day: Long, receivedDay: Long) = day >= receivedDay && day - receivedDay <= MAX_DAYS

    private fun numeric(text: String): Long? {
        val m = NUMERIC.find(text) ?: return null
        val d = m.groupValues[1].toInt()
        val mo = m.groupValues[2].toInt()
        val y = m.groupValues[3].toInt().let { if (it < 100) 2000 + it else it }
        if (mo !in 1..12 || d < 1 || d > CivilDate.lengthOfMonth(y, mo)) return null
        return CivilDate.toEpochDay(y, mo, d)
    }

    /** The receipt's return window, or null when it names none. */
    fun read(receipt: MailReceipt, subject: String?, preview: String?, receivedDay: Long): MailReturn? =
        lastDay(subject, preview, receivedDay)?.let { MailReturn(it, receipt.merchant, receipt.pence) }

    /** One reminder per account and email, so a retried check lands on the same entity. */
    fun obligationId(accountId: String, messageId: String): String = "mr" + ActivityRules.fnv64("mailreturn:$accountId:$messageId")

    /** "Last day to return · Amazon order"; "Last day to return · your order" when no merchant was read. */
    fun title(merchant: String?): String = "Last day to return · " + (merchant?.let { "$it order" } ?: "your order")

    /**
     * The reminder's fields, written once by the server: a one-off [ObligationKind.OTHER] due at 09:00 on the last day in
     * [calendar], shown [LEAD_DAYS] before, the order's price as its cost, the account as its subject, and a note saying
     * where it came from and that Done or Stop tracking clears it.
     */
    fun fields(r: MailReturn, account: String, receivedDay: Long, nowMs: Long, calendar: LocalCalendar): Map<String, FieldValue> {
        val out = linkedMapOf<String, FieldValue>(
            ActionableFields.TITLE to title(r.merchant).take(Lists.MAX_TITLE).fv(),
            ObligationFields.KIND to ObligationKind.OTHER.name.fv(),
            ObligationFields.SUBJECT_LABEL to "$account mail".fv(),
            ActionableFields.NOTES to (
                "From an order email in your $account mail on ${CivilDate.shortLabel(receivedDay)}, tidied by Inbox autopilot. " +
                    "Tap Done once it's gone back or you're keeping it."
                ).fv(),
            ActionableFields.DUE_AT to calendar.toEpochMs(r.lastDay, Lists.DATE_MINUTE).fv(),
            ObligationFields.LEAD_DAYS to LEAD_DAYS.fv(),
            ObligationFields.RECURRENCE to FieldValue.Null,
            ActionableFields.LIFECYCLE to Lifecycle.ACTIVE.name.fv(),
            ActionableFields.CREATED_AT to nowMs.fv(),
            ActionableFields.VISIBILITY to Visibility.PRIVATE.name.fv(),
            ActionableFields.PROVENANCE_SOURCE to SOURCE.fv(),
            ActionableFields.PROVENANCE_TRUST to Trust.UNTRUSTED_EXTERNAL.name.fv(),
        )
        r.pence?.let {
            out[ObligationFields.COST_MINOR] = it.fv()
            out[ObligationFields.CURRENCY] = "GBP".fv()
        }
        return out
    }

    // ---- Returns and warranties, completeness slice (2026-10-16): Sent it back · Keeping it, a refund to wait for ----

    const val TITLE_PREFIX = "Last day to return · "
    /** A return reminder's Done: it's gone back (a one-off, so it leaves the radar) and the refund goes on Waiting for. */
    const val SENT_BACK_LABEL = "Sent it back"
    /** A return reminder's Stop: he's keeping it; off the radar, nothing to wait for. */
    const val KEEPING_LABEL = "Keeping it"
    /** Days until the refund's first chase on Waiting for (most shops refund within two weeks of receiving it). */
    const val REFUND_CHASE_DAYS = 14
    /** The heads-up comes this many days before the last day, and again on it. */
    const val REMIND_DAYS = 2
    /** Minute of the day the heads-up comes (09:00). */
    const val NOTICE_MIN = 9 * 60

    /** Whether a radar item is a return reminder (the title the server writes, or the same words typed). */
    fun isReturn(title: String?): Boolean = title?.trim()?.startsWith(TITLE_PREFIX.trim(), ignoreCase = true) == true

    /** "Amazon" from "Last day to return · Amazon order"; null for "your order", anything else, or no shop. */
    fun merchantOf(title: String?): String? {
        if (!isReturn(title)) return null
        val rest = title!!.trim().substring(TITLE_PREFIX.trim().length).trim().removePrefix("·").trim()
        if (!rest.endsWith(" order", ignoreCase = true)) return null
        val shop = rest.dropLast(" order".length).trim()
        return shop.takeIf { it.isNotEmpty() && !it.equals("your", ignoreCase = true) }
    }

    /** The Waiting for row "Sent it back" adds: "Refund · Amazon order · £40", "Refund · your order". */
    fun refundTitle(merchant: String?, pence: Long?): String =
        "Refund · " + (merchant?.let { "$it order" } ?: "your order") + (pence?.let { " · " + RenewalRules.formatPence(it) } ?: "")

    /** The refund's Waiting for id, from the reminder's, so the Fold and the Mac converge and a second tap lands on it. */
    fun refundWaitId(obligationId: String): String = "rf" + ActivityRules.fnv64("refund:$obligationId")

    /** The undo bar's line after Sent it back. */
    fun sentBackLine(merchant: String?): String =
        "Sent back · waiting for the refund from ${merchant ?: "the shop"} in Lists → Waiting for"

    /** The undo bar's line after Keeping it. */
    fun keepingLine(merchant: String?): String = "Keeping ${merchant?.let { "the $it order" } ?: "it"} · off the radar"

    /**
     * The heads-up for each return reminder still open: [REMIND_DAYS] days before the last day and on it, at 09:00 —
     * "Last day to return · Amazon order is Fri 13 Nov · £40" · "In 2 days · in Lists: Sent it back or Keeping it";
     * on the day "… is today". Nothing once it's past, done or stopped (only open items are passed in).
     */
    fun notices(items: List<RenewalItem>, nowMs: Long, cal: LocalCalendar): List<Notice> {
        val today = cal.epochDayOf(nowMs)
        return items.filter { isReturn(it.title) }.mapNotNull { item ->
            val left = (item.dueDay - today).toInt()
            if (left !in 0..REMIND_DAYS) return@mapNotNull null
            val at = if (left == 0) item.dueDay else item.dueDay - REMIND_DAYS
            val dayWord = when (left) { 0 -> "today"; 1 -> "tomorrow"; else -> CivilDate.shortLabel(item.dueDay) }
            val inWords = when (left) { 0 -> "Today"; 1 -> "Tomorrow"; else -> "In $left days" }
            Notice(
                key = "return:${item.id}:${item.dueDay}:${if (left == 0) "day" else "before"}",
                source = NoticeSource.RETURNS, tier = NoticeTier.HEADS_UP,
                title = "${item.title} is $dayWord" + (item.costPence?.let { " · ${RenewalRules.formatPence(it)}" } ?: ""),
                text = "$inWords · in Lists: $SENT_BACK_LABEL or $KEEPING_LABEL",
                atMs = cal.toEpochMs(at, NOTICE_MIN), target = NoticeTarget.LISTS,
                expiresAtMs = cal.toEpochMs(item.dueDay + 1, 0),
            )
        }
    }

    /** What a tidy's receipts line adds: " · 1 return date → Renewals"; "" for none. */
    fun suffix(count: Int): String = when {
        count <= 0 -> ""
        count == 1 -> " · 1 return date → Renewals"
        else -> " · $count return dates → Renewals"
    }
}
