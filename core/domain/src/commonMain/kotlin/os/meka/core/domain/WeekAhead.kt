package os.meka.core.domain

import os.meka.core.sync.FieldValue
import os.meka.core.sync.Replica
import os.meka.core.sync.fv

/** What a line in the week ahead is about (it picks the lead mark the pane draws). */
enum class WeekAheadKind { EVENT, FIXTURE, SCHOOL, DATE_NIGHT, DUE, APPOINTMENT, BINS, OCCASION, TRIP, MAIL, PARCEL }

/**
 * One thing on a day of the week ahead: "17:30 · Barça v Getafe", "Theo: PE", "Date night from 19:00". [time] leads the
 * line when it has one ("All day" for an all-day event); [lit] colours it in the accent (the boys off while Meka is in
 * the office, a cancel-by date). [spoken] is the screen reader's label.
 */
data class WeekAheadLine(
    val id: String,
    val kind: WeekAheadKind,
    val time: String?,
    val text: String,
    val lit: Boolean,
    val spoken: String,
)

/**
 * One day of the week ahead: "Mon 12 Oct", what kind of day it is ("Office 09:00–17:30", "Work from home 09:00–15:30",
 * "Annual leave", "Christmas Day · no work"; null for a plain day off), its weather and its lines in time order.
 */
data class WeekAheadDay(
    val epochDay: Long,
    val label: String,
    val dayLine: String?,
    val weather: String?,
    val lines: List<WeekAheadLine>,
    /** Lines past [WeekAheadRules.MAX_LINES], left out ("and 2 more"). */
    val more: Int,
    val spoken: String,
    /** Protected time, slice 5: "Protected all day", "Protected from 19:00" ([ProtectedRules.dayMark]); null with none. */
    val protectedLine: String? = null,
)

/**
 * A goal offered in the week ahead's GOALS FOR THE WEEK (Goals, properly, item 6): its title, "This month · Next · Buy
 * running shoes", and whether it is picked for the week shown.
 */
data class WeekAheadGoal(val goalId: String, val title: String, val meta: String, val picked: Boolean, val spoken: String)

/**
 * The week's goals pick: up to [GoalWeekRules.MAX_PICKS] of Meka's open goals for the week the week ahead shows. [line]
 * is "Pick up to 3 for the week", "2 picked · up to 3" or "3 picked · unpick one to swap"; [full] dims the rest.
 * Empty (no section) with no open goals.
 */
data class WeekAheadGoals(val goals: List<WeekAheadGoal>, val picked: Int, val line: String, val full: Boolean) {
    companion object {
        val EMPTY = WeekAheadGoals(emptyList(), 0, "", false)
    }
}

/** Today's header on Sunday evening: "Your week ahead · 4 office days · 2 matches"; tapping it opens the week ahead. */
data class WeekAheadToday(val text: String, val spoken: String)

/**
 * The Sunday week ahead (Meka approved 2026-10-10), slice 1: the coming week day by day and the things that need both
 * him and his wife. Built by [WeekAheadRules.view] from what MEKA already knows; nothing in it changes anything.
 */
data class WeekAheadView(
    /** The week's Monday (local epoch day). */
    val weekStart: Long,
    /** "Next week" from Saturday, else "This week" (the rest of it). */
    val title: String,
    /** "Mon 12 – Sun 18 Oct" */
    val rangeLabel: String,
    /** "4 office days · 1 from home · 2 matches · Theo and Finn off Fri"; "A quiet week so far" with nothing in it. */
    val summary: String,
    val days: List<WeekAheadDay>,
    /** The family sync: things that need both of them ("Fri 16 Oct · Theo and Finn are off and you're in the office — who's covering?"). */
    val sync: List<String>,
    /** The whole week read out: the summary, then each day with something in it, then the family sync and the goals. */
    val spoken: String,
    /** Goals for the week: the pick of up to three (Goals, properly, item 6). */
    val goals: WeekAheadGoals = WeekAheadGoals.EMPTY,
    /** Inbox autopilot, slice 9b: "Mail · 3 emails need you, from … · 2 to chase this week"; null with none ([MailWeekRules.line]). */
    val mail: String? = null,
    /** Sunday's heads-up's mail words: "3 emails need you · 2 to chase" ([MailWeekRules.noticeBit]). */
    val mailNotice: String? = null,
    /** Protected time, slice 5: "Protected · Sat, Sun all day · 5 evenings from 19:00" ([ProtectedRules.weekLine]); null with none. */
    val protectedLine: String? = null,
) {
    companion object {
        val EMPTY = WeekAheadView(0, "This week", "", "A quiet week so far", emptyList(), emptyList(), "")
    }
}

