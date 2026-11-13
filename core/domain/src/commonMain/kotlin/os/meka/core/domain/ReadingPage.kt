package os.meka.core.domain

import kotlin.math.roundToInt

/** One thing a reading row's sheet can do with it ("Start", "Finished", "Put down", "Again", "Pick up"). */
data class ReadingAct(val key: String, val label: String)

/**
 * Ask → Daily → Reading in the page shape (every page under Ask in one shape, slice 6; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ (what the page does and that nothing is looked up or
 * sent), one gold + Add (A book, podcast or course · A reading goal, each a field in the sheet), three numbers (on the
 * go, up next, the year's goal or what finished lately), then **On the go** (newest first, how far on the right),
 * **Up next** (first added first), **Finished lately** (finished and put down in the last 90 days, newest first) and
 * **Reading goal**. A row opens its sheet: a book's how far, its bar, Start / Finished / Put down / Again / Pick up
 * and Remove; the goal's books a year and Clear. Built from the [ReadingView] both apps already have; nothing new is
 * read or sent. Non-AI, pure.
 */
object ReadingPageRules {
    const val TITLE = ReadingRules.TITLE
    const val INFO = "Books, podcasts and courses: what you're reading, what's next and what you finished, with how far you've got and a goal for the year. Kept in your own data on both apps; nothing is looked up or sent anywhere."
    const val ADD_TITLE = "Add to Reading"
    const val GO = "On the go"
    const val NEXT = "Up next"
    const val DONE = "Finished lately"
    const val GOAL = "Reading goal"
    const val GO_EMPTY = "Nothing on the go · open one in Up next and tap Start"
    const val NEXT_EMPTY = "Nothing up next · + Add → A book, podcast or course"
    const val DONE_EMPTY = "Nothing finished in the last 90 days"
    const val NO_GOAL = "No goal for the year"
    const val NO_GOAL_LINE = "Tap to set one, e.g. 12 books"
    /** In a sheet: what typing how far does. */
    const val PROGRESS_NOTE = "Type how far you've got and it moves on; the last page, 100% or the last episode finishes it."
    const val GONE = "Gone from your reading list"

    const val ADD_ITEM = "item"
    const val ADD_GOAL = "goal"
    val ADD_CHOICES = listOf(
        AddChoice(ADD_ITEM, "A book, podcast or course", "e.g. Atomic Habits by James Clear, 320 pages, or podcast The Rest Is History"),
        AddChoice(ADD_GOAL, "A reading goal", "Books to finish this year, e.g. 12 books"),
    )

    /** The field's hint for each choice (the same words the old page's fields had, so TalkBack names them the same). */
    fun hint(choice: String): String = if (choice == ADD_GOAL) ReadingGoalRules.HINT else ReadingRules.HINT

    const val KEY_GOAL = "set:goal"
    fun itemKey(id: String): String = "item:$id"
    fun itemId(key: String): String? = key.takeIf { it.startsWith("item:") }?.removePrefix("item:")

    const val ACT_START = "start"
    const val ACT_FINISH = "finish"
    const val ACT_DOWN = "down"

    /** What a row's sheet offers for where it is: on the go Finished and Put down; up next Start; ended Again / Pick up. */
    fun acts(row: ReadingRow): List<ReadingAct> = when (row.state) {
        ReadingState.READING.key -> listOf(ReadingAct(ACT_FINISH, "Finished"), ReadingAct(ACT_DOWN, "Put down"))
        ReadingState.WANT.key -> listOf(ReadingAct(ACT_START, "Start"))
        ReadingState.FINISHED.key -> listOf(ReadingAct(ACT_START, "Again"))
        else -> listOf(ReadingAct(ACT_START, "Pick up"))
    }

    /** Whether the sheet has a how-far field (only while on the go). */
    fun takesProgress(row: ReadingRow): Boolean = row.state == ReadingState.READING.key

    /** "38%" from the bar, or null when there's nothing to measure against. */
    fun percent(row: ReadingRow): String? = row.fraction?.let { "${(it.coerceIn(0f, 1f) * 100).roundToInt()}%" }

    private fun goRow(r: ReadingRow): PageRow {
        val pct = percent(r)
        return PageRow(itemKey(r.id), r.title, if (pct != null) r.meta else "${r.meta} · ${r.progress}", pct ?: "", false)
    }

    private fun nextRow(r: ReadingRow): PageRow = PageRow(itemKey(r.id), r.title, r.meta, "", false)

    private fun doneRow(r: ReadingRow): PageRow = PageRow(itemKey(r.id), r.title, r.progress, "", false)

    /** Every row of the card [cardKey] (See all), not only the first five. */
    fun allRows(view: ReadingView, cardKey: String): List<PageRow> = when (cardKey) {
        GO -> view.onTheGo.map(::goRow)
        NEXT -> view.upNext.map(::nextRow)
        DONE -> ended(view).map(::doneRow)
        else -> emptyList()
    }

    /** Finished and put down together, newest first (each list is already newest first; stable on ties). */
    private fun ended(view: ReadingView): List<ReadingRow> = view.finished + view.putDown

    fun page(view: ReadingView): LifePageView {
        val go = view.onTheGo.size
        val next = view.upNext.size
        val goTile = PageTile(if (go > 0) "$go" else "None", "on the go", false)
        val nextTile = PageTile(if (next > 0) "$next" else "None", "up next", false)
        val g = view.goal
        val done = ended(view).count { it.state == ReadingState.FINISHED.key }
        val thirdTile = if (g != null) PageTile(g.count, "books this year", g.lit)
        else PageTile(if (done > 0) "$done" else "None", "finished lately", false)
        val goalRow = if (g != null) PageRow(KEY_GOAL, g.title, g.line, g.count, g.lit)
        else PageRow(KEY_GOAL, NO_GOAL, NO_GOAL_LINE, "", false)
        return LifePageView(
            TITLE, INFO,
            listOf(goTile, nextTile, thirdTile),
            listOf(
                LifePageRules.card(GO, GO, allRows(view, GO), empty = GO_EMPTY),
                LifePageRules.card(NEXT, NEXT, allRows(view, NEXT), empty = NEXT_EMPTY),
                LifePageRules.card(DONE, DONE, allRows(view, DONE), empty = DONE_EMPTY),
                LifePageRules.card(GOAL, GOAL, listOf(goalRow)),
            ),
            ADD_TITLE, ADD_CHOICES,
        )
    }

    /** The book, podcast or course behind a row's key, wherever it is now; null when it's gone (removed). */
    fun item(view: ReadingView, key: String): ReadingRow? = itemId(key)?.let { id ->
        (view.onTheGo + view.upNext + view.finished + view.putDown).firstOrNull { it.id == id }
    }
}
