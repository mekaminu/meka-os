package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * School rhythm (build plan V1, Meka approved 2026-10-10), slice 1: Rex's and Logan's school year, entered once by
 * typing one line at a time, and the week-ahead question "the boys are off on the 27th and you're in the office — who's
 * covering?". Plain, non-AI rules; nothing here leaves Meka's own synced data.
 *
 * One synced `school_item` entity per line (ADR-008 addendum 2026-10-10), of three kinds:
 * - [SchoolKind.OFF]: days with no school (term holidays, half term, INSET days, closures): "INSET 27 Oct",
 *   "Half term 26–30 Oct", "Christmas holidays 18 Dec – 4 Jan", "Rex off 3 Nov".
 * - [SchoolKind.DAY]: a one-off date to remember (non-uniform day, a trip, a payment due): "Non-uniform day Fri 13 Nov",
 *   "Logan's trip to the zoo 20 Nov".
 * - [SchoolKind.WEEKLY]: something every school week on one weekday (PE): "Rex PE Tue", "Logan swimming Thursdays".
 *
 * A child named in the line ("Rex", "Logan's") makes it theirs; otherwise it is both boys'. School days are Monday to
 * Friday, not a UK bank holiday and not inside a day off for that child.
 */
object SchoolFields {
    /** [SchoolKind.wire]. */
    const val KIND = "kind"
    const val TITLE = "title"
    /** One child's name ("Rex"), or empty for both. */
    const val WHO = "who"
    /** First day (epoch day); a weekly item's first week. */
    const val START = "startDay"
    /** Last day, inclusive (epoch day); a single day's is its start. */
    const val END = "endDay"
    /** ISO weekday 1..5 of a weekly item; 0 otherwise. */
    const val WEEKDAY = "weekday"
    const val ADDED_AT = "addedAtMs"
    /** The week-ahead question's answer for a day off: [SchoolRules.COVER_HOME] or [SchoolRules.COVER_COVERED]; null while open. */
    const val COVER = "cover"
    /** The days Meka chose to work from home for it (comma-separated epoch days), so the row can say so and Undo can take them back. */
    const val COVER_DAYS = "coverDays"
    const val DELETED = ActionableFields.DELETED
}

enum class SchoolKind(val wire: String) {
    OFF("off"), DAY("day"), WEEKLY("weekly");

    companion object { fun of(wire: String?): SchoolKind? = entries.firstOrNull { it.wire == wire } }
}

/** What one typed line says ([SchoolRules.read]). */
data class SchoolEntry(
    val kind: SchoolKind,
    val title: String,
    /** "Rex", "Logan", or "" for both. */
    val who: String,
    val startDay: Long,
    val endDay: Long,
    /** ISO weekday of a weekly item, else 0. */
    val weekday: Int = 0,
)

/** One stored school item. */
data class SchoolItem(
    val id: String,
    val kind: SchoolKind,
    val title: String,
    val who: String,
    val startDay: Long,
    val endDay: Long,
    val weekday: Int,
    val cover: String?,
    val coverDays: List<Long>,
    val addedAtMs: Long,
)

/** A row in the School pane: "INSET day" · "Mon 27 Oct · Rex and Logan" · "You're working from home". */
data class SchoolRow(
    val id: String,
    val title: String,
    val line: String,
    /** A quieter line under it (the cover answer, or a weekly item's next one); null when there's nothing to add. */
    val note: String?,
    /** One sentence for TalkBack / VoiceOver. */
    val spoken: String,
)

/**
 * The week-ahead question in Needs you: "Rex and Logan are off Mon 27 Oct" · "INSET day · in 5 days" · "You're in the
 * office that day. Who's covering?" with [homeLabel] and [coveredLabel].
 */