/**
 * Sunday week ahead, slice 2: whether Sunday's heads-up comes and when (local minute of the day, default 18:00). Synced
 * as `context_mode/week_ahead` (ADR-008 addendum). Off, the week ahead is still in Ask → More; only the heads-up and
 * Today's Sunday line stop.
 */
data class WeekAheadSetting(val on: Boolean, val minute: Int) {
    companion object {
        val DEFAULT = WeekAheadSetting(true, WeekAheadRules.NOTICE_MIN)
    }
}

/** What the week ahead's settings row shows: the switch, the time and "On · Sundays at 18:00 · Listen reads it out". */
data class WeekAheadSettingView(val on: Boolean, val minute: Int, val timeLabel: String, val summary: String, val choices: List<Int>) {
    companion object {
        val EMPTY = WeekAheadRules.settingView(null)
    }
}

object WeekAheadFields {
    const val ON = "on"
    const val MINUTE = "minute"
}

/** Everything the week ahead reads, as the facade has it. */
data class WeekAheadInputs(
    /** The events shown on my day (hidden ones stay hidden), Meka's own edits laid over. */
    val events: List<CalendarEvent>,
    /** Work: the schedule, the bank holidays, work-from-home days and booked leave. */
    val work: WorkHours,
    /** The bank holidays alone (the school's days follow these). */
    val holidays: HolidayCalendar,
    val school: List<SchoolItem> = emptyList(),
    val dateNight: DateNightSetting? = null,
    val renewals: List<RenewalItem> = emptyList(),
    val appointments: List<AppointmentItem> = emptyList(),
    val forecast: WeatherForecast = WeatherForecast.EMPTY,
    /** Meka's open goals in the Goals order (the week's pick offers them). */
    val goals: List<GoalItem> = emptyList(),
    /** Bin days: each collection on its day (a bank holiday's shift included). */
    val bins: List<BinItem> = emptyList(),
    /** Gift planner: birthdays and anniversaries on their day, and a gift's order-by day (lit). */
    val occasions: List<Occasion> = emptyList(),
    /** Travel mode: each trip on its first day in the week, and its bookings on their days. */
    val trips: List<TripItem> = emptyList(),
    /** Whose club training or fixture a series is ([ClubChildRules]), for "who's taking …?" on a clash. */
    val children: Map<String, String> = emptyMap(),
    /** Inbox autopilot, slice 9b: the emails the server sorted as needing him ([MailWeekRules]). */
    val mail: List<MailItem> = emptyList(),
    /** Waiting for, so an email made into one shows its chase day. */
    val waiting: List<WaitingItem> = emptyList(),
    /** Deliveries, slice 2: the parcels read from shipping emails, on the day each is due ([ParcelRules.weekLine]). */
    val parcels: List<Parcel> = emptyList(),
    /** Protected time, slice 5: the setting, so each day shows its protected time and the week a line. */
    val protectedSetting: ProtectedSetting? = null,
)

