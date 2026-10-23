package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/**
 * Annual leave planner (build plan V1, Meka approved 2026-10-10), slice 1: the leave allowance and the days booked
 * against it, the school holidays coming up with how much of each is booked, and booked leave as days off in work mode.
 * Plain, non-AI rules; nothing here leaves Meka's own synced data.
 *
 * One synced `leave` entity per booked stretch (ADR-008 addendum 2026-10-10): "27–30 Oct", "Spain 18 Dec – 4 Jan".
 * Only the work days in a stretch count against the allowance (weekends, bank holidays and days the schedule has off
 * cost nothing), and a day booked twice counts once. The allowance and the month the leave year starts sit on the
 * `context_mode` entity `leave` ([LeaveSettingFields]).
 *
 * Booked leave is a day off for work mode like a bank holiday ([WorkHours.leaveDays], [LeaveRules.withLeave]): no work
 * band on Today or the Calendar, "Off work · Annual leave", no commute or office weather, the brief's "Annual leave ·
 * no work", and no school cover question for a day Meka is off anyway. The school's own days still follow the bank
 * holidays alone.
 */
object LeaveFields {
    const val TITLE = "title"
    /** First day (epoch day). */
    const val START = "startDay"
    /** Last day, inclusive (epoch day). */
    const val END = "endDay"
    const val ADDED_AT = "addedAtMs"
    const val DELETED = ActionableFields.DELETED
}

/** The allowance and the leave year, on the `context_mode` entity [Leave.SETTINGS_ID]; LWW like work mode's fields. */
object LeaveSettingFields {
    /** Days of leave a leave year (whole days). */
    const val ALLOWANCE = "allowance"
    /** The month (1..12) the leave year starts on, on its 1st. */
    const val YEAR_START = "yearStartMonth"
}

/** One booked stretch. */
data class LeaveItem(val id: String, val title: String, val startDay: Long, val endDay: Long, val addedAtMs: Long)

/** What one typed line says ([LeaveRules.read]). */
data class LeaveEntry(val title: String, val startDay: Long, val endDay: Long)

data class LeaveSettings(val allowance: Int = LeaveRules.DEFAULT_ALLOWANCE, val yearStartMonth: Int = 1)

/** A row in the Leave pane: "Spain" · "Fri 18 Dec – Mon 4 Jan · 7 days" · "Next leave year". */
data class LeaveRow(
    val id: String,
    val title: String,
    val line: String,
    /** A quieter line under it ("On leave now", "Next leave year"); null when there's nothing to add. */
    val note: String?,
    /** One sentence for TalkBack / VoiceOver. */
    val spoken: String,
) {
    /** [line] as drawn: "12 days" and dates kept whole (quality gate slice 24). */
    val lineShown: String get() = PhraseGlue.kept(line)
}

/**
 * A school holiday in the Leave pane: "Half term" · "Mon 26 – Fri 30 Oct · 5 work days" · "In 2 weeks · book before
 * the good weeks go" with [bookLabel] ("Book 5 days") while some of its work days aren't booked.
 */
data class LeaveHoliday(
    /** The school item's id ([SchoolItem.id]). */
    val id: String,
    val title: String,
    val line: String,
    val note: String,
    /** The note is the nudge (lit in the accent): nothing booked and it starts within [LeaveRules.NUDGE_DAYS]. */
    val nudge: Boolean,
    /** "Book 3 days" while some of its work days aren't booked; null once they all are. */
    val bookLabel: String?,
    val spoken: String,
) {
    /** [line] as drawn: "5 work days" and dates kept whole, never "5 work / days" (quality gate slice 24). */
    val lineShown: String get() = PhraseGlue.kept(line)
    /** [note] as drawn: "4 of 5 booked" kept whole. */
    val noteShown: String get() = PhraseGlue.kept(note)
}

/** What adding or booking said: the line under the field, and whether nothing was written. */
data class LeaveAdded(val line: String, val refused: Boolean)