data class SchoolCover(
    val id: String,
    val title: String,
    val line: String,
    val question: String,
    /** What [homeLabel] changes: "Mon 27 Oct shows as work from home on both apps". */
    val detail: String,
    /** The office days it falls on (from today), in order: what [homeLabel] makes work-from-home days. */
    val days: List<Long>,
    val homeLabel: String = SchoolRules.HOME_LABEL,
    val coveredLabel: String = SchoolRules.COVERED_LABEL,
    val spoken: String,
)

/** What answering a cover question did, so Undo can take it back. */
data class SchoolCoverDone(val id: String, val line: String, val homeDays: List<Long>)

data class SchoolView(
    /** Days off from today on, soonest first. */
    val off: List<SchoolRow>,
    /** One-off dates from today on, soonest first. */
    val dates: List<SchoolRow>,
    /** Every week, Monday first. */
    val weekly: List<SchoolRow>,
    /** Open week-ahead questions, soonest first. */
    val covers: List<SchoolCover>,
    /** "Next day off: INSET day · Mon 27 Oct", "Nothing off coming up", or the empty line when nothing is entered. */
    val summary: String,
) {
    val isEmpty: Boolean get() = off.isEmpty() && dates.isEmpty() && weekly.isEmpty()

    companion object { val EMPTY = SchoolView(emptyList(), emptyList(), emptyList(), emptyList(), SchoolRules.EMPTY_LINE) }
}

object SchoolRules {
    /** Rex's and Logan's names, as Meka types them (the same boys as the football fixtures' age groups). */
    val CHILDREN: List<String> = FootballRules.CHILDREN.values.toList()

    const val MAX_TITLE = 60
    /** A day off longer than this is a typing slip, not a holiday (the summer holidays are about six and a half weeks). */
    const val MAX_SPAN_DAYS = 56
    /** How far ahead a date may be (the school year, plus a little). */
    const val MAX_DAYS_AHEAD = 400
    /** The cover question shows from this many days before the first office day off. */
    const val LEAD_DAYS = 7

    const val COVER_HOME = "home"
    const val COVER_COVERED = "covered"
    const val HOME_LABEL = "I'll work from home"
    const val COVERED_LABEL = "Covered"
    const val NO_SCHOOL = "No school"

    const val ADD_HINT = "Add… (INSET 27 Oct, Half term 26–30 Oct, Rex PE Tue)"
    const val EMPTY_LINE = "Nothing entered yet. Type the school's days off once, one line each, and PE days with the weekday."
    const val NOTHING_OFF = "Nothing off coming up"
    const val NOT_READ = "MEKA couldn't read a date in that. Try \"INSET 27 Oct\", \"Half term 26–30 Oct\" or \"Rex PE Tue\"."
    const val SHARED = "Only you see this for now; Jeanette's family page will show it once the family calendar is settled."

    private val I = setOf(RegexOption.IGNORE_CASE)
    private const val ORD = """(?:st|nd|rd|th)?"""
    private const val MONTH = """(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"""
    private const val WEEKDAY = """(mon(?:day)?|tue(?:s(?:day)?)?|wed(?:nesday)?|thu(?:r(?:s(?:day)?)?)?|fri(?:day)?|sat(?:urday)?|sun(?:day)?)"""
    private const val LEAD_WD = """(?:$WEEKDAY,?\s+)?"""
    private const val DASH = """\s*(?:-|–|—|to|until|till)\s*"""
    /** "27 Oct", "27th October 2026", "Oct 27", "27/10", "27/10/26", each with an optional weekday before it. */
    private const val DATE = """$LEAD_WD(?:(\d{1,2})$ORD\s+(?:of\s+)?$MONTH(?:\s+(\d{4}))?|$MONTH\s+(\d{1,2})$ORD(?:\s+(\d{4}))?|(\d{1,2})/(\d{1,2})(?:/(\d{4}|\d{2}))?)"""
    private val dateRe = Regex("""(?:^|[^\w/])$DATE(?![\w/])""", I)
    /** "26–30 Oct", "26th to 30th October 2026". */
    private val shortRange = Regex("""(?:^|[^\w/])$LEAD_WD(\d{1,2})$ORD$DASH$LEAD_WD(\d{1,2})$ORD\s+$MONTH(?:\s+(\d{4}))?(?![\w/])""", I)
    private val fullRange = Regex("""(?:^|[^\w/])$DATE$DASH$DATE(?![\w/])""", I)
    private val weekdayRe = Regex("""\b(?:(?:every|on)\s+)?$WEEKDAY(s)?\b""", I)
    private val bothRe = Regex("""\b(both(?:\s+boys)?|the\s+boys|boys|the\s+kids|kids|children)\b""", I)
    private val offRe = Regex("""\b(inset|training\s+day|holidays?|half[\s-]?term|closed|closure|strike|off|break|no\s+school|staff\s+day|bank\s+holiday)\b""", I)
    private val connectorsLead = Regex("""^(?:\s|,|;|:|-|–|—|&|and\b|from\b|on\b)+""", I)
    private val connectorsTail = Regex("""(?:\s|,|;|:|-|–|—|&|\band|\bfrom|\bon|\bevery|\bthe)+$""", I)