/**
 * The Sunday week ahead, slice 1 (non-AI, pure, unit-tested).
 *
 * - **Which week:** from Saturday the week starting next Monday ("Next week"); Monday to Friday the rest of this week
 *   from today ("This week"), so it can be opened any time from Ask → More → Week ahead.
 * - **Each day:** the kind of day (office or from home with its hours, annual leave, a bank holiday), the forecast
 *   while the 7-day forecast reaches it, then in time order: all-day events (on their first day in the week, "until
 *   Wed 14"), timed events, Barça's and the boys' club fixtures, what the school says (days off, dates, PE), date
 *   night, appointments booked, bin collections, trips and their bookings, and renewals or bills due (a cancel-by date lit). At most [MAX_LINES] a day.
 * - **Family sync** (things that need both of them, at most [MAX_SYNC]): the boys off on an office day (who's
 *   covering?), the boys' club fixtures (who's taking them?), date night (sitter sorted?) and family events (an event
 *   whose title or calendar names his partner or a child (from About you), family, school or the boys).
 * - **The heads-up:** Sunday at [HEADS_UP_MIN] (18:05, just after the evening digest, so it arrives on its own;
 *   [NoticeSource.WEEK_AHEAD], opening the week ahead): "Your week ahead" · the summary. Today's header carries the same
 *   from 18:00 until midnight.
 * - **Goals for the week** (Goals, properly, item 6): up to [GoalWeekRules.MAX_PICKS] open goals picked for the week
 *   shown; they become that week's goals (Today's "This week: …" line and the watch face's goal ring). Offered: the
 *   picked ones, then the other open goals in the Goals order (This week first), Someday left out, at most
 *   [MAX_GOAL_CHOICES]. With none picked the heads-up asks for them.
 */
object WeekAheadRules {
    /** Today's line from Sunday 18:00 (the default time; slice 2 makes it a setting). */
    const val NOTICE_MIN = 18 * 60
    /** The heads-up five minutes later, so it doesn't ride in the 18:00 evening digest but arrives on its own. */
    const val HEADS_UP_MIN = NOTICE_MIN + 5
    /** Minutes after a digest the heads-up waits when its time is a digest's ([headsUpMinute]). */
    const val AFTER_DIGEST_MIN = 5
    const val ENTITY_ID = "week_ahead"
    /** The times offered on both apps (any quarter hour from 06:00 to 22:00 is accepted). */
    val TIME_CHOICES: List<Int> = listOf(16 * 60, 17 * 60, 18 * 60, 19 * 60, 20 * 60)
    private val TIME_RANGE = 6 * 60..22 * 60
    const val MAX_LINES = 6
    const val MAX_SYNC = 6
    const val MAX_GOAL_CHOICES = 8
    const val QUIET = "A quiet week so far"
    /**
     * A family event names one of these (whole words, any case) in its title or calendar, or a name from About you
     * ([HouseholdNames.familyWords]; rule 13, never in the code).
     */
    val FAMILY_WORDS = setOf("family", "school", "boys", "kids", "parents")

    /** "To sort out with Sam" (About you's partner), else "To sort out together". */
    fun syncTitle(): String = Household.names.partnerFirst?.let { "To sort out with $it" } ?: "To sort out together"

    /** The week's Monday: next Monday from Saturday, else this week's. */
    fun weekStart(today: Long): Long {
        val iso = CivilDate.isoDayOfWeek(today)
        return if (iso >= 6) today + (8 - iso) else today - (iso - 1)
    }

    fun title(today: Long): String = if (CivilDate.isoDayOfWeek(today) >= 6) "Next week" else "This week"

    /** The days shown: the week's, from today on. */
    fun days(today: Long): List<Long> {
        val ws = weekStart(today)
        return (maxOf(ws, today)..ws + 6).toList()
    }

    /** "Office 09:00–17:30", "Work from home 09:00–17:30", "Annual leave", "Christmas Day · no work", or null. */
    fun dayLine(work: WorkHours, holidays: HolidayCalendar, day: Long): String? {
        val iso = CivilDate.isoDayOfWeek(day)
        val wouldWork = work.schedule.worksOn(iso)
        holidays.title(day)?.let { return if (wouldWork) "$it · no work" else it }
        if (day in work.leaveDays && wouldWork) return "Annual leave"
        if (!work.isWorkDay(day)) return null
        val hours = work.hoursOn(day).label
        work.away[day]?.let { return "Away · $it · work $hours" }
        return if (day in work.homeDays) "${WorkHours.HOME_TITLE} $hours" else "Office $hours"
    }

    /** An office day: a work day that isn't from home (the commute, and who's with the boys). */
    fun isOfficeDay(work: WorkHours, day: Long): Boolean = work.atOffice(day)

    private fun hhmm(ms: Long, cal: LocalCalendar) = LocalClock.formatMinute(cal.minuteOfDay(ms))

