package os.meka.core.domain

/**
 * Ask → Errands in the page shape (every page under Ask in one shape, slice 7; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ (what the page does and that nothing is sent), one gold
 * + Add (Things to do at a place, a field in the sheet: "Tesco: milk, batteries"), three numbers (errands waiting, the
 * places they wait at, done this week), then **Waiting** (a row a place, the place waiting longest first, what's
 * there in the grey line and how many on the right), **Done lately** (the last week, newest first) and, on the Fold
 * only, **Near-me reminders** (its one row: the switch's line, On or Off on the right, brass while it needs a look).
 * A place's row opens its sheet with each errand's Done and Remove (and, with near-me on, its spot's I'm here /
 * Forget spot); a done row's sheet has Back and Remove; the near-me row's sheet has the switch and the privacy line.
 * Built from the [ErrandView] both apps already have; nothing new is read or sent. Non-AI, pure.
 */
object ErrandsPageRules {
    const val TITLE = "Errands"
    const val INFO = "Things that wait for a place, not a time: type the place, then what to do there, and tick each off when it's done. Kept in your own data on both apps; nothing is sent anywhere."
    /** The Fold's ⓘ adds the near-me line (the Mac has no location reminders). */
    const val INFO_NEAR = "$INFO On this phone, near-me reminders can nudge you as you arrive at a place you saved."
    const val ADD_TITLE = "Add to Errands"
    const val WAITING = "Waiting"
    const val DONE = "Done lately"
    const val NEAR = "Near-me reminders"
    const val WAITING_EMPTY = "Nothing waiting · + Add → Things to do at a place"
    const val DONE_EMPTY = "Nothing ticked off in the last week"
    const val GONE = "Nothing waiting there now"

    const val ADD_THINGS = "things"
    val ADD_CHOICES = listOf(
        AddChoice(ADD_THINGS, "Things to do at a place", "The place, a colon, then what to do there, e.g. Tesco: milk, batteries"),
    )

    /** The field's hint (the old page's, so TalkBack names it the same). */
    fun hint(@Suppress("UNUSED_PARAMETER") choice: String): String = ErrandRules.HINT

    const val KEY_NEAR = "set:near"
    fun placeKey(key: String): String = "place:$key"
    fun doneKey(id: String): String = "done:$id"

    /** "1 to do", "3 to do": a place row's right-hand text. */
    fun toDo(n: Int): String = "$n to do"

    /** Whether the near-me switch is on (its button offers Turn off). */
    fun nearOn(near: ErrandNearSetting): Boolean = near.actionLabel != "Turn on"

    private fun placeRow(g: ErrandGroup): PageRow =
        PageRow(placeKey(g.key), g.place, ErrandRules.shown(g.line), toDo(g.rows.size), false)

    private fun doneRow(r: ErrandRow): PageRow = PageRow(doneKey(r.id), r.shown, r.meta ?: "", "", false)

    /** Every row of the card [cardKey] (See all), not only the first five. */
    fun allRows(view: ErrandView, cardKey: String): List<PageRow> = when (cardKey) {
        WAITING -> view.places.map(::placeRow)
        DONE -> view.done.map(::doneRow)
        else -> emptyList()
    }

    /** [near] is the Fold's switch (null on the Mac, which has no near-me card). */
    fun page(view: ErrandView, near: ErrandNearSetting? = null): LifePageView {
        val waiting = view.count
        val places = view.places.size
        val done = view.done.size
        val tiles = listOf(
            PageTile(if (waiting > 0) "$waiting" else "None", if (waiting == 1) "errand waiting" else "errands waiting", false),
            PageTile(if (places > 0) "$places" else "None", if (places == 1) "place" else "places", false),
            PageTile(if (done > 0) "$done" else "None", "done this week", false),
        )
        val cards = buildList {
            add(LifePageRules.card(WAITING, WAITING, allRows(view, WAITING), empty = WAITING_EMPTY))
            add(LifePageRules.card(DONE, DONE, allRows(view, DONE), empty = DONE_EMPTY))
            if (near != null) {
                add(LifePageRules.card(NEAR, NEAR, listOf(PageRow(KEY_NEAR, NEAR, near.statusLine, if (nearOn(near)) "On" else "Off", near.lit))))
            }
        }
        return LifePageView(TITLE, if (near != null) INFO_NEAR else INFO, tiles, cards, ADD_TITLE, ADD_CHOICES)
    }

    /** The place behind a row's key, as it is now; null when nothing waits there any more. */
    fun place(view: ErrandView, key: String): ErrandGroup? =
        key.takeIf { it.startsWith("place:") }?.removePrefix("place:")?.let { k -> view.places.firstOrNull { it.key == k } }

    /** The errand behind a row's key, wherever it is now (back in Waiting after Back); null when it's gone. */
    fun errand(view: ErrandView, key: String): ErrandRow? =
        key.takeIf { it.startsWith("done:") }?.removePrefix("done:")?.let { id ->
            (view.done + view.places.flatMap { it.rows }).firstOrNull { it.id == id }
        }
}
