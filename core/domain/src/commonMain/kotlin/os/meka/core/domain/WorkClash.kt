package os.meka.core.domain

/** What an event clashes with: work itself, the morning commute ([LEAVING]) or the commute home ([HOME]). */
enum class ClashWith { WORK, LEAVING, HOME }

/**
 * An event during Meka's work hours (Fold review 2026-10-12, bug 4: Wed 14 Oct "Training 16:55–17:55" overlapped
 * "Work until 17:30" and nothing said so), or — on an office day — while he is still on his way there or back (the
 * commute counts everywhere, slice 3: "Training 18:00 — you're home about 18:50 · who's taking?"). Non-AI, pure,
 * built by [WorkClashRules].
 */
data class WorkClash(
    val eventId: String,
    val title: String,
    val startMs: Long,
    /** The event's local start minute (for "16:55" and the spoken "4:55"). */
    val startMinute: Int,
    /** Whose club training or fixture it is ("Rex" for U7s, "Logan" for U10s), when the title or calendar says. */
    val child: String?,
    /** Someone has to take a child (a club fixture, or training or practice): the line asks "who's taking?". */
    val needsCover: Boolean,
    /** "Work", or "Work from home" on a home day. */
    val workTitle: String,
    /** When work ends that day, "17:30". */
    val workUntil: String,
    /** When work ends, as a local minute (for the spoken "half past 5"). */
    val workUntilMinute: Int = 0,
    /** Work, or the commute either side of it on an office day ([CommuteRules.bands]). */
    val against: ClashWith = ClashWith.WORK,
    /** For a commute clash: when he leaves ([ClashWith.LEAVING]) or gets home ([ClashWith.HOME]), as a local minute. */
    val commuteMinute: Int = 0,
) {
    private val time: String get() = LocalClock.formatMinute(startMinute)
    private val commuteAt: String get() = LocalClock.formatMinute(commuteMinute)
    /** "you're home about 18:50" · "you leave about 07:40" */
    private val commuteWords: String
        get() = if (against == ClashWith.HOME) "you're home about $commuteAt" else "you leave about $commuteAt"
    private val home: Boolean get() = workTitle == WorkHours.HOME_TITLE
    private val whileAt: String get() = if (home) "while you're working from home" else "while you're at work"
    private val question: String get() = if (child == null) "who's taking?" else "who's taking $child?"

    /**
     * The event row's mark, lit in the accent on Today and the Calendar: "Clashes with work · who's taking Rex?" for a
     * child's training or match, "Clashes with work until 17:30" for anything else.
     */
    val mark: String
        get() {
            if (against != ClashWith.WORK) {
                return if (needsCover) commuteWords.replaceFirstChar { it.uppercase() } + " · $question"
                else if (against == ClashWith.HOME) "Before you're home · about $commuteAt" else "After you leave · about $commuteAt"
            }
            val what = if (home) "Clashes with work from home" else "Clashes with work"
            return if (needsCover) "$what · $question" else "$what until $workUntil"
        }

    /**
     * Today's line: "Training at 16:55 while you're at work — who's taking Rex?" · "Dentist at 10:00 while you're at
     * work until 17:30".
     */
    val line: String
        get() = if (against != ClashWith.WORK) "$title at $time — $commuteWords" + (if (needsCover) " · $question" else "")
        else if (needsCover) "$title at $time $whileAt — $question" else "$title at $time $whileAt until $workUntil"

    /**
     * As the brief says it, with [clock] saying times aloud and [said] the title ("Rex's under-sevens training"):
     * "Heads up: Rex's under-sevens training at 4:55, while you're at work. Who's taking Rex?"
     */
    fun spoken(said: String, clock: (Int) -> String): String {
        if (against != ClashWith.WORK) {
            val first = if (against == ClashWith.HOME) "Heads up: $said at ${clock(startMinute)}, before you're home at about ${clock(commuteMinute)}"
            else "Heads up: $said at ${clock(startMinute)}, after you've left for work at about ${clock(commuteMinute)}"
            return if (needsCover) "$first. " + question.replaceFirstChar { it.uppercase() } else "$first."
        }
        val first = "Heads up: $said at ${clock(startMinute)}, $whileAt"
        return if (needsCover) "$first. " + question.replaceFirstChar { it.uppercase() }
        else "$first until ${clock(workUntilMinute)}."
    }
}

/**
 * Which of a day's events fall inside work hours. An event clashes when it is timed and overlaps a [WorkBlock] of its
 * day; all-day things, the fixtures feed (watching Barça isn't a clash) and Outlook (the work calendar's own
 * meetings) never do. A club fixture or training names the child from its age group in the title or, failing that,
 * the calendar's name ("BUFC U7s" → Rex, "U10s" → Logan, [FootballRules.CHILDREN]).
 *
 * The commute counts everywhere, slice 3: with the day's [CommuteBand]s (office days only), an event that doesn't
 * overlap work but starts before he's home (overlaps the commute home) or after he's left (the morning commute) clashes
 * too. Work wins when an event overlaps both.
 */
object WorkClashRules {
    /** Words that make an event something a child is taken to. */
    private val COVER_WORDS = setOf("TRAINING", "PRACTICE")

    fun clash(
        e: CalendarEvent, blocks: List<WorkBlock>, calendar: LocalCalendar, children: Map<String, String> = emptyMap(),
        commute: List<CommuteBand> = emptyList(),
    ): WorkClash? {
        if (e.allDay || e.isFixture || e.provider == "microsoft") return null
        val end = maxOf(e.endAtMs, e.startAtMs + 1)
        val w = blocks.firstOrNull { it.endMs > it.startMs && e.startAtMs < it.endMs && end > it.startMs }
        val band = if (w != null) null else commute.firstOrNull { it.endMs > it.startMs && e.startAtMs < it.endMs && end > it.startMs }
        if (w == null && band == null) return null
        val words = e.title.split(Regex("[^A-Za-z0-9]+")).map { it.uppercase() }
        val club = FootballRules.isClubFixture(e)
        val cover = club || words.any { it in COVER_WORDS }
        // The age group in the title or calendar, else whom Meka set for the series ([ClubChildRules]).
        val child = if (!cover) null else ClubChildRules.child(e, children, calendar)
        return WorkClash(
            eventId = e.id, title = e.title.trim(), startMs = e.startAtMs,
            startMinute = calendar.minuteOfDay(e.startAtMs), child = child, needsCover = cover,
            workTitle = w?.title ?: WorkHours.TITLE,
            workUntil = LocalClock.formatMinute(w?.endMinute ?: 0), workUntilMinute = w?.endMinute ?: 0,
            against = when {
                band == null -> ClashWith.WORK
                band.toWork -> ClashWith.LEAVING
                else -> ClashWith.HOME
            },
            // When he leaves (the morning band's start) or gets home (the evening band's end).
            commuteMinute = when {
                band == null -> 0
                band.toWork -> band.startMinute
                else -> band.endMinute
            },
        )
    }

    /** Every clash among [events] with [blocks], in start order. */
    fun clashes(
        events: List<CalendarEvent>, blocks: List<WorkBlock>, calendar: LocalCalendar, children: Map<String, String> = emptyMap(),
        commute: List<CommuteBand> = emptyList(),
    ): List<WorkClash> =
        if (blocks.isEmpty() && commute.isEmpty()) emptyList()
        else events.mapNotNull { clash(it, blocks, calendar, children, commute) }.distinctBy { it.eventId }.sortedWith(compareBy({ it.startMs }, { it.title }))
}
