package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.fv

/*
 * "Archived for you" shows which emails, slice 2 (Meka, 2026-10-15 20:29): **tap an email → its preview**, with
 * **Put back** (just that email back where it was), **Always keep from this sender** (his "never tidy" rule on the sender
 * and the email put back) and **Unsubscribe** where the email offered one. The apps write the ask on the tidy's
 * `mail_tidy` entity ([MailTidyBatchFields.EMAIL_ASKS], one line per ask); the server acts at its next check and answers
 * in [MailTidyBatchFields.EMAIL_DONE] with the line both apps show under the email. Which address a rule or an
 * unsubscribe is for stays on the server (it finds the email by its key). Every word of the preview is the sender's
 * (ADR-006): shown as plain text, never acted on. Non-AI, pure, unit-tested.
 */

enum class MailTidiedAction(
    /** What the email says while the server hasn't acted yet. */
    val pending: String,
) {
    PUT_BACK("Putting back at Navilife's next check…"),
    KEEP("Keeping this sender at Navilife's next check…"),
    UNSUBSCRIBE("Unsubscribing at Navilife's next check…");

    companion object {
        fun of(stored: String?): MailTidiedAction? = entries.firstOrNull { it.name == stored }
    }
}

/** One ask on one email of a tidy. */
data class MailTidiedAsk(val key: String, val action: MailTidiedAction, val atMs: Long)

/** The server's answer to an ask: when it acted and the line the apps show ("Back in the inbox"). */
data class MailTidiedDone(val key: String, val action: MailTidiedAction, val atMs: Long, val note: String)

/** An email's preview, opened from its row in Archived for you. */
data class ArchivedEmailPreview(
    val batchId: String,
    val key: String,
    /** "Deliveroo". */
    val from: String,
    /** "deliveroo.co.uk · Personal". */
    val meta: String,
    val subject: String,
    /** "Mon 12 Oct · 14:02" (always with the day). */
    val time: String,
    /** "Promo", "Receipt £12.99", "Junk · Trash", … */
    val chip: String,
    /** The provider's short preview, or null when the tidy didn't keep one (older tidies, or a full list). */
    val lines: String?,
    /** "Putting back at Navilife's next check…" or the server's answer; null while nothing was asked. */
    val state: String?,
    /** "Put back" / "Put back in Junk", or null when it can't be (already back, too old, the whole tidy put back). */
    val putBack: String?,
    /** "Always keep from Deliveroo", or null. */
    val keep: String?,
    /** "Unsubscribe from Deliveroo" where the email offered a way, or null. */
    val unsubscribe: String?,
    val note: String,
    val spoken: String,
    /** Slice 4: "Open in Gmail" / "Open in Outlook" ([MailOpenRules.label]); null where the caller didn't ask. */
    val openLabel: String? = null,
    /** Slice 4: the line under it ([MailOpenLink.hint]) once the account is known, else null. */
    val openHint: String? = null,
)

object MailTidiedAskRules {
    /** Asks kept on one tidy (newest); far more than one tidy's emails times the three actions ever need. */
    const val MAX_ASKS = 300
    const val MAX_NOTE = 140
    const val NO_LINES = "No preview kept for this email · open it in Gmail or Outlook to read it all"
    const val NOTE = "Shown as the sender wrote it, nothing in it is acted on. Put back returns just this email; " +
        "Always keep also stops Autopilot tidying this sender."
    const val UNKNOWN = "Couldn't find that email"
    const val CANT = "Already asked"

    /** What can be kept from: mail Autopilot tidied out of the inbox (not the Junk review's). */
    private val KEEPABLE = setOf(MailTidy.NEWSLETTER, MailTidy.RECEIPT, MailTidy.ARCHIVE, MailTidy.TRASH)
    /** What can be unsubscribed from here: newsletters and promos (never spam: that only confirms the address). */
    private val UNSUBSCRIBABLE = setOf(MailTidy.NEWSLETTER, MailTidy.ARCHIVE)

    private fun clean(s: String) = s.map { if (it == '\t' || it == '\n' || it == '\r' || it.code < 0x20) ' ' else it }
        .joinToString("").trim().take(MAX_NOTE)

    fun encodeAsks(asks: List<MailTidiedAsk>): String =
        asks.takeLast(MAX_ASKS).joinToString("\n") { "${it.key}\t${it.action.name}\t${it.atMs}" }

    fun decodeAsks(text: String?): List<MailTidiedAsk> = text.orEmpty().split('\n').mapNotNull { l ->
        val p = l.split('\t')
        if (p.size < 3) return@mapNotNull null
        MailTidiedAsk(p[0].takeIf { it.isNotBlank() } ?: return@mapNotNull null, MailTidiedAction.of(p[1]) ?: return@mapNotNull null,
            p[2].toLongOrNull() ?: return@mapNotNull null)
    }

    fun encodeDone(done: List<MailTidiedDone>): String =
        done.takeLast(MAX_ASKS).joinToString("\n") { "${it.key}\t${it.action.name}\t${it.atMs}\t${clean(it.note)}" }

    fun decodeDone(text: String?): List<MailTidiedDone> = text.orEmpty().split('\n').mapNotNull { l ->
        val p = l.split('\t')
        if (p.size < 4) return@mapNotNull null
        MailTidiedDone(p[0].takeIf { it.isNotBlank() } ?: return@mapNotNull null, MailTidiedAction.of(p[1]) ?: return@mapNotNull null,
            p[2].toLongOrNull() ?: return@mapNotNull null, p[3].ifBlank { "Done" })
    }