    /** Trimmed, inner runs of spaces made one. */
    fun tidy(text: String): String = text.trim().replace(Regex("""\s+"""), " ")

    /** "" → "Rex and Logan"; "Rex" → "Rex". */
    fun whoLabel(who: String): String = who.ifEmpty { CHILDREN.joinToString(" and ") }

    /** "Rex and Logan are off", "Rex is off". */
    fun offSubject(who: String): String = if (who.isEmpty()) "${whoLabel(who)} are off" else "$who is off"

    /**
     * What one typed line says, or null when it names no date or weekday MEKA can read (or a weekly or one-off item has
     * no title). [today] decides the year of a date typed without one: the next time it comes round (a day off already
     * under way counts from its end, so "Half term 26–30 Oct" typed on the 28th is this year's).
     */
    fun read(text: String, today: Long): SchoolEntry? {
        var s = tidy(text)
        if (s.isEmpty()) return null
        val named = CHILDREN.filter { Regex("""\b$it(?:'s|’s)?\b""", I).containsMatchIn(s) }
        val who = if (named.size == 1 && !bothRe.containsMatchIn(s)) named.single() else ""
        CHILDREN.forEach { s = s.replace(Regex("""\b$it(?:'s|’s)?\b""", I), " ") }
        s = s.replace(bothRe, " ")

        var start: Long? = null
        var end: Long? = null
        // A range that can't be a day off (gone, back to front, too long) refuses the line rather than reading half of it.
        val short = shortRange.find(s)
        if (short != null) {
            val g = short.groupValues
            val month = monthOf(g[5]) ?: return null
            val year = g[6].toIntOrNull()
            val span = rangeDays(g[2].toInt(), month, year, g[4].toInt(), month, year, today) ?: return null
            start = span.first; end = span.second
            s = s.removeRange(short.range)
        } else fullRange.find(s)?.let { m ->
            val a = dateParts(m.groupValues.subList(1, 11)) ?: return null
            val b = dateParts(m.groupValues.subList(11, 21)) ?: return null
            val span = rangeDays(a.day, a.month, a.year, b.day, b.month, b.year, today) ?: return null
            start = span.first; end = span.second
            s = s.removeRange(m.range)
        }
        var weekday = 0
        if (start == null) {
            val m = dateRe.find(s)
            if (m != null) {
                val p = dateParts(m.groupValues.subList(1, 11)) ?: return null
                val d = dayOf(p.day, p.month, p.year, today) ?: return null
                start = d; end = d
                s = s.removeRange(m.range)
            } else {
                val w = weekdayRe.find(s) ?: return null
                weekday = weekdayOf(w.groupValues[1])
                if (weekday > 5) return null
                s = s.removeRange(w.range)
            }
        }
        val title = cleanTitle(s)
        if (weekday > 0) {
            if (title.isEmpty()) return null
            return SchoolEntry(SchoolKind.WEEKLY, title, who, today, today, weekday)
        }
        val first = start ?: return null
        val last = end ?: first
        if (first > today + MAX_DAYS_AHEAD) return null
        val off = last > first || title.isEmpty() || offRe.containsMatchIn(title)
        if (!off) return SchoolEntry(SchoolKind.DAY, title, who, first, last)
        return SchoolEntry(SchoolKind.OFF, offTitle(title), who, first, last)
    }

