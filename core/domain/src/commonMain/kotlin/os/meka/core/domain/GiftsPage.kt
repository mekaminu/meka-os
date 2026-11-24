package os.meka.core.domain

/**
 * Ask → Life → Gifts in the page shape (every page under Ask in one shape, slice 3; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ (what the page does and that Navilife never orders
 * anything), one gold + Add (A birthday · An anniversary, each a field in the sheet), three numbers (the next occasion,
 * what to order soon, the gift ideas noted), then **Coming up** (the next two months, soonest first, with what to do:
 * order by, sorted) and **Birthdays and anniversaries** (every occasion, See all past five). A row opens that occasion's
 * sheet: its gift ideas (each with ×), a field for another, To a day…, Gift sorted / Not sorted yet and Remove. Built
 * from the [GiftsView] both apps already have; nothing new is read or sent. Non-AI, pure.
 */
object GiftsPageRules {
    const val TITLE = "Gifts"
    const val INFO = "Each birthday and anniversary, typed once. Navilife reminds you two weeks ahead with your gift ideas, and on the day to order by. It never orders or buys anything."
    const val ADD_TITLE = "Add to Gifts"
    const val COMING_UP = "Coming up"
    const val ALL = "Birthdays and anniversaries"
    const val COMING_EMPTY = "Nothing in the next two months · + Add a birthday and it shows here as it comes round"
    const val ALL_EMPTY = "Nothing yet · + Add puts each birthday and anniversary here, once"

    /**
     * Walk-through 2026-11-22 A15: with nothing entered the page said it four ways (the header's "Nothing entered yet…",
     * the tiles "No dates added yet · Nothing to order yet · No ideas noted yet", then [COMING_EMPTY] and [ALL_EMPTY],
     * "+ Add" named three times beside the button). Now [GiftRules.EMPTY_LINE] says it once in one raised card with
     * [ADD_FIRST] under it (it opens + Add on A birthday), and no tiles or cards follow until an occasion is added.
     */
    const val ADD_FIRST = "Add a birthday"

    /** Whether the page is blank: nothing entered, so only the one line and its button show. */
    fun blank(view: GiftsView): Boolean = view.rows.isEmpty()

    /** Whether the three numbers show: not on a blank page. */
    fun showsTiles(view: GiftsView): Boolean = !blank(view)
    /** Coming up holds the occasions this many days ahead. */
    const val COMING_DAYS = 60L
    /** What a sorted row says on the right in Coming up. */
    const val SORTED = "✓ sorted"
    /** The second number's words: the unsorted occasions in the next two weeks, the rows lit on Coming up. */
    const val SORT_ONE = "to sort, next two weeks"
    const val SORT_MANY = "to sort, next two weeks"

    const val ADD_BIRTHDAY = "birthday"
    const val ADD_ANNIVERSARY = "anniversary"
    val ADD_CHOICES = listOf(
        AddChoice(ADD_BIRTHDAY, "A birthday", "e.g. Sam's birthday 14 Nov, or 14/11/1990 for the age"),
        AddChoice(ADD_ANNIVERSARY, "An anniversary", "e.g. Wedding 20 Aug 2015 · add delivery 5 days if it's slow"),
    )

    /** The field's hint for each choice. */
    fun hint(choice: String): String = if (choice == ADD_ANNIVERSARY) "Wedding 20 Aug 2015" else "Sam's birthday 14 Nov"

    private val ANNIVERSARY_WORDS = setOf("anniversary", "anniversaries", "anniv", "wedding", "married")

    /**
     * What the field's words go to Gifts as: an anniversary choice without an anniversary word gets one on the end
     * ("Alex and Sam 3 May" → "Alex and Sam 3 May anniversary"; "Wedding 20 Aug 2015" already has it). A birthday is
     * Gifts' own default, so its words go as typed.
     */
    fun entry(choice: String, text: String): String {
        val t = text.trim()
        if (choice != ADD_ANNIVERSARY) return t
        val has = t.lowercase().split(' ', ',', '.').any { it in ANNIVERSARY_WORDS }
        return if (has || t.isEmpty()) t else "$t anniversary"
    }

