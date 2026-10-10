package os.meka.core.domain

/** Fields of a `venue` (weekend football, slice 2): a ground and the travel time Meka last set for a fixture there. */
object VenueFields {
    /** The ground's key ([FootballRules.venueKey]): the place in lower case, words only. */
    const val KEY = "key"
    /** The place as the fixture had it ("Arlesey Town FC, Hitchin Rd"). */
    const val PLACE = "place"
    /** Minutes it took to get there (Int, 1–240). */
    const val TRAVEL_MIN = "travelMin"
    /** Whether that fixture's Leave by rang as an alarm (Bool). */
    const val LEAVE_ALARM = "leaveAlarm"
    const val SET_AT = "setAtMs"
}

/** What Meka last set for a ground: how long it took and whether Leave by rang as an alarm. */
data class VenueTravel(val place: String, val travelMin: Int, val rings: Boolean, val setAtMs: Long)

/**
 * The detail's offer on a fixture at a ground Meka has set a travel time for before: "Leave by 09:15 · as last time"
 * ([label]); one tap sets the same travel time (and Ring as an alarm when it rang last time). [line] is the undo bar's.
 */
data class LeaveOffer(
    val travelMin: Int,
    val rings: Boolean,
    val label: String,
    val line: String,
    /** Slice 2c: the ground's key when the offer is the server's drive, so the Leave by it sets follows the traffic. */
    val driveKey: String? = null,
)

/**
 * Weekend football, slice 3: a "running late" message Meka can send the coach ([text]), [minutes] late ([label]:
 * "10 min"). MEKA only drafts it: it goes to the phone's share sheet (the Mac's share menu), where Meka picks the chat
 * and sends it himself (Level 3, never sent by MEKA).
 */
data class LateDraft(val minutes: Int, val label: String, val text: String)

/**
 * Weekend football, slice 4: what Meka kept about a match once it was over, on the fixture's `event_mark`
 * ([EventMarkFields.SCORE_FOR] and the rest). [scoreFor] is his kid's team's goals; a score is both sides or neither.
 */
data class FixtureResult(
    val scoreFor: Int?,
    val scoreAgainst: Int?,
    val scorers: String?,
    val note: String?,
    val atMs: Long,
) {
    val hasScore: Boolean get() = scoreFor != null && scoreAgainst != null
    val isEmpty: Boolean get() = !hasScore && scorers == null && note == null
    /** For Swift: the goals for, or -1 without a score. */
    val forOrNone: Int get() = if (hasScore) scoreFor!! else -1
    val againstOrNone: Int get() = if (hasScore) scoreAgainst!! else -1
}

/** A result just saved: the undo bar's [line] ("Saved · Won 3–1") and what was there before ([previous]; null: nothing). */
data class MatchSaved(val eventId: String, val line: String, val previous: FixtureResult?)

/** A kit task just made: its id (Undo deletes it) and the undo bar's line ("Kit reminder tomorrow 19:00"). */
data class KitAdded(val taskId: String, val line: String)

/**
 * Weekend football logistics, slice 1 (Meka approved 2026-10-09): the **kit reminder** for the kids' club fixtures in
 * his calendar. Non-AI, pure.
 *
 * - A **club fixture** is an event of Meka's own calendars whose title or calendar names one of the clubs ([CLUBS],
 *   "BUFC" and "SJFC", as whole words, any case): "BUFC U9s v Arlesey", "SJFC training" on a calendar called "SJFC".
 *   The fixtures feed (Barça) never counts, nor does an event still on its way to Google.
 * - **Kit reminder** on a club fixture's detail makes its kit task: "Pack the kit for BUFC U9s v Arlesey", planned at
 *   [EVENING_MIN] (19:00) the evening before for [KIT_MINUTES] minutes, due at kick-off, with Remind me at that time
 *   (a heads-up through the task reminders), and the kit list as its steps to tick. Too late for the evening before
 *   (it's already past 19:00 that evening): no planned time and no reminder, just due at kick-off.
 * - **The list is Meka's own:** the steps of the most recent kit task that has any are the next one's list, so
 *   adding "Gloves" or removing "Coat" on one fixture's task carries on to the next; until then [DEFAULT_KIT].
 * - Its id comes from the event ([kitTaskId]), so a double tap or both devices offline make one task, and adding it
 *   again after deleting it brings the same task back with the list unticked.
 */