data class LeaveView(
    /** "14 of 25 days booked · 11 left", "27 days booked · 2 over your 25", or the empty line. */
    val summary: String,
    /** "Leave year Jan – Dec 2026 · 9 taken · 5 to come". */
    val yearLine: String,
    val allowance: Int,
    val yearStartMonth: Int,
    /** "25 days a year · the leave year starts 1 Jan". */
    val allowanceLine: String,
    /** Stretches still to come or under way, soonest first. */
    val booked: List<LeaveRow>,
    /** Stretches taken this leave year, latest first. */
    val taken: List<LeaveRow>,
    /** The school's holidays in the next six months with work days in them, soonest first. */
    val holidays: List<LeaveHoliday>,
    /** Days booked this leave year (work days only) and how many are left (negative when over). */
    val daysBooked: Int,
    val daysLeft: Int,
) {
    val isEmpty: Boolean get() = booked.isEmpty() && taken.isEmpty()

    companion object {
        val EMPTY = LeaveView(
            LeaveRules.EMPTY_LINE, "", LeaveRules.DEFAULT_ALLOWANCE, 1, LeaveRules.allowanceLine(LeaveSettings()),
            emptyList(), emptyList(), emptyList(), 0, LeaveRules.DEFAULT_ALLOWANCE,
        )
    }
}

object LeaveRules {
    const val TITLE = "Annual leave"
    const val DEFAULT_ALLOWANCE = 25
    const val MIN_ALLOWANCE = 1
    const val MAX_ALLOWANCE = 60
    /** School holidays starting within this many days show in the pane (about six months). */
    const val HORIZON_DAYS = 183
    /** A school holiday with nothing booked that starts within this many days is lit: "book before the good weeks go". */
    const val NUDGE_DAYS = 56

    /** One example, on one line on the cover screen (quality gate slice 23); TalkBack reads [ADD_DESCRIPTION]. */
    const val ADD_HINT = "Spain 18 Dec – 4 Jan"
    const val ADD_DESCRIPTION = "Add a stretch of leave, for example 27–30 Oct, Spain 18 Dec – 4 Jan, or Fri 13 Nov"
    const val EMPTY_LINE = "Nothing booked yet. Type each stretch of leave once; only work days count."
    const val NOT_READ = "Navilife couldn't read a date in that. Try \"27–30 Oct\" or \"Fri 13 Nov\" (add the year for leave already taken)."
    const val NOTE = "Booked leave is a day off on both apps: no work band, no commute, and the brief says “Annual leave · no work”."

    private val genericTitle = setOf("", "leave", "annual leave", "a/l", "al", "off", "day off", "days off", "holiday", "holidays", "booked", "book", "leave booked")

    /** Words that say only "a day off" ("book the … off", "take … as leave"): a title made of these alone is [TITLE]. */
    private val fillerWords = setOf("the", "a", "an", "of", "on", "for", "as", "book", "booked", "take", "taking", "off", "leave",
        "annual", "day", "days", "holiday", "holidays", "please", "me", "my", "i'm", "im", "i", "am", "want", "to", "be")

    /** "leave" / "off" / "" / "The off" (from "book the 2nd of November off") → [TITLE]; else the words as typed. */
    fun title(words: String): String {
        if (words.lowercase() in genericTitle) return TITLE
        val w = words.lowercase().split(Regex("""[^a-z']+""")).filter { it.isNotEmpty() }
        return if (w.all { it in fillerWords }) TITLE else words
    }

    /**
     * What one typed line says, or null when it names no date MEKA can read. With no year, the next time it comes round;
     * a typed year may go back to [earliest] (the leave year's first day), so leave already taken can be entered.
     */
    fun read(text: String, today: Long, earliest: Long = today): LeaveEntry? {
        val s = SchoolRules.tidy(text)
        if (s.isEmpty()) return null
        val found = SchoolRules.findSpan(s, today, earliest) as? SpanFound.Days ?: return null
        if (found.startDay > today + SchoolRules.MAX_DAYS_AHEAD) return null
        return LeaveEntry(title(SchoolRules.cleanTitle(found.rest)), found.startDay, found.endDay)
    }

