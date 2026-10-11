package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Appointment nudges (build plan V1): the dentist, the optician, a haircut, the car's service — each with its usual
 * interval, counted from the last visit. When one comes due MEKA asks once in Needs you ("Dentist · due Mon 2 Nov ·
 * last went Sat 12 Apr") with Booked… (the date and time Meka booked), Went (a visit today) and Not yet (asks again in
 * two weeks). MEKA books nothing, calls no one and sends nothing: the nudge is the whole job (slice 1). One synced
 * entity type (ADR-008 addendum 2026-10-11): `appointment`, a random id.
 *
 * Typed in Ask → More → Appointments, one line does it all: "Dentist every 6 months, last 12 Apr", "haircut every
 * 6 weeks", "optician last March", "dentist booked Tue 20 Oct 9:30". A booked day that has passed counts as the last
 * visit. Non-AI, pure.
 */
object AppointmentFields {
    const val TITLE = "title"
    /** The interval in weeks (a haircut); 0 when it is counted in months. */
    const val EVERY_WEEKS = "everyWeeks"
    /** The interval in months (the dentist); used when [EVERY_WEEKS] is 0. */
    const val EVERY_MONTHS = "everyMonths"
    /** Local epoch day of the last visit; Null while not noted. */
    const val LAST_DAY = "lastDay"
    /** Local epoch day of the next booked visit; Null when none. */
    const val BOOKED_DAY = "bookedDay"
    /** Local minute of day of the booked visit; Null when no time was given. */
    const val BOOKED_MINUTE = "bookedMinute"
    /** "Not yet": no nudge before this local epoch day; Null when not set aside. */
    const val LATER_DAY = "laterDay"
    const val ADDED_AT = "addedAtMs"
    const val DELETED = ActionableFields.DELETED
}

/** One appointment as stored. */
data class AppointmentItem(
    val id: String,
    val title: String,
    val everyWeeks: Int,
    val everyMonths: Int,
    val lastDay: Long?,
    val bookedDay: Long?,
    val bookedMinute: Int?,
    val laterDay: Long?,
    val addedAtMs: Long,
)

enum class AppointmentState {
    /** A visit is booked for today or later. */
    BOOKED,
    /** Past its due day, nothing booked. */
    OVERDUE,
    /** Within its lead time, nothing booked. */
    DUE,
    /** No visit noted yet, so MEKA can't tell when it's due. */
    UNKNOWN,
    /** Not yet. */
    LATER,
}

/** One appointment as the pane shows it: "Dentist" · "Every 6 months · last went Sat 12 Apr" · "Due Mon 2 Nov". */
data class AppointmentRow(
    val id: String,
    val title: String,
    val line: String,
    val status: String,
    val state: AppointmentState,
    /** Lit in the accent: overdue, due, or not noted. */
    val lit: Boolean,
    /** Local epoch day it is next due; null while no visit is noted. */
    val dueDay: Long?,
)

/** A common appointment not yet on the list, added in one tap: "Haircut" · "Every 6 weeks". */
data class AppointmentSuggestion(val id: String, val title: String, val line: String)

/** The Needs you card for one appointment that has come due. */
data class AppointmentNudge(
    val id: String,
    /** "Due Mon 2 Nov · every 6 months", "Overdue · was due Mon 5 Oct". */
    val line: String,
    val title: String,
    /** "Last went Sat 12 Apr. Booked the next one?" */
    val question: String,
    val detail: String,
    val spoken: String,
) {
    val bookedLabel: String get() = AppointmentRules.BOOKED_LABEL
    val wentLabel: String get() = AppointmentRules.WENT_LABEL
    val laterLabel: String get() = AppointmentRules.LATER_LABEL
    val whenHint: String get() = AppointmentRules.WHEN_HINT
    /** Slice 2b: a "Call to book" task on Today. */
    val callLabel: String get() = AppointmentRules.CALL_LABEL
    /** Slice 2b: a booking email for the clipboard; MEKA sends nothing. */
    val emailLabel: String get() = AppointmentRules.EMAIL_LABEL
}

/**
 * Call to book (slice 2b): the task to put on Today ("Call to book: Dentist", planned for [day] at [minute]) and the
 * day the card asks again if nothing was booked by then ([laterDay]).
 */
data class AppointmentCallPlan(val title: String, val day: Long, val minute: Int, val laterDay: Long, val line: String)

/** What Call to book did: the task made ([taskId]) and the card's set-aside day before ([done]), for Undo. */
data class AppointmentCall(val taskId: String, val line: String, val done: AppointmentDone)

data class AppointmentView(
    /** Overdue and due first, then not noted, booked, and the rest by when they're due. */
    val rows: List<AppointmentRow>,
    val suggestions: List<AppointmentSuggestion>,
    /** One card per appointment due and not booked or set aside, most overdue first. */
    val nudges: List<AppointmentNudge>,
    /** "4 appointments · 1 due", "3 appointments · all in hand", or [AppointmentRules.EMPTY_SUMMARY]. */
    val summary: String,
) {
    companion object {
        val EMPTY = AppointmentView(emptyList(), AppointmentRules.PRESETS.map { AppointmentRules.suggestion(it) }, emptyList(), AppointmentRules.EMPTY_SUMMARY)
    }
}