    /** "inset" → "INSET day"; "off" / "day off" / "closed" / "" → "No school"; else the title as typed (first letter up). */
    fun offTitle(title: String): String {
        val t = title.lowercase()
        return when {
            t.isEmpty() || t in setOf("off", "day off", "days off", "no school", "closed", "school closed") -> NO_SCHOOL
            t == "inset" || t == "inset day" || t == "inset days" -> if (t.endsWith("days")) "INSET days" else "INSET day"
            else -> title.replace(Regex("""\binset\b""", I), "INSET")
        }
    }

    private fun cleanTitle(rest: String): String {
        var t = tidy(rest)
        repeat(3) { t = t.replace(connectorsLead, "").replace(connectorsTail, "").trim() }
        t = tidy(t).take(MAX_TITLE).trim()
        return t.replaceFirstChar { it.uppercase() }
    }

    private data class Parts(val day: Int, val month: Int, val year: Int?)

    /** The ten groups of [DATE]: weekday, d, month, y, month, d, y, d, m, y. */
    private fun dateParts(g: List<String>): Parts? = when {
        g[1].isNotEmpty() -> monthOf(g[2])?.let { Parts(g[1].toInt(), it, g[3].toIntOrNull()) }
        g[5].isNotEmpty() -> monthOf(g[4])?.let { Parts(g[5].toInt(), it, g[6].toIntOrNull()) }
        g[7].isNotEmpty() -> Parts(g[7].toInt(), g[8].toInt(), g[9].toIntOrNull()?.let { if (it < 100) 2000 + it else it })
        else -> null
    }

    private fun valid(day: Int, month: Int, year: Int) =
        month in 1..12 && day in 1..CivilDate.lengthOfMonth(year, month)

    /** A single date: with no year, the next time it comes round (today counts). */
    private fun dayOf(day: Int, month: Int, year: Int?, today: Long): Long? {
        val thisYear = CivilDate.fromEpochDay(today).year
        if (year != null) return if (valid(day, month, year)) CivilDate.toEpochDay(year, month, day).takeIf { it >= today } else null
        for (y in thisYear..thisYear + 1) {
            if (!valid(day, month, y)) continue
            val d = CivilDate.toEpochDay(y, month, day)
            if (d >= today) return d
        }
        return null
    }

    /**
     * A range: with no years, the next time its end comes round, its start in the same year unless it falls later in
     * the year than the end ("18 Dec – 4 Jan"). At most [MAX_SPAN_DAYS] long and not over yet.
     */
    private fun rangeDays(d1: Int, m1: Int, y1: Int?, d2: Int, m2: Int, y2: Int?, today: Long): Pair<Long, Long>? {
        val endDay = dayOf(d2, m2, y2 ?: y1, today) ?: return null
        val endYear = CivilDate.fromEpochDay(endDay).year
        val startYear = y1 ?: if (m1 > m2 || (m1 == m2 && d1 > d2)) endYear - 1 else endYear
        if (!valid(d1, m1, startYear)) return null
        val startDay = CivilDate.toEpochDay(startYear, m1, d1)
        if (endDay < startDay || endDay - startDay >= MAX_SPAN_DAYS) return null
        return startDay to endDay
    }

    private fun monthOf(s: String): Int? = when (s.lowercase().take(3)) {
        "jan" -> 1; "feb" -> 2; "mar" -> 3; "apr" -> 4; "may" -> 5; "jun" -> 6
        "jul" -> 7; "aug" -> 8; "sep" -> 9; "oct" -> 10; "nov" -> 11; "dec" -> 12
        else -> null
    }