    /** The lower-case words of [text] (letters and digits), read by hand so Kotlin/Native's regexes can't differ. */
    private fun words(text: String): Set<String> {
        val out = mutableSetOf<String>()
        val sb = StringBuilder()
        for (c in text.lowercase()) {
            if (c in 'a'..'z' || c in '0'..'9') sb.append(c) else if (sb.isNotEmpty()) { out += sb.toString(); sb.clear() }
        }
        if (sb.isNotEmpty()) out += sb.toString()
        return out
    }

    fun isFamilyEvent(e: CalendarEvent): Boolean =
        !e.isFixture && listOfNotNull(e.title, e.calendarTitle, e.calendarName).any { t -> words(t).any { it in FAMILY_WORDS || it in Household.names.familyWords } }

    private data class Keyed(val key: Long, val line: WeekAheadLine)

    /** [day]'s lines in time order, before the cap. */
    fun lines(inputs: WeekAheadInputs, day: Long, window: DayWindow, weekDays: List<Long>, cal: LocalCalendar): List<WeekAheadLine> {
        val out = mutableListOf<Keyed>()
        val first = weekDays.first()
        // On an office day, an event before he's home or after he's left (the commute counts everywhere, slice 3), and a
        // child's training or match during work, is lit with its mark: "18:00 · Training · You're home about 18:50 ·
        // who's taking?". A plain event during work (a team lunch) stays quiet here; Today and the Calendar mark it.
        val clashes = WorkClashRules.clashes(
            inputs.events.filter { it.overlaps(window) }, inputs.work.blocks(day, cal), cal, inputs.children,
            CommuteRules.bands(inputs.work, day, cal),
        ).filter { it.against != ClashWith.WORK || it.needsCover }.associateBy { it.eventId }
        for (e in inputs.events) {
            if (!e.overlaps(window)) continue
            val fixture = e.isFixture || FootballRules.isClubFixture(e)
            val kind = if (fixture) WeekAheadKind.FIXTURE else WeekAheadKind.EVENT
            if (e.allDay) {
                // On its first day in the week only; a longer one says until when.
                val startDay = e.startAtMs.floorDiv(CivilDate.DAY_MS)
                val lastDay = (e.endAtMs - 1).floorDiv(CivilDate.DAY_MS)
                if (day != maxOf(startDay, first)) continue
                val until = if (lastDay > day) " · until ${CivilDate.shortLabel(lastDay)}" else ""
                val text = e.title.trim() + until
                out += Keyed(Long.MIN_VALUE, WeekAheadLine("e-${e.id}", kind, "All day", text, false, "All day, $text"))
            } else {
                // A timed event from the day before (a night out) shows only on the day it starts.
                if (e.startAtMs < window.startMs) continue
                val time = hhmm(e.startAtMs, cal)
                val text = listOfNotNull(e.title.trim(), e.location?.trim()?.takeIf { it.isNotEmpty() }?.let { WeatherRules.shortPlace(it) ?: it.take(32) })
                    .joinToString(" · ")
                val clash = clashes[e.id]
                val shown = listOfNotNull(text, clash?.mark).joinToString(" · ")
                out += Keyed(e.startAtMs, WeekAheadLine("e-${e.id}", kind, time, shown, clash != null, "$time, $shown"))
            }
        }
        val office = isOfficeDay(inputs.work, day)
        for (t in SchoolRules.onDay(inputs.school, day, inputs.holidays)) {
            val text = SchoolRules.thingText(t)
            val lit = t.kind == SchoolKind.OFF && office
            out += Keyed(window.startMs - 1, WeekAheadLine("s-${t.id}", WeekAheadKind.SCHOOL, null, text, lit, SchoolRules.spokenThing(t)))
        }
        BinRules.weekLine(inputs.bins, day, inputs.holidays)?.let { text ->
            out += Keyed(window.startMs - 1, WeekAheadLine("b-$day", WeekAheadKind.BINS, null, text, false, "Bins: $text"))
        }
        GiftRules.weekLines(inputs.occasions, day).forEachIndexed { i, (text, lit) ->
            out += Keyed(window.startMs - 1, WeekAheadLine("g-$day-$i", WeekAheadKind.OCCASION, null, text, lit, text))
        }
        ParcelRules.weekLine(inputs.parcels, day, office)?.let { l ->
            out += Keyed(window.startMs - 1, WeekAheadLine("p-$day", WeekAheadKind.PARCEL, null, l.text, l.lit, l.spoken))
        }
        for ((minute, text, id) in TripRules.weekLines(inputs.trips, day, first)) {
            val time = minute?.let { LocalClock.formatMinute(it) }
            val key = minute?.let { cal.toEpochMs(day, it) } ?: (window.startMs - 1)
            out += Keyed(key, WeekAheadLine(id, WeekAheadKind.TRIP, time, text, false, listOfNotNull(time, text).joinToString(", ")))
        }
        if (DateNightRules.kept(inputs.dateNight, day)) {
            val s = inputs.dateNight!!
            val time = LocalClock.formatMinute(s.startMin)
            out += Keyed(cal.toEpochMs(day, s.startMin), WeekAheadLine("dn-$day", WeekAheadKind.DATE_NIGHT, time, "Date night", false, "Date night from $time"))
        }
        for (a in inputs.appointments) {
            if (a.bookedDay != day) continue
            val time = a.bookedMinute?.let { LocalClock.formatMinute(it) }
            val key = a.bookedMinute?.let { cal.toEpochMs(day, it) } ?: (window.startMs - 1)
            out += Keyed(key, WeekAheadLine("a-${a.id}", WeekAheadKind.APPOINTMENT, time, a.title, false, listOfNotNull(time, a.title).joinToString(", ")))
        }
        for (r in inputs.renewals) {
            val cancel = r.cancelByDay == day
            if (r.dueDay != day && !cancel) continue
            val text = if (cancel) "${r.title} · last day to cancel" else "${r.title} due" +
                (r.costPence?.let { " · " + RenewalRules.formatPence(it) } ?: "")
            out += Keyed(window.startMs - 1, WeekAheadLine("r-${r.id}-${if (cancel) "c" else "d"}", WeekAheadKind.DUE, null, text, cancel, text))
        }
        return out.sortedWith(compareBy<Keyed> { it.key }.thenBy { it.line.text }).map { it.line }
    }