    // ---- The leave year ----

    /** The first day of the leave year [today] is in. */
    fun yearStart(today: Long, month: Int): Long {
        val d = CivilDate.fromEpochDay(today)
        val m = month.coerceIn(1, 12)
        return CivilDate.toEpochDay(if (d.month >= m) d.year else d.year - 1, m, 1)
    }

    /** The last day of the leave year starting [start]. */
    fun yearEnd(start: Long): Long {
        val d = CivilDate.fromEpochDay(start)
        return CivilDate.toEpochDay(d.year + 1, d.month, 1) - 1
    }

    /** "Jan – Dec 2026", "Apr 2026 – Mar 2027". */
    fun yearLabel(start: Long): String {
        val a = CivilDate.fromEpochDay(start)
        val b = CivilDate.fromEpochDay(yearEnd(start))
        val m = Recurrence.MONTH_SHORT
        return if (a.year == b.year) "${m[a.month - 1]} – ${m[b.month - 1]} ${a.year}" else "${m[a.month - 1]} ${a.year} – ${m[b.month - 1]} ${b.year}"
    }

    /** "25 days a year · the leave year starts 1 Jan". */
    fun allowanceLine(s: LeaveSettings): String =
        "${daysWord(s.allowance)} a year · the leave year starts 1 ${Recurrence.MONTH_SHORT[s.yearStartMonth.coerceIn(1, 12) - 1]}"

    /** "1 day", "5 days". */
    fun daysWord(n: Int): String = if (n == 1) "1 day" else "$n days"

    // ---- Reading the stored items ----

    private fun Map<String, FieldValue>.f(field: String): FieldValue = this[field] ?: FieldValue.Null

    /** The live stretches from each entity's fields (id → fields); unreadable ones are skipped. */
    fun items(entities: Map<String, Map<String, FieldValue>>): List<LeaveItem> = entities.mapNotNull { (id, s) ->
        if (s.f(LeaveFields.DELETED).boolOrNull == true) return@mapNotNull null
        val start = s.f(LeaveFields.START).longOrNull ?: return@mapNotNull null
        val end = s.f(LeaveFields.END).longOrNull ?: start
        if (end < start || end - start >= SchoolRules.MAX_SPAN_DAYS) return@mapNotNull null
        LeaveItem(id, s.f(LeaveFields.TITLE).textOrNull?.takeIf { it.isNotBlank() } ?: TITLE, start, end, s.f(LeaveFields.ADDED_AT).longOrNull ?: 0L)
    }

    /** The settings from the `leave` context entity's fields; out of range values fall back to the defaults. */
    fun settings(fields: Map<String, FieldValue>?): LeaveSettings {
        val allowance = fields?.get(LeaveSettingFields.ALLOWANCE)?.longOrNull?.toInt()?.takeIf { it in MIN_ALLOWANCE..MAX_ALLOWANCE }
        val month = fields?.get(LeaveSettingFields.YEAR_START)?.longOrNull?.toInt()?.takeIf { it in 1..12 }
        return LeaveSettings(allowance ?: DEFAULT_ALLOWANCE, month ?: 1)
    }

    /** What adding [e] writes. */
    fun fields(e: LeaveEntry, nowMs: Long): Map<String, FieldValue> = mapOf(
        LeaveFields.TITLE to e.title.fv(),
        LeaveFields.START to e.startDay.fv(),
        LeaveFields.END to e.endDay.fv(),
        LeaveFields.ADDED_AT to nowMs.fv(),
    )

    /** Every booked day, weekends included. */
    fun days(items: List<LeaveItem>): Set<Long> = items.flatMapTo(HashSet()) { (it.startDay..it.endDay).toList() }

