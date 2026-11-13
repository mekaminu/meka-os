package os.meka.core.domain

/**
 * Ask → Appointments in the page shape (every page under Ask in one shape, slice 10; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ (what the page does, the nudge in Needs you, that Navilife
 * books nothing and calls no one), one gold + Add (Something you keep up with · A visit you've booked, each a field;
 * A common one, the usual ones to add in one tap), three numbers (due to book, the next booked visit, how many he keeps
 * up with), then **To book** (overdue, due or with no visit noted; always there), **Booked** (soonest first, the day and
 * time on the right, brass within a week) and **Next due** (the rest, when each comes due). A row opens its sheet with
 * its status, Went, a Booked for… field, a Last went… field while no visit is noted, and Remove. Built from the
 * [AppointmentView] both apps already have; nothing new is read or sent. Non-AI, pure.
 */
object AppointmentsPageRules {
    const val TITLE = "Appointments"
    const val INFO = "The dentist, the optician, a haircut… each with how often you go, due from your last visit. When one comes due Needs you asks once whether you've booked it. Navilife books nothing and calls no one."
    const val ADD_TITLE = "Add to Appointments"
    const val TO_BOOK = "To book"
    const val BOOKED = "Booked"
    const val NEXT_DUE = "Next due"
    const val TO_BOOK_EMPTY = "Nothing to book · all in hand"
    const val EMPTY = "No appointments yet · + Add the dentist, a haircut, the optician…"
    const val GONE = "That isn't on your appointments now"
    /** The row sheet's fields. */
    const val BOOKED_FOR = "Booked for…"
    const val LAST_WENT = "When did you last go?"
    const val LAST_WENT_HINT = "12 Apr, or March"
    /** On a To book row's right while it has no visit noted. */
    const val ADD_A_DATE = "Add a date"
    const val NOT_YET = "Not yet"
    const val OVERDUE = "Overdue"

    const val ADD_KEPT = "kept"
    const val ADD_BOOKED = "booked"
    const val ADD_COMMON = "common"
    const val BOOKED_HINT = "Dentist booked Tue 20 Oct 9:30"
    private val KEPT_CHOICE = AddChoice(ADD_KEPT, "Something you keep up with", "How often and when you last went: ${AppointmentRules.HINT}")
    private val BOOKED_CHOICE = AddChoice(ADD_BOOKED, "A visit you've booked", "e.g. $BOOKED_HINT")
    private val COMMON_CHOICE = AddChoice(ADD_COMMON, "A common one", "The dentist, a haircut, the optician… with the usual gap")

    /** + Add's choices; A common one only while there's one not on the list. */
    fun addChoices(view: AppointmentView): List<AddChoice> =
        listOfNotNull(KEPT_CHOICE, BOOKED_CHOICE, COMMON_CHOICE.takeIf { view.suggestions.isNotEmpty() })

    /** The field's hint for a choice (A common one has no field). */
    fun hint(choice: String): String? = when (choice) {
        ADD_KEPT -> AppointmentRules.HINT
        ADD_BOOKED -> BOOKED_HINT
        else -> null
    }

    /** What the Last went… field types for [title]: "Dentist last 12 Apr" (the typed line updates the one on the list). */
    fun lastWentLine(title: String, typed: String): String {
        val t = typed.trim()
        return if (t.lowercase().startsWith("last ")) "$title $t" else "$title last $t"
    }

    fun rowKey(id: String): String = "appt:$id"

    /** A booked visit this near (days) is lit. */
    const val SOON_DAYS = 7

    private fun cap(s: String): String = s.replaceFirstChar { it.uppercase() }

    private fun toBook(r: AppointmentRow): Boolean =
        r.state == AppointmentState.OVERDUE || r.state == AppointmentState.DUE || r.state == AppointmentState.UNKNOWN

