package os.meka.core.domain

/**
 * Ask → Dinners in the page shape (every page under Ask in one shape, slice 8; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ (what the page does and who else sees it), one gold
 * + Add (A favourite dinner, a field in the sheet: "Chilli: mince, beans, rice"), three numbers (dinners planned this
 * week, ingredients still to add to shopping, favourites), then **This week** (a row a day, the dinner's name with its
 * ingredients in the grey line and the day on the right, brass for tonight's), **Shopping** (one row: Add n
 * ingredients to shopping, while a dinner is planned) and **Favourites** (by name, See all past five). A day's row
 * opens its sheet with the favourites to pick (or None); Shopping's opens the gold button with its Undo; a favourite's
 * opens its ingredients, the week's days to plan it on and Remove. Built from the [MealPlanView] both apps already
 * have; nothing new is read or sent. Non-AI, pure.
 */
object MealsPageRules {
    const val TITLE = "Dinners"
    const val INFO = "Your favourite dinners with their ingredients, typed once; pick the week's dinners a day at a time and their ingredients go on the shopping list in one tap. Shared with your partner's family page and the shopping list you share; nothing else is sent anywhere."
    const val ADD_TITLE = "Add to Dinners"
    const val WEEK = "This week"
    const val SHOPPING = "Shopping"
    const val FAVOURITES = "Favourites"
    const val EMPTY_SUMMARY = "No favourite dinners yet · + Add one with its ingredients"
    const val WEEK_EMPTY = "Add a favourite first · then pick the week's dinners here"
    const val FAVOURITES_EMPTY = "No favourites yet · + Add → A favourite dinner"
    const val PICK = "Tap to pick a dinner"
    const val NOTHING_TO_ADD = "Nothing to add"
    const val PLAN_FOR = "Plan it for"
    const val PICK_FIRST = "Add a favourite first"
    const val GONE = "That dinner isn't on your favourites now"

    const val ADD_FAVOURITE = "favourite"
    val ADD_CHOICES = listOf(
        AddChoice(ADD_FAVOURITE, "A favourite dinner", "Its name, a colon, then its ingredients, e.g. ${MealRules.ADD_HINT}"),
    )

    /** The field's hint (the old page's, so TalkBack names it the same). */
    fun hint(@Suppress("UNUSED_PARAMETER") choice: String): String = MealRules.ADD_HINT

    const val KEY_SHOP = "shop"
    fun dayKey(day: Long): String = "day:$day"
    fun mealKey(id: String): String = "meal:$id"

    /** Under the header: the week's count ("4 of 7 dinners planned"), or how to start. */
    fun summary(view: MealPlanView): String = if (view.favourites.isEmpty()) EMPTY_SUMMARY else view.paneSummary

    private fun dayRow(r: MealDayRow, today: Boolean): PageRow =
        PageRow(dayKey(r.day), r.title ?: MealRules.NOT_PLANNED, if (r.title != null) r.line else PICK, r.label, today && r.title != null)

    private fun mealRow(r: MealRow): PageRow = PageRow(mealKey(r.id), r.title, r.line, "", false)

    private fun shopRow(view: MealPlanView): PageRow =
        PageRow(KEY_SHOP, view.shoppingLabel ?: NOTHING_TO_ADD, view.shoppingLine, "", view.shoppingLabel != null)

    /** Every row of the card [cardKey] (See all), not only the first five. */
    fun allRows(view: MealPlanView, cardKey: String): List<PageRow> = when (cardKey) {
        WEEK -> if (view.favourites.isEmpty()) emptyList() else view.week.mapIndexed { i, r -> dayRow(r, i == 0) }
        SHOPPING -> if (view.plannedCount > 0) listOf(shopRow(view)) else emptyList()
        FAVOURITES -> view.favourites.map(::mealRow)
        else -> emptyList()
    }

    fun page(view: MealPlanView): LifePageView {
        val planned = view.plannedCount
        val toAdd = view.toAdd.size
        val favs = view.favourites.size
        val tiles = listOf(
            PageTile(if (planned > 0) "$planned of ${MealRules.WEEK_DAYS}" else "None", if (planned == 1) "dinner planned" else "dinners planned", false),
            PageTile(if (toAdd > 0) "$toAdd" else "None", "to add to shopping", toAdd > 0),
            PageTile(if (favs > 0) "$favs" else "None", if (favs == 1) "favourite" else "favourites", false),
        )
        val cards = buildList {
            add(LifePageRules.card(WEEK, WEEK, allRows(view, WEEK), empty = WEEK_EMPTY))
            if (planned > 0) add(LifePageRules.card(SHOPPING, SHOPPING, allRows(view, SHOPPING)))
            add(LifePageRules.card(FAVOURITES, FAVOURITES, allRows(view, FAVOURITES), empty = FAVOURITES_EMPTY))
        }
        return LifePageView(TITLE, INFO, tiles, cards, ADD_TITLE, ADD_CHOICES)
    }

    /** The day behind a row's key, as it is now; null when it's not this week's any more. */
    fun day(view: MealPlanView, key: String): MealDayRow? =
        key.takeIf { it.startsWith("day:") }?.removePrefix("day:")?.toLongOrNull()?.let { d -> view.week.firstOrNull { it.day == d } }

    /** The favourite behind a row's key; null once it's removed. */
    fun meal(view: MealPlanView, key: String): MealRow? =
        key.takeIf { it.startsWith("meal:") }?.removePrefix("meal:")?.let { id -> view.favourites.firstOrNull { it.id == id } }

    /** The days of this week a favourite is planned on (its sheet lights them). */
    fun plannedDays(view: MealPlanView, mealId: String): Set<Long> = view.week.filter { it.mealId == mealId }.map { it.day }.toSet()

    /** Whether [mealId] is [day]'s dinner (the Mac's day buttons; only a Long and a String cross). */
    fun isPlanned(view: MealPlanView, mealId: String, day: Long): Boolean = view.week.any { it.day == day && it.mealId == mealId }
}
