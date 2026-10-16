package os.meka.core.domain

/**
 * **Drafted replies come to him**, slice 4: Navilife's draft of a reply on the Galaxy Watch, with **Send** and
 * **Not needed** (Meka, 2026-10-15: "haven't seen it ask me to approve a draft"). Pure, no AI; nothing is stored.
 *
 * The watch keeps its own copy of the day, emails included, so it reads the same drafts the Fold's and the Mac's From your
 * mail show ([MailAutoDraftRules.draftShowing]): the newest one, who it's from, what they want and the whole draft. Send
 * is his tap and goes through the same reply path as the phone's (the server holds it [MailNeedsRules.REPLY_HOLD_MS] and
 * the watch offers Undo for [UNDO_MS], inside that hold). **Nothing is sent without his tap** (Level 3, hard rule), and
 * a draft too long to read whole on the wrist ([MAX_CHARS]) is never sent from it: the watch says to send it from the
 * phone and offers only Not needed. Edit stays on the phone and the Mac.
 */
data class WatchDraft(
    val mailId: String,
    /** "DRAFTED REPLY" · "DRAFTED REPLY · 1 OF 3". */
    val label: String,
    /** "Sam Taylor · Personal". */
    val from: String,
    /** "Can you take the boys on Saturday?" — their words; null when there are none. */
    val wants: String?,
    /** The draft as the wrist shows it: whole, or cut with "…" when [canSend] is false. */
    val draft: String,
    /** The draft fits the wrist whole, so Send is offered. */
    val canSend: Boolean,
    /** "Goes only when you tap Send" · "Long draft · send it from your phone". */
    val note: String,
    /** "+2 more on your phone"; null with one. */
    val more: String?,
    val spoken: String,
)

object WatchDraftRules {
    const val LABEL = "DRAFTED REPLY"
    const val SEND = "Send"
    const val NOT_NEEDED = "Not needed"
    const val UNDO = "Undo"
    const val NOTE = "Goes only when you tap Send"
    const val TOO_LONG = "Long draft · send it from your phone"
    const val PUT_AWAY = "Draft put away · the email stays on your phone"
    const val TAKEN_BACK = "Taken back · not sent"
    const val GONE = "That email has gone · nothing sent"

    /** The longest draft the wrist shows whole (about six short lines at caption size, scrolled). */
    const val MAX_CHARS = 420

    /** How long the watch shows Undo after Send: inside the server's 20-second hold, so Undo always stops it. */
    const val UNDO_MS = 15_000L

    /** How long the line after Not needed (with Undo) or after Undo stays. */
    const val LINE_MS = 5_000L

    /** The drafts waiting on him, newest first: the emails on From your mail showing Navilife's draft. */
    fun waiting(items: List<MailItem>, nowMs: Long): List<MailItem> =
        MailNeedsRules.showingSorted(items, nowMs).filter { MailAutoDraftRules.draftShowing(it) }

    /** The newest draft for the wrist, or null when none waits. */
    fun view(items: List<MailItem>, nowMs: Long): WatchDraft? {
        val drafts = waiting(items, nowMs)
        val i = drafts.firstOrNull() ?: return null
        val text = i.autoDraft.orEmpty().trim()
        val fits = text.length <= MAX_CHARS
        val shown = if (fits) text else cut(text)
        val n = drafts.size
        val wants = i.wants?.trim()?.takeIf { it.isNotEmpty() }
        val name = i.from.trim().ifEmpty { "Someone" }
        val spoken = buildString {
            append("Drafted reply to $name")
            wants?.let { append(". They ask: $it") }
            append(". Navilife's draft: $shown")
            append(if (fits) ". It goes only when you tap Send." else ". It's long: send it from your phone.")
            if (n > 1) append(" ${n - 1} more on your phone.")
        }
        return WatchDraft(
            mailId = i.id,
            label = if (n > 1) "$LABEL · 1 OF $n" else LABEL,
            from = "$name · ${i.account}",
            wants = wants,
            draft = shown,
            canSend = fits,
            note = if (fits) NOTE else TOO_LONG,
            more = if (n > 1) "+${n - 1} more on your phone" else null,
            spoken = spoken,
        )
    }

    /** The draft [mailId] the watch may send now: still showing and short enough to have been read whole; else null. */
    fun sendable(items: List<MailItem>, mailId: String, nowMs: Long): String? {
        val i = waiting(items, nowMs).firstOrNull { it.id == mailId } ?: return null
        val text = i.autoDraft.orEmpty().trim()
        return text.takeIf { it.isNotEmpty() && it.length <= MAX_CHARS }
    }

    /** "Sending to Sam · Undo stops it" (the first name only; the wrist is small). */
    fun sendingLine(from: String): String {
        val first = from.trim().substringBefore(' ').ifEmpty { "them" }
        return "Sending to $first · Undo stops it"
    }

    private fun cut(text: String): String {
        val head = text.take(MAX_CHARS - 1)
        val space = head.lastIndexOf(' ')
        val t = if (space > MAX_CHARS / 2) head.substring(0, space) else head
        return t.trimEnd(',', '.', ';', ':', '-', ' ') + "…"
    }
}