/** A typed line read: what it is, and whichever of how often, the last visit and a booking it says. */
data class AppointmentParse(
    val title: String,
    val everyWeeks: Int?,
    val everyMonths: Int?,
    val lastDay: Long?,
    val bookedDay: Long?,
    val bookedMinute: Int?,
    /** Why the line can't be used (a date that couldn't be read); null when it can. */
    val problem: String?,
)

/** What a typed line did: the line under the field, and whether nothing was written. */
data class AppointmentSaid(val line: String, val refused: Boolean, val id: String? = null)

/**
 * What Went, Booked… or Not yet did, with what was there before so Undo can put it back. [refused]: nothing was
 * written (a booking date that couldn't be read), and [line] says why.
 */
data class AppointmentDone(
    val id: String,
    val line: String,
    val refused: Boolean,
    val prevLastDay: Long?,
    val prevBookedDay: Long?,
    val prevBookedMinute: Long?,
    val prevLaterDay: Long?,
)

data class AppointmentPreset(val id: String, val title: String, val everyWeeks: Int, val everyMonths: Int, val aliases: List<String>)

/** Non-AI, pure. */
object AppointmentRules {
    const val MAX_TITLE = 60
    /** "Not yet" asks again after this many days. */
    const val LATER_DAYS = 14
    const val MAX_WEEKS = 104
    const val MAX_MONTHS = 120
    const val HINT = "Dentist every 6 months, last 12 Apr"
    const val EMPTY_SUMMARY = "No appointments yet · add the ones you keep up with"
    const val NOTE = "Type what it is, how often, and when you last went: “Haircut every 6 weeks, last 26 Sep”. When you've booked one: “Dentist booked Tue 20 Oct 9:30”. MEKA nudges you when one comes due; it never books or calls anyone."
    const val NO_TITLE = "Start with what it is: “Dentist every 6 months, last 12 Apr”"
    const val NO_INTERVAL = "How often? “Physio every 3 months”"
    const val BAD_LAST = "Couldn't read when you last went · try “last 12 Apr” or “last March”"
    const val BAD_BOOKED = "Couldn't read the booking · try “booked Tue 20 Oct 9:30”"
    const val NOTHING_NEW = "Nothing to change · add how often, “last …” or “booked …”"
    const val BOOKED_LABEL = "Booked…"
    const val WENT_LABEL = "Went"
    const val LATER_LABEL = "Not yet"
    const val WHEN_HINT = "When? “Tue 20 Oct 9:30”"
    const val CARD_DETAIL = "Booked… takes the day and time, Went notes a visit today, Not yet asks again in two weeks. Call to book puts the call on Today, Copy email gives you an email to send yourself. MEKA books nothing and calls no one."
    const val CALL_LABEL = "Call to book"
    const val EMAIL_LABEL = "Copy email"
    const val EMAIL_COPIED = "Booking email copied · paste it into an email to them"
    /** Call to book lands at lunchtime on a weekday, when a practice is open and Meka can step out. */
    const val CALL_MINUTE = 12 * 60 + 30
    /** After this on a weekday the call goes to the next weekday. */
    const val CALL_LAST_MINUTE = 17 * 60
    /** If nothing is booked by this many days after the call, the card asks again. */
    const val CALL_LATER_DAYS = 2
    /** The heads-up the day an appointment starts asking (or a Not yet runs out), at 09:00. */
    const val NOTICE_MIN = 9 * 60

    val PRESETS: List<AppointmentPreset> = listOf(
        AppointmentPreset("dentist", "Dentist", 0, 6, listOf("dentist", "dental")),
        AppointmentPreset("hygienist", "Hygienist", 0, 6, listOf("hygienist")),
        AppointmentPreset("optician", "Optician", 0, 24, listOf("optician", "eye test", "optometrist")),
        AppointmentPreset("haircut", "Haircut", 6, 0, listOf("haircut", "hair cut", "barber")),
        AppointmentPreset("car-service", "Car service", 0, 12, listOf("car service")),
        AppointmentPreset("gp", "GP check-up", 0, 12, listOf("gp check", "health check")),
    )

    fun preset(id: String): AppointmentPreset? = PRESETS.firstOrNull { it.id == id }

    fun suggestion(p: AppointmentPreset): AppointmentSuggestion = AppointmentSuggestion(p.id, p.title, intervalLabel(p.everyWeeks, p.everyMonths))

