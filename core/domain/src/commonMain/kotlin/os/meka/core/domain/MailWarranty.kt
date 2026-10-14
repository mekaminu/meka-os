package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/*
 * Inbox autopilot, slice 3f (build plan; non-AI, pure): **warranty ends from receipts, only above a value he sets**.
 * When Autopilot tidies a receipt or order email whose price (read in slice 3d) is at or above his threshold — Mail's
 * **Warranty reminders** (Off · £50 · £100 · £250 · £500, synced `context_mode/mail_warranty`, absent = £100) — the
 * server puts a one-off reminder on the Renewals radar: "Warranty ends · Currys order", due on the day the warranty
 * ends, shown 30 days before, with the order's price. The length is what the subject or the kept preview says
 * ("2-year warranty", "guaranteed for 5 years", "12-month guarantee"), else a year (the usual manufacturer's warranty),
 * and the note says which. Tickets, bookings, food, subscriptions and the like get nothing unless they name a warranty.
 *
 * Every word of an email is someone else's (ADR-006): the length only sets a reminder he can clear; nothing is sent,
 * paid or moved because of it. An offer to *buy* a warranty ("add a 3-year warranty for £29") is not one. Read from the
 * subject and the provider's preview only, never the body. A refund (no price read) sets nothing.
 */

/** Synced fields of `context_mode/mail_warranty` (written by the apps). */
object MailWarrantyFields {
    /** The lowest order price, in pence, that sets a reminder; 0 = Off. */
    const val MIN_PENCE = "minPence"
    const val CHANGED_AT = "changedAtMs"
}

/** Mail's Warranty reminders choice. */
enum class MailWarrantyLevel(val key: String, val label: String, val minPence: Long) {
    OFF("off", "Off", 0),
    FIFTY("50", "£50", 5_000),
    HUNDRED("100", "£100", 10_000),
    TWO_FIFTY("250", "£250", 25_000),
    FIVE_HUNDRED("500", "£500", 50_000);

    companion object {
        fun of(key: String?): MailWarrantyLevel? = entries.firstOrNull { it.key == key }
        /** A stored threshold back to its chip (an unknown one to the nearest at or below it, never Off by mistake). */
        fun ofPence(pence: Long): MailWarrantyLevel =
            if (pence <= 0) OFF else entries.filter { it != OFF && it.minPence <= pence }.maxByOrNull { it.minPence } ?: FIFTY
    }
}

/** A warranty read from a receipt: the last day it covers, and whether the email said how long. */
data class MailWarranty(val endDay: Long, val months: Int, val stated: Boolean, val merchant: String?, val pence: Long)

object MailWarrantyRules {
    const val ENTITY_ID = "mail_warranty"
    const val TITLE = "Warranty reminders"
    val DEFAULT = MailWarrantyLevel.HUNDRED
    /** The reminder shows this many days before the warranty ends. */
    const val LEAD_DAYS = 30
    /** When the email doesn't say, a year (the usual manufacturer's warranty). */
    const val DEFAULT_MONTHS = 12
    /** Longer than this is a misread ("100-year guarantee" on a pan is marketing). */
    const val MAX_MONTHS = 120
    const val SOURCE = MailReturnRules.SOURCE

    fun read(fields: Map<String, FieldValue>?): MailWarrantyLevel =
        fields?.get(MailWarrantyFields.MIN_PENCE)?.longOrNull?.let { MailWarrantyLevel.ofPence(it) } ?: DEFAULT

    fun settingFields(level: MailWarrantyLevel, nowMs: Long): Map<String, FieldValue> =
        mapOf(MailWarrantyFields.MIN_PENCE to level.minPence.fv(), MailWarrantyFields.CHANGED_AT to nowMs.fv())

    /** Under the title in Mail. */
    fun settingLine(level: MailWarrantyLevel): String =
        if (level == MailWarrantyLevel.OFF) "Off · receipts don't put warranty ends on Renewals"
        else "Orders of ${level.label} or more · Renewals reminds you a month before the warranty ends"

    /** What Mail says after a change. */
    fun switchedLine(level: MailWarrantyLevel): String =
        if (level == MailWarrantyLevel.OFF) "Warranty reminders off · reminders already on Renewals stay"
        else "Warranty reminders for orders of ${level.label} or more"

    /** "2-year warranty", "2 yr guarantee", "12-month warranty", "warranty: 3 years", "guaranteed for 5 years". */
    private val BEFORE = Regex(
        """\b(\d{1,3}|one|two|three|four|five|six|seven|ten)[\s-]?(years?|yrs?|months?|mths?)(?:'s?)?\s+(?:manufacturer'?s?\s+|full\s+|free\s+|limited\s+|parts\s+and\s+labour\s+)?(warranty|guarantee)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val AFTER = Regex(
        """\b(?:warranty|guarantee|guaranteed)\s*(?::|-|of|for|lasts?|covers?\s+you\s+for)?\s*(\d{1,3}|one|two|three|four|five|six|seven|ten)[\s-]?(years?|yrs?|months?|mths?)\b""",
        RegexOption.IGNORE_CASE,
    )
    /** Words just before a match that make it an offer to buy one, not the one he has. */
    private val OFFER = Regex("""\b(add|adding|buy|extend|extended|upgrade|protect|optional|for just|from just|get an?)\b[^.·|]{0,24}$""", RegexOption.IGNORE_CASE)
    /** Orders that aren't goods: no warranty unless one is named. */
    private val NOT_GOODS = Regex(
        """\b(tickets?|booking|booked|reservation|flight|hotel|rail|train|trainline|journey|subscription|renewal|membership|insurance|policy|groceries|grocery|takeaway|food|meal|restaurant|parking|donation|top[- ]?up|gift card|e-?ticket|cinema|event)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val WORDS = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "ten" to 10)