object FootballRules {
    /** The clubs whose fixtures get kit reminders (whole words in the title or the calendar's name). */
    val CLUBS = listOf("BUFC", "SJFC")
    val DEFAULT_KIT = listOf("Boots", "Shin pads", "Kit and socks", "Water bottle", "Coat")
    /** The kit list's longest: more is dropped. */
    const val MAX_KIT = 20
    const val MAX_ITEM = 60
    /** 19:00 the evening before. */
    const val EVENING_MIN = 19 * 60
    const val KIT_MINUTES = 10
    const val CHIP = "Kit reminder"

    // ---- Slice 2: the travel time Meka set for each ground ----

    /**
     * A ground's key: the place in lower case with only its words ("Arlesey Town FC, Hitchin Rd" →
     * "arlesey town fc hitchin rd"), so a stray comma or capital still finds it. Null for no place or just a call link.
     */
    fun venueKey(place: String?): String? {
        val p = place?.trim()?.takeIf { it.isNotEmpty() && !ReminderRules.isLink(it) } ?: return null
        return p.lowercase().map { if (it.isLetterOrDigit()) it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }.joinToString(" ").take(200).ifEmpty { null }
    }

    /** The `venue` entity's id for a key: the same on every device. */
    fun venueId(key: String): String = "vn" + ActivityRules.fnv64("venue:$key")

    /**
     * The ground to remember once Meka sets Leave by (or its alarm switch) on [e]: a club fixture with a place and a
     * travel time; null otherwise. Clearing the travel time forgets nothing (the ground keeps what was set last).
     */
    fun venueToRemember(e: CalendarEvent, marks: EventMarks): Pair<String, VenueTravel>? {
        if (!isClubFixture(e)) return null
        val place = LeaveAlarmRules.place(e) ?: return null
        val key = venueKey(place) ?: return null
        val travel = marks.travel[e.id] ?: return null
        return key to VenueTravel(place.take(200), travel, e.id in marks.leaveAlarms, 0L)
    }

    /**
     * "Leave by 09:15 · as last time" on a club fixture still to come at a ground Meka set a travel time for before,
     * while this one has none and leaving would still be ahead of now. Null otherwise (all day, no place, hidden,
     * an event still on its way to Google).
     */
    fun leaveOffer(e: CalendarEvent, marks: EventMarks, nowMs: Long, cal: LocalCalendar): LeaveOffer? {
        if (e.allDay || e.isProvisional || !isClubFixture(e) || marks.travel[e.id] != null || marks.isHidden(e.id)) return null
        val key = venueKey(LeaveAlarmRules.place(e)) ?: return null
        val v = marks.venues[key]
        // Slice 2b: the server's drive with traffic comes first ("Leave by 08:55 · 25 min drive"); it rings as an
        // alarm unless Meka turned the alarm off at this ground last time.
        TravelRules.current(e, marks.routes)?.let { r ->
            val travel = TravelRules.travelMin(r.driveMin)
            val at = e.startAtMs - travel * 60_000L
            if (at > nowMs) {
                val rings = v?.rings ?: true
                val time = LocalClock.formatMinute(cal.minuteOfDay(at))
                val drive = TravelRules.driveLabel(r.driveMin)
                val line = "Leave by $time · $drive" + if (rings) " · ${ReminderRules.ALARM_WORD}" else ""
                return LeaveOffer(travel, rings, "Leave by $time · $drive", line, r.placeKey)
            }
        }
        if (v == null) return null
        val at = e.startAtMs - v.travelMin * 60_000L
        if (at <= nowMs) return null
        val time = LocalClock.formatMinute(cal.minuteOfDay(at))
        val line = "Leave by $time · ${ReminderRules.travelLabel(v.travelMin)}" + if (v.rings) " · ${ReminderRules.ALARM_WORD}" else ""
        return LeaveOffer(v.travelMin, v.rings, "Leave by $time · $AS_LAST_TIME", line)
    }

    const val AS_LAST_TIME = "as last time"

    // ---- Slice 3: "running late" drafts ----

    /** How late Meka can say he'll be. */
    val LATE_MINUTES = listOf(5, 10, 15, 20)
    /** Offered from 2 hours before kick-off… */
    const val LATE_FROM_MIN = 120
    /** …until 30 minutes after it. */
    const val LATE_UNTIL_MIN = 30
    const val LATE_TITLE = "Running late?"
    const val LATE_CAPTION = "Drafts a message; you pick the chat and send it"
    private const val MAX_LATE_TITLE = 60