    /** The bank holidays with each booked day added as "Annual leave" (a bank holiday keeps its own name). */
    fun withLeave(holidays: HolidayCalendar, leave: Set<Long>): HolidayCalendar =
        if (leave.isEmpty()) holidays
        else HolidayCalendar(holidays.byDay + leave.filter { it !in holidays.byDay }.associateWith { TITLE })

    /** Whether Meka would work on [day] with no leave booked (the schedule and the bank holidays). */
    private fun baseWorkDay(work: WorkHours, day: Long): Boolean =
        WorkModeRules.isWorkDay(work.schedule, work.holidays, day)

    /** The work days in [first]..[last] (what a stretch costs). */
    fun workDays(work: WorkHours, first: Long, last: Long): List<Long> = (first..last).filter { baseWorkDay(work, it) }

    /**
     * The Leave pane. [work] gives the schedule and bank holidays (its own leave and sick day are ignored); [school] the
     * school's days off for the holidays section.
     */
    fun view(items: List<LeaveItem>, school: List<SchoolItem>, work: WorkHours, settings: LeaveSettings, today: Long): LeaveView {
        val start = yearStart(today, settings.yearStartMonth)
        val end = yearEnd(start)
        val all = days(items)
        val counted = all.filter { it in start..end && baseWorkDay(work, it) }
        val taken = counted.count { it < today }
        val toCome = counted.size - taken
        val left = settings.allowance - counted.size
        val summary = when {
            counted.isEmpty() && items.none { it.endDay >= today } -> EMPTY_LINE
            left >= 0 -> "${counted.size} of ${settings.allowance} days booked · ${daysWord(left)} left"
            else -> "${daysWord(counted.size)} booked · ${-left} over your ${settings.allowance}"
        }
        val yearLine = listOfNotNull(
            "Leave year ${yearLabel(start)}",
            if (taken > 0) "$taken taken" else null,
            if (toCome > 0) "$toCome to come" else null,
        ).joinToString(" · ")
        val booked = items.filter { it.endDay >= today }
            .sortedWith(compareBy<LeaveItem> { it.startDay }.thenBy { it.addedAtMs }.thenBy { it.id })
            .map { row(it, work, today, end) }
        val past = items.filter { it.endDay < today && it.endDay >= start }
            .sortedWith(compareByDescending<LeaveItem> { it.startDay }.thenBy { it.id })
            .map { row(it, work, today, end) }
        return LeaveView(
            summary, yearLine, settings.allowance, settings.yearStartMonth, allowanceLine(settings),
            booked, past, holidays(school, all, work, today), counted.size, left,
        )
    }

    private fun row(it: LeaveItem, work: WorkHours, today: Long, yearEnd: Long): LeaveRow {
        val n = workDays(work, it.startDay, it.endDay).size
        val line = "${SchoolRules.spanLabel(it.startDay, it.endDay)} · ${if (n == 0) "no work days" else daysWord(n)}"
        val note = when {
            today in it.startDay..it.endDay -> "On leave now"
            it.startDay > yearEnd -> "Next leave year"
            else -> null
        }
        return LeaveRow(it.id, it.title, line, note, listOfNotNull(it.title, line, note).joinToString(". "))
    }

    /** "Tomorrow", "In 5 days", "Next week", "In 3 weeks". */
    private fun inWeeks(n: Long): String = when {
        n <= 0L -> "Now"
        n == 1L -> "Tomorrow"
        n < 7L -> "In $n days"
        n < 14L -> "Next week"
        else -> "In ${n / 7} weeks"
    }

