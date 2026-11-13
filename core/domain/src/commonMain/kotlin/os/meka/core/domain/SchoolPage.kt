package os.meka.core.domain

/**
 * Ask → School in the page shape (every page under Ask in one shape, slice 14; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ (what School keeps, where it shows, the week-ahead
 * question, nothing sent to the school), one gold + Add (A day off · A school date · Every week, each one typed line
 * read by [SchoolRules]; The school's INSET days, pasted dates or a letter read by [SchoolTerms]), three numbers (how
 * soon the next day off is, the questions waiting or the dates to come, the weekly things), then **Days off**, **Dates**,
 * **Every week** and **Holidays from the council** (five rows each, See all for the rest). A day off waiting on "who's
 * covering?" says so on its row in brass. A row opens its sheet: its day and who, the cover answer or reminder, where it
 * came from, the cover taps while one is waiting, To a day… for a date, and Remove; a council's row opens the council's
 * Fill in / Remove all with Undo. Built from the [SchoolView] both apps already have; nothing new is read or sent.
 * Non-AI, pure.
 */
object SchoolPageRules {
    const val TITLE = "School"
    const val INFO = "Navilife keeps the children's days off, school dates and weekly things like PE, says them on Today and the evening before, and asks who's covering a week before a day off that falls on an office day. Nothing is sent to the school."
    const val ADD_TITLE = "Add to School"
    const val GONE = "That isn't in School now"

    const val OFF = "off"
    const val DATES = "dates"
    const val WEEKLY = "weekly"
    const val COUNCIL = "council"

    const val ADD_OFF = "off"
    const val ADD_DATE = "date"
    const val ADD_WEEKLY = "weekly"
    const val ADD_INSET = "inset"

    /** + Add's choices; all but [ADD_INSET] go through the same one-line reader, which decides the kind. */
    val ADD_CHOICES = listOf(
        AddChoice(ADD_OFF, "A day off", "INSET 2 Nov, or Half term 26–30 Oct"),
        AddChoice(ADD_DATE, "A school date", "Alex's trip 20 Nov"),
        AddChoice(ADD_WEEKLY, "Every week", "Alex PE Tue"),
        AddChoice(ADD_INSET, "The school's INSET days", "Paste the dates, or the school's letter"),
    )

    /** The field's one-line example for a choice. */
    fun hint(choice: String): String = ADD_CHOICES.firstOrNull { it.key == choice }?.hint ?: ADD_CHOICES.first().hint

    /** What TalkBack and VoiceOver read for the field. */
    fun spokenHint(choice: String): String = when (choice) {
        ADD_INSET -> "The school's INSET days: paste the dates, or the school's letter"
        else -> "${ADD_CHOICES.firstOrNull { it.key == choice }?.label ?: ADD_CHOICES.first().label}, for example ${hint(choice)}"
    }

    /** Whether a choice's line goes to the INSET reader (pasted dates or a letter) rather than the one-line reader. */
    fun isInset(choice: String): Boolean = choice == ADD_INSET

    /** The answer lines that mean nothing was read (shown in the accent, the words kept in the field to fix). */
    fun isNotRead(line: String?): Boolean = line == SchoolRules.NOT_READ || line == SchoolTerms.INSET_NONE

    const val OFF_EMPTY = "None coming up · + Add → A day off"
    const val DATES_EMPTY = "None coming up · + Add → A school date"
    const val WEEKLY_EMPTY = "Nothing weekly · + Add → Every week"
    const val COUNCIL_EMPTY = "Type the holidays with + Add → A day off"
    /** A day off's row while Needs you asks who's covering it. */
    const val COVER_ASK = "Who's covering?"
    /** A council's row on the right while its holidays aren't in, and once they are. */
    const val FILL_IN = "Fill in"
    const val FILLED = "In"
    /** A day off and a date are lit (brass) this many days ahead. */
    const val SOON_DAYS = 7
    /** A card row's grey line fits one line on the cover screen; the whole line is in the row's sheet. */
    const val SHORT_LINE = 34

    fun itemKey(id: String): String = "item:$id"
    fun councilKey(id: String): String = "council:$id"
    fun isCouncil(key: String): Boolean = key.startsWith("council:")

    /** [line] when it fits [SHORT_LINE], else its first part ("Mon 26 – Fri 30 Oct"), else its first words with "…". */
    fun shortLine(line: String): String {
        if (line.length <= SHORT_LINE) return PhraseGlue.kept(line)
        val head = line.substringBefore(" · ")
        if (head.length <= SHORT_LINE) return PhraseGlue.kept(head)
        val cut = head.take(SHORT_LINE - 1).let { it.substringBeforeLast(' ', it) }.trimEnd(',', ';', ' ', '.')
        return PhraseGlue.kept(cut) + "…"
    }