    private fun answered(b: MailTidyBatch, a: MailTidiedAsk): MailTidiedDone? =
        b.emailDone.lastOrNull { it.key == a.key && it.action == a.action && it.atMs >= a.atMs }

    /** The asks the server still has to act on, oldest first. */
    fun pending(b: MailTidyBatch): List<MailTidiedAsk> = b.emailAsks.filter { answered(b, it) == null }.sortedBy { it.atMs }

    /** What the server writes after acting on some asks: every earlier answer kept, these added. */
    fun doneFields(b: MailTidyBatch, adds: List<MailTidiedDone>): Map<String, FieldValue> =
        mapOf(MailTidyBatchFields.EMAIL_DONE to encodeDone(b.emailDone + adds).fv())

    /** The line under an email: its latest ask, answered or not; null while nothing was asked. */
    fun state(b: MailTidyBatch, key: String): String? {
        val last = b.emailAsks.filter { it.key == key }.maxByOrNull { it.atMs } ?: return null
        return answered(b, last)?.note ?: last.action.pending
    }

    private fun inWindow(b: MailTidyBatch, nowMs: Long) = nowMs - b.atMs <= MailAutopilotRules.UNDO_DAYS * 86_400_000L

    /** What can still be asked for [e] in [b]. */
    fun available(b: MailTidyBatch, e: MailTidiedEmail, nowMs: Long): Set<MailTidiedAction> {
        if (!inWindow(b, nowMs) || b.backlog) return emptySet()
        val asked = b.emailAsks.filter { it.key == e.key }.map { it.action }.toSet()
        val back = MailTidiedAction.PUT_BACK in asked || MailTidiedAction.KEEP in asked
        return buildSet {
            if (b.canUndo && !back && e.tidy != MailTidy.KEEP) add(MailTidiedAction.PUT_BACK)
            if (e.tidy in KEEPABLE && MailTidiedAction.KEEP !in asked) add(MailTidiedAction.KEEP)
            if (e.unsubscribe && e.tidy in UNSUBSCRIBABLE && MailTidiedAction.UNSUBSCRIBE !in asked) add(MailTidiedAction.UNSUBSCRIBE)
        }
    }

    /** The ask's write, or null when it can't be asked (gone, already asked, too old). */
    fun ask(b: MailTidyBatch, key: String, action: MailTidiedAction, nowMs: Long): Map<String, FieldValue>? {
        val e = b.emails.firstOrNull { it.key == key } ?: return null
        if (action !in available(b, e, nowMs)) return null
        return mapOf(MailTidyBatchFields.EMAIL_ASKS to encodeAsks(b.emailAsks + MailTidiedAsk(key, action, nowMs)).fv())
    }

    /** Undo on the bar: the ask comes off while the server hasn't acted on it; null when it already has (or there's none). */
    fun unask(b: MailTidyBatch, key: String, action: MailTidiedAction): Map<String, FieldValue>? {
        val a = b.emailAsks.lastOrNull { it.key == key && it.action == action } ?: return null
        if (answered(b, a) != null) return null
        return mapOf(MailTidyBatchFields.EMAIL_ASKS to encodeAsks(b.emailAsks - a).fv())
    }

    /** What the bar says after the tap. */
    fun barLine(action: MailTidiedAction, from: String, junk: Boolean = false): String = when (action) {
        MailTidiedAction.PUT_BACK -> if (junk) "Putting $from's email back in Junk at Navilife's next check" else "Putting $from's email back in the inbox at Navilife's next check"
        MailTidiedAction.KEEP -> "$from stays in the inbox from now on · this email goes back at Navilife's next check"
        MailTidiedAction.UNSUBSCRIBE -> "Unsubscribing from $from at Navilife's next check"
    }

    /** The preview of one email, or null when the tidy or the email isn't there any more. */
    fun preview(b: MailTidyBatch, key: String, nowMs: Long, cal: LocalCalendar): ArchivedEmailPreview? {
        val e = b.emails.firstOrNull { it.key == key } ?: return null
        val can = available(b, e, nowMs)
        val day = cal.epochDayOf(e.receivedAtMs)
        val time = "${CivilDate.shortLabel(day)} · ${LocalClock.formatMinute(cal.minuteOfDay(e.receivedAtMs))}"
        val meta = listOfNotNull(e.domain, b.account).joinToString(" · ")
        val junk = e.tidy == MailTidy.JUNK_TRASH || e.tidy == MailTidy.RESCUE
        val chip = MailTidiedRules.chip(e)
        val state = state(b, key)
        return ArchivedEmailPreview(
            batchId = b.id, key = key, from = e.from, meta = meta, subject = e.subject, time = time, chip = chip,
            lines = e.preview, state = state,
            putBack = if (MailTidiedAction.PUT_BACK in can) (if (junk) "Put back in Junk" else ArchivedForYouRules.PUT_BACK) else null,
            keep = if (MailTidiedAction.KEEP in can) "Always keep from ${e.from}" else null,
            unsubscribe = if (MailTidiedAction.UNSUBSCRIBE in can) "Unsubscribe from ${e.from}" else null,
            note = NOTE,
            spoken = listOfNotNull("${e.from}, $meta", e.subject, "$chip, $time", e.preview ?: NO_LINES, state).joinToString(". ") + ".",
        )
    }
}