    /** The warranty's length in months as the words state it, or null when they don't. */
    fun statedMonths(subject: String?, preview: String?): Int? {
        for (t in listOfNotNull(subject, preview).filter { it.isNotBlank() }) {
            for (r in listOf(BEFORE, AFTER)) {
                for (m in r.findAll(t)) {
                    if (OFFER.containsMatchIn(t.substring(0, m.range.first))) continue
                    val n = m.groupValues[1].let { WORDS[it.lowercase()] ?: it.toIntOrNull() } ?: continue
                    val months = if (m.groupValues[2].lowercase().startsWith("y")) n * 12 else n
                    if (months in 1..MAX_MONTHS) return months
                }
            }
        }
        return null
    }

    /** [months] after [day] (a 31st into a shorter month lands on its last day). */
    fun plusMonths(day: Long, months: Int): Long {
        val d = CivilDate.fromEpochDay(day)
        val total = d.year * 12 + (d.month - 1) + months
        val y = total / 12
        val mo = total % 12 + 1
        return CivilDate.toEpochDay(y, mo, minOf(d.day, CivilDate.lengthOfMonth(y, mo)))
    }

    /**
     * The receipt's warranty, or null: [level] Off, no price read (a refund, or unsure), a price under the threshold, or
     * an order that isn't goods and names no warranty. The cover runs from the day the email came; it ends the day
     * before the same date [months] on.
     */
    fun read(receipt: MailReceipt, subject: String?, preview: String?, receivedDay: Long, level: MailWarrantyLevel): MailWarranty? {
        if (level == MailWarrantyLevel.OFF) return null
        val pence = receipt.pence ?: return null
        if (pence < level.minPence) return null
        val stated = statedMonths(subject, preview)
        if (stated == null && listOfNotNull(subject, preview, receipt.merchant).any { NOT_GOODS.containsMatchIn(it) }) return null
        val months = stated ?: DEFAULT_MONTHS
        return MailWarranty(plusMonths(receivedDay, months) - 1, months, stated != null, receipt.merchant, pence)
    }

    /** One reminder per account and email, so a retried check lands on the same entity. */
    fun obligationId(accountId: String, messageId: String): String = "mw" + ActivityRules.fnv64("mailwarranty:$accountId:$messageId")

    /** "Warranty ends · Currys order"; "Warranty ends · your order" when no merchant was read. */
    fun title(merchant: String?): String = "Warranty ends · " + (merchant?.let { "$it order" } ?: "your order")

    /** "2 years", "18 months", "1 year". */
    fun lengthLabel(months: Int): String = when {
        months % 12 == 0 -> (months / 12).let { "$it ${if (it == 1) "year" else "years"}" }
        else -> "$months ${if (months == 1) "month" else "months"}"
    }

    /**
     * The reminder's fields, written once by the server: a one-off [ObligationKind.WARRANTY] due at 09:00 on its last
     * day in [calendar], shown [LEAD_DAYS] before, the order's price as its cost, the account as its subject, and a note
     * saying where it came from and how long it was taken to be.
     */
    fun fields(w: MailWarranty, account: String, receivedDay: Long, nowMs: Long, calendar: LocalCalendar): Map<String, FieldValue> {
        val length = if (w.stated) "the email says ${lengthLabel(w.months)}" else "the email didn't say, so a year (check the paperwork)"
        return linkedMapOf(
            ActionableFields.TITLE to title(w.merchant).take(Lists.MAX_TITLE).fv(),
            ObligationFields.KIND to ObligationKind.WARRANTY.name.fv(),
            ObligationFields.SUBJECT_LABEL to "$account mail".fv(),
            ActionableFields.NOTES to (
                "From an order email in your $account mail on ${CivilDate.shortLabel(receivedDay)}, tidied by Inbox autopilot; " +
                    "$length. Anything to claim, claim before then. Tap Done if there's nothing to do."
                ).fv(),
            ActionableFields.DUE_AT to calendar.toEpochMs(w.endDay, Lists.DATE_MINUTE).fv(),
            ObligationFields.LEAD_DAYS to LEAD_DAYS.fv(),
            ObligationFields.RECURRENCE to FieldValue.Null,
            ObligationFields.COST_MINOR to w.pence.fv(),
            ObligationFields.CURRENCY to "GBP".fv(),
            ActionableFields.LIFECYCLE to Lifecycle.ACTIVE.name.fv(),
            ActionableFields.CREATED_AT to nowMs.fv(),
            ActionableFields.VISIBILITY to Visibility.PRIVATE.name.fv(),
            ActionableFields.PROVENANCE_SOURCE to SOURCE.fv(),
            ActionableFields.PROVENANCE_TRUST to Trust.UNTRUSTED_EXTERNAL.name.fv(),
        )
    }

    /** What a tidy's receipts line adds: " · 1 warranty → Renewals"; "" for none. */
    fun suffix(count: Int): String = when {
        count <= 0 -> ""
        count == 1 -> " · 1 warranty → Renewals"
        else -> " · $count warranties → Renewals"
    }
}
