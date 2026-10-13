package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/**
 * Inbox autopilot, slice 5c: the weekly **"Not opened in 30 days · unsubscribe from 11?"** (Meka, 2026-10-12: unsubscribe
 * done for him). Once a week, from Saturday 09:00, Needs you on both apps lists the newsletter and promo senders whose
 * last 30 days of email he never opened (at least [MIN_EMAILS] of them, worked out by the server from each email's
 * read flag), with **Unsubscribe from all N** (one tap asks the server to unsubscribe from each, as Mail's own
 * Unsubscribe does), **Keep** per sender (never offered again) and **Not now** (back next Saturday, if any are left).
 * Each has Undo; nothing is sent before the server's next check, and an Undo before then sends nothing.
 *
 * Only senders the server can unsubscribe from by itself are offered (one-click or the list's unsubscribe email; an
 * Outlook sender whose headers are read on the tap); a sender with only a web page stays in Mail. Non-AI, pure,
 * unit-tested.
 *
 * Seen is one `context_mode` entity (id [ENTITY_ID], ADR-008 addendum 2026-10-13): [MailUnopenedFields.SEEN_AT] (Int64),
 * LWW, written by either app. Keep is [MailSenderFields.KEPT_AT] on the sender.
 */
object MailUnopenedFields {
    const val SEEN_AT = "seenAtMs"
}

/** One sender on the offer. */
data class MailUnopenedRow(
    val id: String,
    /** "The Weekly Digest". */
    val name: String,
    /** "Personal · 6 emails, none opened · last Mon 12 Oct". */
    val line: String,
)

data class MailUnopenedCard(
    /** "Not opened in 30 days". */
    val title: String,
    /** "Unsubscribe from 11?" */
    val headline: String,
    /** "Unsubscribe from all 11" (or "Unsubscribe" for one). */
    val unsubscribeLabel: String,
    val note: String,
    /** Busiest first, at most [MailUnopenedRules.SHOWN]. */
    val rows: List<MailUnopenedRow>,
    /** "+ 3 more · all unsubscribed by the button" when more than [MailUnopenedRules.SHOWN]; else null. */
    val moreLine: String?,
    /** Every sender the button asks for, the shown ones first. */
    val ids: List<String>,
    /** What a screen reader says for the card's header. */
    val spoken: String,
)

object MailUnopenedRules {
    const val ENTITY_ID = "mail_unopened_seen"
    const val TITLE = "Not opened in 30 days"
    const val KEEP = "Keep"
    const val NOT_NOW = "Not now"
    const val SHOW = "Show"
    const val HIDE = "Hide"
    const val NOTE = "You haven't opened any of their emails in the last 30 days. Navilife unsubscribes at its next check, " +
        "by one-click or the list's unsubscribe email from that account, and anything they send after goes to Trash (recoverable)."
    const val NOT_NOW_LINE = "Not opened in 30 days · asks again next Saturday"
    const val NONE_LEFT = "Nothing to unsubscribe from"
    /** At least this many emails in the 30 days, none opened. */
    const val MIN_EMAILS = 3
    /** The server's count older than this (it stopped checking that account) isn't trusted. */
    const val FRESH_MS = 8 * 86_400_000L
    /** Saturday (ISO), 09:00: the offer's week starts. */
    const val OFFER_WEEKDAY = 6
    const val OFFER_MINUTE = 9 * 60
    const val SHOWN = 8

    private val SERVER_CAN = setOf(UnsubscribeMethod.ONE_CLICK, UnsubscribeMethod.MAILTO, UnsubscribeMethod.CHECK)

    fun readSeen(fields: Map<String, FieldValue>?): Long? =
        (fields?.get(MailUnopenedFields.SEEN_AT) as? FieldValue.Int64)?.value?.takeIf { it > 0 }

    fun seenFields(atMs: Long): Map<String, FieldValue> = mapOf(MailUnopenedFields.SEEN_AT to atMs.fv())

    /** Not opened: at least [MIN_EMAILS] in 30 days, none opened, by a count the server made within [FRESH_MS]. */
    fun unopened(s: MailSender, nowMs: Long): Boolean {
        val at = s.unopenedAtMs ?: return false
        return s.unopened >= MIN_EMAILS && nowMs - at <= FRESH_MS
    }

    /**
     * On the offer: not opened, the server can unsubscribe by itself, never asked or tried (Mail shows those), not kept.
     */
    fun candidate(s: MailSender, nowMs: Long): Boolean =
        unopened(s, nowMs) && s.method in SERVER_CAN && s.askedAtMs == null && s.state == null && s.keptAtMs == null