    /**
     * The "running late" drafts on a club fixture's detail, one per [LATE_MINUTES], from [LATE_FROM_MIN] before
     * kick-off until [LATE_UNTIL_MIN] after it (an all-day tournament: from 09:00 on its first day); empty otherwise.
     * "Hi, sorry, running about 10 min late for BUFC U9s v Arlesey. Should be there by 09:40." — by kick-off plus the
     * minutes, or once kick-off has gone, now plus the minutes rounded up to five. Nothing is sent: Meka sends it.
     */
    fun lateDrafts(e: CalendarEvent, nowMs: Long, cal: LocalCalendar): List<LateDraft> {
        if (!isClubFixture(e)) return emptyList()
        val kickOff = kickOffMs(e, cal)
        if (nowMs < kickOff - LATE_FROM_MIN * 60_000L || nowMs > kickOff + LATE_UNTIL_MIN * 60_000L) return emptyList()
        val what = lateTitle(e)
        val who = child(e)?.let { "$it and I are " } ?: ""
        return LATE_MINUTES.map { m ->
            val by = if (nowMs <= kickOff) kickOff + m * 60_000L else roundUpTo5(nowMs + m * 60_000L)
            val time = LocalClock.formatMinute(cal.minuteOfDay(by))
            LateDraft(m, "$m min", "Hi, sorry, ${who}running about $m min late for $what. Should be there by $time.")
        }
    }

    /** The fixture as the message names it: its title cut at a word to [MAX_LATE_TITLE], or the club. */
    private fun lateTitle(e: CalendarEvent): String {
        val t = e.title.trim().replace(Regex("\\s+"), " ").ifEmpty { return club(e) ?: "football" }
        if (t.length <= MAX_LATE_TITLE) return t
        val head = t.take(MAX_LATE_TITLE - 1)
        val space = head.lastIndexOf(' ')
        return (if (space > MAX_LATE_TITLE / 2) head.take(space) else head).trimEnd() + "…"
    }

    private fun roundUpTo5(ms: Long): Long {
        val step = 5 * 60_000L
        return (ms + step - 1).floorDiv(step) * step
    }

    // ---- Slice 4: the result and a note after the match ----

    const val RESULT_TITLE = "How did it go?"
    const val RESULT_SAVE = "Save"
    const val RESULT_EDIT = "Edit result"
    const val SCORERS_HINT = "Scorers · Leo 2, Sam (optional)"
    const val NOTE_HINT = "A note · man of the match, how he played… (optional)"
    /** Offered from the end of the match for this many days (a result already kept can always be changed). */
    const val RESULT_DAYS = 7
    /** The prompt posts this long after the end… */
    const val RESULT_PROMPT_AFTER_MIN = 15
    /** …and is stale this long after it. */
    const val RESULT_PROMPT_HOURS = 12
    const val MAX_SCORE = 99
    const val MAX_SCORERS = 120
    const val MAX_NOTE = 500
    /** An all-day tournament counts as over at 17:00 on its first day. */
    const val ALL_DAY_END_MIN = 17 * 60
    /** Words that make a club event a training session: a note is kept, never a score, and no prompt is posted. */
    val TRAINING_WORDS = setOf("TRAINING", "PRACTICE", "SESSION", "COACHING")

    /** Whether [e] is a training session rather than a match (its title names one of [TRAINING_WORDS]). */
    fun isTraining(e: CalendarEvent): Boolean =
        e.title.split(Regex("[^A-Za-z0-9]+")).any { it.uppercase() in TRAINING_WORDS }

    /** When the match is over: a timed one's end (at least its start), an all-day one at 17:00 on its first day. */
    fun matchEndMs(e: CalendarEvent, cal: LocalCalendar): Long =
        if (e.allDay) cal.toEpochMs(matchDay(e, cal), ALL_DAY_END_MIN) else maxOf(e.endAtMs, e.startAtMs)

    /**
     * Whether the detail offers "How did it go?": a club fixture that is over, for [RESULT_DAYS] days after; one with a
     * result kept already always shows it (to change it). Never an event still on its way to Google.
     */
    fun canRecord(e: CalendarEvent, result: FixtureResult?, nowMs: Long, cal: LocalCalendar): Boolean {
        if (e.isProvisional || !isClubFixture(e)) return false
        if (result != null) return true
        val end = matchEndMs(e, cal)
        return nowMs >= end && nowMs < end + RESULT_DAYS * CivilDate.DAY_MS
    }