    private val I = RegexOption.IGNORE_CASE
    private const val MONTH = """(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)"""
    private const val NUM = """(\d{1,3}|a|an|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|eighteen)"""
    private val keyword = Regex("""(?:^|[\s,;:–—-])(every|each|yearly|annually|monthly|weekly|fortnightly|last|went|booked)\b""", setOf(I))
    private val everyRe = Regex("""\b(?:every|each)\s+(other\s+)?(?:$NUM\s+)?(day|week|fortnight|month|year)s?\b""", setOf(I))
    private val wordlyRe = Regex("""\b(yearly|annually|monthly|weekly|fortnightly)\b""", setOf(I))
    private const val STOP = """(?=\s*(?:[,;]|\bevery\b|\beach\b|\bbooked\b|\blast\b|\bwent\b|\byearly\b|\bannually\b|\bmonthly\b|\bweekly\b|\bfortnightly\b|$))"""
    private val lastRe = Regex("""\b(?:last(?:\s+went)?|went)(?:\s+(?:on|in))?\s+(.+?)$STOP""", setOf(I))
    private val bookedRe = Regex("""\bbooked(?:\s+(?:for|on|in))?\s+(.+?)$STOP""", setOf(I))
    private val agoRe = Regex("""^$NUM\s+(day|week|month|year)s?\s+ago$""", setOf(I))
    private val dmRe = Regex("""^(?:[a-z]+,?\s+)?(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?$MONTH(?:,?\s+(\d{4}))?$""", setOf(I))
    private val mdRe = Regex("""^$MONTH\s+(\d{1,2})(?:st|nd|rd|th)?(?:,?\s+(\d{4}))?$""", setOf(I))
    private val monthRe = Regex("""^$MONTH(?:\s+(\d{4}))?$""", setOf(I))
    private val slashRe = Regex("""^(\d{1,2})/(\d{1,2})(?:/(\d{4}|\d{2}))?$""")

    private fun clean(text: String): String =
        text.replace('’', '\'').replace('‘', '\'').replace(Regex("""\s+"""), " ").trim().trimEnd('.', '!')

    private fun number(s: String?): Int? = when (s?.lowercase()) {
        null, "" -> null
        "a", "an", "one" -> 1; "two" -> 2; "three" -> 3; "four" -> 4; "five" -> 5; "six" -> 6; "seven" -> 7
        "eight" -> 8; "nine" -> 9; "ten" -> 10; "eleven" -> 11; "twelve" -> 12; "eighteen" -> 18
        else -> s.toIntOrNull()
    }

    private fun monthOf(s: String): Int? = when (s.lowercase().take(3)) {
        "jan" -> 1; "feb" -> 2; "mar" -> 3; "apr" -> 4; "may" -> 5; "jun" -> 6
        "jul" -> 7; "aug" -> 8; "sep" -> 9; "oct" -> 10; "nov" -> 11; "dec" -> 12
        else -> null
    }

    /** "dentist." → "Dentist"; at most [MAX_TITLE]. Null when nothing is left. */
    fun tidyTitle(title: String): String? {
        val t = title.trim().trim(',', ';', ':', '-', '–', '—', '.').trim().replace(Regex("""\s+"""), " ").take(MAX_TITLE).trim()
        return t.takeIf { it.isNotEmpty() }?.replaceFirstChar { it.uppercase() }
    }

    /** The common appointment [title] is ("dental check-up" is the dentist), or null. */
    fun presetFor(title: String): AppointmentPreset? {
        val t = title.lowercase()
        return PRESETS.firstOrNull { p -> t == p.title.lowercase() || p.aliases.any { t.contains(it) } }
    }

    /** Appointments that are the same thing: one preset, or the same words ignoring case. */
    fun key(title: String): String = presetFor(title)?.id ?: title.trim().lowercase()

    /** How often from "every …" or "yearly": weeks or months (a day count becomes weeks). Null when it says neither. */
    fun interval(text: String): Pair<Int, Int>? {
        everyRe.find(text)?.let { m ->
            val other = m.groupValues[1].isNotBlank()
            val n = (number(m.groupValues[2]) ?: 1) * (if (other) 2 else 1)
            if (n <= 0) return null
            return when (m.groupValues[3].lowercase()) {
                "day" -> maxOf(1, (n + 3) / 7) to 0
                "week" -> n to 0
                "fortnight" -> n * 2 to 0
                "month" -> 0 to n
                else -> 0 to n * 12
            }.takeIf { (w, mo) -> w <= MAX_WEEKS && mo <= MAX_MONTHS }
        }
        return when (wordlyRe.find(text)?.groupValues?.get(1)?.lowercase()) {
            "yearly", "annually" -> 0 to 12
            "monthly" -> 0 to 1
            "weekly" -> 1 to 0
            "fortnightly" -> 2 to 0
            else -> null
        }
    }

    /** [day] moved on by [months] calendar months (the 31st becomes the month's last day). */
    fun addMonths(day: Long, months: Int): Long {
        val d = CivilDate.fromEpochDay(day)
        val i = (d.month - 1) + months
        val y = d.year + i.floorDiv(12)
        val m = i.mod(12) + 1
        return CivilDate.toEpochDay(y, m, minOf(d.day, CivilDate.lengthOfMonth(y, m)))
    }

