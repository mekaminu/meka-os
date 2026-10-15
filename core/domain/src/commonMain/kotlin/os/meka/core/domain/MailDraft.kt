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

/**
 * Email triage completeness (2026-10-15): **Draft replies** and **Send all** on "From your mail" (Meka, 2026-10-07:
 * "easy replies are batch-approved ('Send all 5'), never sent on their own"). Non-AI, pure, unit-tested.
 *
 * **Draft replies** on the card (two or more emails showing) asks [MailDraftRules]'s Draft it once per shown email whose
 * editor is empty or still holds Navilife's last draft, one after another, and opens each email's editor with its draft:
 * every draft is there to read and change before anything goes. **Send all N** appears once two or more open editors
 * hold an easy reply ([easy]: words that could be sent, at most [MAX_EASY] characters in at most [MAX_PARAGRAPHS]
 * paragraphs; a long one is sent on its own Send after a closer read). One tap sends each through Send's own path
 * (`MekaCore.replyToMailItems`): each from the account its email came to, in its thread, at the server's next check
 * once [MailNeedsRules.REPLY_HOLD_MS] have passed, so the one Undo on the bar stops them all. Nothing is sent without
 * that tap, and Navilife never sends a draft by itself.
 */
object MailSendAllRules {
    const val MAX_EASY = 500
    const val MAX_PARAGRAPHS = 3
    const val DRAFTING = "Drafting replies…"
    const val NONE_DRAFTED = "Navilife couldn't draft these · write them yourself"
    const val ALL_HIS = "Each reply already holds your own words · Navilife drafts only into an empty one"
    const val LINE = "Each goes from the account its email came to, only when you tap · read each one first"

    /** "Draft replies to 3", offered with two or more emails showing; null otherwise (one email has its own Draft it). */
    fun draftLabel(rows: Int): String? = if (rows >= 2) "Draft replies to $rows" else null

    fun draftSpoken(rows: Int): String = "Let Navilife draft replies to the $rows emails for you to read; nothing is sent"

    /** A reply that may go with Send all: sendable, short and at most [MAX_PARAGRAPHS] paragraphs. */
    fun easy(text: String): Boolean {
        if (MailNeedsRules.replyProblem(text) != null) return false
        val t = text.replace("\r\n", "\n").trim()
        if (t.length > MAX_EASY) return false
        val paragraphs = t.split("\n").fold(mutableListOf(StringBuilder())) { acc, line ->
            if (line.isBlank()) { if (acc.last().isNotEmpty()) acc.add(StringBuilder()) } else acc.last().append(line)
            acc
        }.count { it.isNotEmpty() }
        return paragraphs <= MAX_PARAGRAPHS
    }

    /**
     * "Send all 3" over the shown [rows] whose open editor holds an easy reply ([texts]: email id → the editor's words),
     * in the card's order; null with fewer than two.
     */
    fun sendAll(rows: List<MailNeedsRow>, texts: Map<String, String>): MailSendAll? {
        val ready = rows.filter { r -> texts[r.id]?.let { easy(it) } == true }
        if (ready.size < 2) return null
        val names = ready.map { it.from }.distinct()
        val who = when (names.size) {
            1 -> names[0]
            2 -> "${names[0]} and ${names[1]}"
            else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
        }
        return MailSendAll(
            ids = ready.map { it.id },
            texts = ready.map { texts.getValue(it.id).trim() },
            label = "Send all ${ready.size}",
            spoken = "Send the ${ready.size} short replies to $who, each from the account its email came to",
        )
    }

    /** What Draft replies says once it's done: how many drafts are in the editors, or why none is. */
    fun draftedLine(drafted: Int, asked: Int, lastProblem: String?): String = when {
        asked == 0 -> ALL_HIS
        drafted == 0 -> lastProblem ?: NONE_DRAFTED
        drafted == asked -> "Drafted ${plural(drafted)} · read each one and change anything · nothing goes until you tap Send all or Send"
        else -> "Drafted ${plural(drafted)} of $asked · write the others yourself · nothing goes until you tap"
    }

    /** The bar's line after Send all. */
    fun sendingLine(n: Int): String =
        if (n == 1) MailNeedsRules.replySendingLine("its account")
        else "$n replies sending at Navilife's next check, each from the account its email came to · Undo stops them all"

    private fun plural(n: Int) = if (n == 1) "1 reply" else "$n replies"
}

/** Send all: the emails (card order) and the words each one sends. */
data class MailSendAll(val ids: List<String>, val texts: List<String>, val label: String, val spoken: String)