    private fun weekdayOf(s: String): Int = when (s.lowercase().take(3)) {
        "mon" -> 1; "tue" -> 2; "wed" -> 3; "thu" -> 4; "fri" -> 5; "sat" -> 6
        else -> 7
    }

    // ---- Reading the stored items ----

    private fun Map<String, FieldValue>.f(field: String): FieldValue = this[field] ?: FieldValue.Null

    /** The live items from each entity's fields (id → fields); unreadable ones are skipped. */
    fun items(entities: Map<String, Map<String, FieldValue>>): List<SchoolItem> = entities.mapNotNull { (id, s) ->
        if (s.f(SchoolFields.DELETED).boolOrNull == true) return@mapNotNull null
        val kind = SchoolKind.of(s.f(SchoolFields.KIND).textOrNull) ?: return@mapNotNull null
        val title = s.f(SchoolFields.TITLE).textOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val start = s.f(SchoolFields.START).longOrNull ?: return@mapNotNull null
        val end = s.f(SchoolFields.END).longOrNull ?: start
        val weekday = (s.f(SchoolFields.WEEKDAY).longOrNull ?: 0L).toInt()
        if (kind == SchoolKind.WEEKLY && weekday !in 1..5) return@mapNotNull null
        SchoolItem(
            id, kind, title, s.f(SchoolFields.WHO).textOrNull.orEmpty(), start, maxOf(start, end), weekday,
            s.f(SchoolFields.COVER).textOrNull?.takeIf { it == COVER_HOME || it == COVER_COVERED },
            WorkHours.decodeHomeDays(s.f(SchoolFields.COVER_DAYS).textOrNull).sorted(),
            s.f(SchoolFields.ADDED_AT).longOrNull ?: 0L,
        )
    }

    /** What adding [e] writes. */
    fun fields(e: SchoolEntry, nowMs: Long): Map<String, FieldValue> = mapOf(
        SchoolFields.KIND to e.kind.wire.fv(),
        SchoolFields.TITLE to e.title.fv(),
        SchoolFields.WHO to e.who.fv(),
        SchoolFields.START to e.startDay.fv(),
        SchoolFields.END to e.endDay.fv(),
        SchoolFields.WEEKDAY to e.weekday.toLong().fv(),
        SchoolFields.ADDED_AT to nowMs.fv(),
    )

    /** Whether [who]'s off on [day] ("" asks for either boy being off counts only when both are). */
    private fun offOn(items: List<SchoolItem>, day: Long, who: String): Boolean = items.any {
        it.kind == SchoolKind.OFF && day in it.startDay..it.endDay && (it.who.isEmpty() || it.who == who)
    }

    /**
     * A school day for [who] ("" for both): Monday to Friday, not a bank holiday and not a day off for them. For both,
     * a day off for one boy still leaves the other at school, so it is a school day.
     */
    fun isSchoolDay(items: List<SchoolItem>, day: Long, who: String, holidays: HolidayCalendar): Boolean {
        if (CivilDate.isoDayOfWeek(day) > 5 || holidays.isHoliday(day)) return false
        if (who.isEmpty()) return CHILDREN.any { !offOn(items, day, it) }
        return !offOn(items, day, who)
    }

    /** A weekly item's next school day from [today] on (within a term's length), or null. */
    fun nextWeekly(item: SchoolItem, items: List<SchoolItem>, today: Long, holidays: HolidayCalendar): Long? {
        var d = today + (item.weekday - CivilDate.isoDayOfWeek(today)).mod(7)
        repeat(12) {
            if (isSchoolDay(items, d, item.who, holidays)) return d
            d += 7
        }
        return null
    }

    /** "Today", "Tomorrow", else "Mon 27 Oct". */
    private fun dayWord(day: Long, today: Long): String = when (day) {
        today -> "Today"
        today + 1 -> "Tomorrow"
        else -> CivilDate.shortLabel(day)
    }

