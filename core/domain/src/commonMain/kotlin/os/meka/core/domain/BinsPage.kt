package os.meka.core.domain

/**
 * Ask → Life → Bins in the page shape (every page under Ask in one shape, slice 5; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ (what the page does, the evening-before reminder and that
 * nothing is sent), one gold + Add (A bin · A one-off, each a field in the sheet; The council's dates, which opens the
 * file picker), three numbers (the next collection, the bins on his list, the one-offs to come), then **Your bins**
 * (soonest collection first, the day on the right, brass for tonight's), **One-offs** (Christmas weeks and the like)
 * and **Reminder and dates** (when the evening reminder comes, and whether the council's calendar is in use). A row
 * opens its sheet: a bin's pattern, next collection and Remove; a one-off's Remove; the reminder's times; the council's
 * Use the council's dates / Use my own days. Built from the [BinsView] both apps already have; nothing new is read or
 * sent. Non-AI, pure.
 */
object BinsPageRules {
    const val TITLE = "Bins"
    const val INFO = "Each bin typed once with its day. Navilife reminds you the evening before, a day later after a bank holiday, and Today says it from 16:00. Everything stays on your devices; nothing is sent."
    const val ADD_TITLE = "Add to Bins"
    const val YOURS = "Your bins"
    const val ONE_OFFS = "One-offs"
    const val SETTINGS = "Reminder and dates"
    const val YOURS_EMPTY = "No bins yet. Tap + Add and pick “A bin”, e.g. Food caddy every Tuesday."
    const val ONE_OFFS_EMPTY = "None to come. For a Christmas week or a missed collection, tap + Add and pick “A one-off”."
    const val REMINDER = "Reminder"
    const val COUNCIL = "Council's dates"
    /**
     * Found and fixing week 5, A12: the row's line fits its two lines on the closed Fold ("…pick “The council's da" was
     * cut on every cover shot) and names what the row's own sheet offers, Use the council's dates, not another route.
     */
    const val OWN_DAYS = "Your own days. Tap here to use the council's dates."

    const val ADD_BIN = "bin"
    const val ADD_ONE_OFF = "one-off"
    /** No field: the app opens its file picker for the council's bin calendar. */
    const val ADD_COUNCIL = "council"
    val ADD_CHOICES = listOf(
        AddChoice(ADD_BIN, "A bin", "e.g. Food caddy every Tuesday, or Black bin Tue fortnightly from 13 Oct"),
        AddChoice(ADD_ONE_OFF, "A one-off", "e.g. Black bin Sat 2 Jan instead of 29 Dec, or No black bin 29 Dec"),
        AddChoice(ADD_COUNCIL, "The council's dates", "Pick the bin calendar (.ics) you downloaded from your council's bin-day page"),
    )

    /** Whether a choice takes a typed line (the council's dates open the file picker instead). */
    fun takesText(choice: String): Boolean = choice != ADD_COUNCIL

    /** The field's hint for each choice. */
    fun hint(choice: String): String = if (choice == ADD_ONE_OFF) "No black bin 29 Dec" else BinRules.ADD_HINT

    const val KEY_REMIND = "set:remind"
    const val KEY_COUNCIL = "set:council"
    fun binKey(id: String): String = "bin:$id"
    fun moveKey(id: String): String = "move:$id"
    fun binId(key: String): String? = key.takeIf { it.startsWith("bin:") }?.removePrefix("bin:")
    fun moveId(key: String): String? = key.takeIf { it.startsWith("move:") }?.removePrefix("move:")

    /** "Today", "Tomorrow", "Thu", "2 Dec": a collection's day on a tile or a row's right. */
    fun dayWord(day: Long, today: Long): String = PeoplePageRules.dayWord(day, today)