    /**
     * What gets kept from the fields Meka filled in: a score only when both sides are 0–[MAX_SCORE] and it isn't
     * training; scorers and the note trimmed (runs of spaces as one, the note keeps its lines) and cut to their
     * limits. Null when nothing is left (saving that clears the result).
     */
    fun result(e: CalendarEvent, scoreFor: Int, scoreAgainst: Int, scorers: String, note: String, nowMs: Long): FixtureResult? {
        val score = !isTraining(e) && scoreFor in 0..MAX_SCORE && scoreAgainst in 0..MAX_SCORE
        val who = scorers.replace(Regex("\\s+"), " ").trim().take(MAX_SCORERS).trim().ifEmpty { null }?.takeIf { !isTraining(e) }
        val text = note.lines().joinToString("\n") { it.replace(Regex("[ \\t]+"), " ").trim() }.trim()
            .replace(Regex("\n{3,}"), "\n\n").take(MAX_NOTE).trim().ifEmpty { null }
        val r = FixtureResult(if (score) scoreFor else null, if (score) scoreAgainst else null, who, text, nowMs)
        return r.takeIf { !it.isEmpty }
    }

    /** "Won 3–1" · "Drew 2–2" · "Lost 0–1"; null without a score. */
    fun scoreLabel(r: FixtureResult): String? {
        if (!r.hasScore) return null
        val f = r.scoreFor!!
        val a = r.scoreAgainst!!
        val word = when { f > a -> "Won"; f < a -> "Lost"; else -> "Drew" }
        return "$word $f–$a"
    }

    /** The detail's line: "Won 3–1 · Leo 2, Sam" · "Scorers · Leo 2, Sam" · null (only a note, or nothing). */
    fun resultLine(r: FixtureResult?): String? {
        if (r == null) return null
        val score = scoreLabel(r)
        return when {
            score != null && r.scorers != null -> "$score · ${r.scorers}"
            score != null -> score
            r.scorers != null -> "Scorers · ${r.scorers}"
            else -> null
        }
    }

    /** The undo bar's line once saved: "Saved · Won 3–1" · "Saved the note" · "Result cleared". */
    fun savedLine(r: FixtureResult?): String = when {
        r == null -> "Result cleared"
        resultLine(r) != null -> "Saved · ${resultLine(r)}"
        else -> "Saved the note"
    }

    /**
     * The prompt after the match: a heads-up [RESULT_PROMPT_AFTER_MIN] minutes after the end of each club match
     * (not training, not hidden) with nothing kept yet, "How did BUFC U9s v Arlesey go?", standing for
     * [RESULT_PROMPT_HOURS] hours. Through Event reminders, so quiet hours and Notifications' choice apply.
     */
    fun notices(events: List<CalendarEvent>, marks: EventMarks, nowMs: Long, cal: LocalCalendar): List<Notice> =
        events.mapNotNull { e ->
            if (e.isProvisional || !isClubFixture(e) || isTraining(e) || marks.isHidden(e.id) || marks.results[e.id] != null) return@mapNotNull null
            val end = matchEndMs(e, cal)
            val at = end + RESULT_PROMPT_AFTER_MIN * 60_000L
            val expires = end + RESULT_PROMPT_HOURS * 3_600_000L
            if (nowMs >= expires) return@mapNotNull null
            Notice(
                key = "event:${e.id}:${e.startAtMs}:result", source = NoticeSource.EVENT_REMINDER, tier = NoticeTier.HEADS_UP,
                title = child(e)?.let { "How did $it's match go?" } ?: "How did ${lateTitle(e)} go?",
                text = (if (child(e) != null) lateTitle(e) + " · " else "") + "Keep the score, the scorers and a note · open it from Today",
                atMs = at, target = NoticeTarget.TODAY, expiresAtMs = expires,
            )
        }

    // ---- Whose team it is, and saying a match the way people do (Meka, 2026-10-10) ----

    /** The age group each child plays in: U7 fixtures are Rex's, U10 fixtures are Logan's (Meka, 2026-10-10). */
    val CHILDREN: Map<Int, String> = mapOf(7 to "Rex", 10 to "Logan")
    /** Club abbreviations said in full. */
    val CLUB_NAMES: Map<String, String> = mapOf("BUFC" to "Biggleswade United")

    private val AGE_GROUP = Regex("""\b(?:U|Under)[\s-]?(\d{1,2})(?:s)?\b""", RegexOption.IGNORE_CASE)
    private val VERSUS = Regex("""\s+(?:v|vs|versus)\.?\s+""", RegexOption.IGNORE_CASE)