    /** "Mon 27 Oct", "Mon 26 – Fri 30 Oct", "Fri 18 Dec – Mon 4 Jan". */
    fun spanLabel(first: Long, last: Long): String = CalendarAgenda.spanLabel(first, last)

    /** "today", "tomorrow", "in 5 days". */
    private fun inDays(day: Long, today: Long): String = when (val n = day - today) {
        0L -> "today"
        1L -> "tomorrow"
        else -> "in $n days"
    }

    private fun coverNote(item: SchoolItem): String? = when (item.cover) {
        COVER_HOME -> if (item.coverDays.isEmpty()) "You're working from home" else
            "You're working from home ${item.coverDays.joinToString(", ") { CivilDate.shortLabel(it) }}"
        COVER_COVERED -> "Covered"
        else -> null
    }

    /**
     * The School pane and the week-ahead questions. [hours] says which days Meka works and which he works from home
     * (its holidays are the bank holidays).
     */
    fun view(items: List<SchoolItem>, today: Long, hours: WorkHours): SchoolView {
        val off = items.filter { it.kind == SchoolKind.OFF && it.endDay >= today }
            .sortedWith(compareBy<SchoolItem> { it.startDay }.thenBy { it.addedAtMs }.thenBy { it.id })
        val dates = items.filter { it.kind == SchoolKind.DAY && it.startDay >= today }
            .sortedWith(compareBy<SchoolItem> { it.startDay }.thenBy { it.addedAtMs }.thenBy { it.id })
        val weekly = items.filter { it.kind == SchoolKind.WEEKLY }
            .sortedWith(compareBy<SchoolItem> { it.weekday }.thenBy { it.addedAtMs }.thenBy { it.id })
        val offRows = off.map { o ->
            val line = "${spanLabel(o.startDay, o.endDay)} · ${whoLabel(o.who)}"
            val note = coverNote(o)
            SchoolRow(o.id, o.title, line, note, listOfNotNull(o.title, line, note).joinToString(". "))
        }
        val dateRows = dates.map { d ->
            val line = "${dayWord(d.startDay, today)} · ${whoLabel(d.who)}"
            SchoolRow(d.id, d.title, line, null, "${d.title}. $line")
        }
        val weeklyRows = weekly.map { w ->
            val line = "Every ${CivilDate.DAY_LONG[w.weekday - 1]} · ${whoLabel(w.who)}"
            val note = nextWeekly(w, items, today, hours.holidays)?.let { "Next: ${dayWord(it, today)}" }
            SchoolRow(w.id, w.title, line, note, listOfNotNull(w.title, line, note).joinToString(". "))
        }
        val summary = when {
            items.isEmpty() -> EMPTY_LINE
            off.isEmpty() -> NOTHING_OFF
            else -> off.first().let { "Next day off: ${it.title} · ${spanLabel(maxOf(it.startDay, today), it.endDay)}" }
        }
        return SchoolView(offRows, dateRows, weeklyRows, covers(off, today, hours), summary)
    }

    /**
     * The week-ahead questions: each day off not answered yet that falls on office days (work days that aren't
     * work-from-home days) from today on, shown from [LEAD_DAYS] before the first of them.
     */
    fun covers(items: List<SchoolItem>, today: Long, hours: WorkHours): List<SchoolCover> =
        items.filter { it.kind == SchoolKind.OFF && it.cover == null && it.endDay >= today }
            .mapNotNull { o ->
                val days = (maxOf(o.startDay, today)..o.endDay).filter { hours.isWorkDay(it) && it !in hours.homeDays }
                val first = days.firstOrNull() ?: return@mapNotNull null
                if (first - today > LEAD_DAYS) return@mapNotNull null
                val title = "${offSubject(o.who)} ${spanLabel(o.startDay, o.endDay)}"
                val line = "${o.title} · ${inDays(first, today)}"
                val question = when {
                    days.size == 1 && o.startDay == o.endDay -> "You're in the office that day. Who's covering?"
                    days.size == 1 -> "You're in the office on ${CivilDate.shortLabel(first)}. Who's covering?"
                    days.size <= 3 -> "You're in the office on ${listWords(days.map { LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(it) - 1] })}. Who's covering?"
                    else -> "You're in the office on ${days.size} of those days. Who's covering?"
                }
                val detail = if (days.size == 1) "${CivilDate.shortLabel(first)} shows as work from home on both apps"
                else "Those ${days.size} days show as work from home on both apps"
                SchoolCover(o.id, title, line, question, detail, days, spoken = "$title. $line. $question")
            }