    /** The family sync for the week: things that need both of them. */
    fun sync(inputs: WeekAheadInputs, days: List<Long>, window: (Long) -> DayWindow, cal: LocalCalendar): List<String> {
        val out = mutableListOf<String>()
        // The boys off while Meka is in the office (each stretch once, on its first such day).
        val offSeen = mutableSetOf<String>()
        for (d in days) {
            if (!isOfficeDay(inputs.work, d)) continue
            for (t in SchoolRules.onDay(inputs.school, d, inputs.holidays)) {
                if (t.kind != SchoolKind.OFF || !offSeen.add(t.id)) continue
                out += "${CivilDate.shortLabel(d)} · ${SchoolRules.offSubject(t.who)} and you're in the office — who's covering?"
            }
        }
        for (d in days) {
            val w = window(d)
            inputs.events.filter { FootballRules.isClubFixture(it) && it.overlaps(w) && (it.allDay || it.startAtMs >= w.startMs) }
                .sortedBy { it.startAtMs }
                .forEach { e ->
                    val time = if (e.allDay) "" else " ${hhmm(e.startAtMs, cal)}"
                    out += "${CivilDate.shortLabel(d)}$time · ${e.title.trim()} — who's taking them?"
                }
            if (DateNightRules.kept(inputs.dateNight, d)) {
                val booked = d in inputs.dateNight!!.booked
                out += "${CivilDate.shortLabel(d)} · Date night" + if (booked) " — booked; sitter sorted?" else " — sitter and plans sorted?"
            }
            inputs.events.filter { isFamilyEvent(it) && !FootballRules.isClubFixture(it) && it.overlaps(w) }
                .filter { e -> if (e.allDay) d == maxOf(e.startAtMs.floorDiv(CivilDate.DAY_MS), days.first()) else e.startAtMs >= w.startMs }
                .sortedBy { it.startAtMs }
                .forEach { e ->
                    val time = if (e.allDay) "" else " ${hhmm(e.startAtMs, cal)}"
                    out += "${CivilDate.shortLabel(d)}$time · ${e.title.trim()}"
                }
        }
        return out.distinct().take(MAX_SYNC)
    }