    /**
     * Found and fixing week 4, A22: the right column of Your bins never mixes days and dates ("Fri", "Tue", then
     * "3 Nov"): Today and Tomorrow stay words, every later collection reads "Tue 27 Oct", so this week's Tuesday and
     * next week's can be told apart at a glance.
     */
    fun rowWhen(day: Long, today: Long): String = when (day - today) {
        0L, 1L -> dayWord(day, today)
        else -> LocalClock.DAY_SHORT[CivilDate.isoDayOfWeek(day) - 1] + " " +
            CivilDate.fromEpochDay(day).let { "${it.day} ${Recurrence.MONTH_SHORT[it.month - 1]}" }
    }

    /** Brass: the collection is today or tomorrow (out tonight). */
    private fun soon(day: Long?, today: Long): Boolean = day != null && day - today in 0..1

    fun page(view: BinsView, today: Long): LifePageView {
        val dated = view.rows.filter { it.nextDay != null }
        val firstDay = dated.minOfOrNull { it.nextDay!! }
        val nextTile = if (firstDay != null) {
            val going = dated.filter { it.nextDay == firstDay }.mapIndexed { i, r -> if (i == 0) r.name else r.name.replaceFirstChar { it.lowercase() } }
            val names = if (going.size <= 1) going.joinToString() else going.dropLast(1).joinToString(", ") + " and " + going.last()
            PageTile(dayWord(firstDay, today), names, soon(firstDay, today))
        } else if (view.rows.isEmpty()) PageTile("Nothing", "to put out yet", false)
        else PageTile("No dates", "in the next six weeks", false)
        val n = view.rows.size
        val countTile = if (n > 0) PageTile("$n", if (n == 1) "bin on your list" else "bins on your list", false)
        else PageTile("0", "bins on your list", false)
        val m = view.oneOffs.size
        val oneOffTile = if (m > 0) PageTile("$m", if (m == 1) "one-off to come" else "one-offs to come", false)
        else LifePageRules.none("one-offs to come")

        val yours = view.rows.sortedWith(compareBy<BinRow> { it.nextDay ?: Long.MAX_VALUE }.thenBy { it.name.lowercase() }).map { r ->
            val line = r.line + (r.shiftNote?.let { " · $it" } ?: "")
            PageRow(binKey(r.id), r.name, line, r.nextDay?.let { rowWhen(it, today) } ?: "", soon(r.nextDay, today))
        }
        val oneOffs = view.oneOffs.map { r -> PageRow(moveKey(r.id), r.name, r.line, "", false) }
        val settings = listOf(
            PageRow(KEY_REMIND, REMINDER, remindRowLine(), LocalClock.formatMinute(view.remindMinute), false),
            PageRow(KEY_COUNCIL, COUNCIL, view.councilLine ?: OWN_DAYS, if (view.councilLine != null) "In use" else "", false),
        )
        return LifePageView(
            TITLE, INFO,
            listOf(nextTile, countTile, oneOffTile),
            listOf(
                LifePageRules.card(YOURS, YOURS, yours, empty = YOURS_EMPTY),
                LifePageRules.card(ONE_OFFS, ONE_OFFS, oneOffs, empty = ONE_OFFS_EMPTY),
                LifePageRules.settings(SETTINGS, SETTINGS, settings),
            ),
            ADD_TITLE, ADD_CHOICES,
        )
    }

    /**
     * The Reminder row's grey line (walk-through 2026-11-15, g7 7: the sheet's "Reminder the evening before at 19:00 · Today
     * says it from 16:00" was cut to "… at 19:00 · …" under the row's own name and time): the time is on the right and
     * the name says Reminder, so the line says only when.
     */
    fun remindRowLine(): String = "The evening before · Today says it from ${LocalClock.formatMinute(BinRules.EVE_FROM_MIN)}"

    /** The bin behind a row's key, or null when it's gone (removed while the sheet was opening). */
    fun bin(view: BinsView, key: String): BinRow? = binId(key)?.let { id -> view.rows.firstOrNull { it.id == id } }

    /** The one-off behind a row's key, or null when it's gone. */
    fun oneOff(view: BinsView, key: String): BinRow? = moveId(key)?.let { id -> view.oneOffs.firstOrNull { it.id == id } }
}
