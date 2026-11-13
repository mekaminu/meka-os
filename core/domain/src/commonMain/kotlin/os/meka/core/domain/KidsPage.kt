package os.meka.core.domain

/**
 * Ask → Kids in the page shape (every page under Ask in one shape, slice 15; approved 2026-11-11,
 * `docs/design/look/LifePage.dc.html`): the title with its ⓘ (what Kids keeps, the three-month size check, that it
 * never pays or moves money), one gold + Add (A club fee · A chore · Pocket money a week, each one typed line that
 * starts with whose it is when there's more than one child: "Alex: Football £35 monthly from 1 Nov"), three numbers
 * (what they need this week, the next club fee, what's to pay this week or the sizes to check), then **This week**,
 * **Children**, **Club fees** and **Chores and pocket money** (five rows each, See all for the rest). A child's row opens
 * their card (sizes, All still fit, their fees, chores and pocket money); a fee's row its Paid and Remove; a chores row
 * the child's chores and pocket money; a week's row the day and the thing. Built from the [KidsView] both apps already
 * have; nothing new is read or sent. Non-AI, pure.
 */
object KidsPageRules {
    const val TITLE = KidsRules.TITLE
    const val INFO = "Navilife keeps each child's sizes, club fees, chores and pocket money, lists what they need in the week ahead, and asks you to check their sizes every three months. It never pays a fee or moves money."
    const val ADD_TITLE = "Add to Kids"
    const val GONE = "That isn't in Kids now"

    const val WEEK = "week"
    const val CHILDREN = "children"
    const val FEES = "fees"
    const val MONEY = "money"

    const val ADD_FEE = "fee"
    const val ADD_CHORE = "chore"
    const val ADD_POCKET = "pocket"

    /** + Add's choices: each one typed line, "Alex: …" first when there's more than one child. */
    val ADD_CHOICES = listOf(
        AddChoice(ADD_FEE, "A club fee", "Alex: Football £35 monthly from 1 Nov"),
        AddChoice(ADD_CHORE, "A chore", "Alex: Tidy room £1"),
        AddChoice(ADD_POCKET, "Pocket money a week", "Alex: £5"),
    )

    /** The field's one-line example for a choice. */
    fun hint(choice: String): String = ADD_CHOICES.firstOrNull { it.key == choice }?.hint ?: ADD_CHOICES.first().hint

    /** What TalkBack and VoiceOver read for the field. */
    fun spokenHint(choice: String): String =
        "${ADD_CHOICES.firstOrNull { it.key == choice }?.label ?: ADD_CHOICES.first().label}, for example ${hint(choice)}"

    const val WEEK_EMPTY = "Nothing they need in the next 7 days"
    const val CHILDREN_EMPTY = "Add the children in Ask → Settings → About you first"
    const val FEES_EMPTY = "No club fees · + Add → A club fee"
    const val MONEY_EMPTY = "No chores or pocket money · + Add → A chore"
    /** A child's row on the right once their sizes are three months old. */
    const val CHECK = "Check sizes"
    /** A card row's grey line fits one line on the cover screen; the whole line is in the row's sheet. */
    const val SHORT_LINE = 34
    /** What + Add says when there's more than one child and the line doesn't say whose it is. */
    fun whoseFirst(names: List<String>): String =
        "Start with whose it is: “${names.firstOrNull() ?: "Alex"}: …”"

    fun kidKey(name: String): String = "kid:$name"
    fun feeKey(id: String): String = "fee:$id"
    fun moneyKey(name: String): String = "money:$name"
    fun weekKey(day: Long, index: Int): String = "week:$day:$index"

    /** [line] when it fits [SHORT_LINE], else its first part, else its first words with "…". */
    fun shortLine(line: String): String {
        if (line.length <= SHORT_LINE) return PhraseGlue.kept(line)
        val head = line.substringBefore(" · ")
        if (head.length <= SHORT_LINE) return PhraseGlue.kept(head)
        val cut = head.take(SHORT_LINE - 1).let { it.substringBeforeLast(' ', it) }.trimEnd(',', ';', ' ', '.')
        return PhraseGlue.kept(cut) + "…"
    }