    /** "4 office days · 1 from home · 2 matches · Theo and Finn off Fri · date night Fri"; [QUIET] when there's nothing. */
    fun summary(inputs: WeekAheadInputs, days: List<WeekAheadDay>): String {
        val office = days.count { isOfficeDay(inputs.work, it.epochDay) }
        val home = days.count { inputs.work.isWorkDay(it.epochDay) && it.epochDay in inputs.work.homeDays && it.epochDay !in inputs.work.away }
        val matches = days.sumOf { d -> d.lines.count { it.kind == WeekAheadKind.FIXTURE } }
        val off = days.firstNotNullOfOrNull { d ->
            SchoolRules.onDay(inputs.school, d.epochDay, inputs.holidays).firstOrNull { it.kind == SchoolKind.OFF }?.let { t ->
                SchoolRules.offSubject(t.who).replace(" are off", " off").replace(" is off", " off") + " " +
                    LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(d.epochDay) - 1]
            }
        }
        val night = days.firstOrNull { DateNightRules.kept(inputs.dateNight, it.epochDay) }
            ?.let { "date night " + LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(it.epochDay) - 1] }
        val due = days.sumOf { d -> d.lines.count { it.kind == WeekAheadKind.DUE } }
        val events = days.sumOf { d -> d.lines.count { it.kind == WeekAheadKind.EVENT || it.kind == WeekAheadKind.APPOINTMENT } }
        val work = listOfNotNull(
            office.takeIf { it > 0 }?.let { ShutdownRules.count(it, "office day") },
            home.takeIf { it > 0 }?.let { "$it from home" },
            days.count { it.epochDay in inputs.work.away }.takeIf { it > 0 }?.let { ShutdownRules.count(it, "day") + " away" },
        )
        val rest = listOfNotNull(
            matches.takeIf { it > 0 }?.let { if (it == 1) "1 match" else "$it matches" },
            off,
            night,
            events.takeIf { it > 0 }?.let { ShutdownRules.count(it, "event") },
            due.takeIf { it > 0 }?.let { "$it due" },
        )
        return when {
            work.isEmpty() && rest.isEmpty() -> QUIET
            rest.isEmpty() -> (work + "otherwise quiet").joinToString(" · ")
            else -> (work + rest).joinToString(" · ")
        }
    }

    /** The goals offered for the week starting [weekStart], the picked ones first. */
    fun goalPick(goals: List<GoalItem>, weekStart: Long): WeekAheadGoals {
        val open = goals.filter { !it.done && it.horizon != GoalHorizon.SOMEDAY }
        if (open.isEmpty()) return WeekAheadGoals.EMPTY
        val picked = open.filter { it.weekOf == weekStart }.take(GoalWeekRules.MAX_PICKS)
        val pickedIds = picked.map { it.id }.toSet()
        val rest = open.filter { it.id !in pickedIds }
        val shown = (picked + rest).take(maxOf(MAX_GOAL_CHOICES, picked.size))
        val n = picked.size
        val full = n >= GoalWeekRules.MAX_PICKS
        val line = when {
            n == 0 -> "Pick up to ${GoalWeekRules.MAX_PICKS} for the week"
            full -> "$n picked · unpick one to swap"
            else -> "$n picked · up to ${GoalWeekRules.MAX_PICKS}"
        }
        return WeekAheadGoals(
            shown.map { g ->
                val on = g.id in pickedIds
                val meta = "${GoalRules.horizonLabel(g.horizon)} · ${g.nextLine}"
                WeekAheadGoal(g.id, g.title, meta, on, "${g.title}, ${if (on) "picked for the week" else "not picked"}. $meta")
            },
            n, line, full,
        )
    }

    /** "Your goals for the week: Run 5k and Read 12 books."; with none picked, a nudge to pick; empty with no goals. */
    fun spokenGoals(g: WeekAheadGoals): String {
        if (g.goals.isEmpty()) return ""
        val titles = g.goals.filter { it.picked }.map { it.title.trim() }
        return when (titles.size) {
            0 -> "No goals picked for the week yet."
            1 -> "Your goal for the week: ${titles[0]}."
            else -> "Your goals for the week: ${titles.dropLast(1).joinToString(", ")} and ${titles.last()}."
        }
    }

    private fun spokenDay(label: String, dayLine: String?, weather: String?, lines: List<WeekAheadLine>): String {
        val name = label.substringBefore(' ').let { short -> CivilDate.DAY_LONG[LocalClock.DAY_SHORT.indexOf(short).coerceAtLeast(0)] }
        val bits = listOfNotNull(dayLine?.replace("–", " to "), weather?.replace("°", " degrees")) + lines.map { it.spoken }
        return "$name: " + bits.joinToString(". ") + "."
    }

    fun view(inputs: WeekAheadInputs, nowMs: Long, cal: LocalCalendar, window: (Long) -> DayWindow): WeekAheadView {
        val today = cal.epochDayOf(nowMs)
        val ws = weekStart(today)
        val dayList = days(today)
        // Inbox autopilot, slice 9b: emails naming a day of the week, and email chases, after the day's own lines.
        val mailLines = MailWeekRules.lines(inputs.mail, inputs.waiting, dayList, nowMs, cal).groupBy { it.day }
        val days = dayList.map { d ->
            val all = lines(inputs, d, window(d), dayList, cal) + mailLines[d].orEmpty().map { m ->
                WeekAheadLine(m.id, WeekAheadKind.MAIL, null, m.text, false, m.spoken)
            }
            val shown = all.take(MAX_LINES)
            val dayLine = dayLine(inputs.work, inputs.holidays, d)
            val weather = WeatherRules.dayGlance(inputs.forecast, d, cal)
            WeekAheadDay(d, CivilDate.shortLabel(d), dayLine, weather, shown, all.size - shown.size, spokenDay(CivilDate.shortLabel(d), dayLine, weather, shown),
                ProtectedRules.dayMark(inputs.protectedSetting, d, inputs.work.schedule, inputs.holidays))
        }
        val protectedLine = ProtectedRules.weekLine(inputs.protectedSetting, dayList, inputs.work.schedule, inputs.holidays)
        val summary = summary(inputs, days)
        val sync = sync(inputs, dayList, window, cal)
        val goals = goalPick(inputs.goals, ws)
        val mail = MailWeekRules.line(inputs.mail, inputs.waiting, dayList, nowMs, title(today))
        val spoken = buildList {
            add("Your week ahead. ${summary.replace(" · ", ", ")}.")
            mail?.let { add(MailWeekRules.spokenLine(it)) }
            ProtectedRules.weekSpoken(protectedLine, inputs.protectedSetting)?.let { add(it) }
            days.filter { it.dayLine != null || it.lines.isNotEmpty() }.forEach { add(it.spoken) }
            if (sync.isNotEmpty()) add(syncTitle() + ": " + sync.joinToString("; ") { it.replace(" · ", ", ") } + ".")
            spokenGoals(goals).takeIf { it.isNotEmpty() }?.let { add(it) }
        }.joinToString(" ")
        return WeekAheadView(ws, title(today), ReviewRules.rangeLabel(ws), summary, days, sync, spoken, goals, mail,
            MailWeekRules.noticeBit(inputs.mail, inputs.waiting, dayList, nowMs), protectedLine)
    }

    // ---- Slice 2: the time setting ----

    fun validMinute(minute: Int): Boolean = minute in TIME_RANGE && minute % 15 == 0

    fun read(fields: Map<String, FieldValue>?): WeekAheadSetting {
        if (fields == null) return WeekAheadSetting.DEFAULT
        val minute = fields[WeekAheadFields.MINUTE]?.longOrNull?.toInt()?.takeIf { validMinute(it) } ?: NOTICE_MIN
        return WeekAheadSetting(fields[WeekAheadFields.ON]?.boolOrNull ?: true, minute)
    }

    /** "On · Sundays at 18:00 · Listen reads it out" / "Off · no Sunday heads-up · the week ahead is still here". */
    fun summary(s: WeekAheadSetting): String =
        if (s.on) "On · Sundays at ${LocalClock.formatMinute(s.minute)} · Listen reads it out"
        else "Off · no Sunday heads-up · the week ahead is still here"

    fun settingView(s: WeekAheadSetting?): WeekAheadSettingView {
        val set = s ?: WeekAheadSetting.DEFAULT
        val choices = (TIME_CHOICES + set.minute).distinct().sorted()
        return WeekAheadSettingView(set.on, set.minute, LocalClock.formatMinute(set.minute), summary(set), choices)
    }

    /** The heads-up's minute: [minute], or [AFTER_DIGEST_MIN] later when a digest goes then, so it arrives on its own. */
    fun headsUpMinute(minute: Int, digests: List<Int>): Int = if (minute in digests) minute + AFTER_DIGEST_MIN else minute

    /** Sunday from the set time until midnight: Today's header line for the coming week; null otherwise or when off. */
    fun todayLine(view: WeekAheadView, nowMs: Long, cal: LocalCalendar, setting: WeekAheadSetting = WeekAheadSetting.DEFAULT): WeekAheadToday? {
        if (!setting.on) return null
        val today = cal.epochDayOf(nowMs)
        if (CivilDate.isoDayOfWeek(today) != 7 || cal.minuteOfDay(nowMs) < setting.minute || view.weekStart != today + 1) return null
        return WeekAheadToday("Your week ahead · ${view.summary}", "Your week ahead: ${view.summary.replace(" · ", ", ")}. Opens the week ahead.")
    }

    /**
     * Sunday's heads-up at the set time (18:05 by default: [headsUpMinute] after the 18:00 evening digest) for the week
     * starting tomorrow, with **Listen** (opens the week ahead reading itself aloud); gone by Monday noon; none when off.
     */
    fun notices(
        view: WeekAheadView, nowMs: Long, cal: LocalCalendar,
        setting: WeekAheadSetting = WeekAheadSetting.DEFAULT, digests: List<Int> = NotificationSettings.DEFAULT_DIGESTS,
    ): List<Notice> {
        if (!setting.on) return emptyList()
        val today = cal.epochDayOf(nowMs)
        if (CivilDate.isoDayOfWeek(today) != 7 || view.weekStart != today + 1) return emptyList()
        // Inbox autopilot, slice 9b: the mail's counts only (never who or what: a notification is seen on the lock screen).
        val head = view.summary + (view.mailNotice?.let { " · $it" } ?: "")
        val text = listOfNotNull(
            head,
            view.sync.takeIf { it.isNotEmpty() }?.let { "${ShutdownRules.count(it.size, "thing")} ${syncTitle().replaceFirstChar { c -> c.lowercase() }}" },
            if (view.goals.goals.isNotEmpty() && view.goals.picked == 0) "Pick your goals for the week?" else null,
        ).joinToString(". ").let { if (it.endsWith("?")) it else if (it == head) it else "$it." }
        return listOf(
            Notice(
                key = "weekahead:${view.weekStart}", source = NoticeSource.WEEK_AHEAD, tier = NoticeTier.HEADS_UP,
                title = "Your week ahead", text = text, atMs = cal.toEpochMs(today, headsUpMinute(setting.minute, digests)),
                target = NoticeTarget.WEEK_AHEAD, expiresAtMs = cal.toEpochMs(today + 1, 12 * 60),
                actions = listOf(NoticeAction.LISTEN),
            ),
        )
    }
}

/** The setting, synced (Sunday week ahead, slice 2). */
class WeekAheadSettings(private val replica: Replica) {
    fun setting(): WeekAheadSetting = WeekAheadRules.read(replica.entity(EntityTypes.CONTEXT_MODE, WeekAheadRules.ENTITY_ID)?.fields)

    /** False (nothing written) when the minute isn't a quarter hour from 06:00 to 22:00, or nothing changes. */
    fun set(on: Boolean, minute: Int): Boolean {
        if (!WeekAheadRules.validMinute(minute)) return false
        val next = WeekAheadSetting(on, minute)
        if (replica.entity(EntityTypes.CONTEXT_MODE, WeekAheadRules.ENTITY_ID) != null && setting() == next) return false
        replica.commitLocal(
            EntityTypes.CONTEXT_MODE, WeekAheadRules.ENTITY_ID,
            mapOf(WeekAheadFields.ON to on.fv(), WeekAheadFields.MINUTE to minute.toLong().fv()),
        )
        return true
    }
}
