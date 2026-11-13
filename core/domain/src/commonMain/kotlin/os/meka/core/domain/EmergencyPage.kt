package os.meka.core.domain

/**
 * Ask → Emergency card in the page shape (every page under Ask in one shape, slice 12; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ (what the card is for, who sees it, and that secrets are
 * turned away), one gold + Add (Someone to call · Where something is · A key account, each a field in the sheet: "GP:
 * Dr Patel, 01632 123456"), three numbers (people to call, things noted, key accounts), then one card a part — **Who
 * to call**, **Where things are**, **Key accounts** — each at most five rows with See all. A line's row opens its
 * sheet with its details, a field to change them and Remove. Built from the [EmergencyView] both apps already have;
 * nothing new is read or sent, and the same refusals apply (no passwords, PINs or numbers). Non-AI, pure.
 */
object EmergencyPageRules {
    const val TITLE = "Emergency card"
    const val INFO = "One page your partner can open from their family page in a hurry: who to call, where important things are, and key accounts by name. Passwords, PINs and card or account numbers are turned away."
    const val ADD_TITLE = "Add to the emergency card"
    const val GONE = "That line isn't on the card now"
    /** A line with only a name. */
    const val NAME_ONLY = "Name only"
    /** The row sheet's field. */
    const val CHANGE_HINT = "New details, e.g. Dr Patel, 01632 123456"

    /** + Add's three choices, one a part; the key is the part's [EmergencySection.key]. */
    val ADD_CHOICES = listOf(
        AddChoice(EmergencySection.CALL.key, "Someone to call", "A name, a colon, then the details, e.g. ${EmergencySection.CALL.hint}"),
        AddChoice(EmergencySection.DOCUMENTS.key, "Where something is", "What, a colon, then where, e.g. ${EmergencySection.DOCUMENTS.hint}"),
        AddChoice(EmergencySection.ACCOUNTS.key, "A key account", "By name only, never its number, e.g. ${EmergencySection.ACCOUNTS.hint}"),
    )

    /** The field's hint for a choice: the part's one-line example. */
    fun hint(choice: String): String = EmergencySection.of(choice)?.hint ?: EmergencySection.CALL.hint

    /** What TalkBack and VoiceOver read for the field. */
    fun spokenHint(choice: String): String = EmergencySection.of(choice)?.spokenHint ?: EmergencySection.CALL.spokenHint

    fun lineKey(id: String): String = "line:$id"

    private fun empty(section: EmergencySection): String = when (section) {
        EmergencySection.CALL -> "No one yet · + Add → Someone to call"
        EmergencySection.DOCUMENTS -> "Nothing yet · + Add → Where something is"
        EmergencySection.ACCOUNTS -> "None yet · + Add → A key account, by name"
    }

    /** A card row's grey line fits one line on the cover screen; the whole details are in the row's sheet. */
    const val SHORT_LINE = 34

    /**
     * A row's grey line: the details when they fit [SHORT_LINE]; under Who to call, the phone number as typed when they
     * don't; otherwise their first words with "…" (the sheet has them all). [NAME_ONLY] for a name alone.
     */
    fun shortLine(r: EmergencyRow): String {
        val d = r.detail ?: return NAME_ONLY
        if (d.length <= SHORT_LINE) return PhraseGlue.kept(d)
        if (r.phone != null) {
            EmergencyRules.digitRuns(d).firstOrNull(EmergencyRules::isPhone)?.let { return PhraseGlue.kept(it.trim()) }
        }
        val head = d.take(SHORT_LINE - 1)
        val cut = head.substringBeforeLast(' ', head).trimEnd(',', ';', ' ', '.')
        return PhraseGlue.kept(cut) + "…"
    }

    private fun pageRow(r: EmergencyRow): PageRow = PageRow(lineKey(r.id), r.title, shortLine(r), "", false)

    private fun group(view: EmergencyView, cardKey: String): EmergencyGroup? = view.groups.firstOrNull { it.section == cardKey }

    /** Every row of the card [cardKey] (See all), not only the first five. */
    fun allRows(view: EmergencyView, cardKey: String): List<PageRow> = group(view, cardKey)?.rows?.map(::pageRow).orEmpty()

    private fun count(view: EmergencyView, s: EmergencySection): Int = group(view, s.key)?.rows?.size ?: 0

    private fun tile(n: Int, one: String, many: String, none: String): PageTile =
        if (n == 0) PageTile("None", none, false) else PageTile("$n", if (n == 1) one else many, false)

    fun page(view: EmergencyView): LifePageView {
        val tiles = listOf(
            tile(count(view, EmergencySection.CALL), "to call", "to call", "to call yet"),
            tile(count(view, EmergencySection.DOCUMENTS), "thing placed", "things placed", "placed yet"),
            tile(count(view, EmergencySection.ACCOUNTS), "key account", "key accounts", "accounts yet"),
        )
        val cards = EmergencySection.entries.map { s ->
            LifePageRules.card(s.key, s.label, allRows(view, s.key), empty = empty(s))
        }
        return LifePageView(TITLE, INFO, tiles, cards, ADD_TITLE, ADD_CHOICES)
    }

    /** The line behind a row's key, as it is now; null once it has been removed. */
    fun line(view: EmergencyView, key: String): EmergencyRow? =
        key.takeIf { it.startsWith("line:") }?.removePrefix("line:")?.let { id ->
            view.groups.asSequence().flatMap { it.rows }.firstOrNull { it.id == id }
        }

    /** The part's name for a line's sheet ("Who to call"). */
    fun partLabel(row: EmergencyRow): String = EmergencySection.of(row.section)?.label ?: TITLE

    /**
     * What the row sheet's field adds: the line's own name with the newly typed details, so [EmergencyRules.addPlan]
     * updates that line (and turns away a secret or a number exactly as + Add does). Null when nothing was typed.
     */
    fun changeText(row: EmergencyRow, typed: String): String? = typed.trim().takeIf { it.isNotEmpty() }?.let { "${row.title}: $it" }
}
