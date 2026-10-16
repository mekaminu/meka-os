package os.meka.core.domain

/**
 * Drafted replies, slice 3 (2026-10-16): **every reply he sends shows in Activity**. One row per email he tapped Send
 * on (his own reply or Navilife's draft), read from the synced `mail_item` itself — like Unsubscribe's rows, nothing new
 * is stored and nothing migrates:
 * - while the server holds it ([MailNeedsRules.REPLY_HOLD_MS] after Send, until its next check): "Sending reply to Sam ·
 *   Personal" with **Undo**, which stops it exactly as the bar's Undo does (the email comes back to the card);
 * - once the server sent it: "Replied to Sam · Personal", detail "Re: Saturday · “Yes, I can…” · Sent". Gmail and
 *   Outlook can't recall a sent email through their APIs, so the hold is the Undo window and a sent row has none;
 * - when it couldn't be sent: "Reply to Sam didn't send · couldn't reach Gmail", his words kept on the card;
 * - taken back in time: "Reply to Sam not sent", shown as undone at the moment he took it back.
 * His reply and the subject are shown as plain text only (ADR-006); nothing here sends anything.
 */
object MailSentActivityRules {
    const val SOURCE = "mail_reply"
    const val WHY_OWN = "You tapped Send in Mail · it goes from that account, in its thread"
    const val WHY_DRAFT = "You tapped Send on Navilife's draft · it goes from that account, in its thread"
    const val SENT = "Sent"
    const val HOLD_NOTE = "goes at Navilife's next check · Undo stops it"
    const val KEPT_NOTE = "your words are kept on the card"
    const val STOPPED = "Reply stopped · the email is back in From your mail"
    const val TOO_LATE = "Already sent · Gmail and Outlook can't take a sent email back"
    private const val QUOTE_MAX = 60
    /** Emails of one thread archived with a reply are marked Done within this of its Send (the same tap). */
    const val LINKED_MS = 2_000L

    /** Row id for the reply on email [mailId]: one per email, on every device. */
    fun id(mailId: String): String = "s$mailId"

    fun isReply(id: String): Boolean = id.startsWith("s")

    fun mailIdOf(id: String): String = id.removePrefix("s")

    /** Was the reply he sent Navilife's draft as it was written? */
    fun fromDraft(i: MailItem): Boolean = i.autoDraft != null && i.replyText?.trim() == i.autoDraft.trim()

    /** He took it back before the server sent it (or gave up on it). */
    fun stopped(i: MailItem): Boolean {
        val asked = i.replyAskedAtMs ?: return false
        if (MailNeedsRules.replySent(i) || MailNeedsRules.replyFailed(i)) return false
        return i.undoAskedAtMs != null && i.undoAskedAtMs >= asked
    }

    /** The reply's first line, quoted and cut: "“Yes, I can take them…”". */
    fun quote(text: String): String {
        val line = text.trim().lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        val cut = if (line.length > QUOTE_MAX) line.take(QUOTE_MAX - 1).trimEnd() + "…" else line
        return "“$cut”"
    }

    private fun re(subject: String): String {
        val s = subject.trim()
        return when {
            s.isEmpty() -> "(no subject)"
            s.startsWith("re:", ignoreCase = true) -> s
            else -> "Re: $s"
        }
    }

    /** The Activity row for the reply on [i], or null when he never tapped Send on it. */
    fun item(i: MailItem): ActivityItem? {
        val asked = i.replyAskedAtMs ?: return null
        val text = i.replyText?.takeIf { it.isNotBlank() } ?: return null
        val who = i.from.ifBlank { "the sender" }
        val quote = quote(text)
        val (summary, detail, at) = when {
            MailNeedsRules.replySent(i) -> Triple("Replied to $who · ${i.account}", "${re(i.subject)} · $quote · $SENT", i.replySentAtMs!!)
            MailNeedsRules.replyFailed(i) -> Triple(
                "Reply to $who didn't send · ${i.replyFailedWhy ?: "try again"}", "${re(i.subject)} · $KEPT_NOTE", i.replyFailedAtMs!!,
            )
            stopped(i) -> Triple("Reply to $who not sent · ${i.account}", "${re(i.subject)} · $quote", asked)
            else -> Triple("Sending reply to $who · ${i.account}", "${re(i.subject)} · $quote · $HOLD_NOTE", asked)
        }
        return ActivityItem(
            id = id(i.id), atMs = at, kind = ActivityKind.CHANGED,
            summary = summary, detail = detail, why = if (fromDraft(i)) WHY_DRAFT else WHY_OWN, source = SOURCE, level = null,
            changes = emptyList(), undoneAtMs = if (stopped(i)) i.undoAskedAtMs else null, undoNote = null,
            mailReplyWaiting = MailNeedsRules.replyWaiting(i),
        )
    }

    /**
     * The emails to take back with the reply on [reply] when Undo stops it: the email itself, and the others of its
     * thread that the same Send archived (Done within [LINKED_MS] of it and not taken back since).
     */
    fun undoGroup(reply: MailItem, all: List<MailItem>): List<String> {
        val asked = reply.replyAskedAtMs ?: return listOf(reply.id)
        val key = reply.threadKey ?: return listOf(reply.id)
        val linked = all.filter { o ->
            o.id != reply.id && o.threadKey == key && MailNeedsRules.done(o) &&
                o.doneAskedAtMs != null && kotlin.math.abs(o.doneAskedAtMs - asked) <= LINKED_MS
        }
        return listOf(reply.id) + linked.map { it.id }
    }
}