    /**
     * Whose a typed + Add line is: "Alex: Football …" → (Alex, "Football …"), matched on the child's name or first name,
     * any case. With one child the name can be left off; with more and none named, the child is null (ask whose).
     */
    fun split(typed: String, names: List<String>): Pair<String?, String> {
        val t = typed.trim()
        val colon = t.indexOf(':')
        if (colon > 0) {
            val who = t.substring(0, colon).trim()
            val rest = t.substring(colon + 1).trim()
            names.firstOrNull { it.equals(who, ignoreCase = true) || it.substringBefore(' ').equals(who, ignoreCase = true) }
                ?.let { return it to rest }
        }
        // "Alex Tidy room £1": the name first without a colon.
        val first = t.substringBefore(' ')
        if (first.length < t.length) {
            names.firstOrNull { it.substringBefore(' ').equals(first, ignoreCase = true) }?.let { n ->
                val rest = if (t.startsWith(n, ignoreCase = true)) t.substring(n.length) else t.substring(first.length)
                return n to rest.trim().trimStart(':', '-', '–', ' ')
            }
        }
        return (names.singleOrNull()) to t
    }

    private fun weekRows(view: KidsView): List<PageRow> = view.week?.days.orEmpty().flatMap { d ->
        d.items.mapIndexed { i, thing -> PageRow(weekKey(d.day, i), thing.text, "", d.label, thing.lit) }
    }

    private fun childRows(view: KidsView): List<PageRow> = view.kids.map { k ->
        PageRow(kidKey(k.name), k.name, shortLine(k.sizesLine ?: KidsRules.NO_SIZES), if (k.due) CHECK else "", k.due)
    }

    private fun feeRows(view: KidsView, today: Long): List<PageRow> =
        view.kids.flatMap { k -> k.fees.map { k to it } }.sortedBy { it.second.dueDay }.map { (k, f) ->
            val cost = f.line.split(" · ").firstOrNull { it.startsWith("£") }
            PageRow(feeKey(f.id), f.title, shortLine(listOfNotNull(k.name, cost).joinToString(" · ")),
                LifePageRules.until(f.dueDay, today), f.due)
        }

    private fun moneyRows(view: KidsView): List<PageRow> = view.kids.filter { it.money.shown }.map { k ->
        val m = k.money
        val line = when {
            m.chores.isEmpty() -> m.pocketValue?.let { "$it a week" } ?: KidChoreRules.HEAD
            else -> "${m.chores.size} ${if (m.chores.size == 1) "chore" else "chores"}" + (m.pocketValue?.let { " · $it a week" } ?: "")
        }
        PageRow(moneyKey(k.name), k.name, shortLine(line), if (m.toPayPence > 0) "${KidChoreRules.fmt(m.toPayPence)} to pay" else "", m.lit)
    }

    /** Every row of the card [cardKey] (See all), not only the first five. */
    fun allRows(view: KidsView, cardKey: String, today: Long): List<PageRow> = when (cardKey) {
        WEEK -> weekRows(view)
        CHILDREN -> childRows(view)
        FEES -> feeRows(view, today)
        MONEY -> moneyRows(view)
        else -> emptyList()
    }

    /** The card's title for See all's sheet. */
    fun cardTitle(cardKey: String): String = when (cardKey) {
        WEEK -> "This week"
        CHILDREN -> "Children"
        FEES -> "Club fees"
        MONEY -> "Chores and pocket money"
        else -> TITLE
    }

    private fun weekTile(view: KidsView): PageTile {
        val w = view.week
        val n = w?.count ?: 0
        if (n == 0) return PageTile("None", "needed this week", false)
        val lit = w?.days?.any { d -> d.items.any { it.lit } } == true
        return PageTile("$n", if (n == 1) "thing this week" else "things this week", lit)
    }

    private fun feeTile(view: KidsView, today: Long): PageTile {
        val (k, f) = view.kids.flatMap { k -> k.fees.map { k to it } }.minByOrNull { it.second.dueDay }
            ?: return PageTile("No fees", "club fees kept", false)
        return PageTile(LifePageRules.until(f.dueDay, today), "to ${k.name}'s ${f.title.lowercaseFirst()}", f.due)
    }