    /**
     * A day in the past from "12 Apr", "Sat 12 April 2026", "April 12", "March" (its 1st), "12/4", "3 weeks ago",
     * "yesterday" or "today": with no year, the latest one not after [today]. Null when unreadable or still to come.
     */
    fun pastDay(text: String, today: Long): Long? {
        val s = clean(text).lowercase()
        val y0 = CivilDate.fromEpochDay(today).year
        fun latest(month: Int, day: Int, year: Int?): Long? {
            val years = if (year != null) listOf(year) else listOf(y0, y0 - 1)
            return years.firstNotNullOfOrNull { y ->
                if (month !in 1..12 || day !in 1..CivilDate.lengthOfMonth(y, month)) null
                else CivilDate.toEpochDay(y, month, day).takeIf { it <= today }
            }
        }
        return when {
            s == "today" -> today
            s == "yesterday" -> today - 1
            else -> agoRe.matchEntire(s)?.let { m ->
                val n = number(m.groupValues[1]) ?: return null
                when (m.groupValues[2]) {
                    "day" -> today - n
                    "week" -> today - 7L * n
                    "month" -> addMonths(today, -n)
                    else -> addMonths(today, -12 * n)
                }
            } ?: dmRe.matchEntire(s)?.let { m -> latest(monthOf(m.groupValues[2]) ?: return null, m.groupValues[1].toInt(), m.groupValues[3].toIntOrNull()) }
            ?: mdRe.matchEntire(s)?.let { m -> latest(monthOf(m.groupValues[1]) ?: return null, m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull()) }
            ?: monthRe.matchEntire(s)?.let { m -> latest(monthOf(m.groupValues[1]) ?: return null, 1, m.groupValues[2].toIntOrNull()) }
            ?: slashRe.matchEntire(s)?.let { m ->
                val y = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
                latest(m.groupValues[2].toInt(), m.groupValues[1].toInt(), y)
            }
        }
    }

    /** A booking's day and time from "Tue 20 Oct 9:30", "Friday 3pm", "tomorrow": today or later only. */
    fun bookedWhen(text: String, today: Long, nowMinute: Int): Pair<Long, Int?>? {
        val p = EventTypingRules.parse("visit ${clean(text)}", today, nowMinute) ?: return null
        if (p.title.lowercase() != "visit") return null
        val day = p.day ?: return null
        return if (day < today) null else day to (if (p.allDay) null else p.minute)
    }

    /** Reads a typed line; null when it names nothing. */
    fun parse(text: String, today: Long, nowMinute: Int): AppointmentParse? {
        val t = clean(text)
        if (t.isEmpty()) return null
        val k = keyword.find(t)
        val title = tidyTitle(if (k == null) t else t.substring(0, k.range.first)) ?: return null
        if (k == null) return AppointmentParse(title, null, null, null, null, null, null)
        val rest = t.substring(k.range.first)
        val every = interval(rest)
        var problem: String? = null
        val last = lastRe.find(rest)?.let { m -> pastDay(m.groupValues[1], today).also { if (it == null) problem = BAD_LAST } }
            ?: if (Regex("""\b(?:last|went)\b""", I).containsMatchIn(rest) && problem == null) { problem = BAD_LAST; null } else null
        val booked = bookedRe.find(rest)?.let { m -> bookedWhen(m.groupValues[1], today, nowMinute).also { if (it == null && problem == null) problem = BAD_BOOKED } }
            ?: if (Regex("""\bbooked\b""", I).containsMatchIn(rest) && problem == null) { problem = BAD_BOOKED; null } else null
        return AppointmentParse(title, every?.first, every?.second, last, booked?.first, booked?.second, problem)
    }

    /** "Every 6 weeks", "Every month", "Every 6 months", "Every year", "Every 2 years". */
    fun intervalLabel(weeks: Int, months: Int): String = when {
        weeks == 1 -> "Every week"
        weeks > 1 -> "Every $weeks weeks"
        months == 1 -> "Every month"
        months == 12 -> "Every year"
        months > 12 && months % 12 == 0 -> "Every ${months / 12} years"
        else -> "Every $months months"
    }

    /** The visit the interval counts from: the noted one, or a booked day already gone (taken as a visit). */
    fun lastVisit(item: AppointmentItem, today: Long): Long? {
        val b = item.bookedDay?.takeIf { it < today }
        return listOfNotNull(item.lastDay, b).maxOrNull()
    }

    fun dueDay(item: AppointmentItem, today: Long): Long? {
        val last = lastVisit(item, today) ?: return null
        return if (item.everyWeeks > 0) last + 7L * item.everyWeeks else addMonths(last, maxOf(1, item.everyMonths))
    }

    /** How long before its due day an appointment starts asking: a week for a haircut, two for the dentist. */
    fun leadDays(item: AppointmentItem): Int = when {
        item.everyWeeks > 0 -> (item.everyWeeks * 7 / 6).coerceIn(2, 7)
        item.everyMonths <= 2 -> 7
        else -> 14
    }

    fun state(item: AppointmentItem, today: Long): AppointmentState {
        if (item.bookedDay != null && item.bookedDay >= today) return AppointmentState.BOOKED
        val due = dueDay(item, today) ?: return AppointmentState.UNKNOWN
        return when {
            today > due -> AppointmentState.OVERDUE
            today >= due - leadDays(item) -> AppointmentState.DUE
            else -> AppointmentState.LATER
        }
    }

    /** "today", "tomorrow", "Mon 2 Nov", or "12 Apr 2025" when far off. */
    fun dayWord(day: Long, today: Long): String = RenewalRules.dayWord(day, today)

