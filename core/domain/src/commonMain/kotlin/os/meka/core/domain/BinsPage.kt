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
    const val YOURS_EMPTY = "No bins yet · + Add → A bin, e.g. Food caddy every Tuesday"
    const val ONE_OFFS_EMPTY = "None to come · + Add → A one-off for a Christmas week or a missed collection"
    const val REMINDER = "Reminder"
    const val COUNCIL = "Council's dates"
    const val OWN_DAYS = "Your own days · + Add → The council's dates to follow your council's calendar"

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

    /** Brass: the collection is today or tomorrow (out tonight). */
    private fun soon(day: Long?, today: Long): Boolean = day != null && day - today in 0..1

    fun page(view: BinsView, today: Long): LifePageView {
        val dated = view.rows.filter { it.nextDay != null }
        val firstDay = dated.minOfOrNull { it.nextDay!! }
        val nextTile = if (firstDay != null) {
            val going = dated.filter { it.nextDay == firstDay }.mapIndexed { i, r -> if (i == 0) r.name else r.name.replaceFirstChar { it.lowercase() } }
            val names = if (going.size <= 1) going.joinToString() else going.dropLast(1).joinToString(", ") + " and " + going.last()
            PageTile(dayWord(firstDay, today), names, soon(firstDay, today))
        } else if (view.rows.isEmpty()) PageTile("No bins", "added yet", false)
        else PageTile("No dates", "in the next six weeks", false)
        val n = view.rows.size
        val countTile = if (n > 0) PageTile("$n", if (n == 1) "bin on your list" else "bins on your list", false)
        else PageTile("0", "bins on your list", false)
        val m = view.oneOffs.size
        val oneOffTile = if (m > 0) PageTile("$m", if (m == 1) "one-off to come" else "one-offs to come", false)
        else PageTile("None", "one-offs to come", false)

        val yours = view.rows.sortedWith(compareBy<BinRow> { it.nextDay ?: Long.MAX_VALUE }.thenBy { it.name.lowercase() }).map { r ->
            val line = r.line + (r.shiftNote?.let { " · $it" } ?: "")
            PageRow(binKey(r.id), r.name, line, r.nextDay?.let { dayWord(it, today) } ?: "", soon(r.nextDay, today))
        }
        val oneOffs = view.oneOffs.map { r -> PageRow(moveKey(r.id), r.name, r.line, "", false) }
        val settings = listOf(
            PageRow(KEY_REMIND, REMINDER, view.remindLine, LocalClock.formatMinute(view.remindMinute), false),
            PageRow(KEY_COUNCIL, COUNCIL, view.councilLine ?: OWN_DAYS, if (view.councilLine != null) "In use" else "", false),
        )
        return LifePageView(
            TITLE, INFO,
            listOf(nextTile, countTile, oneOffTile),
            listOf(
                LifePageRules.card(YOURS, YOURS, yours, empty = YOURS_EMPTY),
                LifePageRules.card(ONE_OFFS, ONE_OFFS, oneOffs, empty = ONE_OFFS_EMPTY),
                LifePageRules.card(SETTINGS, SETTINGS, settings),
            ),
            ADD_TITLE, ADD_CHOICES,
        )
    }

    /** The bin behind a row's key, or null when it's gone (removed while the sheet was opening). */
    fun bin(view: BinsView, key: String): BinRow? = binId(key)?.let { id -> view.rows.firstOrNull { it.id == id } }

    /** The one-off behind a row's key, or null when it's gone. */
    fun oneOff(view: BinsView, key: String): BinRow? = moveId(key)?.let { id -> view.oneOffs.firstOrNull { it.id == id } }
}
