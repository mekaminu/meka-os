package os.meka.core.domain

/**
 * Ask's Daily rows with live lines (Ask polish, item 3; Meka 2026-10-12 20:53): instead of each row's fixed words, what
 * it holds right now — the brief "Tomorrow 06:40 · 5 things" (lit "Ready · tap to hear" while this morning's is
 * unread), the week ahead "Sunday 18:00" (lit "Ready · tap to hear" on Sunday evening), News a Barça fixture or how many
 * stories, Shut down "2 left", the Train queue "2 saved", the Life admin hour "Thu 19:00 · 3 jobs", the Bedside clock
 * "Screen saver on/off" (the Fold only). A row with nothing live keeps its fixed words. Both apps; pure, unit-tested.
 */
enum class DailyRow { BRIEF, WEEK_AHEAD, NEWS, SHUTDOWN, TRAIN_QUEUE, VOICE_NOTES, LIFE_ADMIN, BEDSIDE }

/** A Daily row's live line; [lit] when it's ready now or wants a look (drawn in the accent). */
data class DailyLine(val text: String, val lit: Boolean)

data class DailyLines(val lines: Map<DailyRow, DailyLine>) {
    fun of(row: DailyRow): DailyLine? = lines[row]

    companion object { val EMPTY = DailyLines(emptyMap()) }
}

/** What the lines are made from, as the facade has it (plain values, so the rules stay pure). */
data class DailyInputs(
    val nowMinute: Int,
    /** This morning's brief is waiting to be read. */
    val briefOffered: Boolean = false,
    val briefStartMinute: Int = BriefRules.DEFAULT_START_MIN,
    /** Today's events + tasks (the brief's own count) and tomorrow's (Shut down's preview). */
    val todayThings: Int = 0,
    val tomorrowThings: Int = 0,
    /** Sunday evening's line for the coming week is up (the week ahead is ready to hear). */
    val weekAheadReady: Boolean = false,
    val weekAheadOn: Boolean = true,
    /** "18:00" */
    val weekAheadTime: String = "18:00",
    /** The next Barça fixture's line ("Barça v Girona tomorrow"), today's lit. */
    val fixture: String? = null,
    val fixtureToday: Boolean = false,
    val stories: Int = 0,
    val shutdownDoneLine: String? = null,
    val shutdownOffered: Boolean = false,
    val shutdownLeft: Int = 0,
    val queueWaiting: Int = 0,
    val lifeAdminOn: Boolean = false,
    /** The next hour's day (epoch day) and start minute; null with none ahead. */
    val lifeAdminDay: Long? = null,
    val lifeAdminMinute: Int? = null,
    val lifeAdminJobs: Int = 0,
    val todayDay: Long = 0,
)

object DailyRules {
    const val READY = "Ready · tap to hear"
    const val SCREEN_SAVER_ON = "Screen saver on"
    const val SCREEN_SAVER_OFF = "Screen saver off · set it up"

    /** How far ahead News looks for a fixture. */
    const val FIXTURE_DAYS = 7

    fun lines(i: DailyInputs): DailyLines {
        val m = linkedMapOf<DailyRow, DailyLine>()
        brief(i)?.let { m[DailyRow.BRIEF] = it }
        weekAhead(i)?.let { m[DailyRow.WEEK_AHEAD] = it }
        news(i)?.let { m[DailyRow.NEWS] = it }
        shutdown(i)?.let { m[DailyRow.SHUTDOWN] = it }
        if (i.queueWaiting > 0) m[DailyRow.TRAIN_QUEUE] = DailyLine("${i.queueWaiting} saved", false)
        lifeAdmin(i)?.let { m[DailyRow.LIFE_ADMIN] = it }
        return DailyLines(m)
    }

    /** Before today's brief starts: today's; after it: tomorrow's at the same time (it follows quiet hours). */
    fun brief(i: DailyInputs): DailyLine {
        if (i.briefOffered) return DailyLine(READY, true)
        val time = LocalClock.formatMinute(i.briefStartMinute)
        val (day, n) = if (i.nowMinute < i.briefStartMinute) "Today" to i.todayThings else "Tomorrow" to i.tomorrowThings
        return DailyLine("$day $time" + if (n > 0) " · ${things(n)}" else "", false)
    }