    /** "Apr 2027". */
    fun monthWord(day: Long): String = CivilDate.fromEpochDay(day).let { "${Recurrence.MONTH_SHORT[it.month - 1]} ${it.year}" }

    private fun bookedWord(item: AppointmentItem, today: Long): String {
        val day = item.bookedDay ?: return ""
        val time = item.bookedMinute?.let { " " + LocalClock.formatMinute(it) } ?: ""
        return dayWord(day, today).replaceFirstChar { it.uppercase() } + time
    }

    fun status(item: AppointmentItem, today: Long): String {
        val due = dueDay(item, today)
        return when (state(item, today)) {
            AppointmentState.BOOKED -> "Booked · ${bookedWord(item, today)}"
            AppointmentState.UNKNOWN -> "When did you last go? · type “${item.title} last 12 Apr”"
            AppointmentState.OVERDUE, AppointmentState.DUE -> {
                val later = item.laterDay?.takeIf { it > today }
                when {
                    later != null -> "Not yet · asks again ${dayWord(later, today)}"
                    due!! < today -> "Overdue · was due ${dayWord(due, today)}"
                    due == today -> "Due today"
                    else -> "Due ${dayWord(due, today)}"
                }
            }
            AppointmentState.LATER -> if (due!! - today > 60) "Next due ${monthWord(due)}" else "Next due ${dayWord(due, today)}"
        }
    }

    fun line(item: AppointmentItem, today: Long): String {
        val every = intervalLabel(item.everyWeeks, item.everyMonths)
        val last = lastVisit(item, today) ?: return every
        return "$every · last went ${dayWord(last, today)}"
    }

    private fun nudging(item: AppointmentItem, today: Long): Boolean {
        val s = state(item, today)
        return (s == AppointmentState.OVERDUE || s == AppointmentState.DUE) && (item.laterDay == null || item.laterDay <= today)
    }

    fun nudge(item: AppointmentItem, today: Long): AppointmentNudge? {
        if (!nudging(item, today)) return null
        val due = dueDay(item, today) ?: return null
        val every = intervalLabel(item.everyWeeks, item.everyMonths).lowercase()
        val line = when {
            due < today -> "Overdue · was due ${dayWord(due, today)}"
            due == today -> "Due today · $every"
            else -> "Due ${dayWord(due, today)} · $every"
        }
        val last = lastVisit(item, today)?.let { dayWord(it, today) }
        val question = if (last != null) "Last went $last. Booked the next one?" else "Booked the next one?"
        return AppointmentNudge(item.id, line, item.title, question, CARD_DETAIL, "${item.title}. $line. $question")
    }

    private fun Map<String, FieldValue>.f(field: String): FieldValue = this[field] ?: FieldValue.Null

    /** The live appointments from each entity's fields (id → fields); removed ones and blanks are gone. */
    fun items(raw: Map<String, Map<String, FieldValue>>): List<AppointmentItem> =
        raw.filterValues { it.f(AppointmentFields.DELETED).boolOrNull != true }
            .map { (id, s) ->
                AppointmentItem(
                    id,
                    s.f(AppointmentFields.TITLE).textOrNull.orEmpty(),
                    (s.f(AppointmentFields.EVERY_WEEKS).longOrNull ?: 0L).toInt().coerceIn(0, MAX_WEEKS),
                    (s.f(AppointmentFields.EVERY_MONTHS).longOrNull ?: 0L).toInt().coerceIn(0, MAX_MONTHS),
                    s.f(AppointmentFields.LAST_DAY).longOrNull,
                    s.f(AppointmentFields.BOOKED_DAY).longOrNull,
                    s.f(AppointmentFields.BOOKED_MINUTE).longOrNull?.toInt()?.takeIf { it in 0 until 24 * 60 },
                    s.f(AppointmentFields.LATER_DAY).longOrNull,
                    s.f(AppointmentFields.ADDED_AT).longOrNull ?: 0L,
                )
            }
            .filter { it.title.isNotEmpty() && (it.everyWeeks > 0 || it.everyMonths > 0) }

    private fun rank(s: AppointmentState): Int = when (s) {
        AppointmentState.OVERDUE -> 0
        AppointmentState.DUE -> 1
        AppointmentState.UNKNOWN -> 2
        AppointmentState.BOOKED -> 3
        AppointmentState.LATER -> 4
    }

    fun row(item: AppointmentItem, today: Long): AppointmentRow {
        val s = state(item, today)
        val lit = s == AppointmentState.UNKNOWN || nudging(item, today)
        return AppointmentRow(item.id, item.title, line(item, today), status(item, today), s, lit, dueDay(item, today))
    }

    fun view(items: List<AppointmentItem>, today: Long): AppointmentView {
        val rows = items.map { row(it, today) to it }
            .sortedWith(compareBy<Pair<AppointmentRow, AppointmentItem>> { rank(it.first.state) }
                .thenBy { if (it.first.state == AppointmentState.BOOKED) it.second.bookedDay else it.first.dueDay ?: Long.MAX_VALUE }
                .thenBy { it.second.addedAtMs }.thenBy { it.second.id })
        val keys = items.map { key(it.title) }.toSet()
        val suggestions = PRESETS.filter { it.id !in keys }.map { suggestion(it) }
        val nudges = rows.mapNotNull { (_, item) -> nudge(item, today) }
        val due = nudges.size
        val summary = when {
            items.isEmpty() -> EMPTY_SUMMARY
            due > 0 -> "${items.size} ${if (items.size == 1) "appointment" else "appointments"} · $due due"
            else -> "${items.size} ${if (items.size == 1) "appointment" else "appointments"} · all in hand"
        }
        return AppointmentView(rows.map { it.first }, suggestions, nudges, summary)
    }