    /** Mail's sender row adds "none opened in 30 days" (after the count) for a not-opened sender. */
    fun rowNote(s: MailSender, nowMs: Long): String? =
        if (unopened(s, nowMs) && s.state?.finished != true) "none opened in 30 days" else null

    /** The latest Saturday 09:00 at or before [nowMs]. */
    fun weekStartMs(nowMs: Long, cal: LocalCalendar): Long {
        val today = cal.epochDayOf(nowMs)
        var day = today - (CivilDate.isoDayOfWeek(today) - OFFER_WEEKDAY).mod(7)
        if (day == today && cal.minuteOfDay(nowMs) < OFFER_MINUTE) day -= 7
        return cal.toEpochMs(day, OFFER_MINUTE)
    }

    fun headline(n: Int): String = if (n == 1) "Unsubscribe from 1 sender?" else "Unsubscribe from $n?"

    fun unsubscribeLabel(n: Int): String = if (n == 1) MailSenderPanelRules.UNSUBSCRIBE else "Unsubscribe from all $n"

    /**
     * The offer, or null: due from this week's Saturday 09:00 while he hasn't answered it this week ([seenAtMs]: the
     * button or Not now, on either app) and a sender is a [candidate]. Busiest first (then newest).
     */
    fun card(senders: List<MailSender>, seenAtMs: Long?, nowMs: Long, cal: LocalCalendar): MailUnopenedCard? {
        val week = weekStartMs(nowMs, cal)
        if (seenAtMs != null && seenAtMs >= week) return null
        val all = senders.filter { candidate(it, nowMs) }
            .sortedWith(compareByDescending<MailSender> { it.unopened }.thenByDescending { it.lastAtMs }.thenBy { it.id })
        if (all.isEmpty()) return null
        val today = cal.epochDayOf(nowMs)
        val rows = all.take(SHOWN).map { s ->
            val day = cal.epochDayOf(s.lastAtMs)
            val last = when {
                s.lastAtMs <= 0 -> null
                day == today -> "last today"
                day == today - 1 -> "last yesterday"
                else -> "last ${CivilDate.shortLabel(day)}"
            }
            MailUnopenedRow(s.id, s.name, listOfNotNull(s.account, "${s.unopened} emails, none opened", last).joinToString(" · "))
        }
        val more = all.size - rows.size
        val head = headline(all.size)
        return MailUnopenedCard(
            title = TITLE,
            headline = head,
            unsubscribeLabel = unsubscribeLabel(all.size),
            note = NOTE,
            rows = rows,
            moreLine = if (more > 0) "+ $more more · the button unsubscribes from them too" else null,
            ids = all.map { it.id },
            spoken = "$TITLE. $head",
        )
    }

    /** The server refreshes an unchanged count this often, so [FRESH_MS] holds while it keeps checking. */
    const val REFRESH_MS = 86_400_000L
    const val WINDOW_MS = 30 * 86_400_000L

    /**
     * What the server writes on a sender after counting its emails in the last 30 days ([total], [opened] of them read):
     * the not-opened count (0 when one was opened) and when; null when the published value still holds (same count,
     * counted within [REFRESH_MS]) so a check doesn't rewrite it.
     */
    fun serverFields(previous: MailSender?, total: Int, opened: Int, nowMs: Long): Map<String, FieldValue>? {
        val count = if (opened > 0) 0 else total.coerceAtLeast(0)
        val at = previous?.unopenedAtMs
        if (previous != null && previous.unopened == count && at != null && nowMs - at < REFRESH_MS) return null
        return mapOf(MailSenderFields.UNOPENED to count.toLong().fv(), MailSenderFields.UNOPENED_AT to nowMs.fv())
    }

    /** What the button says once it asked for [n]. */
    fun askedLine(n: Int): String = when (n) {
        0 -> NONE_LEFT
        1 -> "Unsubscribing from 1 sender at the next check"
        else -> "Unsubscribing from $n senders at the next check"
    }

    fun keptLine(name: String): String = "Keeping $name · not offered again"

    fun keepFields(atMs: Long): Map<String, FieldValue> = mapOf(MailSenderFields.KEPT_AT to atMs.fv())

    /**
     * Undo on the button for one sender: clears its ask, only while the server hasn't acted on it (nothing sent yet).
     * Null when it already has (Mail then shows the outcome).
     */
    fun undoAsk(s: MailSender?): Map<String, FieldValue>? {
        if (s == null || !MailUnsubscribeRules.due(s)) return null
        return mapOf(MailSenderFields.ASKED_AT to 0L.fv())
    }
}
