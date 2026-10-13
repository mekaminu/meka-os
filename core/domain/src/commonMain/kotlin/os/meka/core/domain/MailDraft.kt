package os.meka.core.domain

/**
 * Inbox autopilot, slice 6b-2b: **Navilife drafts the reply, he sends it** (Meka, 2026-10-12: "Reply (AI draft in his
 * tone, sent from the same account only on tap)"). Non-AI, pure, unit-tested.
 *
 * In the reply editor on an email in "From your mail", **Draft it** asks MEKA's AI once (`POST /v1/ai/mail-draft`,
 * metered `mail.draft`, Activity's "Mail") with what the card already shows — the account's label, the sender's name,
 * the subject and the provider's short preview — and, so it sounds like him, up to [MAX_EXAMPLES] replies he wrote and
 * sent from Navilife before ([examples]; his own words, never anyone else's). The draft comes back into the editor, where
 * he reads and changes it; **nothing is sent until he taps Send** (slice 6b-2a's path, Level 3). A draft is kept only
 * when [check] passes: one to six paragraphs, at most [HandleThisRules.MAX_REPLY] characters, no link, email address,
 * long number or placeholder (the same screen as Handle this and the messages assistant), so an email can't put an
 * address or a link in his mouth. Draft it never replaces words he typed: it is offered while the editor is empty or
 * still holds the last draft unchanged ([canDraft]).
 */
object MailDraftRules {
    const val DRAFT = "Draft it"
    const val AGAIN = "Another draft"
    const val DRAFTING = "Drafting…"
    const val DRAFTED_LINE = "Navilife's draft · read it and change anything · it goes only when you tap Send"
    const val UNUSABLE_LINE = "Navilife couldn't draft this one · write it yourself"
    const val MAX_EXAMPLES = 3
    const val MAX_EXAMPLE = 600

    /**
     * His own earlier replies, newest sent first, for the model to match his tone: only replies the server sent (his
     * words as he tapped Send), different ones, each cut to [MAX_EXAMPLE].
     */
    fun examples(items: List<MailItem>): List<String> =
        items.filter { MailNeedsRules.replySent(it) && !it.replyText.isNullOrBlank() }
            .sortedByDescending { it.replySentAtMs }
            .map { it.replyText!!.trim().take(MAX_EXAMPLE).trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_EXAMPLES)

    /** The model's draft as the editor takes it, or null (dropped whole) — [HandleThisRules.cleanReply]'s screen. */
    fun check(raw: String?): String? = HandleThisRules.cleanReply(raw)

    /** Draft it is offered while the editor is empty or still holds [lastDraft] unchanged; never over his own words. */
    fun canDraft(editor: String, lastDraft: String?): Boolean = editor.isBlank() || (lastDraft != null && editor.trim() == lastDraft.trim())

    /** The pill's label: [DRAFT] at first, [AGAIN] once the editor holds a draft. */
    fun label(editor: String): String = if (editor.isBlank()) DRAFT else AGAIN
}

/** What Draft it brought back: [text] for the editor (null when nothing usable came) and the line to show under it. */
data class MailDraft(val text: String?, val line: String)