    private fun String.lowercaseFirst(): String = if (length > 1 && this[1].isUpperCase()) this else replaceFirstChar { it.lowercase() }

    private fun thirdTile(view: KidsView): PageTile {
        val due = view.kids.count { it.due }
        if (due > 0) return PageTile("$due", if (due == 1) "child's sizes to check" else "children's sizes to check", true)
        if (view.kids.any { it.money.shown }) {
            val pay = view.kids.sumOf { it.money.toPayPence }
            return if (pay > 0) PageTile(KidChoreRules.fmt(pay), "to pay this week", view.kids.any { it.money.lit })
            else PageTile("Paid", "pocket money this week", false)
        }
        val kept = view.kids.count { it.sizesLine != null }
        return if (kept == 0) PageTile("None", "sizes kept yet", false)
        else PageTile("$kept of ${view.kids.size}", "sizes kept", false)
    }

    fun page(view: KidsView, today: Long): LifePageView {
        val cards = buildList {
            if (view.week != null) add(LifePageRules.card(WEEK, cardTitle(WEEK), allRows(view, WEEK, today), empty = WEEK_EMPTY))
            add(LifePageRules.card(CHILDREN, cardTitle(CHILDREN), allRows(view, CHILDREN, today), empty = CHILDREN_EMPTY))
            if (!view.isEmpty) {
                add(LifePageRules.card(FEES, cardTitle(FEES), allRows(view, FEES, today), empty = FEES_EMPTY))
                add(LifePageRules.card(MONEY, cardTitle(MONEY), allRows(view, MONEY, today), empty = MONEY_EMPTY))
            }
        }
        return LifePageView(TITLE, INFO, listOf(weekTile(view), feeTile(view, today), thirdTile(view)), cards, ADD_TITLE, ADD_CHOICES)
    }

    /** The child behind a "kid:…" or "money:…" key, as they are now; null once they've left About you. */
    fun kid(view: KidsView, key: String): KidCard? {
        val name = when {
            key.startsWith("kid:") -> key.removePrefix("kid:")
            key.startsWith("money:") -> key.removePrefix("money:")
            else -> return null
        }
        return view.kids.firstOrNull { it.name == name }
    }

    /** Whether a key opens a child's chores and pocket money (rather than their whole card). */
    fun isMoney(key: String): Boolean = key.startsWith("money:")

    /** The fee behind a "fee:…" key and whose it is; null once it's been removed or paid off. */
    fun fee(view: KidsView, key: String): Pair<KidCard, KidFeeRow>? {
        val id = key.takeIf { it.startsWith("fee:") }?.removePrefix("fee:") ?: return null
        return view.kids.firstNotNullOfOrNull { k -> k.fees.firstOrNull { it.id == id }?.let { k to it } }
    }

    /** The week's day and thing behind a "week:…" key; null once it's gone from the week. */
    fun weekItem(view: KidsView, key: String): Pair<KidWeekDay, KidWeekItem>? {
        val parts = key.takeIf { it.startsWith("week:") }?.split(':') ?: return null
        val day = parts.getOrNull(1)?.toLongOrNull() ?: return null
        val i = parts.getOrNull(2)?.toIntOrNull() ?: return null
        val d = view.week?.days?.firstOrNull { it.day == day } ?: return null
        return d.items.getOrNull(i)?.let { d to it }
    }

    /** The Mac's plain halves of [fee] and [weekItem] (no Pair across to Swift). */
    fun feeRow(view: KidsView, key: String): KidFeeRow? = fee(view, key)?.second
    fun feeOwner(view: KidsView, key: String): KidCard? = fee(view, key)?.first
    fun weekThing(view: KidsView, key: String): KidWeekItem? = weekItem(view, key)?.second

    /** What a row's sheet is titled. */
    fun sheetTitle(view: KidsView, key: String): String =
        kid(view, key)?.name ?: fee(view, key)?.second?.title ?: weekItem(view, key)?.first?.label ?: TITLE
}