    /**
     * What a typed line writes: an appointment already on the list (same thing, [key]) takes whatever the line says;
     * a new one needs how often (said, or a common appointment's usual interval). Refused, writing nothing, when the
     * line names nothing, says a date that can't be read, or (new) says no interval.
     */
    fun type(
        text: String,
        items: List<AppointmentItem>,
        today: Long,
        nowMinute: Int,
        nowMs: Long,
        newId: () -> String,
    ): Pair<AppointmentSaid, Pair<String, Map<String, FieldValue>>?> {
        val p = parse(text, today, nowMinute) ?: return AppointmentSaid(NO_TITLE, true) to null
        p.problem?.let { return AppointmentSaid(it, true) to null }
        val existing = items.firstOrNull { key(it.title) == key(p.title) }
        val fields = mutableMapOf<String, FieldValue>()
        if (p.everyWeeks != null) {
            fields[AppointmentFields.EVERY_WEEKS] = p.everyWeeks.fv()
            fields[AppointmentFields.EVERY_MONTHS] = (p.everyMonths ?: 0).fv()
        }
        if (p.lastDay != null) {
            fields[AppointmentFields.LAST_DAY] = p.lastDay.fv()
            fields[AppointmentFields.LATER_DAY] = FieldValue.Null
        }
        if (p.bookedDay != null) {
            fields[AppointmentFields.BOOKED_DAY] = p.bookedDay.fv()
            fields[AppointmentFields.BOOKED_MINUTE] = p.bookedMinute.fv()
            fields[AppointmentFields.LATER_DAY] = FieldValue.Null
        }
        val id: String
        val updated: AppointmentItem
        if (existing != null) {
            if (fields.isEmpty()) return AppointmentSaid(NOTHING_NEW, true, existing.id) to null
            id = existing.id
            updated = existing.copy(
                everyWeeks = p.everyWeeks ?: existing.everyWeeks,
                everyMonths = if (p.everyWeeks != null) p.everyMonths ?: 0 else existing.everyMonths,
                lastDay = p.lastDay ?: existing.lastDay,
                bookedDay = p.bookedDay ?: existing.bookedDay,
                bookedMinute = if (p.bookedDay != null) p.bookedMinute else existing.bookedMinute,
                laterDay = if (p.lastDay != null || p.bookedDay != null) null else existing.laterDay,
            )
        } else {
            val preset = presetFor(p.title)
            val weeks = p.everyWeeks ?: preset?.everyWeeks ?: return AppointmentSaid(NO_INTERVAL, true) to null
            val months = if (p.everyWeeks != null) p.everyMonths ?: 0 else preset!!.everyMonths
            id = newId()
            fields[AppointmentFields.TITLE] = p.title.fv()
            fields[AppointmentFields.EVERY_WEEKS] = weeks.fv()
            fields[AppointmentFields.EVERY_MONTHS] = months.fv()
            fields[AppointmentFields.ADDED_AT] = nowMs.fv()
            updated = AppointmentItem(id, p.title, weeks, months, p.lastDay, p.bookedDay, p.bookedMinute, null, nowMs)
        }
        return AppointmentSaid("${updated.title} · ${status(updated, today)}", false, id) to (id to fields.toMap())
    }

    /** "Dentist · went today · next due Apr 2027". */
    fun wentLine(item: AppointmentItem, today: Long): String {
        val after = item.copy(lastDay = today, bookedDay = null, bookedMinute = null, laterDay = null)
        val due = dueDay(after, today)!!
        return "${item.title} · went today · next due ${if (due - today > 60) monthWord(due) else dayWord(due, today)}"
    }

    private fun weekday(day: Long): Boolean = CivilDate.isoDayOfWeek(day) <= 5

    /**
     * When Call to book plans the call: today at 12:30 on a weekday morning, later today (the next quarter hour) on a
     * weekday afternoon before 17:00, else the next weekday at 12:30.
     */
    fun callWhen(today: Long, nowMinute: Int): Pair<Long, Int> {
        if (weekday(today) && nowMinute < CALL_MINUTE) return today to CALL_MINUTE
        if (weekday(today) && nowMinute < CALL_LAST_MINUTE - 15) return today to ((nowMinute / 15) + 1) * 15
        var d = today + 1
        while (!weekday(d)) d++
        return d to CALL_MINUTE
    }

    /** Call to book on [item]'s card: "Call to book: Dentist" on Today, asking again two days after the call. */
    fun callPlan(item: AppointmentItem, today: Long, nowMinute: Int): AppointmentCallPlan {
        val (day, minute) = callWhen(today, nowMinute)
        val title = "Call to book: ${item.title}"
        val line = "$title · planned ${dayWord(day, today)} at ${LocalClock.formatMinute(minute)}"
        return AppointmentCallPlan(title, day, minute, day + CALL_LATER_DAYS, line)
    }