    /** The school's days off coming up (within [HORIZON_DAYS]) that fall on work days, with how much is booked. */
    fun holidays(school: List<SchoolItem>, leave: Set<Long>, work: WorkHours, today: Long): List<LeaveHoliday> =
        school.filter { it.kind == SchoolKind.OFF && it.endDay >= today && it.startDay <= today + HORIZON_DAYS }
            .sortedWith(compareBy<SchoolItem> { it.startDay }.thenBy { it.addedAtMs }.thenBy { it.id })
            .mapNotNull { h ->
                val days = workDays(work, maxOf(h.startDay, today), h.endDay)
                if (days.isEmpty()) return@mapNotNull null
                val booked = days.count { it in leave }
                val open = days.size - booked
                val title = if (h.who.isEmpty()) h.title else "${h.title} · ${h.who}"
                val line = "${SchoolRules.spanLabel(h.startDay, h.endDay)} · ${days.size} work ${if (days.size == 1) "day" else "days"}"
                val nudge = booked == 0 && days.first() - today <= NUDGE_DAYS && h.cover == null
                val note = when {
                    open == 0 -> "Booked"
                    booked > 0 -> "$booked of ${days.size} booked"
                    h.cover == SchoolRules.COVER_HOME -> "You're working from home"
                    h.cover == SchoolRules.COVER_COVERED -> "Covered"
                    nudge -> "${inWeeks(days.first() - today)} · book before the good weeks go"
                    else -> "Not booked"
                }
                val book = if (open > 0) "Book ${daysWord(open)}" else null
                LeaveHoliday(h.id, title, line, note, nudge, book, listOf(title, line, note).joinToString(". "))
            }

    /**
     * What adding [e] would do: refused when it holds no work days or they are all booked already; else the line
     * ("Booked Spain · Fri 18 Dec – Mon 4 Jan · 7 days").
     */
    fun addPlan(e: LeaveEntry, items: List<LeaveItem>, work: WorkHours): LeaveAdded {
        val span = SchoolRules.spanLabel(e.startDay, e.endDay)
        val workDays = workDays(work, e.startDay, e.endDay)
        if (workDays.isEmpty()) return LeaveAdded("No work days in $span, so no leave is needed", refused = true)
        val booked = days(items)
        val new = workDays.count { it !in booked }
        if (new == 0) return LeaveAdded("Already booked: $span", refused = true)
        return LeaveAdded("Booked ${e.title} · $span · ${daysWord(new)}", refused = false)
    }

    // ---- Slice 2: the nudge as a heads-up, and the lines either side of leave ----

    /** The nudge's heads-up goes out at 09:00. */
    const val NOTICE_MIN = 9 * 60
    /** How far ahead the day Meka is back at work is looked for (the longest stretch, and a fortnight more). */
    private const val BACK_SEARCH_DAYS = SchoolRules.MAX_SPAN_DAYS + 14
    /** The next work day after the last one before leave is looked for this far ahead (a long weekend, Christmas). */
    private const val NEXT_WORK_DAY_SEARCH = 15

    /**
     * The nudge as a heads-up (slice 2): one for each school holiday still to start whose work days have nothing
     * booked, no cover answer and the first of them within [NUDGE_DAYS] — at 09:00 on the day the pane's nudge lights
     * (or straight away when it is typed in later), opening the Leave pane. Once per holiday (its key holds the school
     * item and its first day); gone once anything in it is booked, it is answered with cover, or its first work day comes.
     * [work] gives the schedule and bank holidays (its own leave is ignored); null, or work mode off, tells nothing.
     */
    fun notices(school: List<SchoolItem>, items: List<LeaveItem>, work: WorkHours?, nowMs: Long, cal: LocalCalendar): List<Notice> {
        if (work == null || !work.schedule.enabled) return emptyList()
        val today = cal.epochDayOf(nowMs)
        val leave = days(items)
        return school.filter { it.kind == SchoolKind.OFF && it.cover == null && it.startDay > today && it.startDay - today <= HORIZON_DAYS }
            .sortedWith(compareBy<SchoolItem> { it.startDay }.thenBy { it.id })
            .mapNotNull { h ->
                val days = workDays(work, h.startDay, h.endDay)
                val first = days.firstOrNull() ?: return@mapNotNull null
                if (first - today > NUDGE_DAYS || days.any { it in leave }) return@mapNotNull null
                val name = if (h.who.isEmpty()) h.title else "${h.title} · ${h.who}"
                Notice(
                    key = "leave:${h.id}:${h.startDay}", source = NoticeSource.LEAVE, tier = NoticeTier.HEADS_UP,
                    title = "$name · nothing booked",
                    text = "${SchoolRules.spanLabel(h.startDay, h.endDay)} · ${days.size} work ${if (days.size == 1) "day" else "days"} · book before the good weeks go",
                    atMs = cal.toEpochMs(maxOf(first - NUDGE_DAYS, today), NOTICE_MIN), target = NoticeTarget.LEAVE,
                    expiresAtMs = cal.toEpochMs(first, 0),
                )
            }
    }

