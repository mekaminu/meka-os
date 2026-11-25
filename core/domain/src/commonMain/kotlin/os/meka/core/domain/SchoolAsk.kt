package os.meka.core.domain

/**
 * School emails first, slice 6: Obi, the morning brief and the week ahead know what's on at school.
 *
 * The school's dates live as School lines ([SchoolItem]); since slices 3a–5 most of them are written from the school's
 * emails ([SchoolMailRules.SOURCE]), so their titles are other people's words: a question that carries them makes the
 * context untrusted ([fromEmail], ADR-006), and nothing here follows a link or acts on its own.
 *
 * - [weekLines]: "what's on at school this week?" — every school thing from today for [WEEK_DAYS] days, by day, sent to
 *   the model before the school year's longer view ([SchoolRules.askLines]); [localAnswer] says the same when the AI
 *   can't be reached.
 * - [briefLine]: the brief's school line also names the next one-off dates of the coming days ("School fair Sat 28 Nov"),
 *   so a Saturday fair is heard on Wednesday, not only on the day.
 */
object SchoolAskRules {
    /** "This week" is today and the six days after it. */
    const val WEEK_DAYS = 7
    /** Lines at most for the week (a header and the things). */
    const val ASK_WEEK = 8
    /** The brief looks this many days past today for one-off dates. */
    const val BRIEF_AHEAD = 6
    /** ... and names at most this many. */
    const val BRIEF_NAMED = 2
    /** The words that mark a line as read from the school's email. */
    const val FROM_EMAIL = "from the school's email"

    private val I = RegexOption.IGNORE_CASE
    private val aboutRe = Regex("""\b(schools?|inset|half[\s-]?term|non[\s-]?uniform|term\s+dates?|pe\s+(?:day|kit))\b""", I)
    private val weekRe = Regex("""\b(this\s+week|next\s+few\s+days|coming\s+up|what'?s\s+on|what\s+is\s+on|happening|on\s+at\s+school|anything\s+on)\b""", I)

    /** Whether [question] is about school ("what's on at school this week?", "when's half term?"). */
    fun isAbout(question: String): Boolean = aboutRe.containsMatchIn(question)

    /** Whether [question] asks what is on at school in the coming days, rather than what an email said. */
    fun isWeek(question: String): Boolean = isAbout(question) && weekRe.containsMatchIn(question)

    /** Whether any School line came from the school's email (other people's words). */
    fun fromEmail(items: List<SchoolItem>): Boolean = items.any { it.source == SchoolMailRules.SOURCE }

    /** One school thing on a day of the week ahead, and whether its line came from the school's email. */
    data class WeekThing(val day: Long, val thing: SchoolThing, val fromEmail: Boolean)

    /**
     * The school things from [today] for [days] days ([SchoolRules.onDay]), by day: a day off spanning several days is
     * listed once, on its first day in the window.
     */
    fun week(items: List<SchoolItem>, today: Long, holidays: HolidayCalendar, days: Int = WEEK_DAYS): List<WeekThing> {
        val byId = items.associateBy { it.id }
        val seenOff = HashSet<String>()
        return (today until today + days).flatMap { d ->
            SchoolRules.onDay(items, d, holidays)
                .filter { it.kind != SchoolKind.OFF || seenOff.add(it.id) }
                .map { WeekThing(d, it, byId[it.id]?.source == SchoolMailRules.SOURCE) }
        }
    }

