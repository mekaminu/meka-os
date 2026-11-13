package os.meka.core.domain

/**
 * Ask → Date night in the page shape (every page under Ask in one shape, slice 11; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ (what the page does, the nudge a week before, that
 * Navilife books nothing and tells no one), one gold + Add (The evening: the weekday, the time and which week it
 * starts, in the sheet, never chips on the page), three numbers (how soon the next one is, whether it's booked, the
 * time and its weekday), then **Coming up** (the next four nights: the day, its line, Booked / To book / Skipped or how
 * far off on the right; always there, saying how to start while it's off) and, while on, **The evening** (one row that
 * opens the same chooser with Turn off). A night's row opens its sheet with Booked (or Not booked yet), Skip this one
 * or Keep it. Built from the [DateNightView] both apps already have; nothing new is read or sent. Non-AI, pure.
 */
object DateNightPageRules {
    const val TITLE = DateNightRules.TITLE
    const val INFO = "An evening every two weeks, kept clear: the planner and Gym bookings leave it free. A week before, Needs you asks once whether you've booked somewhere and arranged cover. Navilife books nothing and tells no one."
    const val ADD_TITLE = "Add to Date night"
    const val COMING_UP = "Coming up"
    const val EVENING = "The evening"
    const val COMING_EMPTY = "Off · + Add → The evening to keep one clear every two weeks"
    const val GONE = "That night isn't coming up now"
    /** On a night's right. */
    const val BOOKED = "Booked"
    const val TO_BOOK = "To book"
    const val SKIPPED = "Skipped"
    /** A skipped night's line. */
    const val FREE = "Free to plan"
    /** The sheet's button that takes Booked back. */
    const val NOT_BOOKED = "Not booked yet"
    /** The evening row's right. */
    const val CHANGE = "Change"
    /** The chooser's three rows. */
    const val WEEKDAY = "Weekday"
    const val START = "Starts at"
    const val FIRST = "First night"

    const val ADD_EVENING = "evening"
    const val EVENING_KEY = "evening"

    fun nightKey(day: Long): String = "night:$day"

    /** + Add's one choice: the evening, picked or changed. */
    fun addChoices(view: DateNightView): List<AddChoice> = listOf(
        AddChoice(
            ADD_EVENING, EVENING,
            if (view.on) "${weekdayLine(view)} from ${LocalClock.formatMinute(view.startMin)} · change it"
            else "Pick the weekday and time, kept clear every two weeks",
        ),
    )

    /** "Every other Friday". */
    fun weekdayLine(view: DateNightView): String = "Every other ${CivilDate.DAY_LONG[(view.weekday.coerceIn(1, 7)) - 1]}"

    private fun kept(r: DateNightRow): Boolean = !r.skipped

    private fun nightRow(r: DateNightRow, view: DateNightView, today: Long): PageRow {
        val hhmm = LocalClock.formatMinute(view.startMin)
        val due = view.nudge?.day == r.day
        val (line, right) = when {
            r.skipped -> FREE to SKIPPED
            r.booked -> "From $hhmm · kept clear" to BOOKED
            due -> "From $hhmm · kept clear" to TO_BOOK
            r.day == today -> "From $hhmm · kept clear" to ""
            else -> "From $hhmm · kept clear" to LifePageRules.until(r.day, today)
        }
        return PageRow(nightKey(r.day), r.label, line, right, lit = !r.skipped && (due || r.day == today))
    }

    /** The coming nights as rows. */
    fun nightRows(view: DateNightView, today: Long): List<PageRow> = view.nights.map { nightRow(it, view, today) }

    private fun nextTile(view: DateNightView, today: Long): PageTile {
        if (!view.on) return PageTile("Off", "date night", false)
        val next = view.nights.firstOrNull(::kept) ?: return PageTile(SKIPPED, "the next ${view.nights.size}", false)
        if (next.day == today) return PageTile("Tonight", "date night", true)
        return PageTile(LifePageRules.until(next.day, today), "to date night", next.day - today <= DateNightRules.NUDGE_DAYS)
    }

    private fun bookedTile(view: DateNightView): PageTile {
        if (!view.on) return PageTile("None", "to book", false)
        val next = view.nights.firstOrNull(::kept) ?: return PageTile("None", "to book", false)
        return when {
            next.booked -> PageTile(BOOKED, "the next one", false)
            view.nudge != null -> PageTile(TO_BOOK, "somewhere and cover", true)
            else -> PageTile("Not yet", "to book · a week before", false)
        }
    }

    private fun timeTile(view: DateNightView): PageTile =
        if (!view.on) PageTile("Not set", "the evening", false)
        else PageTile(LocalClock.formatMinute(view.startMin), weekdayLine(view).replaceFirstChar { it.lowercase() }, false)

    private fun eveningRow(view: DateNightView): PageRow =
        PageRow(EVENING_KEY, weekdayLine(view), "From ${LocalClock.formatMinute(view.startMin)} · kept clear", CHANGE, false)

    fun page(view: DateNightView, today: Long): LifePageView {
        val cards = buildList {
            add(LifePageRules.card(COMING_UP, COMING_UP, nightRows(view, today), empty = COMING_EMPTY))
            if (view.on) add(LifePageRules.card(EVENING, EVENING, listOf(eveningRow(view))))
        }
        return LifePageView(TITLE, INFO, listOf(nextTile(view, today), bookedTile(view), timeTile(view)), cards, ADD_TITLE, addChoices(view))
    }

    /** The night behind a row's key, as it is now; null once it has gone (or for the evening's row). */
    fun night(view: DateNightView, key: String): DateNightRow? =
        key.takeIf { it.startsWith("night:") }?.removePrefix("night:")?.toLongOrNull()?.let { d -> view.nights.firstOrNull { it.day == d } }

    /** Whether the night's sheet offers Booked / Not booked yet (a kept night only: a skipped one is free to plan). */
    fun canBook(r: DateNightRow): Boolean = !r.skipped

    /** The sheet's book button: "Booked" to mark it, [NOT_BOOKED] to take that back. */
    fun bookLabel(r: DateNightRow): String = if (r.booked) NOT_BOOKED else BOOKED

    /** The sheet's skip button. */
    fun skipLabel(r: DateNightRow): String = if (r.skipped) DateNightRules.KEEP_LABEL else DateNightRules.SKIP_LABEL
}
