package os.meka.core.domain

/**
 * The commute counts everywhere, slice 5: Ask and Talk know the commute and the wake alarm, so "when do I need to
 * leave?", "when will I be home?" and "what time should I get up?" use the same numbers as Today, the Calendar, the
 * brief and Shut down ([CommuteRules], [AlarmRules]). Lines go with every question (kind `commute`; Meka's own setting
 * and work schedule in MEKA's words, never untrusted). When the AI can't be reached, a question about leaving, getting
 * home or getting up is answered on the device from the same numbers ([localAnswer]). Non-AI, pure.
 */
object CommuteAskRules {
    const val ASK_LINES = 5
    /** How far ahead the next office day is looked for when neither today nor tomorrow is one. */
    const val LOOK_AHEAD_DAYS = 7

    private val ABOUT = listOf(
        Regex("""\bwhen (do|should|must|shall) i (need to |have to )?(leave|set off|head off|go to work)\b"""),
        Regex("""\bwhat time (do|should|must|shall) i (need to |have to )?(leave|set off|head off|get up|wake up)\b"""),
        Regex("""\b(get|getting) up\b"""),
        Regex("""\bwake[- ]?(up|time)\b|\bwake me\b|\bwake alarm\b"""),
        Regex("""\bcommut(e|ing)\b"""),
        Regex("""\bleave by\b|\bleave for work\b"""),
        Regex("""\b(when|what time) (will|am|do) i (be |get )?home\b|\bhome by\b"""),
    )

    /** Whether [question] is about leaving, getting home or getting up (for the offline answer). */
    fun isAbout(question: String): Boolean {
        val q = question.lowercase()
        return ABOUT.any { it.containsMatchIn(q) }
    }

    /** Whether [question] is about getting up rather than leaving. */
    private fun aboutWaking(question: String): Boolean {
        val q = question.lowercase()
        return ABOUT[2].containsMatchIn(q) || ABOUT[3].containsMatchIn(q) || "get up" in q
    }

    private fun clock(m: Int) = LocalClock.formatMinute(m)

    /**
     * "office day · leave 07:40 · work 09:00–17:30 · home about 18:50", or why there's no commute: "work from home
     * 09:00–17:30 · no commute", "annual leave · no commute", "bank holiday (Christmas Day) · no commute", "not a work
     * day · no commute".
     */
    fun dayLine(work: WorkHours, epochDay: Long, calendar: LocalCalendar): String {
        if (CommuteRules.officeDay(work, epochDay)) {
            val bs = CommuteRules.bands(work, epochDay, calendar)
            return listOfNotNull(
                "office day",
                bs.firstOrNull { it.toWork }?.let { "leave ${clock(it.startMinute)}" },
                "work ${work.hoursOn(epochDay).label}",
                bs.firstOrNull { !it.toWork }?.let { "home about ${clock(it.endMinute)}" },
            ).joinToString(" · ")
        }
        val why = when {
            epochDay in work.leaveDays -> "annual leave"
            work.holidays.isHoliday(epochDay) -> "bank holiday" + (work.holidays.title(epochDay)?.let { " ($it)" } ?: "")
            epochDay == work.offDay -> "work switched off today"
            work.isWorkDay(epochDay) && epochDay in work.homeDays -> "work from home ${work.hoursOn(epochDay).label}"
            else -> "not a work day"
        }
        return "$why · no commute"
    }

    /** Where today's office day has got to at [nowMs]: "not left yet", "on the way in", "at work", "on the way home", "home". */
    private fun progress(bands: List<CommuteBand>, nowMs: Long): String? {
        if (bands.isEmpty()) return null
        val to = bands.firstOrNull { it.toWork }
        val home = bands.firstOrNull { !it.toWork }
        return when {
            to != null && nowMs < to.startMs -> "not left yet"
            to != null && nowMs < to.endMs -> "on the way in now"
            home != null && nowMs < home.startMs -> "at work now"
            home != null && nowMs < home.endMs -> "on the way home now"
            home != null -> "home now"
            else -> null
        }
    }

    /** The first office day after tomorrow within [LOOK_AHEAD_DAYS], or null. */
    private fun nextOfficeDay(work: WorkHours, today: Long): Long? =
        ((today + 2)..(today + LOOK_AHEAD_DAYS)).firstOrNull { CommuteRules.officeDay(work, it) }

