package os.meka.core.domain

/**
 * Requests from people Meka watches become proposals (build plan V1, "Requests from my wife become tasks", slice 1).
 * Non-AI and pure: which messages may be read, what a model's proposals are allowed to become, the dates resolved
 * again from the message's own words, duplicates merged, and how the Needs you card reads on both apps.
 *
 * Messages are untrusted (ADR-006): a proposal never acts by itself; it is a card with Add · Change · Not a task, and
 * nothing here replies to anyone, marks anything read or dismisses a notification.
 */
enum class RequestKind(val wire: String, val verb: String, val decline: String) {
    TASK("task", "Add task", "Not a task"),
    WORK_FROM_HOME("work_from_home", "Work from home", "Not needed"),
    EVENT("event", "Add event", "Not an event"),
    REMINDER("reminder", "Remind me", "Not needed"),
    ;

    companion object {
        fun of(wire: String): RequestKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** A proposal as the server passed it on (the model's words, unchecked). */
data class RawRequestProposal(
    val kind: String,
    val title: String? = null,
    val date: String? = null,
    val time: String? = null,
    val words: String? = null,
)

/** A checked proposal: a known kind, a clean title, a day within reach (when it has one) and a valid time. */
data class RequestProposal(val kind: RequestKind, val title: String, val day: Long?, val minute: Int?)

/** One message from a watched person, as the listener read it. */
data class RequestMessage(
    /** The captured item's id (stable across WhatsApp's re-posts). */
    val id: String,
    val personName: String,
    val text: String,
    val atMs: Long,
)

/** The Needs you card for one proposal. */
data class RequestCard(
    /** The message's id and the proposal's place in it: the same card when the same message is read again. */
    val id: String,
    val messageId: String,
    val personKey: String,
    val proposal: RequestProposal,
    /** "From Wife · 14:02". */
    val from: String,
    /** "“can you pick up the dry cleaning tomorrow?”" (shortened at a word past [MessageRequestRules.QUOTE_CHARS]). */
    val quote: String,
    /** "Add task: Pick up dry cleaning · Tomorrow". */
    val action: String,
    /** What happens on Add, when it does more than its line says ("Sets Thu 15 Oct to Home and blocks the day"). */
    val detail: String?,
    val addLabel: String = "Add",
    val changeLabel: String = "Change",
    val declineLabel: String,
    /** One sentence for TalkBack / VoiceOver. */
    val spoken: String,
)

object MessageRequestRules {
    const val MAX_TITLE = 80
    const val QUOTE_CHARS = 140
    /** A proposed day further than this ahead is dropped (a model's misread year, not a plan). */
    const val MAX_DAYS_AHEAD = 366

    private val voiceNote = Regex("""^\W*(voice (message|note)|audio)\b""", RegexOption.IGNORE_CASE)
    private val media = Regex("""^\W*(photo|image|video|gif|sticker|document|contact card|location)\b""", RegexOption.IGNORE_CASE)

    /**
     * Whether [item] may be read for requests: a message (not a missed call) with text, from someone on the family list
     * or [watching] (Work mode → People → Watch for requests from), and from a 1:1 chat unless its group is in
     * [groups] (named groups Meka turned on).
     */
    fun shouldRead(item: CapturedItem, lists: PeopleLists, watching: Set<String>, groups: Set<String> = emptySet()): Boolean {
        if (item.kind != CaptureKind.MESSAGE || item.text.isNullOrBlank()) return false
        val group = item.conversation
        if (group != null && People.key(group) !in groups.map(People::key).toSet()) return false
        return isWatched(item.personName, lists, watching)
    }

    fun isWatched(name: String, lists: PeopleLists, watching: Set<String>): Boolean =
        lists.isFamily(name) || People.key(name) in watching.map(People::key).toSet()

    /** A voice note can't be read: MEKA offers to remind Meka to listen instead of asking the AI. */
    fun isVoiceNote(text: String): Boolean = voiceNote.containsMatchIn(text.trim())

    /** A photo, video, sticker…: nothing to read, nothing to propose. */
    fun isMediaOnly(text: String): Boolean = media.containsMatchIn(text.trim()) && text.trim().length <= 40

    /** Whether the AI should see [text] at all (not a voice note, not a bare photo). */
    fun worthAsking(text: String): Boolean = text.isNotBlank() && !isVoiceNote(text) && !isMediaOnly(text)

    /** The one proposal for a voice note, made on the phone with no AI: "Listen to Wife's voice note". */
    fun voiceNoteProposal(personName: String): RequestProposal =
        RequestProposal(RequestKind.REMINDER, cleanTitle("Listen to ${personName.trim()}'s voice note")!!, null, null)

    /**
     * The model's proposals checked: known kinds only; the day resolved again from the message's own when-words when
     * they read (the model's date is used only when they don't), never in the past or more than [MAX_DAYS_AHEAD]
     * ahead; work from home and events need a day; a task or reminder needs a title; duplicates within the answer
     * merged. [today] and [nowMinute] are the time the message came.
     */
    fun check(raw: List<RawRequestProposal>, today: Long, nowMinute: Int): List<RequestProposal> {
        val out = mutableListOf<RequestProposal>()
        raw.forEach { r ->
            val kind = RequestKind.of(r.kind) ?: return@forEach
            val fromWords = r.words?.let { RequestDates.resolve(it, today, nowMinute) }
            val day = (fromWords?.day ?: r.date?.let(::isoDay))?.takeIf { it in today..today + MAX_DAYS_AHEAD }
            val minute = r.time?.let(::minuteOf) ?: fromWords?.minute
            val title = when (kind) {
                RequestKind.WORK_FROM_HOME -> "Work from home"
                else -> cleanTitle(r.title) ?: return@forEach
            }
            if ((kind == RequestKind.WORK_FROM_HOME || kind == RequestKind.EVENT) && day == null) return@forEach
            val p = RequestProposal(kind, title, day, if (kind == RequestKind.WORK_FROM_HOME) null else minute)
            if (out.none { sameRequest(it, p) }) out += p
        }
        return out
    }

    /** The same request asked twice: the same kind and day, and titles that match ignoring case and punctuation. */
    fun sameRequest(a: RequestProposal, b: RequestProposal): Boolean =
        a.kind == b.kind && a.day == b.day && titleKey(a.title) == titleKey(b.title)

    /**
     * Cards for [proposals] read from [message], leaving out any [open] card already asks for (the same person asking
     * again, or WhatsApp re-posting the message). [today] is the day the cards are shown; [calendar] reads the time.
     */
    fun cards(
        message: RequestMessage,
        proposals: List<RequestProposal>,
        open: List<RequestCard>,
        today: Long,
        calendar: LocalCalendar,
    ): List<RequestCard> {
        val person = People.key(message.personName)
        val made = mutableListOf<RequestCard>()
        proposals.forEachIndexed { i, p ->
            val id = "${message.id}#$i"
            if (open.any { it.id == id || (it.personKey == person && sameRequest(it.proposal, p)) }) return@forEachIndexed
            if (made.any { sameRequest(it.proposal, p) }) return@forEachIndexed
            made += card(message, i, p, today, calendar)
        }
        return made
    }

    /** The card for proposal [index] of [message]; also how a stored card is shown again ([RequestCards.open]). */
    fun card(message: RequestMessage, index: Int, p: RequestProposal, today: Long, calendar: LocalCalendar): RequestCard {
        val quote = "“${shorten(message.text.replace(Regex("""\s+"""), " ").trim(), QUOTE_CHARS)}”"
        val action = actionLine(p, today)
        return RequestCard(
            id = "${message.id}#$index",
            messageId = message.id,
            personKey = People.key(message.personName),
            proposal = p,
            from = "From ${message.personName.trim().take(40)} · ${TaskWhenRules.timeLabel(calendar.minuteOfDay(message.atMs))}",
            quote = quote,
            action = action,
            detail = detailLine(p),
            declineLabel = p.kind.decline,
            spoken = "${message.personName.trim()} wrote $quote. $action?",
        )
    }

    /** "Add task: Pick up dry cleaning · Tomorrow", "Work from home · Thu 15 Oct", "Add event: Parents' evening · Tue 20 Oct · 18:00". */
    fun actionLine(p: RequestProposal, today: Long): String {
        val whenLine = p.day?.let { TaskWhenRules.label(it, p.minute, today) }
            ?: p.minute?.let { "Today · ${TaskWhenRules.timeLabel(it)}" }
        return when (p.kind) {
            RequestKind.WORK_FROM_HOME -> "Work from home · ${whenLine ?: "?"}"
            else -> "${p.kind.verb}: ${p.title}" + (whenLine?.let { " · $it" } ?: "")
        }
    }

    /** The line under a work-from-home card: what Add will change. */
    fun detailLine(p: RequestProposal): String? = when (p.kind) {
        RequestKind.WORK_FROM_HOME -> p.day?.let { "Sets ${CivilDate.shortLabel(it)} to Home and blocks the day" }
        else -> null
    }

    /** One line, no control characters, at most [MAX_TITLE] characters (shortened at a word); null when empty. */
    fun cleanTitle(s: String?): String? {
        val t = s?.replace(Regex("""[\u0000-\u001F\u007F]"""), " ")?.replace(Regex("""\s+"""), " ")?.trim()?.trim('"', '“', '”')?.trim()
        if (t.isNullOrEmpty()) return null
        val first = t.first().uppercaseChar() + t.drop(1)
        return shorten(first, MAX_TITLE)
    }

    private fun titleKey(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun shorten(s: String, max: Int): String {
        if (s.length <= max) return s
        val cut = s.take(max)
        val at = cut.lastIndexOf(' ').takeIf { it >= max / 2 } ?: max
        return cut.take(at).trimEnd() + "…"
    }

    private fun isoDay(s: String): Long? {
        val m = Regex("""(\d{4})-(\d{2})-(\d{2})""").matchEntire(s) ?: return null
        val (y, mo, d) = m.destructured
        val month = mo.toInt()
        if (month !in 1..12 || d.toInt() !in 1..CivilDate.lengthOfMonth(y.toInt(), month)) return null
        return CivilDate.toEpochDay(y.toInt(), month, d.toInt())
    }

    private fun minuteOf(s: String): Int? {
        val m = Regex("""(\d{2}):(\d{2})""").matchEntire(s) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) h * 60 + min else null
    }
}

/**
 * The when-words of a message ("tomorrow", "Thursday", "next Friday", "on the 15th", "15 Oct", "Thu at 6pm")
 * resolved against the day it came. "the 15th" alone is the next 15th (this month, else next month); everything else
 * reads as Add event's typing does ([EventTypingRules]), so the two agree.
 */
object RequestDates {
    data class Resolved(val day: Long?, val minute: Int?)

    private val ordinalOnly = Regex("""^(?:on\s+)?(?:the\s+)?(\d{1,2})(?:st|nd|rd|th)$""", RegexOption.IGNORE_CASE)

    fun resolve(words: String, today: Long, nowMinute: Int): Resolved? {
        val w = words.replace(Regex("""\s+"""), " ").trim().trimEnd('.', ',', '?', '!')
        if (w.isEmpty()) return null
        ordinalOnly.matchEntire(w)?.let { m -> return nthOfMonth(m.groupValues[1].toInt(), today)?.let { Resolved(it, null) } }
        val p = EventTypingRules.parse("x $w", today, nowMinute) ?: return null
        // Everything must be read: "Thursday after school" is not a date MEKA can trust.
        if (p.title != "x") return null
        if (p.day == null && p.minute == null) return null
        return Resolved(p.day, p.minute)
    }

    /** The next [n]th: this month when it is still to come (today counts), else the next month that has one. */
    private fun nthOfMonth(n: Int, today: Long): Long? {
        if (n !in 1..31) return null
        var (y, m, _) = CivilDate.fromEpochDay(today)
        repeat(3) {
            if (n <= CivilDate.lengthOfMonth(y, m)) {
                val day = CivilDate.toEpochDay(y, m, n)
                if (day >= today) return day
            }
            m += 1
            if (m > 12) { m = 1; y += 1 }
        }
        return null
    }
}