    /** The first day from [from] on that Meka works (no leave booked, not a day off); null when none is near. */
    fun backDay(leave: Set<Long>, work: WorkHours, from: Long): Long? =
        (from until from + BACK_SEARCH_DAYS).firstOrNull { it !in leave && baseWorkDay(work, it) }

    /**
     * The brief's and the shutdown's work line for [day] ([base] is [BriefRules.workLine]'s, null on a day off) with
     * leave either side of it: on the last work day before leave "Work 09:00–17:30 · last day before leave · back Mon 2 Nov";
     * on a day of leave "Annual leave · no work · back Mon 2 Nov"; otherwise [base] as it was.
     */
    fun workLine(base: String?, leave: Set<Long>, work: WorkHours, day: Long): String? {
        base ?: return null
        if (leave.isEmpty()) return base
        if (day in leave) {
            if (!baseWorkDay(work, day)) return base
            return backDay(leave, work, day)?.let { "$base · back ${CivilDate.shortLabel(it)}" } ?: base
        }
        if (!baseWorkDay(work, day)) return base
        val next = (day + 1 until day + NEXT_WORK_DAY_SEARCH).firstOrNull { baseWorkDay(work, it) } ?: return base
        if (next !in leave) return base
        val back = backDay(leave, work, next)
        return if (back == null) "$base · last day before leave" else "$base · last day before leave · back ${CivilDate.shortLabel(back)}"
    }

    /** Booking a school holiday: its work days not yet booked, from the first to the last, under the holiday's name. */
    fun holidayEntry(h: SchoolItem, leave: Set<Long>, work: WorkHours, today: Long): LeaveEntry? {
        val open = workDays(work, maxOf(h.startDay, today), h.endDay).filter { it !in leave }
        if (open.isEmpty()) return null
        return LeaveEntry(h.title, open.first(), open.last())
    }
}