    /** The age group a title names ("BUFC U7s v Arlesey" → 7, "Under-10s training" → 10), or null. */
    fun ageGroup(title: String): Int? = AGE_GROUP.find(title)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 5..18 }

    /** Whose fixture [e] is ("Rex"), from its age group; null when it isn't a club fixture or the group isn't a child's. */
    fun child(e: CalendarEvent): String? = if (isClubFixture(e)) ageGroup(e.title)?.let { CHILDREN[it] } else null

    /** 7 → "under-sevens", 6 → "under-sixes", 12 → "under-twelves". */
    fun spokenAgeGroup(n: Int): String {
        val word = NUMBER_WORDS.getOrNull(n) ?: return "under-$n"
        return "under-" + if (word.endsWith("x")) word + "es" else word + "s"
    }

    /**
     * A club fixture's title as MEKA says it aloud (the spoken brief; null when [title] names no club): "BUFC U7s v
     * Arlesey" → "Rex's under-sevens play Arlesey", "BUFC U9s v Arlesey" → "Biggleswade United under-nines play Arlesey",
     * "SJFC U10s training" → "Logan's under-tens training". The club is dropped when the child is named.
     */
    fun spokenFixture(title: String): String? {
        val club = clubIn(title) ?: return null
        val group = ageGroup(title)
        val kid = group?.let { CHILDREN[it] }
        var t = title.trim().replace(Regex("\\s+"), " ")
        if (group != null) {
            val team = (kid?.let { "$it's " } ?: "") + spokenAgeGroup(group)
            t = AGE_GROUP.replaceFirst(t, Regex.escapeReplacement(team))
        }
        t = if (kid != null) t.replace(Regex("\\b$club\\b\\s*", RegexOption.IGNORE_CASE), "")
        else t.replace(Regex("\\b$club\\b", RegexOption.IGNORE_CASE), Regex.escapeReplacement(CLUB_NAMES[club] ?: club))
        t = VERSUS.replaceFirst(t, if (group != null) " play " else " versus ")
        return t.replace(VERSUS, " versus ").trim().replaceFirstChar { it.uppercase() }
    }

    /** "10:00" → "ten o'clock", "10:30" → "half past ten", "09:15" → "quarter past nine", "09:45" → "quarter to ten", "18:20" → "six twenty"; null for anything else. */
    fun spokenClock(time: String): String? {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(time.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h > 23 || min > 59) return null
        fun hour(x: Int) = NUMBER_WORDS[((x + 11) % 12) + 1]
        return when (min) {
            0 -> "${hour(h)} o'clock"
            15 -> "quarter past ${hour(h)}"
            30 -> "half past ${hour(h)}"
            45 -> "quarter to ${hour(h + 1)}"
            in 1..9 -> "${hour(h)} oh ${NUMBER_WORDS[min]}"
            else -> "${hour(h)} ${minuteWords(min)}"
        }
    }

    private fun minuteWords(n: Int): String = when {
        n < NUMBER_WORDS.size -> NUMBER_WORDS[n]
        n % 10 == 0 -> TENS[n / 10]
        else -> TENS[n / 10] + "-" + NUMBER_WORDS[n % 10]
    }

    private val NUMBER_WORDS = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
        "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty")

    /** The kit task's id for an event: the same on every device. */
    fun kitTaskId(eventId: String) = "k$eventId"

    /** Whether [t] is a fixture's kit task. */
    fun isKitTask(t: Task): Boolean = t.eventId != null && t.id == kitTaskId(t.eventId)

    /** The club [e] is a fixture of ("BUFC"), or null when it isn't one. */
    fun club(e: CalendarEvent): String? {
        if (e.isFixture || e.isProvisional) return null
        return listOfNotNull(e.title, e.calendarTitle, e.calendarName).firstNotNullOfOrNull { clubIn(it) }
    }

    fun isClubFixture(e: CalendarEvent): Boolean = club(e) != null

    /** The first club named in [text] as a whole word, any case. */
    fun clubIn(text: String): String? {
        val words = text.split(Regex("[^A-Za-z0-9]+")).map { it.uppercase() }.toSet()
        return CLUBS.firstOrNull { it in words }
    }

    /** The local day the fixture is on: an all-day one's first day (its bounds are UTC midnights). */
    fun matchDay(e: CalendarEvent, cal: LocalCalendar): Long =
        if (e.allDay) e.startAtMs.floorDiv(CivilDate.DAY_MS) else cal.epochDayOf(e.startAtMs)

    /** When the fixture starts as a moment: an all-day one at 09:00 local on its first day. */
    fun kickOffMs(e: CalendarEvent, cal: LocalCalendar): Long =
        if (e.allDay) cal.toEpochMs(matchDay(e, cal), 9 * 60) else e.startAtMs

    /** The kit task as it is made: title, planned and reminded the evening before (null once that's gone), due at kick-off. */
    data class Plan(val title: String, val scheduledAtMs: Long?, val remindAtMs: Long?, val dueAtMs: Long)

    fun plan(e: CalendarEvent, nowMs: Long, cal: LocalCalendar): Plan {
        val evening = cal.toEpochMs(matchDay(e, cal) - 1, EVENING_MIN)
        val ahead = evening.takeIf { it > nowMs }
        return Plan(title(e), ahead, ahead, kickOffMs(e, cal))
    }

    /** "Pack the kit for BUFC U9s v Arlesey" (cut at a word to the task title's limit). */
    fun title(e: CalendarEvent): String {
        val what = e.title.trim().ifEmpty { club(e) ?: "football" }
        val s = child(e)?.let { "Pack $it's kit for $what" } ?: "Pack the kit for $what"
        if (s.length <= PrepRules.MAX_TITLE) return s
        val head = s.take(PrepRules.MAX_TITLE - 1)
        val space = head.lastIndexOf(' ')
        return (if (space > PrepRules.MAX_TITLE / 2) head.take(space) else head).trimEnd() + "…"
    }

    /**
     * The kit list for the next kit task: the steps of the most recent kit task that has any (by when it was made),
     * else [DEFAULT_KIT]; trimmed, blank and repeated ones dropped, at most [MAX_KIT].
     */
    fun kitList(tasks: List<Task>): List<String> {
        val last = tasks.filter { isKitTask(it) && it.checklist.any { c -> c.text.isNotBlank() } }
            .maxByOrNull { it.createdAtMs }
        val raw = last?.checklist?.sortedBy { it.position }?.map { it.text } ?: DEFAULT_KIT
        val seen = HashSet<String>()
        return raw.map { it.trim().take(MAX_ITEM) }.filter { it.isNotEmpty() && seen.add(it.lowercase()) }.take(MAX_KIT)
    }

    /** Whether the detail offers Kit reminder: a club fixture not yet started, with no kit task (a done one counts). */
    fun canKit(e: CalendarEvent, kit: Task?, nowMs: Long, cal: LocalCalendar): Boolean =
        isClubFixture(e) && kickOffMs(e, cal) > nowMs && (kit == null || kit.lifecycle == Lifecycle.CANCELLED)

    /**
     * The detail's kit line: "Kit reminder Fri 19:00 · 2 of 5 packed" · "Kit list · 2 of 5 packed" (no reminder) ·
     * "Kit packed" (every step ticked, or the task done); null without a kit task.
     */
    fun line(kit: Task?, nowMs: Long, cal: LocalCalendar): String? {
        if (kit == null || kit.lifecycle == Lifecycle.CANCELLED) return null
        val steps = kit.checklist
        val packed = steps.count { it.checked }
        if (kit.isDone || (steps.isNotEmpty() && packed == steps.size)) return "Kit packed"
        val count = if (steps.isEmpty()) null else "$packed of ${steps.size} packed"
        val remind = kit.remindAtMs?.takeIf { it > nowMs }?.let { "Kit reminder ${whenLabel(it, nowMs, cal)}" }
        return listOfNotNull(remind ?: "Kit list", count).joinToString(" · ")
    }

    /** What the undo bar says once the kit task is made: "Kit reminder tomorrow 19:00" · "Kit list added". */
    fun addedLine(plan: Plan, nowMs: Long, cal: LocalCalendar): String {
        val at = plan.remindAtMs ?: return "Kit list added"
        return "Kit reminder ${whenLabel(at, nowMs, cal)}"
    }

    /** "today 19:00" · "tomorrow 19:00" · "Fri 19:00". */
    private fun whenLabel(at: Long, nowMs: Long, cal: LocalCalendar): String {
        val day = cal.epochDayOf(at)
        val dayLabel = when (day - cal.epochDayOf(nowMs)) {
            0L -> "today"
            1L -> "tomorrow"
            else -> LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(day) - 1]
        }
        return "$dayLabel ${LocalClock.formatMinute(cal.minuteOfDay(at))}"
    }
}
