package os.meka.core.domain

/**
 * Ask → What Navilife knows in the page shape (every page under Ask in one shape, slice 13; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ (what the page is for, that it stays in his own data, and
 * that passwords and card, bank or ID numbers are turned away), one gold + Add (A fact · A fact kept from the AI, each
 * a field: "Car reg: AB12 CDE"), three numbers (facts kept, how many Obi can use, how many kept from the AI), then one
 * card a group — Sizes, Health and care, Car, Home and utilities, Family and school, Other — each at most five rows
 * with See all. A fact's row opens its sheet with the fact whole, a field to change it, Copy, Keep from AI (or Let
 * Navilife use it) and Remove. Built from the [FactsView] both apps already have; nothing new is read or sent, and the
 * same refusals apply. Non-AI, pure.
 */
object FactsPageRules {
    const val TITLE = "What Navilife knows"
    const val INFO = "The things you look up, kept in one place: the car's reg, the GP, shoe sizes, the energy supplier. Obi answers from them, and a fact kept from the AI is never sent with a question. Kept in your own Navilife data only; passwords, PINs and card, bank or ID numbers are turned away."
    const val ADD_TITLE = "Add to what Navilife knows"
    const val GONE = "That fact isn't on the page now"
    /** The row sheet's field. */
    const val CHANGE_HINT = "The new fact, e.g. AB12 CDE"
    /** A row's right-hand word while the AI never sees it (the sheet says it in full). */
    const val KEPT_SHORT = "Kept"
    /** The one card while nothing is on the page yet. */
    const val ALL = "all"
    const val ALL_TITLE = "Your facts"
    const val EMPTY_CARD = "Nothing yet · + Add puts the car's reg, the GP, sizes and the utilities here"

    const val ADD_FACT = "fact"
    const val ADD_KEPT = "kept"

    /** + Add's choices: an ordinary fact, or one Obi never sees. */
    val ADD_CHOICES = listOf(
        AddChoice(ADD_FACT, "A fact", "A name, a colon, then the fact, e.g. ${FactRules.HINT}"),
        AddChoice(ADD_KEPT, "A fact kept from the AI", "Only you see it, e.g. Spare key: with the neighbour"),
    )

    /** True when the choice adds a fact Obi never sees. */
    fun keepsFromAi(choice: String): Boolean = choice == ADD_KEPT

    fun hint(choice: String): String = if (keepsFromAi(choice)) "Spare key: with the neighbour" else FactRules.HINT

    /** What TalkBack and VoiceOver read for the field. */
    fun spokenHint(choice: String): String =
        if (keepsFromAi(choice)) "Add a fact kept from the AI: its name, a colon, then the fact, for example Spare key: with the neighbour"
        else FactRules.DESCRIPTION

    fun lineKey(id: String): String = "fact:$id"

    /**
     * A row's grey line: the whole fact (spaces tidied), cut by the row's width on screen (one line, "…" where it
     * runs out), never by a count of characters, so the open Fold and the Mac show as much as they have room for
     * (A14, walk-through 2026-11-15: "Oakfield Combi 30, serviced…" was cut with three-quarters of the row empty).
     * The sheet has the fact whole.
     */
    fun shortLine(row: FactRow): String = PhraseGlue.kept(row.value.replace(Regex("\\s+"), " ").trim())

    private fun pageRow(r: FactRow): PageRow =
        PageRow(lineKey(r.id), r.label, shortLine(r), if (r.keptFromAi) KEPT_SHORT else "", false)

    /** Every row of the card [cardKey] (See all), not only the first five. */
    fun allRows(view: FactsView, cardKey: String): List<PageRow> =
        view.groups.firstOrNull { it.group == cardKey }?.rows?.map(::pageRow).orEmpty()

    private fun tile(n: Int, one: String, many: String, none: String): PageTile =
        if (n == 0) PageTile("None", none, false) else PageTile("$n", if (n == 1) one else many, false)

    fun page(view: FactsView): LifePageView {
        val rows = view.groups.flatMap { it.rows }
        val kept = rows.count { it.keptFromAi }
        val tiles = listOf(
            tile(rows.size, "fact kept", "facts kept", "facts yet"),
            tile(rows.size - kept, "Obi can use", "Obi can use", "for Obi yet"),
            tile(kept, "kept from AI", "kept from AI", "kept from AI"),
        )
        val cards =
            if (view.groups.isEmpty()) listOf(LifePageRules.card(ALL, ALL_TITLE, emptyList(), empty = EMPTY_CARD))
            else view.groups.map { g -> LifePageRules.card(g.group, g.label, g.rows.map(::pageRow)) }
        return LifePageView(TITLE, INFO, tiles, cards, ADD_TITLE, ADD_CHOICES)
    }

    /** The fact behind a row's key, as it is now; null once it has been removed. */
    fun fact(view: FactsView, key: String): FactRow? =
        key.takeIf { it.startsWith("fact:") }?.removePrefix("fact:")?.let { id ->
            view.groups.asSequence().flatMap { it.rows }.firstOrNull { it.id == id }
        }

    /** The group's name for a fact's sheet ("Home and utilities"). */
    fun groupLabel(row: FactRow): String = FactGroup.of(row.group).label

    /**
     * What the row sheet's field adds: the fact's own name with the newly typed fact, so [FactRules.addPlan] updates
     * that fact (and turns away a secret or a number exactly as + Add does). Null when nothing was typed.
     */
    fun changeText(row: FactRow, typed: String): String? =
        typed.trim().takeIf { it.isNotEmpty() }?.let { "${row.label}: $it" }
}