/** The booked leave and its settings on a replica. */
class Leave(
    private val replica: Replica,
    private val newId: () -> String,
    private val nowMs: () -> Long,
    private val calendar: LocalCalendar = LocalCalendar.UTC,
) {
    fun items(): List<LeaveItem> = LeaveRules.items(replica.entities(EntityTypes.LEAVE).associate { it.ref.entityId to it.fields })

    /** Every booked day (work mode's days off). */
    fun days(): Set<Long> = LeaveRules.days(items())

    fun settings(): LeaveSettings = LeaveRules.settings(replica.entity(EntityTypes.CONTEXT_MODE, SETTINGS_ID)?.fields)

    private fun today() = calendar.epochDayOf(nowMs())

    fun view(work: WorkHours, school: List<SchoolItem>): LeaveView = LeaveRules.view(items(), school, work, settings(), today())

    /** Books what [text] says ([LeaveRules.read]); null when MEKA couldn't read a date (nothing is written). */
    fun add(text: String, work: WorkHours): LeaveAdded? {
        val today = today()
        val e = LeaveRules.read(text, today, LeaveRules.yearStart(today, settings().yearStartMonth)) ?: return null
        return book(e, work)
    }

    /** Books a school holiday's work days not yet booked; null when it isn't there or they all are. */
    fun bookHoliday(schoolId: String, school: List<SchoolItem>, work: WorkHours): LeaveAdded? {
        val h = school.firstOrNull { it.id == schoolId && it.kind == SchoolKind.OFF } ?: return null
        val e = LeaveRules.holidayEntry(h, days(), work, today()) ?: return null
        return book(e, work)
    }

    private fun book(e: LeaveEntry, work: WorkHours): LeaveAdded = bookEntry(e, work).first

    /**
     * Books [e] as it stands (an Ask card's stretch, checked again now): what [LeaveRules.addPlan] says, and the new
     * stretch's id (null when refused: no work days in it, or they are all booked already).
     */
    fun bookEntry(e: LeaveEntry, work: WorkHours): Pair<LeaveAdded, String?> {
        val plan = LeaveRules.addPlan(e, items(), work)
        if (plan.refused) return plan to null
        val id = newId()
        replica.commitLocal(EntityTypes.LEAVE, id, LeaveRules.fields(e, nowMs()))
        return plan to id
    }

    /** Takes a stretch off for good (cancelled, or typed by mistake); false when it isn't there. */
    fun remove(id: String): Boolean {
        replica.entity(EntityTypes.LEAVE, id)?.takeIf { it[LeaveFields.DELETED].boolOrNull != true } ?: return false
        replica.commitLocal(EntityTypes.LEAVE, id, mapOf(LeaveFields.DELETED to true.fv()))
        return true
    }

    /** The allowance (clamped to [LeaveRules.MIN_ALLOWANCE]..[LeaveRules.MAX_ALLOWANCE]) and the leave year's month. */
    fun setSettings(allowance: Int, yearStartMonth: Int): Boolean {
        val next = LeaveSettings(allowance.coerceIn(LeaveRules.MIN_ALLOWANCE, LeaveRules.MAX_ALLOWANCE), yearStartMonth.coerceIn(1, 12))
        val stored = replica.entity(EntityTypes.CONTEXT_MODE, SETTINGS_ID)
        if (stored != null && LeaveRules.settings(stored.fields) == next && stored.fields[LeaveSettingFields.ALLOWANCE] != null) return false
        replica.commitLocal(EntityTypes.CONTEXT_MODE, SETTINGS_ID, mapOf(
            LeaveSettingFields.ALLOWANCE to next.allowance.toLong().fv(),
            LeaveSettingFields.YEAR_START to next.yearStartMonth.toLong().fv(),
        ))
        return true
    }

    companion object {
        const val SETTINGS_ID = "leave"
    }
}

/** What Ask needs to check a `book_leave` card: the stretches booked, work hours without leave, and the settings. */
data class LeaveAskData(val items: List<LeaveItem>, val work: WorkHours, val settings: LeaveSettings)

/**
 * Ask and Talk know the leave (annual leave, slice 2b): "how many days have I got left?" is answered from the planner,
 * and "book the 2nd of November off" becomes a card that books it on Meka's tap, with Undo. Non-AI, pure. Every line is
 * Meka's own (his allowance, his stretches, the school holidays he typed), so leave lines never mark a question untrusted.
 */
object LeaveAskRules {
    /** How many stretches to come go with a question (soonest first). */
    const val ASK_BOOKED = 6
    /** How many taken this leave year (latest first). */
    const val ASK_TAKEN = 3
    /** How many school holidays coming up. */
    const val ASK_HOLIDAYS = 4
    /** The most lines leave adds to a question (the head and the rows above). */
    const val MAX_LINES = 1 + ASK_BOOKED + ASK_TAKEN + ASK_HOLIDAYS