    /**
     * The Ask lines (kind `commute`): the setting ("Commute · 1 h 20 to work · 1 h 20 home · door to door, office days
     * only"), today (with where the day has got to), tomorrow, the next office day when neither is one, and the wake
     * alarm ([wake], the shutdown's: set or suggested, with why). At most [ASK_LINES].
     */
    fun askLines(work: WorkHours, calendar: LocalCalendar, nowMs: Long, wake: WakeView?): List<String> = buildList {
        val c = work.commute
        val setting = if (c.chosen) "${CommuteRules.label(c.toWorkMin)} to work · ${CommuteRules.label(c.homeMin)} home"
        else "about ${CommuteRules.label(c.toWorkMin)} each way (the default; Meka hasn't set his own)"
        add("Commute · $setting · door to door, office days only")
        val today = calendar.epochDayOf(nowMs)
        val prog = progress(CommuteRules.bands(work, today, calendar), nowMs)
        add(listOfNotNull("Today · ${CivilDate.shortLabel(today)}", dayLine(work, today, calendar), prog).joinToString(" · "))
        add("Tomorrow · ${CivilDate.shortLabel(today + 1)} · ${dayLine(work, today + 1, calendar)}")
        if (!CommuteRules.officeDay(work, today) && !CommuteRules.officeDay(work, today + 1)) {
            val next = nextOfficeDay(work, today)
            add(if (next != null) "Next office day · ${CivilDate.shortLabel(next)} · ${dayLine(work, next, calendar)}"
                else "Next office day · none in the next $LOOK_AHEAD_DAYS days")
        }
        wake?.let { add(wakeLine(it)) }
    }.take(ASK_LINES)

    /**
     * "Wake alarm · Tomorrow · Fri 9 Oct · set for 06:30 · suggested 06:40 (Work at 09:00 · 1 h 20 commute · 1 h to
     * get ready)" · "… · not set · nothing early, so no suggestion".
     */
    fun wakeLine(w: WakeView): String = listOfNotNull(
        "Wake alarm · ${w.dayLabel}",
        w.setMinute?.let { "set for ${clock(it)}" } ?: "not set",
        w.suggestion?.let { "suggested ${clock(it.minute)} (${it.line})" } ?: "nothing early, so no suggestion",
    ).joinToString(" · ")

    /**
     * The device's own answer when the AI can't be reached and [question] [isAbout] the commute; null otherwise.
     * Getting up: the wake alarm and why. Leaving: today's leave time while he hasn't left, his arrival home while
     * he's out, else tomorrow's (or the next office day's) leave and home times.
     */
    fun localAnswer(question: String, work: WorkHours, calendar: LocalCalendar, nowMs: Long, wake: WakeView?): String? {
        if (!isAbout(question)) return null
        if (aboutWaking(question) && wake != null) {
            val day = wake.dayLabel.substringBefore(" · ").lowercase()
            val s = wake.suggestion
            return when {
                wake.setMinute != null && s != null && s.minute < wake.setMinute ->
                    "Your alarm is set for ${clock(wake.setMinute)} $day, but ${clock(s.minute)} would give you time: ${s.line}."
                wake.setMinute != null && s != null -> "Your alarm is set for ${clock(wake.setMinute)} $day: ${s.line}."
                wake.setMinute != null -> "Your alarm is set for ${clock(wake.setMinute)} $day; nothing early is planned."
                s != null -> "Get up at about ${clock(s.minute)} $day: ${s.line}."
                else -> "Nothing early $day, so get up when you like."
            }
        }
        val today = calendar.epochDayOf(nowMs)
        val todayBands = CommuteRules.bands(work, today, calendar)
        val to = todayBands.firstOrNull { it.toWork }
        val home = todayBands.firstOrNull { !it.toWork }
        if (to != null && nowMs < to.startMs) {
            return "Leave about ${clock(to.startMinute)} for work at ${clock(to.endMinute)}" +
                (home?.let { "; home about ${clock(it.endMinute)}." } ?: ".")
        }
        if (home != null && nowMs < home.endMs) {
            return if (nowMs < home.startMs) "Work ends at ${clock(home.startMinute)}, so you're home about ${clock(home.endMinute)}."
            else "You're home about ${clock(home.endMinute)}."
        }
        val tomorrow = today + 1
        val day = if (CommuteRules.officeDay(work, tomorrow)) tomorrow else nextOfficeDay(work, today)
            ?: return "No office days in the next $LOOK_AHEAD_DAYS days, so no commute."
        val bs = CommuteRules.bands(work, day, calendar)
        val word = if (day == tomorrow) "tomorrow" else "on ${CivilDate.shortLabel(day)}"
        val leave = bs.firstOrNull { it.toWork } ?: return "Work starts at ${clock(work.hoursOn(day).startMinute)} $word."
        return "Leave about ${clock(leave.startMinute)} $word for work at ${clock(leave.endMinute)}" +
            (bs.firstOrNull { !it.toWork }?.let { "; home about ${clock(it.endMinute)}." } ?: ".")
    }
}