    /** On a row's right: "Overdue", "Today", "Mon 2 Nov", "Not yet", "Add a date"; the booked day and time; "Apr 2027". */
    fun whenText(r: AppointmentRow, today: Long): String = when (r.state) {
        AppointmentState.UNKNOWN -> ADD_A_DATE
        AppointmentState.OVERDUE, AppointmentState.DUE -> when {
            r.laterDay != null -> NOT_YET
            r.dueDay == null -> ""
            r.dueDay < today -> OVERDUE
            else -> cap(AppointmentRules.dayWord(r.dueDay, today))
        }
        AppointmentState.BOOKED -> r.status.substringAfter(" · ", "").ifEmpty { r.bookedDay?.let { cap(AppointmentRules.dayWord(it, today)) }.orEmpty() }
        AppointmentState.LATER -> r.dueDay?.let { d -> if (d - today > 60) AppointmentRules.monthWord(d) else cap(AppointmentRules.dayWord(d, today)) }.orEmpty()
    }

    private fun pageRow(r: AppointmentRow, today: Long): PageRow {
        val lit = when (r.state) {
            AppointmentState.BOOKED -> (r.bookedDay ?: Long.MAX_VALUE) - today <= SOON_DAYS
            AppointmentState.LATER -> false
            else -> r.lit
        }
        return PageRow(rowKey(r.id), r.title, r.line, whenText(r, today), lit)
    }

    /** Every row of the card [cardKey] (See all), not only the first five. */
    fun allRows(view: AppointmentView, cardKey: String, today: Long): List<PageRow> = when (cardKey) {
        TO_BOOK -> view.rows.filter(::toBook)
        BOOKED -> view.rows.filter { it.state == AppointmentState.BOOKED }
        NEXT_DUE -> view.rows.filter { it.state == AppointmentState.LATER }
        else -> emptyList()
    }.map { pageRow(it, today) }

    private fun dueTile(view: AppointmentView): PageTile {
        val n = view.nudges.size
        return if (n > 0) PageTile("$n", "due to book", true) else PageTile("None", "due to book", false)
    }

    private fun bookedTile(view: AppointmentView, today: Long): PageTile {
        val next = view.rows.filter { it.state == AppointmentState.BOOKED && it.bookedDay != null }.minByOrNull { it.bookedDay!! }
            ?: return PageTile("None", "booked", false)
        val day = next.bookedDay!!
        return PageTile(LifePageRules.until(day, today), "to ${next.title}", day - today <= SOON_DAYS)
    }

    private fun keptTile(view: AppointmentView): PageTile {
        val n = view.rows.size
        return PageTile(if (n > 0) "$n" else "None", "kept up with", false)
    }

    fun page(view: AppointmentView, today: Long): LifePageView {
        val cards = buildList {
            add(LifePageRules.card(TO_BOOK, TO_BOOK, allRows(view, TO_BOOK, today), empty = if (view.rows.isEmpty()) EMPTY else TO_BOOK_EMPTY))
            allRows(view, BOOKED, today).takeIf { it.isNotEmpty() }?.let { add(LifePageRules.card(BOOKED, BOOKED, it)) }
            allRows(view, NEXT_DUE, today).takeIf { it.isNotEmpty() }?.let { add(LifePageRules.card(NEXT_DUE, NEXT_DUE, it)) }
        }
        return LifePageView(TITLE, INFO, listOf(dueTile(view), bookedTile(view, today), keptTile(view)), cards, ADD_TITLE, addChoices(view))
    }

    /** The appointment behind a row's key, as it is now; null once removed. */
    fun row(view: AppointmentView, key: String): AppointmentRow? =
        key.takeIf { it.startsWith("appt:") }?.removePrefix("appt:")?.let { id -> view.rows.firstOrNull { it.id == id } }

    /** Whether the row's sheet offers the Last went… field (no visit noted yet). */
    fun asksLastWent(r: AppointmentRow): Boolean = r.state == AppointmentState.UNKNOWN
}
