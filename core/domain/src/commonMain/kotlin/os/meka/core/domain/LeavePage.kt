package os.meka.core.domain

/**
 * Ask → Annual leave in the page shape (every page under Ask in one shape, slice 9; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ (what the page does, that only work days count, that
 * booked leave is a day off on both apps and nothing is sent), one gold + Add (A stretch of leave, a field in the sheet:
 * "Spain 18 Dec – 4 Jan"), three numbers (days left, or how many over; the next leave; school holidays still to book),
 * then **Booked** (soonest first, how far off on the right, brass while on leave or within a week), **School holidays**
 * (while any are coming: booked, part booked or to book on the right, brass for the nudge), **Taken this leave year**
 * (while any) and **Allowance** (one row). A stretch's row opens its sheet with Remove; a holiday's its note and the gold
 * Book n days; Allowance's the two steppers. Built from the [LeaveView] both apps already have; nothing new is read or
 * sent. Non-AI, pure.
 */
object LeavePageRules {
    const val TITLE = LeaveRules.TITLE
    const val INFO = "Your leave allowance and each stretch you book against it; only work days count, and a day booked twice counts once. Booked leave is a day off on both apps: no work band, no commute, and the brief says “Annual leave · no work”. Nothing is sent anywhere."
    const val ADD_TITLE = "Add to Annual leave"
    const val BOOKED = "Booked"
    const val HOLIDAYS = "School holidays"
    const val TAKEN = "Taken this leave year"
    const val ALLOWANCE = "Allowance"
    const val BOOKED_EMPTY = "Nothing booked yet · + Add a stretch of leave"
    const val GONE = "That isn't on your leave now"
    const val DAYS_A_YEAR = "Days a year"
    const val YEAR_STARTS = "Leave year starts"

    const val ADD_LEAVE = "leave"
    val ADD_CHOICES = listOf(
        AddChoice(ADD_LEAVE, "A stretch of leave", "A day or a stretch, e.g. 27–30 Oct or ${LeaveRules.ADD_HINT}"),
    )

    /** The field's hint (the old page's, so TalkBack names it the same). */
    fun hint(@Suppress("UNUSED_PARAMETER") choice: String): String = LeaveRules.ADD_HINT

    const val KEY_ALLOWANCE = "allowance"
    fun leaveKey(id: String): String = "leave:$id"
    fun holidayKey(id: String): String = "holiday:$id"

    /** A stretch this near (days) is lit on its row. */
    const val SOON_DAYS = 7

    /** On a holiday's row, right: "Booked", "Part booked", "Book soon", "Covered", "From home", or "". */
    fun holidayWhen(h: LeaveHoliday): String = when {
        h.bookLabel == null -> "Booked"
        h.note.endsWith(" booked") -> "Part booked"
        h.nudge -> "Book soon"
        h.note == "Covered" -> "Covered"
        h.note == "You're working from home" -> "From home"
        else -> ""
    }

    private fun bookedRow(r: LeaveRow, today: Long): PageRow {
        val now = today in r.startDay..r.endDay
        val whenText = if (now) "Now" else LifePageRules.until(r.startDay, today)
        // The line stays one line on the cover screen; "Next leave year" is in the row's sheet.
        return PageRow(leaveKey(r.id), r.title, r.lineShown, whenText, now || r.startDay - today <= SOON_DAYS)
    }

    private fun takenRow(r: LeaveRow): PageRow = PageRow(leaveKey(r.id), r.title, r.lineShown, "", false)

    private fun holidayRow(h: LeaveHoliday): PageRow = PageRow(holidayKey(h.id), h.title, h.lineShown, holidayWhen(h), h.nudge)

    private fun allowanceRow(v: LeaveView): PageRow = PageRow(
        KEY_ALLOWANCE, "${LeaveRules.daysWord(v.allowance)} a year",
        "The leave year starts ${yearStartLabel(v.yearStartMonth)}", "", false,
    )

    /** Every row of the card [cardKey] (See all), not only the first five. */
    fun allRows(view: LeaveView, cardKey: String, today: Long): List<PageRow> = when (cardKey) {
        BOOKED -> view.booked.map { bookedRow(it, today) }
        HOLIDAYS -> view.holidays.map(::holidayRow)
        TAKEN -> view.taken.map(::takenRow)
        ALLOWANCE -> listOf(allowanceRow(view))
        else -> emptyList()
    }

    private fun daysLeftTile(v: LeaveView): PageTile =
        if (v.daysLeft >= 0) PageTile("${v.daysLeft}", if (v.daysLeft == 1) "day left" else "days left", false)
        else PageTile("${-v.daysLeft} over", "your ${LeaveRules.daysWord(v.allowance)}", true)

    private fun nextTile(v: LeaveView, today: Long): PageTile {
        val next = v.booked.firstOrNull() ?: return PageTile("None", "leave to come", false)
        if (today in next.startDay..next.endDay) return PageTile("Now", "on leave", true)
        val name = if (next.title == LeaveRules.TITLE) "your next leave" else next.title
        return PageTile(LifePageRules.until(next.startDay, today), "to $name", next.startDay - today <= SOON_DAYS)
    }

    private fun holidaysTile(v: LeaveView): PageTile {
        val open = v.holidays.count { it.bookLabel != null }
        return PageTile(if (open > 0) "$open" else "None", if (open == 1) "holiday to book" else "holidays to book", v.holidays.any { it.nudge })
    }

    fun page(view: LeaveView, today: Long): LifePageView {
        val tiles = listOf(daysLeftTile(view), nextTile(view, today), holidaysTile(view))
        val cards = buildList {
            add(LifePageRules.card(BOOKED, BOOKED, allRows(view, BOOKED, today), empty = BOOKED_EMPTY))
            if (view.holidays.isNotEmpty()) add(LifePageRules.card(HOLIDAYS, HOLIDAYS, allRows(view, HOLIDAYS, today)))
            if (view.taken.isNotEmpty()) add(LifePageRules.card(TAKEN, TAKEN, allRows(view, TAKEN, today)))
            add(LifePageRules.card(ALLOWANCE, ALLOWANCE, allRows(view, ALLOWANCE, today)))
        }
        return LifePageView(TITLE, INFO, tiles, cards, ADD_TITLE, ADD_CHOICES)
    }

    /** The stretch behind a row's key (to come or taken), as it is now; null once removed. */
    fun leave(view: LeaveView, key: String): LeaveRow? =
        key.takeIf { it.startsWith("leave:") }?.removePrefix("leave:")?.let { id -> (view.booked + view.taken).firstOrNull { it.id == id } }

    /** The school holiday behind a row's key, as it is now; null once it's gone by. */
    fun holiday(view: LeaveView, key: String): LeaveHoliday? =
        key.takeIf { it.startsWith("holiday:") }?.removePrefix("holiday:")?.let { id -> view.holidays.firstOrNull { it.id == id } }

    /** The month name for the leave year's start ("1 Apr"), for the Allowance sheet's stepper. */
    fun yearStartLabel(month: Int): String = "1 ${Recurrence.MONTH_SHORT[month.coerceIn(1, 12) - 1]}"

    /** The month before and after [month] (the stepper's − and +). */
    fun monthBefore(month: Int): Int = (month + 10) % 12 + 1
    fun monthAfter(month: Int): Int = month % 12 + 1
}