    /**
     * The leave as MEKA describes it to the model (Ask's `leave` lines): first the count ("Annual leave · 17 of 25 days
     * booked · 8 days left · Leave year Jan – Dec 2026 · 5 taken · 12 to come"), then each stretch to come ("Booked ·
     * Spain · Fri 18 Dec – Mon 4 Jan · 7 days · Next leave year"), the few taken this leave year ("Taken · …") and the
     * school holidays coming up with how much is booked ("School holiday · Half term · Mon 26 – Fri 30 Oct · 5 work days
     * · Not booked"). While nothing is booked and no school holiday is coming, only for a question about leave
     * ([mentionsLeave]) with work mode on, so other questions don't carry an empty planner.
     */
    fun askLines(view: LeaveView, workOn: Boolean, question: String): List<String> {
        if (view.isEmpty && view.holidays.isEmpty() && !(workOn && mentionsLeave(question))) return emptyList()
        val left = if (view.daysLeft >= 0) "${LeaveRules.daysWord(view.daysLeft)} left" else "${-view.daysLeft} over the allowance"
        val head = listOf("Annual leave", "${view.daysBooked} of ${view.allowance} days booked", left, view.yearLine)
            .filter { it.isNotEmpty() }.joinToString(" · ")
        fun row(kind: String, r: LeaveRow) = listOfNotNull(kind, r.title, r.line, r.note).joinToString(" · ")
        return listOf(head) +
            view.booked.take(ASK_BOOKED).map { row("Booked", it) } +
            view.taken.take(ASK_TAKEN).map { row("Taken", it) } +
            view.holidays.take(ASK_HOLIDAYS).map { "School holiday · ${it.title} · ${it.line} · ${it.note}" }
    }

    private val leaveWords = Regex("""\b(leave|holidays?|day off|days off|time off|off work|allowance|annual)\b""", RegexOption.IGNORE_CASE)

    /** "How many days have I got left?" counts too: a question about leave, holidays or days off. */
    fun mentionsLeave(question: String): Boolean =
        leaveWords.containsMatchIn(question) || Regex("""\bdays\b.*\bleft\b""", RegexOption.IGNORE_CASE).containsMatchIn(question)

    /**
     * The stretch a model's `book_leave` names: [title] read as Meka would type it in the Leave pane ("Mon 2 Nov",
     * "27–30 Oct", "Spain 18 Dec – 4 Jan"), else the one day [day] (the card's ISO date) as plain annual leave. Null when
     * neither reads, or when it holds no work days or they are all booked already (no card).
     */
    fun proposal(title: String?, day: Long?, data: LeaveAskData, today: Long): AskProposal.BookLeave? {
        val earliest = LeaveRules.yearStart(today, data.settings.yearStartMonth)
        val entry = title?.let { LeaveRules.read(it, today, earliest) }
            ?: day?.let { LeaveEntry(LeaveRules.TITLE, it, it) }
            ?: return null
        val plan = LeaveRules.addPlan(entry, data.items, data.work)
        if (plan.refused) return null
        val booked = LeaveRules.days(data.items)
        val days = LeaveRules.workDays(data.work, entry.startDay, entry.endDay).count { it !in booked }
        return AskProposal.BookLeave(entry.title, entry.startDay, entry.endDay, days)
    }

    /** "annual leave" for the plain title, else the name as typed ("Spain"). */
    private fun name(title: String) = if (title == LeaveRules.TITLE) "annual leave" else title

    /** The card's words: "Book annual leave · Mon 2 Nov · 1 day", "Book Spain · Fri 18 Dec – Mon 4 Jan · 7 days". */
    fun cardLine(p: AskProposal.BookLeave): String =
        "Book ${name(p.title)} · ${SchoolRules.spanLabel(p.startDay, p.endDay)} · ${LeaveRules.daysWord(p.days)}"

    /** The undo bar's line: "Booked annual leave · Mon 2 Nov · 1 day". */
    fun doneLine(p: AskProposal.BookLeave): String =
        "Booked ${name(p.title)} · ${SchoolRules.spanLabel(p.startDay, p.endDay)} · ${LeaveRules.daysWord(p.days)}"
}
