package os.meka.core.domain

/** Fields of a `club_series` (one per repeating club session, id = [ClubChildRules.seriesId]). */
object ClubSeriesFields {
    /** Whose session it is, one of [FootballRules.CHILDREN]'s names ("Rex"); null clears it. */
    const val CHILD = "child"
    const val SET_AT = "setAtMs"
}

/**
 * Whose club training or match it is (Fold review 2026-10-12 17:56, item 4: "name the child on club training"), non-AI
 * and pure. The age group in the title or the calendar's name says it first ("BUFC U7s" → Rex, [FootballRules.CHILDREN]);
 * when neither does, Meka sets it once for the series and every session of it carries the name.
 *
 * A **series** is what Google repeats: the same calendar, the same title, the same weekday and the same start time
 * ([seriesId]); nothing about the event leaves the device beyond the synced `club_series` (only its id, the child's
 * name and when it was set). A one-off with the same shape as a series reads as part of it, which is what Meka wants.
 */
object ClubChildRules {
    /** The children in age order, "Rex", "Logan": the choices on an event whose child isn't known. */
    val NAMES: List<String> get() = FootballRules.CHILDREN.entries.sortedBy { it.key }.map { it.value }

    /** "Rex or Logan?": the agenda row's line while nobody is set. */
    val ASK: String get() = NAMES.joinToString(" or ") + "?"

    /** Words that make an event a child's session even off a club's calendar ("Training", "Practice"). */
    private val SESSION_WORDS = setOf("training", "practice")

    /**
     * Whether [e] is a child's club session or match, so its row names the child: a club fixture ([FootballRules]) or
     * anything called training or practice; timed, never the fixtures feed or Outlook (the work calendar's training).
     */
    fun asks(e: CalendarEvent): Boolean = !e.allDay && !e.isFixture && e.provider != "microsoft" &&
        (FootballRules.isClubFixture(e) || words(e.title).split(' ').any { it in SESSION_WORDS })

    /** The child the event names itself, by an age group in its title or its calendar's name; null otherwise. */
    fun fromEvent(e: CalendarEvent): String? =
        listOfNotNull(e.title, e.calendarTitle, e.calendarName).firstNotNullOfOrNull { FootballRules.ageGroup(it) }
            ?.let { FootballRules.CHILDREN[it] }

    /** The title's words in lower case, punctuation dropped ("Training (U?)" → "training u"); no regex (Kotlin/Native). */
    private fun words(s: String): String =
        s.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ")

    /** The series [e] belongs to: calendar, title, weekday and start time. */
    fun seriesKey(e: CalendarEvent, calendar: LocalCalendar): String {
        val day = calendar.epochDayOf(e.startAtMs)
        return listOf(CalendarRules.key(e), words(e.title), CivilDate.isoDayOfWeek(day).toString(), calendar.minuteOfDay(e.startAtMs).toString())
            .joinToString("|")
    }

    /** The `club_series` id for [e]'s series: the same on every device; the key itself is never stored. */
    fun seriesId(e: CalendarEvent, calendar: LocalCalendar): String = "cs" + ActivityRules.fnv64("club:" + seriesKey(e, calendar))

    /** Whose session [e] is: its own age group, else what Meka set for the series ([set]: series id → name); else null. */
    fun child(e: CalendarEvent, set: Map<String, String>, calendar: LocalCalendar): String? =
        fromEvent(e) ?: set[seriesId(e, calendar)]?.takeIf { it in NAMES }

    /**
     * The agenda row's line for [e]: the child's name, or [ASK] while nobody is set; null when [e] isn't a child's
     * club session.
     */
    fun line(e: CalendarEvent, set: Map<String, String>, calendar: LocalCalendar): String? =
        if (!asks(e)) null else child(e, set, calendar) ?: ASK

    /** Whether the event's pane offers the choice (a club session whose title and calendar don't say). */
    fun canSet(e: CalendarEvent): Boolean = asks(e) && !e.isProvisional && fromEvent(e) == null

    /** "For Rex · every Wed 16:55" — the pane's line once a child is set for the series; null otherwise. */
    fun setLine(e: CalendarEvent, set: Map<String, String>, calendar: LocalCalendar): String? {
        if (!canSet(e)) return null
        val who = set[seriesId(e, calendar)]?.takeIf { it in NAMES } ?: return null
        val weekday = weekday(e, calendar)
        return "For $who · every $weekday ${LocalClock.formatMinute(calendar.minuteOfDay(e.startAtMs))}"
    }

    /** "Rex · “Training” every Wed" — the undo bar's line after setting. */
    fun savedLine(e: CalendarEvent, child: String?, calendar: LocalCalendar): String {
        val weekday = weekday(e, calendar)
        val what = e.title.trim().ifEmpty { "Training" }.take(40)
        return if (child == null) "Cleared who “$what” is for" else "$child · “$what” every $weekday"
    }

    private fun weekday(e: CalendarEvent, calendar: LocalCalendar): String =
        LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(calendar.epochDayOf(e.startAtMs)) - 1]

    /** What the synced `club_series` entities say: series id → child, ignoring names that aren't children's. */
    fun read(entities: List<Pair<String, String?>>): Map<String, String> =
        entities.mapNotNull { (id, child) -> child?.trim()?.takeIf { it in NAMES }?.let { id to it } }.toMap()
}

/** What setting a club series' child did: the undo bar's [line] and who was set before ([previous], null: nobody). */
data class ClubChildSaved(val event: CalendarEvent, val line: String, val previous: String?)
