package os.meka.core.domain

/**
 * Work mode → Commute as rows opening sheets (walk-through week 5, A17: no open chip rows on a page). The usual commute's
 * To work and Home chip rows are each one card row saying the time in use ("To work" · "1 h 20" ›, "About 1 h 20" while
 * it is Navilife's guess) opening a sheet of the times ([CommuteRules.choices]), the one in use lit, each saying what it
 * does to the morning or the evening. Just one day's row of day chips is one row ("Just one day" · "Choose a day")
 * opening a sheet of the coming office days, and the chosen day's two rows open the same time sheets for that day.
 *
 * A choice writes what the chip's tap wrote ([CommuteRules.tap], so picking the lit guess keeps it as his own); the Mac
 * already picks these from menus, built from the same [CommuteRules]. Reuses Notifications' row and sheet shapes
 * ([NotifyChoiceRow], [NotifyChoice], [NotifyChoiceSheet]). Pure; nothing stored.
 */
object CommuteChoiceRules {
    const val TO_WORK = "To work"
    const val HOME = "Home"
    const val CHOOSE_DAY = "Choose a day"
    /** The day sheet's first choice: no day picked, the page shows only the usual times. */
    const val NO_DAY = "none"
    const val NO_DAY_LABEL = "No day"
    const val NO_DAY_LINE = "Just the usual times"
    const val TO_WORK_SHEET = "Commute to work"
    const val HOME_SHEET = "Commute home"
    const val GUESS_LINE = "Navilife's guess, in use now · pick it to keep it"
    const val DAY_NOTE = "Only that day changes; the next day goes back to the usual."

    /** The usual commute's row for one direction: "To work" · "1 h 20", "About 1 h 20" while it is the guess. */
    fun row(s: CommuteSetting, toWork: Boolean): NotifyChoiceRow {
        val title = if (toWork) TO_WORK else HOME
        val min = if (toWork) s.toWorkMin else s.homeMin
        val value = if (s.chosen) CommuteRules.label(min) else "About ${CommuteRules.label(min)}"
        val guess = if (s.chosen) "" else ", ${CommuteRules.GUESS_SPOKEN.substringBefore(',')}"
        return NotifyChoiceRow(title, value, "Commute ${title.lowercase()}, $value$guess. Opens the choices")
    }

    /** The usual commute's sheet for one direction: every time on offer, the one in use lit (the guess too). */
    fun sheet(s: CommuteSetting, toWork: Boolean): NotifyChoiceSheet {
        val current = if (toWork) s.toWorkMin else s.homeMin
        val choices = CommuteRules.choices(current).map { m ->
            val lit = CommuteRules.lit(s, toWork, m)
            val line = if (lit && !s.chosen) GUESS_LINE else does(toWork, m, null)
            choice(m, lit, line)
        }
        return NotifyChoiceSheet(if (toWork) TO_WORK_SHEET else HOME_SHEET, CommuteRules.NOTE, choices)
    }

    /** What a choice writes, as (to work, home), or null when [name] isn't a time on offer or changes nothing. */
    fun named(s: CommuteSetting, toWork: Boolean, name: String): Pair<Int, Int>? {
        val m = name.toIntOrNull() ?: return null
        if (m !in CommuteRules.choices(if (toWork) s.toWorkMin else s.homeMin)) return null
        return CommuteRules.tap(s, toWork, m)
    }

    /** Just one day's row: "Just one day" · the picked day ("Thu 26 Nov •" when it has its own times) or "Choose a day". */
    fun dayRow(days: List<CommuteDayRow>, picked: Long?): NotifyChoiceRow {
        val d = days.firstOrNull { it.epochDay == picked }
        val value = d?.let { if (it.changed) "${it.label} •" else it.label } ?: CHOOSE_DAY
        return NotifyChoiceRow(CommuteRules.ONE_DAY_TITLE, value, "${CommuteRules.ONE_DAY_TITLE}, $value. Opens the choices")
    }

    /** Just one day's sheet: No day, then each coming office day with the times it uses (a day of its own marked •). */
    fun daySheet(days: List<CommuteDayRow>, picked: Long?): NotifyChoiceSheet {
        val known = picked?.takeIf { p -> days.any { it.epochDay == p } }
        val none = NotifyChoice(NO_DAY, NO_DAY_LABEL, NO_DAY_LINE, known == null,
            "$NO_DAY_LABEL, $NO_DAY_LINE" + if (known == null) ", chosen" else "")
        return NotifyChoiceSheet(CommuteRules.ONE_DAY_TITLE, CommuteRules.ONE_DAY_NOTE, listOf(none) + days.map { d ->
            val label = if (d.changed) "${d.label} •" else d.label
            val line = listOfNotNull(CommuteRules.timesLine(d.toWorkMin, d.homeMin), CommuteRules.JUST_THIS_DAY.takeIf { d.changed }).joinToString(" · ")
            val lit = d.epochDay == known
            NotifyChoice(d.epochDay.toString(), label, line, lit, "${d.label}, $line" + if (lit) ", chosen" else "")
        })
    }

    /** The day a day-sheet choice picks: null for No day (or a day no longer on offer). */
    fun pickedDay(days: List<CommuteDayRow>, name: String): Long? =
        if (name == NO_DAY) null else name.toLongOrNull()?.takeIf { e -> days.any { it.epochDay == e } }

    /** The picked day's row for one direction: "To work" · "1 h 45" (that day's time, its own or the usual). */
    fun oneDayRow(r: CommuteDayRow, toWork: Boolean): NotifyChoiceRow {
        val title = if (toWork) TO_WORK else HOME
        val value = CommuteRules.label(if (toWork) r.toWorkMin else r.homeMin)
        return NotifyChoiceRow(title, value, "${r.label}, commute ${title.lowercase()}, $value. Opens the choices")
    }

    /** The picked day's sheet for one direction: lit only when the day has its own times (as its chips were). */
    fun oneDaySheet(r: CommuteDayRow, toWork: Boolean): NotifyChoiceSheet {
        val current = if (toWork) r.toWorkMin else r.homeMin
        val choices = CommuteRules.choices(current).map { m -> choice(m, r.changed && m == current, does(toWork, m, r.label)) }
        return NotifyChoiceSheet("${if (toWork) TO_WORK_SHEET else HOME_SHEET} · ${r.label}", DAY_NOTE, choices)
    }

    /** What a picked day's choice writes, as (to work, home) for that day, or null when it isn't on offer or is in force. */
    fun oneDayNamed(r: CommuteDayRow, toWork: Boolean, name: String): Pair<Int, Int>? {
        val m = name.toIntOrNull() ?: return null
        val current = if (toWork) r.toWorkMin else r.homeMin
        if (m !in CommuteRules.choices(current)) return null
        if (r.changed && m == current) return null
        return if (toWork) m to r.homeMin else r.toWorkMin to m
    }

    /** "Leave home 1 h 20 before work starts" · "Home 1 h 20 after work ends" (on [day] when given). */
    fun does(toWork: Boolean, min: Int, day: String?): String {
        val on = day?.let { " on $it" } ?: ""
        return if (toWork) "Leave home ${CommuteRules.label(min)} before work starts$on" else "Home ${CommuteRules.label(min)} after work ends$on"
    }

    private fun choice(m: Int, lit: Boolean, line: String): NotifyChoice {
        val label = CommuteRules.label(m)
        return NotifyChoice(m.toString(), label, line, lit, "$label, $line" + if (lit) ", chosen" else "")
    }
}