    /** What the email asks for: "a check-up" for the dentist, "an eye test" for the optician, else the title. */
    fun bookingWhat(title: String): String = when (presetFor(title)?.id) {
        "dentist" -> "a dental check-up"
        "hygienist" -> "a hygienist appointment"
        "optician" -> "an eye test"
        "haircut" -> "a haircut"
        "car-service" -> "a service for my car"
        "gp" -> "a health check"
        else -> "an appointment (${title.trim()})"
    }

    /**
     * Copy email on the card (slice 2b): a short booking email Meka pastes into his own mail and sends himself; MEKA
     * sends nothing. Says the last visit when it's known; no times, so Meka adds what suits him.
     */
    fun bookingEmail(item: AppointmentItem, today: Long): String {
        val last = lastVisit(item, today)?.let { d ->
            val y = CivilDate.fromEpochDay(d).year
            " My last visit was on ${CivilDate.longLabel(d)} $y."
        } ?: ""
        return "Subject: Booking ${bookingWhat(item.title)}\n\n" +
            "Hello,\n\n" +
            "I'd like to book ${bookingWhat(item.title)}.$last Could you let me know your next available times?\n\n" +
            "Thank you,\nMeka"
    }

    /**
     * The day [item] starts asking: its lead time before the due day, or the end of a Not yet; null while it can't be
     * due (booked, or no visit noted).
     */
    fun askFrom(item: AppointmentItem, today: Long): Long? {
        if (item.bookedDay != null && item.bookedDay >= today) return null
        val due = dueDay(item, today) ?: return null
        return maxOf(due - leadDays(item), item.laterDay ?: Long.MIN_VALUE)
    }

    /**
     * The heads-up the day an appointment starts asking ([NoticeSource.APPOINTMENTS]), at 09:00, through the governor
     * like every notice (quiet hours and the digests apply): "Dentist · due Mon 2 Nov" · "Last went Wed 1 Apr. Booked
     * the next one?", opening Needs you. One per asking day (a Not yet that runs out asks once more); gone by the next
     * morning, since the Needs you card stays. Nothing once it's booked or went.
     */
    fun notices(items: List<AppointmentItem>, nowMs: Long, cal: LocalCalendar): List<Notice> {
        val today = cal.epochDayOf(nowMs)
        return items.mapNotNull { item ->
            val from = askFrom(item, today) ?: return@mapNotNull null
            if (from < today) return@mapNotNull null
            val due = dueDay(item, today) ?: return@mapNotNull null
            val title = when {
                due < from -> "${item.title} · overdue"
                due == from -> "${item.title} · due today"
                else -> "${item.title} · due ${dayWord(due, from)}"
            }
            val last = lastVisit(item, today)?.let { "Last went ${dayWord(it, from)}. " } ?: ""
            Notice(
                key = "appointment:${item.id}:ask:$from", source = NoticeSource.APPOINTMENTS, tier = NoticeTier.HEADS_UP,
                title = title, text = "${last}Booked the next one?",
                atMs = cal.toEpochMs(from, NOTICE_MIN), target = NoticeTarget.NEEDS_YOU,
                expiresAtMs = cal.toEpochMs(from + 1, 0),
            )
        }
    }
}