    private fun dayRow(r: SchoolRow, today: Long, asking: Boolean): PageRow {
        val day = r.startDay
        val soon = day != null && day - today in 0..SOON_DAYS.toLong()
        return PageRow(
            itemKey(r.id), r.title, if (asking) COVER_ASK else shortLine(r.line),
            day?.let { LifePageRules.until(it, today) } ?: "", soon || asking,
        )
    }

    private fun weeklyRow(r: SchoolRow): PageRow = PageRow(itemKey(r.id), r.title, shortLine(r.line), "", false)

    private fun councilRows(card: SchoolTermCard?): List<PageRow> {
        card ?: return emptyList()
        card.filled?.let { f -> return listOf(PageRow(councilKey(f.id), f.name, shortLine(f.line), FILLED, card.refresh != null)) }
        return card.choices.map { c -> PageRow(councilKey(c.id), c.name, shortLine(c.line), FILL_IN, true) }
    }

    /** Every row of the card [cardKey] (See all), not only the first five. */
    fun allRows(view: SchoolView, cardKey: String, today: Long): List<PageRow> {
        val asking = view.covers.map { it.id }.toSet()
        return when (cardKey) {
            OFF -> view.off.map { dayRow(it, today, it.id in asking) }
            DATES -> view.dates.map { dayRow(it, today, false) }
            WEEKLY -> view.weekly.map(::weeklyRow)
            COUNCIL -> councilRows(view.terms)
            else -> emptyList()
        }
    }

    /** The card's title for See all's sheet. */
    fun cardTitle(cardKey: String): String = when (cardKey) {
        OFF -> "Days off"
        DATES -> "Dates"
        WEEKLY -> "Every week"
        COUNCIL -> SchoolTerms.CARD_TITLE
        else -> TITLE
    }

    private fun offTile(view: SchoolView, today: Long): PageTile {
        val day = view.nextOffDay ?: return PageTile("None", "days off coming up", false)
        val title = view.nextOffTitle ?: "day off"
        return PageTile(LifePageRules.until(day, today), "to ${lowerFirst(title)}", day - today <= SOON_DAYS)
    }

    /** "Half term" → "half term"; "INSET day" stays as it is. */
    private fun lowerFirst(s: String): String = if (s.length > 1 && s[1].isUpperCase()) s else s.replaceFirstChar { it.lowercase() }

    private fun middleTile(view: SchoolView, today: Long): PageTile {
        val n = view.covers.size
        if (n > 0) return PageTile("$n", if (n == 1) "day off to cover" else "days off to cover", true)
        val d = view.dates.size
        if (d == 0) return PageTile("None", "school dates ahead", false)
        val soon = view.dates.any { r -> r.startDay?.let { it - today in 0..SOON_DAYS.toLong() } == true }
        return PageTile("$d", if (d == 1) "school date ahead" else "school dates ahead", soon)
    }

    private fun weeklyTile(view: SchoolView): PageTile {
        val n = view.weekly.size
        return if (n == 0) PageTile("None", "weekly yet", false) else PageTile("$n", "every week", false)
    }

    fun page(view: SchoolView, today: Long): LifePageView {
        val cards = buildList {
            add(LifePageRules.card(OFF, cardTitle(OFF), allRows(view, OFF, today), empty = OFF_EMPTY))
            add(LifePageRules.card(DATES, cardTitle(DATES), allRows(view, DATES, today), empty = DATES_EMPTY))
            add(LifePageRules.card(WEEKLY, cardTitle(WEEKLY), allRows(view, WEEKLY, today), empty = WEEKLY_EMPTY))
            view.terms?.let { add(LifePageRules.card(COUNCIL, cardTitle(COUNCIL), councilRows(it), empty = COUNCIL_EMPTY)) }
        }
        return LifePageView(TITLE, INFO, listOf(offTile(view, today), middleTile(view, today), weeklyTile(view)), cards, ADD_TITLE, ADD_CHOICES)
    }

    /** The row behind a key ("item:…"), as it is now; null once it has been removed (or for a council's key). */
    fun row(view: SchoolView, key: String): SchoolRow? =
        key.takeIf { it.startsWith("item:") }?.removePrefix("item:")?.let { id ->
            (view.off + view.dates + view.weekly).firstOrNull { it.id == id }
        }

    /** The week-ahead question for a day off's row while one is waiting, else null. */
    fun cover(view: SchoolView, key: String): SchoolCover? =
        key.takeIf { it.startsWith("item:") }?.removePrefix("item:")?.let { id -> view.covers.firstOrNull { it.id == id } }

    /** "Day off", "School date", "Every week": the part a row's sheet names. */
    fun partLabel(view: SchoolView, row: SchoolRow): String = when {
        view.off.any { it.id == row.id } -> "Day off"
        view.dates.any { it.id == row.id } -> "School date"
        else -> "Every week"
    }
}
