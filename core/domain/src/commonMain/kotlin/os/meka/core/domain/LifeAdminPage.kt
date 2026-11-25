package os.meka.core.domain

/**
 * Ask → Life admin hour in the page shape (week 5's walk-through A16: "the one Life page not yet in the shared shape";
 * the shape approved 2026-11-11, `docs/design/look/LifePage.dc.html`): the title with its ⓘ (what the hour does), one
 * gold + Add (The hour: the weekday and the start time, as chips in its sheet, never open chip rows on the page), three
 * numbers (how soon the next hour is, the jobs waiting, how many are overdue), then **Gathered into it** (the jobs, at
 * most five with See all) and **The hour** (this week's hour, which opens Skip this week / Keep it; the next one when
 * this week's is skipped or over; and the weekly setting, which opens the same chooser with Turn off). Off, the page is
 * one raised card with [ADD_FIRST]. Built from the [LifeAdminView] both apps already have; nothing new is read or sent.
 * Non-AI, pure.
 */
object LifeAdminPageRules {
    const val TITLE = LifeAdminRules.TITLE
    const val INFO = "One hour a week, booked around your calendar, for the small jobs: calls, forms, renewals and replies. " +
        "Overdue ones wait for the hour instead of coming up in every digest. The planner and Gym leave it clear; if a " +
        "meeting lands on it, it moves. Skip a week and that hour is free again."
    const val ADD_TITLE = "Add to Life admin hour"
    const val JOBS = "Gathered into it"
    const val HOUR = "The hour"
    const val JOBS_EMPTY = "Nothing waiting yet. Tasks like “Call the GP” or “Renew passport” gather here by themselves."
    /** See all's way back once every job shows. */
    const val FEWER = "Fewer"
    const val CHANGE = "Change"
    const val WEEKDAY = "Weekday"
    const val START = "Starts at"
    /** The week row's lines. */
    const val BOOKED_LINE = "Booked around your calendar"
    const val SKIPPED_LINE = "Skipped · the hour is free again"
    const val NEXT_LINE = "The next hour"
    const val EVERY_LINE = "One hour a week"
    /** A gathered task's sheet button, and a renewal's line in its sheet. */
    const val DONE = "Done"
    const val RENEWAL_IN_LISTS = "Renewals are kept in Lists → Renewals; mark it renewed there."
    const val GONE = "That job isn't waiting now"

    /**
     * Off, the page says it once (as Date night's, Car's and the other Life pages' blank states): this line in one raised
     * card with [ADD_FIRST] under it, and no summary, chip rows, tiles, cards or note (the old page drew the off line, seven
     * day chips, six time chips and the note, every shot).
     */
    const val EMPTY_LINE = "Life admin hour is off. Pick a day once and Navilife books one hour a week for calls, forms and renewals, around your calendar."
    const val ADD_FIRST = "Pick the day"

    const val ADD_HOUR = "hour"
    const val WEEK_KEY = "week"
    const val NEXT_KEY = "next"
    const val EVERY_KEY = "every"
    const val JOB_PREFIX = "job:"

    fun blank(view: LifeAdminView): Boolean = !view.on

    /** "every Saturday". */
    fun everyDay(view: LifeAdminView): String = "every ${CivilDate.DAY_LONG[view.weekday.coerceIn(1, 7) - 1]}"

    fun addChoices(view: LifeAdminView): List<AddChoice> = listOf(
        AddChoice(
            ADD_HOUR, HOUR,
            if (view.on) "${LifeAdminRules.everyLine(LifeAdminSetting(view.weekday, view.startMin))} · change it"
            else "Pick the weekday and time, one hour a week",
        ),
    )

    private fun cap(s: String): String = s.replaceFirstChar { it.uppercase() }

    private fun nextTile(view: LifeAdminView, today: Long, cal: LocalCalendar): PageTile {
        val next = view.next ?: return if (view.skipped) PageTile("Skipped", "this week", false) else PageTile("No room", "this week", true)
        if (next.day == today) return PageTile("Today", "at ${LocalClock.formatMinute(cal.minuteOfDay(next.startMs))}", true)
        return PageTile(cap(LifePageRules.until(next.day, today)), "to the hour", next.day - today <= 1)
    }

    private fun jobsTile(view: LifeAdminView): PageTile = when (view.jobCount) {
        0 -> LifePageRules.none("waiting")
        1 -> PageTile("1", "job waiting", false)
        else -> PageTile("${view.jobCount}", "jobs waiting", false)
    }

    private fun overdueTile(view: LifeAdminView): PageTile =
        if (view.overdueCount == 0) LifePageRules.none("overdue") else PageTile("${view.overdueCount}", "overdue", true)

    /** A job's row; its name keeps its last words together ([LoneWordGlue.kept]), as the old page's rows did. */
    fun jobRow(j: LifeAdminJob): PageRow = PageRow(JOB_PREFIX + j.id, LoneWordGlue.kept(j.title), j.lineShown ?: "", "", j.overdue)

    private fun hourRows(view: LifeAdminView, today: Long): List<PageRow> = buildList {
        view.thisWeek?.let { w ->
            add(PageRow(WEEK_KEY, w.whenLine, if (view.skipped) SKIPPED_LINE else BOOKED_LINE, "", false))
        }
        view.next?.takeIf { it != view.thisWeek }?.let { n ->
            add(PageRow(NEXT_KEY, n.whenLine, NEXT_LINE, LifePageRules.until(n.day, today), false))
        }
        add(PageRow(EVERY_KEY, LifeAdminRules.everyLine(LifeAdminSetting(view.weekday, view.startMin)), EVERY_LINE, CHANGE, false))
    }

    /** The page; [all] is See all (every gathered job in the card, with [FEWER] to go back). */
    fun page(view: LifeAdminView, today: Long, cal: LocalCalendar, all: Boolean = false): LifePageView {
        if (blank(view)) return LifePageView(TITLE, INFO, emptyList(), emptyList(), ADD_TITLE, addChoices(view))
        val jobs = (view.allJobs.ifEmpty { view.jobs }).map(::jobRow)
        val jobsCard = if (all && jobs.size > LifePageRules.MAX_ROWS) PageCard(JOBS, JOBS, view.jobCount, jobs, FEWER, JOBS_EMPTY)
        else LifePageRules.card(JOBS, JOBS, jobs, empty = JOBS_EMPTY, count = view.jobCount)
        val cards = listOf(jobsCard, LifePageRules.settings(HOUR, HOUR, hourRows(view, today)))
        return LifePageView(TITLE, INFO, listOf(nextTile(view, today, cal), jobsTile(view), overdueTile(view)), cards, ADD_TITLE, addChoices(view))
    }

    /** The job behind a row's key, as it is now; null once it has gone (or for the hour's rows). */
    fun job(view: LifeAdminView, key: String): LifeAdminJob? =
        key.takeIf { it.startsWith(JOB_PREFIX) }?.removePrefix(JOB_PREFIX)?.let { id -> view.allJobs.ifEmpty { view.jobs }.firstOrNull { it.id == id } }

    /** A gathered task's id for Done; null for a renewal (marked renewed in Lists). */
    fun taskId(job: LifeAdminJob): String? = if (job.kind == LifeAdminJobKind.TASK) job.id.removePrefix("task:") else null

    /** The week sheet's button. */
    fun skipLabel(view: LifeAdminView): String = if (view.skipped) LifeAdminRules.KEEP_LABEL else LifeAdminRules.SKIP_LABEL
}