    fun weekAhead(i: DailyInputs): DailyLine? = when {
        !i.weekAheadOn -> null
        i.weekAheadReady -> DailyLine(READY, true)
        else -> DailyLine("Sunday ${i.weekAheadTime}", false)
    }

    fun news(i: DailyInputs): DailyLine? = when {
        i.fixture != null -> DailyLine(i.fixture, i.fixtureToday)
        i.stories > 0 -> DailyLine(ShutdownRules.count(i.stories, "story", "stories"), false)
        else -> null
    }

    fun shutdown(i: DailyInputs): DailyLine? = when {
        i.shutdownDoneLine != null -> DailyLine(i.shutdownDoneLine, false)
        i.shutdownLeft > 0 -> DailyLine("${i.shutdownLeft} left", i.shutdownOffered)
        i.shutdownOffered -> DailyLine("Nothing left · close the day", true)
        else -> null
    }

    fun lifeAdmin(i: DailyInputs): DailyLine? {
        val day = i.lifeAdminDay ?: return null
        val minute = i.lifeAdminMinute ?: return null
        if (!i.lifeAdminOn) return null
        val label = when (day - i.todayDay) {
            0L -> "Today"
            1L -> "Tomorrow"
            else -> LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(day) - 1]
        }
        val jobs = if (i.lifeAdminJobs > 0) " · ${ShutdownRules.count(i.lifeAdminJobs, "job")}" else ""
        return DailyLine("$label ${LocalClock.formatMinute(minute)}$jobs", day == i.todayDay)
    }

    /**
     * The Fold's Voice notes row (voice notes into actions, slice 1): how many this phone keeps, the newest one's day
     * (null: the fixed words). Lit when one was made today.
     */
    fun voiceNotes(count: Int, newestDay: Long?, today: Long): DailyLine? {
        if (count <= 0 || newestDay == null) return null
        val n = ShutdownRules.count(count, "note")
        return when (newestDay) {
            today -> DailyLine("$n · newest today", true)
            today - 1 -> DailyLine("$n · newest yesterday", false)
            else -> DailyLine(n, false)
        }
    }

    /** The Fold's Bedside clock row: whether MEKA is Android's screen saver (null: couldn't tell → the fixed words). */
    fun bedside(isScreenSaver: Boolean?): DailyLine? = when (isScreenSaver) {
        true -> DailyLine(SCREEN_SAVER_ON, false)
        false -> DailyLine(SCREEN_SAVER_OFF, false)
        null -> null
    }

    /**
     * News' fixture line: the first Barça fixture (not all-day) from now to [FIXTURE_DAYS] ahead that hasn't finished:
     * today's as the News place says it ("Barça v Girona · 21:00 · in 3 h"), then "Barça v Girona tomorrow", then
     * "Barça v Girona Sat". Pass the events shown on my day. Second: whether it's today's.
     */
    fun fixture(events: List<CalendarEvent>, nowMs: Long, cal: LocalCalendar): Pair<String, Boolean>? {
        NewsRules.matchday(events, nowMs, cal)?.let { return it.line to true }
        val today = cal.epochDayOf(nowMs)
        val e = events.filter {
            it.isFixture && !it.allDay && it.endAtMs > nowMs && cal.epochDayOf(it.startAtMs) in (today + 1)..(today + FIXTURE_DAYS)
        }.minWithOrNull(compareBy<CalendarEvent> { it.startAtMs }.thenBy { it.id }) ?: return null
        val title = e.title.trimEnd().removeSuffix("(kick-off TBC)").trim().ifEmpty { "Barça" }
        val day = cal.epochDayOf(e.startAtMs)
        val label = if (day == today + 1) "tomorrow" else LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(day) - 1]
        return "$title $label" to false
    }

    /** "5 things" · "1 thing" */
    private fun things(n: Int) = ShutdownRules.count(n, "thing")
}