    private fun listWords(words: List<String>): String =
        if (words.size <= 1) words.joinToString() else words.dropLast(1).joinToString(", ") + " and " + words.last()

    /** The undo bar's line after answering: "Working from home Mon 27 Oct" · "Covered · INSET day". */
    fun coverLine(cover: SchoolCover, home: Boolean, item: String): String =
        if (home) "Working from home ${cover.days.joinToString(", ") { CivilDate.shortLabel(it) }}" else "$COVERED_LABEL · $item"

    /** The line after adding: "Added INSET day · Mon 27 Oct", "Added PE · every Tuesday · Rex". */
    fun addedLine(e: SchoolEntry): String = when (e.kind) {
        SchoolKind.WEEKLY -> "Added ${e.title} · every ${CivilDate.DAY_LONG[e.weekday - 1]} · ${whoLabel(e.who)}"
        else -> "Added ${e.title} · ${spanLabel(e.startDay, e.endDay)} · ${whoLabel(e.who)}"
    }
}

/** The school items on a replica. */
class School(
    private val replica: Replica,
    private val newId: () -> String,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    fun items(): List<SchoolItem> =
        SchoolRules.items(replica.entities(EntityTypes.SCHOOL_ITEM).associate { it.ref.entityId to it.fields })

    fun view(hours: WorkHours): SchoolView = SchoolRules.view(items(), calendar.epochDayOf(nowMs()), hours)

    /** Adds what [text] says ([SchoolRules.read]); null when MEKA couldn't read it (nothing is written). */
    fun add(text: String): Pair<String, SchoolEntry>? {
        val e = SchoolRules.read(text, calendar.epochDayOf(nowMs())) ?: return null
        val id = newId()
        replica.commitLocal(EntityTypes.SCHOOL_ITEM, id, SchoolRules.fields(e, nowMs()))
        return id to e
    }

    /** Removes [id] for good (entered by mistake); false when it isn't there. */
    fun remove(id: String): Boolean {
        live(id) ?: return false
        replica.commitLocal(EntityTypes.SCHOOL_ITEM, id, mapOf(SchoolFields.DELETED to true.fv()))
        return true
    }

    /**
     * Answers a day off's cover question: [home] records the office [days] Meka will work from home (the caller marks
     * them in work mode), else "Covered". False when it isn't there.
     */
    fun answer(id: String, home: Boolean, days: List<Long>): Boolean {
        live(id) ?: return false
        replica.commitLocal(EntityTypes.SCHOOL_ITEM, id, mapOf(
            SchoolFields.COVER to (if (home) SchoolRules.COVER_HOME else SchoolRules.COVER_COVERED).fv(),
            SchoolFields.COVER_DAYS to (if (home) WorkHours.encodeHomeDays(days.toSet()).fv() else FieldValue.Null),
        ))
        return true
    }

    /** Undo: the question is open again. */
    fun reopen(id: String): Boolean {
        live(id) ?: return false
        replica.commitLocal(EntityTypes.SCHOOL_ITEM, id, mapOf(SchoolFields.COVER to FieldValue.Null, SchoolFields.COVER_DAYS to FieldValue.Null))
        return true
    }

    private fun live(id: String) =
        replica.entity(EntityTypes.SCHOOL_ITEM, id)?.takeIf { it[SchoolFields.DELETED].boolOrNull != true }
}
