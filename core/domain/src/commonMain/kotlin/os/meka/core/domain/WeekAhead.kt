package os.meka.core.domain

/** What a line in the week ahead is about (it picks the lead mark the pane draws). */
enum class WeekAheadKind { EVENT, FIXTURE, SCHOOL, DATE_NIGHT, DUE, APPOINTMENT }

/**
 * One thing on a day of the week ahead: "17:30 · Barça v Getafe", "Rex: PE", "Date night from 19:00". [time] leads the
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
)

/** Today's header on Sunday evening: "Your week ahead · 4 office days · 2 matches"; tapping it opens the week ahead. */
data class WeekAheadToday(val text: String, val spoken: String)

/**
 * The Sunday week ahead (Meka approved 2026-10-10), slice 1: the coming week day by day and the things that need both
 * him and Jeanette. Built by [WeekAheadRules.view] from what MEKA already knows; nothing in it changes anything.
 */
data class WeekAheadView(
    /** The week's Monday (local epoch day). */
    val weekStart: Long,
    /** "Next week" from Saturday, else "This week" (the rest of it). */
    val title: String,
    /** "Mon 12 – Sun 18 Oct" */
    val rangeLabel: String,
    /** "4 office days · 1 from home · 2 matches · Rex and Logan off Fri"; "A quiet week so far" with nothing in it. */
    val summary: String,
    val days: List<WeekAheadDay>,
    /** The family sync: things that need both of them ("Fri 16 Oct · Rex and Logan are off and you're in the office — who's covering?"). */
    val sync: List<String>,
    /** The whole week read out: the summary, then each day with something in it, then the family sync. */
    val spoken: String,
) {
    companion object {
        val EMPTY = WeekAheadView(0, "This week", "", "A quiet week so far", emptyList(), emptyList(), "")
    }
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
)

/**
 * The Sunday week ahead, slice 1 (non-AI, pure, unit-tested).
 *
 * - **Which week:** from Saturday the week starting next Monday ("Next week"); Monday to Friday the rest of this week
 *   from today ("This week"), so it can be opened any time from Ask → More → Week ahead.
 * - **Each day:** the kind of day (office or from home with its hours, annual leave, a bank holiday), the forecast
 *   while the 7-day forecast reaches it, then in time order: all-day events (on their first day in the week, "until
 *   Wed 14"), timed events, Barça's and the boys' club fixtures, what the school says (days off, dates, PE), date
 *   night, appointments booked and renewals or bills due (a cancel-by date lit). At most [MAX_LINES] a day.
 * - **Family sync** (things that need both of them, at most [MAX_SYNC]): the boys off on an office day (who's
 *   covering?), the boys' club fixtures (who's taking them?), date night (sitter sorted?) and family events (an event
 *   whose title or calendar names Jeanette, family, school or the boys).
 * - **The heads-up:** Sunday at [HEADS_UP_MIN] (18:05, just after the evening digest, so it arrives on its own;
 *   [NoticeSource.WEEK_AHEAD], opening the week ahead): "Your week ahead" · the summary. Today's header carries the same
 *   from 18:00 until midnight.
 */
