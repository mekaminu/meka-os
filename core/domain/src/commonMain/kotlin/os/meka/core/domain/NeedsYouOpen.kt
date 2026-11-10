package os.meka.core.domain

/**
 * A chat's row opened in Needs you, as the approved look draws it (Meka 2026-11-09 22:33,
 * `docs/design/needs-you/needs-you.dc.html`, MESSAGES): the chat's messages as plain lines, then Reply · Done · Later ·
 * Task; a swipe right on the row is Done. Non-AI and pure. Nothing here sends, marks read or answers anything: Reply
 * only shows the chat's own card (its drafted reply and Send, still Meka's tap), Later sets the chat aside until the
 * next 08:00 ([TriageCards.later]), Task makes one task of it at his tap.
 */
data class NeedsYouLater(
    /** The messages set aside, for Undo ([TriageCards.back]). */
    val ids: List<String>,
    val untilMs: Long,
    /** "Your wife · back at 08:00" / "Your wife · back tomorrow 08:00", for the undo bar. */
    val line: String,
)

object NeedsYouOpenRules {
    const val REPLY = "Reply"
    const val DONE = "Done"
    const val LATER = "Later"
    const val TASK = "Task"
    const val SHOW_LESS = "Hide reply"
    /** Under MESSAGES (the mockup's foot line). */
    const val HINT = "Tap a person to see their messages · swipe right for Done"
    /** The most message lines an open row shows; earlier ones are counted on one line above them. */
    const val MAX_LINES = 5
    /** Later brings a chat back at this minute of the day (08:00). */
    const val BACK_MINUTE = 8 * 60
    /** Later never brings a chat back within the hour: 07:30's Later is tomorrow's 08:00. */
    const val MIN_AWAY_MS = 60 * 60_000L

    /**
     * Whether the row opens to the plain lines and four taps. A chat that carries requests (Add · Change · No each) keeps
     * its full card, so no request loses its own answers.
     */
    fun plain(chat: TriageChat): Boolean = chat.requests.isEmpty()

    /**
     * The chat's messages as lines, oldest first: each message's gist ("Asks if you're coming Saturday"), in a group with
     * who said it ("Sam: Asks you to post the time"); repeats in a row once; at most [MAX_LINES], the earlier ones as
     * "3 earlier messages" first; the group's chatter line last. Gists are display only, untrusted (ADR-006).
     */
    fun lines(chat: TriageChat): List<String> {
        val all = chat.cards.sortedBy { it.atMs }.map { c ->
            val gist = c.gist.lineSequence().firstOrNull()?.trim().orEmpty().ifEmpty { TriageCard.NO_GIST_LINE }
            if (chat.isGroup) "${who(c)}: $gist" else gist
        }.fold(mutableListOf<String>()) { acc, l -> if (acc.lastOrNull() != l) acc += l; acc }
        val shown = all.takeLast(MAX_LINES)
        val earlier = all.size - shown.size
        return buildList {
            if (earlier > 0) add(if (earlier == 1) "1 earlier message" else "$earlier earlier messages")
            addAll(shown)
            chat.chatterLine?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
    }

    private fun who(c: TriageCard): String = TriageReplyRules.who(c).substringBefore(" in ").trim().ifEmpty { "Someone" }

    /** The next 08:00 at least [MIN_AWAY_MS] from [nowMs]: before 07:00 today's, else tomorrow's. */
    fun laterUntil(nowMs: Long, cal: LocalCalendar): Long {
        val day = cal.epochDayOf(nowMs)
        val today = cal.toEpochMs(day, BACK_MINUTE)
        return if (today - nowMs >= MIN_AWAY_MS) today else cal.toEpochMs(day + 1, BACK_MINUTE)
    }

    /** "Your wife · back at 08:00" (later today), "Your wife · back tomorrow 08:00". */
    fun laterLine(title: String, untilMs: Long, nowMs: Long, cal: LocalCalendar): String {
        val time = TaskWhenRules.timeLabel(cal.minuteOfDay(untilMs))
        val sameDay = cal.epochDayOf(untilMs) == cal.epochDayOf(nowMs)
        return "$title · back ${if (sameDay) "at" else "tomorrow"} $time"
    }

    /** "Reply to Your wife" for a person, "Reply in Game testing group" for a group. */
    fun taskTitle(chat: TriageChat): String = (if (chat.isGroup) "Reply in " else "Reply to ") + chat.title.trim().take(60)

    /** The task's notes: where it came from, then the lines. */
    fun taskNotes(chat: TriageChat): String {
        val app = chat.app?.label ?: "a message"
        return "From $app in Needs you.\n" + lines(chat).joinToString("\n") { "• $it" }
    }

    /** "Task added · Reply to Your wife", for the undo bar. */
    fun taskLine(title: String): String = "Task added · $title"

    /** One sentence for the open view's screen reader: the messages read in order. */
    fun spoken(chat: TriageChat): String = "${chat.title}'s messages: " + lines(chat).joinToString(". ") { it.trimEnd('.') } + "."
}