    /**
     * The week's lines for Ask's `school` items: "School this week · today to Tue 1 Dec · 2 things", then each ("Sat 28
     * Nov · School fair · Alex and Sam · from the school's email"; a day off with its days). Empty when there are no
     * School lines at all, so a pane he never filled says nothing.
     */
    fun weekLines(items: List<SchoolItem>, today: Long, holidays: HolidayCalendar): List<String> {
        if (items.isEmpty()) return emptyList()
        val things = week(items, today, holidays)
        val span = "today to ${CivilDate.shortLabel(today + WEEK_DAYS - 1)}"
        val head = "School this week · $span · " + when (things.size) {
            0 -> "nothing on"
            1 -> "1 thing"
            else -> "${things.size} things"
        }
        val byId = items.associateBy { it.id }
        val lines = things.take(ASK_WEEK - 1).map { w ->
            val t = w.thing
            val item = byId[t.id]
            val whenLabel = if (t.kind == SchoolKind.OFF && item != null && item.endDay > item.startDay) SchoolRules.spanLabel(item.startDay, item.endDay)
            else dayLabel(w.day, today)
            val what = when (t.kind) {
                SchoolKind.OFF -> "Day off · ${t.title}"
                SchoolKind.WEEKLY -> "${t.title} (every ${CivilDate.DAY_LONG[CivilDate.isoDayOfWeek(w.day) - 1]})"
                else -> t.title
            }
            listOfNotNull(whenLabel, what, SchoolRules.whoLabel(t.who).takeIf { it.isNotBlank() }, if (w.fromEmail) FROM_EMAIL else null).joinToString(" · ")
        }
        return listOf(head) + lines
    }

    /** "today", "tomorrow", "Sat 28 Nov". */
    private fun dayLabel(day: Long, today: Long): String = when (day) {
        today -> "today"
        today + 1 -> "tomorrow"
        else -> CivilDate.shortLabel(day)
    }

    /**
     * The device's own answer when the AI can't be reached and [question] asks what's on at school ([isWeek]); null
     * otherwise (what an email said is [MailAskRules.localAnswer]'s). "At school this week: School fair on Sat 28 Nov,
     * and Alex has PE tomorrow." / "Nothing on at school this week." / null with no School lines.
     */
    fun localAnswer(question: String, items: List<SchoolItem>, today: Long, holidays: HolidayCalendar): String? {
        if (!isWeek(question) || items.isEmpty()) return null
        val things = week(items, today, holidays)
        if (things.isEmpty()) return "Nothing on at school this week."
        val said = things.take(ASK_WEEK - 1).map { w ->
            val d = dayLabel(w.day, today)
            "${SchoolRules.spokenThing(w.thing)} ${if (d == "today" || d == "tomorrow") d else "on $d"}"
        }
        val more = things.size - said.size
        return "At school this week: " + listWords(said) + (if (more > 0) ", and $more more." else ".")
    }

    /**
     * The brief's school line: today's ([SchoolRules.todayLine]) and the next one-off dates of the [BRIEF_AHEAD] days
     * after it, at most [BRIEF_NAMED] ("PE today · School fair Sat 28 Nov" / "Coming up · School fair Sat 28 Nov"),
     * spoken "School today: Alex has PE. Coming up at school: School fair on Sat 28 Nov." Null when neither has anything.
     */
    fun briefLine(items: List<SchoolItem>, today: Long, holidays: HolidayCalendar): SchoolLine? {
        val now = SchoolRules.todayLine(items, today, holidays)
        val todayIds = SchoolRules.onDay(items, today, holidays).map { it.id }.toSet()
        val ahead = items.filter { it.kind == SchoolKind.DAY && it.startDay in (today + 1)..(today + BRIEF_AHEAD) && it.id !in todayIds }
            .sortedWith(compareBy<SchoolItem> { it.startDay }.thenBy { it.addedAtMs }.thenBy { it.id })
            .distinctBy { it.title.lowercase() to it.startDay }
            .take(BRIEF_NAMED)
        if (ahead.isEmpty()) return now
        val shown = ahead.map { "${SchoolRules.thingText(SchoolThing(it.id, it.kind, it.title, it.who))} ${dayLabel(it.startDay, today)}" }
        val spoken = ahead.map { a ->
            val d = dayLabel(a.startDay, today)
            "${SchoolRules.spokenThing(SchoolThing(a.id, a.kind, a.title, a.who))} ${if (d == "tomorrow") d else "on $d"}"
        }
        val text = (now?.text?.let { "$it · " } ?: "Coming up · ") + shown.joinToString(" · ")
        val said = listOfNotNull(now?.spoken, "Coming up at school: ${listWords(spoken)}.").joinToString(" ")
        return SchoolLine(text, said)
    }

    private fun listWords(words: List<String>): String =
        if (words.size <= 1) words.joinToString() else words.dropLast(1).joinToString(", ") + " and " + words.last()
}