/** The appointments on a replica. */
class Appointments(
    private val replica: Replica,
    private val newId: () -> String,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    private fun today(): Long = calendar.epochDayOf(nowMs())

    fun items(): List<AppointmentItem> =
        AppointmentRules.items(replica.entities(EntityTypes.APPOINTMENT).associate { it.ref.entityId to it.fields })

    fun view(): AppointmentView = AppointmentRules.view(items(), today())

    /** One typed line ([AppointmentRules.type]). */
    fun type(text: String): AppointmentSaid {
        val now = nowMs()
        val (said, write) = AppointmentRules.type(text, items(), today(), calendar.minuteOfDay(now), now, newId)
        write?.let { (id, fields) -> replica.commitLocal(EntityTypes.APPOINTMENT, id, fields) }
        return said
    }

    /** Adds a common appointment ([AppointmentRules.PRESETS]) with its usual interval; no visit noted yet. */
    fun addPreset(presetId: String): AppointmentSaid {
        val p = AppointmentRules.preset(presetId) ?: return AppointmentSaid(AppointmentRules.NO_TITLE, true)
        return type(p.title)
    }

    private fun before(item: AppointmentItem, line: String, refused: Boolean = false) = AppointmentDone(
        item.id, line, refused, item.lastDay, item.bookedDay, item.bookedMinute?.toLong(), item.laterDay,
    )

    /** Went: a visit today; any booking and Not yet go. Null when it isn't on the list. */
    fun went(id: String): AppointmentDone? {
        val item = items().firstOrNull { it.id == id } ?: return null
        val today = today()
        val done = before(item, AppointmentRules.wentLine(item, today))
        replica.commitLocal(EntityTypes.APPOINTMENT, id, mapOf(
            AppointmentFields.LAST_DAY to today.fv(),
            AppointmentFields.BOOKED_DAY to FieldValue.Null,
            AppointmentFields.BOOKED_MINUTE to FieldValue.Null,
            AppointmentFields.LATER_DAY to FieldValue.Null,
        ))
        return done
    }

    /** Booked…: the day and time typed ("Tue 20 Oct 9:30"); refused (nothing written) when it can't be read. */
    fun book(id: String, whenText: String): AppointmentDone? {
        val item = items().firstOrNull { it.id == id } ?: return null
        val now = nowMs()
        val today = today()
        val w = AppointmentRules.bookedWhen(whenText, today, calendar.minuteOfDay(now))
            ?: return before(item, AppointmentRules.BAD_BOOKED, refused = true)
        val after = item.copy(bookedDay = w.first, bookedMinute = w.second, laterDay = null)
        val done = before(item, "${item.title} · ${AppointmentRules.status(after, today)}")
        replica.commitLocal(EntityTypes.APPOINTMENT, id, mapOf(
            AppointmentFields.BOOKED_DAY to w.first.fv(),
            AppointmentFields.BOOKED_MINUTE to w.second.fv(),
            AppointmentFields.LATER_DAY to FieldValue.Null,
        ))
        return done
    }

    /** Not yet: no nudge for [AppointmentRules.LATER_DAYS] days. */
    fun notYet(id: String): AppointmentDone? {
        val item = items().firstOrNull { it.id == id } ?: return null
        val today = today()
        val until = today + AppointmentRules.LATER_DAYS
        val done = before(item, "${item.title} · asks again ${AppointmentRules.dayWord(until, today)}")
        replica.commitLocal(EntityTypes.APPOINTMENT, id, mapOf(AppointmentFields.LATER_DAY to until.fv()))
        return done
    }

    /**
     * Call to book (slice 2b): the plan for the task ([AppointmentRules.callPlan]) and the card set aside until two days
     * after the call. The caller makes the task. Null when it isn't on the list.
     */
    fun callToBook(id: String): Pair<AppointmentCallPlan, AppointmentDone>? {
        val item = items().firstOrNull { it.id == id } ?: return null
        val now = nowMs()
        val plan = AppointmentRules.callPlan(item, today(), calendar.minuteOfDay(now))
        val done = before(item, plan.line)
        replica.commitLocal(EntityTypes.APPOINTMENT, id, mapOf(AppointmentFields.LATER_DAY to plan.laterDay.fv()))
        return plan to done
    }

    /** Copy email (slice 2b): the booking email for the clipboard; null when it isn't on the list. */
    fun bookingEmail(id: String): String? =
        items().firstOrNull { it.id == id }?.let { AppointmentRules.bookingEmail(it, today()) }

    /** Undo for Went, Booked… and Not yet: the visit, booking and set-aside day go back to what they were. */
    fun undo(done: AppointmentDone): Boolean {
        if (done.refused || items().none { it.id == done.id }) return false
        replica.commitLocal(EntityTypes.APPOINTMENT, done.id, mapOf(
            AppointmentFields.LAST_DAY to done.prevLastDay.fv(),
            AppointmentFields.BOOKED_DAY to done.prevBookedDay.fv(),
            AppointmentFields.BOOKED_MINUTE to done.prevBookedMinute.fv(),
            AppointmentFields.LATER_DAY to done.prevLaterDay.fv(),
        ))
        return true
    }

    /** Takes an appointment off the list for good (both apps). */
    fun remove(id: String): Boolean {
        replica.entity(EntityTypes.APPOINTMENT, id)?.takeIf { it[AppointmentFields.DELETED].boolOrNull != true } ?: return false
        replica.commitLocal(EntityTypes.APPOINTMENT, id, mapOf(AppointmentFields.DELETED to true.fv()))
        return true
    }
}

/**
 * Appointments in Ask and Talk (appointment nudges, slice 2a): "when's my dentist?" and "am I due a haircut?" are
 * answered from the list. Non-AI, pure. Only Meka's own typed appointments go with a question (what it is, how often,
 * the last visit, a booking and when it's due), so never untrusted.
 */
object AppointmentAskRules {
    /** At most this many appointments go with a question (in the pane's order: due ones first). */
    const val ASK_ITEMS = 12

    private fun tidy(s: String): String = s.replace(Regex("""\s+"""), " ").trim()

    /**
     * The list as MEKA describes it to the model (Ask's `appointments` lines): "Appointments · 2 appointments · 1 due",
     * then each "Dentist · every 6 months · last went Wed 1 Apr · Overdue · was due Thu 1 Oct". Nothing while the list
     * is empty.
     */
    fun askLines(view: AppointmentView): List<String> {
        if (view.rows.isEmpty()) return emptyList()
        val more = if (view.rows.size > ASK_ITEMS) " · the first $ASK_ITEMS below" else ""
        return listOf("Appointments · ${view.summary}$more") +
            view.rows.take(ASK_ITEMS).map { r -> "${tidy(r.title)} · ${r.line.replaceFirstChar { it.lowercase() }} · ${r.status}" }
    }
}