    /** The key a row carries ("gift:o1"); [occasionId] gives it back. */
    fun rowKey(id: String): String = "gift:$id"
    fun occasionId(rowKey: String): String? = rowKey.takeIf { it.startsWith("gift:") }?.removePrefix("gift:")

    /** The page; a blank one (nothing entered) has no tiles and no cards: the header's one line and button say it all. */
    fun page(view: GiftsView, today: Long): LifePageView {
        if (blank(view)) return LifePageView(TITLE, INFO, emptyList(), emptyList(), ADD_TITLE, ADD_CHOICES)
        val rows = view.rows
        val next = rows.firstOrNull()
        val nextDay = next?.day
        val nextTile = if (next != null && nextDay != null) {
            PageTile(PeoplePageRules.dayWord(nextDay, today), next.title, next.noteLit)
        } else PageTile("No dates", "added yet", false)
        val toOrder = rows.count { it.noteLit }
        val orderTile = when {
            // Found and fixing, week 5, A6: "3 gifts to sort soon" sat over Coming up's four; it counts the unsorted ones
            // in the next two weeks (lit on Coming up), so it says so, as "All sorted for the next two weeks" does.
            toOrder > 0 -> PageTile("$toOrder", if (toOrder == 1) SORT_ONE else SORT_MANY, true)
            else -> PageTile("All sorted", "for the next two weeks", false)
        }
        val ideas = rows.sumOf { it.ideas.size }
        val ideasTile = if (ideas > 0) PageTile("$ideas", if (ideas == 1) "gift idea noted" else "gift ideas noted", false)
        else PageTile("No ideas", "noted yet", false)

        val coming = rows.filter { r -> r.day?.let { it - today in 0..COMING_DAYS } == true }.map { r ->
            PageRow(rowKey(r.id), r.title, r.note.removePrefix("✓ "), if (r.sorted) SORTED else PeoplePageRules.dayWord(r.day!!, today), r.noteLit)
        }
        // Found and fixing, week 4, A9: the ideas the third number counts are named on their occasion's row.
        val all = rows.map { r -> PageRow(rowKey(r.id), r.title, listOfNotNull(r.line, ideasLine(r)).joinToString(" · "), "", false) }
        return LifePageView(
            TITLE, INFO,
            listOf(nextTile, orderTile, ideasTile),
            listOf(
                LifePageRules.card(COMING_UP, COMING_UP, coming, empty = COMING_EMPTY),
                LifePageRules.card(ALL, ALL, all, empty = ALL_EMPTY),
            ),
            ADD_TITLE, ADD_CHOICES,
        )
    }

    /** "Idea: Lego" · "Ideas: Lego, A ball" · "Ideas: Lego, A ball +1 more" · null with none. */
    fun ideasLine(r: GiftRow): String? {
        val i = r.ideas
        if (i.isEmpty()) return null
        val named = i.take(2).joinToString(", ") { short(it.text) }
        return (if (i.size == 1) "Idea: " else "Ideas: ") + named + if (i.size > 2) " +${i.size - 2} more" else ""
    }

    /** An idea cut at [IDEA_CHARS] on a word ("A space Lego set with the…"), whole in the occasion's sheet. */
    private fun short(t: String): String {
        val w = t.trim()
        if (w.length <= IDEA_CHARS) return w
        val cut = w.take(IDEA_CHARS).substringBeforeLast(' ').trimEnd(',', ' ')
        return (cut.ifEmpty { w.take(IDEA_CHARS) }) + "…"
    }

    const val IDEA_CHARS = 26

    /** The occasion behind a row's key, or null when it's gone (removed while the sheet was opening). */
    fun occasion(view: GiftsView, rowKey: String): GiftRow? = occasionId(rowKey)?.let { id -> view.rows.firstOrNull { it.id == id } }
}