object WeekAheadRules {
    /** Today's line from Sunday 18:00. */
    const val NOTICE_MIN = 18 * 60
    /** The heads-up five minutes later, so it doesn't ride in the 18:00 evening digest but arrives on its own. */
    const val HEADS_UP_MIN = NOTICE_MIN + 5
    const val MAX_LINES = 6
    const val MAX_SYNC = 6
    const val QUIET = "A quiet week so far"
    /** A family event names one of these (whole words, any case) in its title or calendar. */
    val FAMILY_WORDS = setOf("jeanette", "family", "school", "rex", "logan", "boys", "kids", "parents")

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
        return if (day in work.homeDays) "${WorkHours.HOME_TITLE} $hours" else "Office $hours"
    }

    /** An office day: a work day that isn't from home (the commute, and who's with the boys). */
    fun isOfficeDay(work: WorkHours, day: Long): Boolean = work.isWorkDay(day) && day !in work.homeDays

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
        !e.isFixture && listOfNotNull(e.title, e.calendarTitle, e.calendarName).any { t -> words(t).any { it in FAMILY_WORDS } }

    private data class Keyed(val key: Long, val line: WeekAheadLine)

    /** [day]'s lines in time order, before the cap. */
    fun lines(inputs: WeekAheadInputs, day: Long, window: DayWindow, weekDays: List<Long>, cal: LocalCalendar): List<WeekAheadLine> {
        val out = mutableListOf<Keyed>()
        val first = weekDays.first()
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
                out += Keyed(e.startAtMs, WeekAheadLine("e-${e.id}", kind, time, text, false, "$time, $text"))
            }
        }
        val office = isOfficeDay(inputs.work, day)
        for (t in SchoolRules.onDay(inputs.school, day, inputs.holidays)) {
            val text = SchoolRules.thingText(t)
            val lit = t.kind == SchoolKind.OFF && office
            out += Keyed(window.startMs - 1, WeekAheadLine("s-${t.id}", WeekAheadKind.SCHOOL, null, text, lit, SchoolRules.spokenThing(t)))
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

    /** "4 office days · 1 from home · 2 matches · Rex and Logan off Fri · date night Fri"; [QUIET] when there's nothing. */
    fun summary(inputs: WeekAheadInputs, days: List<WeekAheadDay>): String {
        val office = days.count { isOfficeDay(inputs.work, it.epochDay) }
        val home = days.count { inputs.work.isWorkDay(it.epochDay) && it.epochDay in inputs.work.homeDays }
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

    private fun spokenDay(label: String, dayLine: String?, weather: String?, lines: List<WeekAheadLine>): String {
        val name = label.substringBefore(' ').let { short -> CivilDate.DAY_LONG[LocalClock.DAY_SHORT.indexOf(short).coerceAtLeast(0)] }
        val bits = listOfNotNull(dayLine?.replace("–", " to "), weather?.replace("°", " degrees")) + lines.map { it.spoken }
        return "$name: " + bits.joinToString(". ") + "."
    }

    fun view(inputs: WeekAheadInputs, nowMs: Long, cal: LocalCalendar, window: (Long) -> DayWindow): WeekAheadView {
        val today = cal.epochDayOf(nowMs)
        val ws = weekStart(today)
        val dayList = days(today)
        val days = dayList.map { d ->
            val all = lines(inputs, d, window(d), dayList, cal)
            val shown = all.take(MAX_LINES)
            val dayLine = dayLine(inputs.work, inputs.holidays, d)
            val weather = WeatherRules.dayGlance(inputs.forecast, d, cal)
            WeekAheadDay(d, CivilDate.shortLabel(d), dayLine, weather, shown, all.size - shown.size, spokenDay(CivilDate.shortLabel(d), dayLine, weather, shown))
        }
        val summary = summary(inputs, days)
        val sync = sync(inputs, dayList, window, cal)
        val spoken = buildList {
            add("Your week ahead. ${summary.replace(" · ", ", ")}.")
            days.filter { it.dayLine != null || it.lines.isNotEmpty() }.forEach { add(it.spoken) }
            if (sync.isNotEmpty()) add("To sort out with Jeanette: " + sync.joinToString("; ") { it.replace(" · ", ", ") } + ".")
        }.joinToString(" ")
        return WeekAheadView(ws, title(today), ReviewRules.rangeLabel(ws), summary, days, sync, spoken)
    }

    /** Sunday from [NOTICE_MIN] until midnight: Today's header line for the coming week; null otherwise. */
    fun todayLine(view: WeekAheadView, nowMs: Long, cal: LocalCalendar): WeekAheadToday? {
        val today = cal.epochDayOf(nowMs)
        if (CivilDate.isoDayOfWeek(today) != 7 || cal.minuteOfDay(nowMs) < NOTICE_MIN || view.weekStart != today + 1) return null
        return WeekAheadToday("Your week ahead · ${view.summary}", "Your week ahead: ${view.summary.replace(" · ", ", ")}. Opens the week ahead.")
    }

    /** Sunday's heads-up at [HEADS_UP_MIN] for the week starting tomorrow; gone by Monday noon. */
    fun notices(view: WeekAheadView, nowMs: Long, cal: LocalCalendar): List<Notice> {
        val today = cal.epochDayOf(nowMs)
        if (CivilDate.isoDayOfWeek(today) != 7 || view.weekStart != today + 1) return emptyList()
        val text = if (view.sync.isEmpty()) view.summary else "${view.summary}. ${ShutdownRules.count(view.sync.size, "thing")} to sort out with Jeanette."
        return listOf(
            Notice(
                key = "weekahead:${view.weekStart}", source = NoticeSource.WEEK_AHEAD, tier = NoticeTier.HEADS_UP,
                title = "Your week ahead", text = text, atMs = cal.toEpochMs(today, HEADS_UP_MIN), target = NoticeTarget.WEEK_AHEAD,
                expiresAtMs = cal.toEpochMs(today + 1, 12 * 60),
            ),
        )
    }
}
