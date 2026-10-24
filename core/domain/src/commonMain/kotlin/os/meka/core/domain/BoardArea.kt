package os.meka.core.domain

/**
 * The part of his life a card belongs to, as a small coloured dot and its name in the card's foot (the Week board
 * redesign, Option B, slice 3; the approved mockup's family / work / side hustle / money / house). Each app maps it to a
 * palette colour: Family the warm orange, Work the blue, Side hustle the brass, Money the green, House the violet.
 */
enum class BoardArea { FAMILY, WORK, SIDE_HUSTLE, MONEY, HOUSE }

/** A row of the card menu's AREA section: one of the five, or [area] null for "No area"; [label] "✓ Family" when set. */
data class AreaChoice(val area: BoardArea?, val label: String, val selected: Boolean)

/**
 * Option B, slice 3: the colour categories. Non-AI and pure, the same on the Fold and the Mac.
 *
 * - A task stores its area in [TaskFields.AREA] (additive, Lww): one of [BoardArea]'s names, or [NONE] when he took it
 *   off. Until he picks one, Navilife guesses from the task itself ([guess]: where it came from, its Someday kind and a
 *   few plain words in its title), checked in a fixed order so "Boiler quote" is House, not Side hustle.
 * - The card's foot shows the area's dot and name ("● Family"); a card with no area keeps its source ("● Mail").
 * - Under the weekend, Someday splits into three lists: Someday, Side hustle and House ([listOf]); dropping a card on
 *   Side hustle or House files it there (and on Someday), dropping one on Someday takes those two off it ([listRaw]).
 */
object BoardAreaRules {
    /** Stored when he chose "No area": no guess is shown for it any more. */
    const val NONE = "NONE"

    const val MENU_TITLE = "AREA"
    const val NO_AREA = "No area"

    /** The two areas with their own list under the weekend, in the mockup's order after Someday. */
    val LISTS: List<BoardArea> = listOf(BoardArea.SIDE_HUSTLE, BoardArea.HOUSE)

    fun label(area: BoardArea): String = when (area) {
        BoardArea.FAMILY -> "Family"
        BoardArea.WORK -> "Work"
        BoardArea.SIDE_HUSTLE -> "Side hustle"
        BoardArea.MONEY -> "Money"
        BoardArea.HOUSE -> "House"
    }

    /** The stored value read back: an area, [NONE] (taken off) or nothing stored yet. */
    fun stored(raw: String?): BoardArea? = raw?.let { r -> BoardArea.entries.firstOrNull { it.name == r } }

    /** The area a task shows: the one he picked, none when he took it off, else Navilife's [guess]. */
    fun of(task: Task): BoardArea? = when (task.area) {
        null -> guess(task)
        NONE -> null
        else -> stored(task.area) ?: guess(task)
    }

    /** The card's area (the same as [of] its task). */
    fun of(card: BoardCard): BoardArea? = of(card.task)

    /** The words beside the dot ("Family"), or null for a card with no area. */
    fun cardLabel(card: BoardCard): String? = of(card)?.let(::label)

    /** The list a Someday card sits in under the weekend: Side hustle, House, or null for Someday itself. */
    fun listOf(card: BoardCard): BoardArea? = of(card)?.takeIf { it in LISTS }

    // Checked in this order; a title matching two areas takes the first ("Pay the school trip" is Family).
    private val WORDS: List<Pair<BoardArea, List<String>>> = listOf(
        BoardArea.FAMILY to listOf(
            "school", "kids", "kid's", "son's", "daughter", "wife", "husband", "family", "birthday", "half term", "half-term",
            "homework", "reading book", "nursery", "playdate", "parents evening", "parents' evening", "swimming lesson", "mum", "dad",
        ),
        BoardArea.HOUSE to listOf(
            "boiler", "garden", "bins", "bin day", "house", "plumber", "electrician", "gutter", "gutters", "fridge", "washing machine",
            "dishwasher", "hoover", "laundry", "diy", "decorat", "paint the", "mot", "car service", "car wash", "tyres", "smoke alarm",
            "council tax", "leak", "repair",
        ),
        BoardArea.SIDE_HUSTLE to listOf(
            "invoice", "quote", "client", "rate card", "proposal", "side hustle", "freelance", "portfolio",
        ),
        BoardArea.MONEY to listOf(
            "£", "pay ", "payment", "bill", "savings", "budget", "bank", "tax return", "pension", "isa", "refund", "mortgage",
            "credit card", "direct debit", "transfer", "money", "trading",
        ),
        BoardArea.WORK to listOf(
            "work", "office", "meeting", "stand-up", "standup", "1:1", "one-to-one", "timesheet", "appraisal", "deck", "slides",
            "colleague", "team ", "jira",
        ),
    )

    /**
     * Navilife's guess from the task alone: School cards are Family; a Someday kind of Home improvement is House and
     * Trading is Money; else the first area with a plain word in the title (whole words, case aside), or null.
     */
    fun guess(task: Task): BoardArea? {
        if (BoardSourceRules.of(task) == CardSource.SCHOOL) return BoardArea.FAMILY
        when (task.somedayKind) {
            SomedayKind.HOME_IMPROVEMENT -> return BoardArea.HOUSE
            SomedayKind.TRADING -> return BoardArea.MONEY
            else -> Unit
        }
        // Read by hand, not with a regex, so Kotlin/Native reads it exactly as the JVM does.
        val title = " " + task.title.lowercase().map { c -> if (c.isLetterOrDigit() || c in KEPT) c else ' ' }.joinToString("") + " "
        return WORDS.firstOrNull { (_, words) -> words.any { has(title, it) } }?.first
    }

    private const val KEPT = "£:'-"

    /** [word] in [padded] (spaces either side): a whole word, or a stem ("decorat") at a word's start. */
    private fun has(padded: String, word: String): Boolean {
        if (word == "£") return padded.contains('£')
        val w = word.trimEnd()
        var from = 0
        while (true) {
            val at = padded.indexOf(w, from)
            if (at < 0) return false
            val before = padded[at - 1]
            val end = at + w.length
            val after = if (end < padded.length) padded[end] else ' '
            val stem = word == "decorat"
            if (!before.isLetterOrDigit() && (stem || !after.isLetterOrDigit())) return true
            from = at + 1
        }
    }

    /** The card menu's AREA rows: the five, then "No area" once it has one; the one it shows ticked. */
    fun choices(card: BoardCard): List<AreaChoice> {
        val now = of(card)
        val rows = BoardArea.entries.map { a -> AreaChoice(a, if (a == now) "✓ ${label(a)}" else label(a), a == now) }
        return if (now == null) rows else rows + AreaChoice(null, NO_AREA, false)
    }

    /** What to store when he picks [area] in the menu: its name, or [NONE] for "No area". */
    fun raw(area: BoardArea?): String = area?.name ?: NONE

    /**
     * What to store when a card is dropped on the list for [list] (Side hustle, House, or null for Someday): the list's
     * area when it isn't already showing it; for Someday, [NONE] only when it shows Side hustle or House (any other area
     * stays). Null when nothing needs storing.
     */
    fun listRaw(task: Task, list: BoardArea?): String? {
        val now = of(task)
        return when {
            list != null -> if (now == list) null else list.name
            now in LISTS -> NONE
            else -> null
        }
    }

    /** The undo bar's line: "“Boiler service” · House", "“Boiler service” · no area". */
    fun line(title: String, area: BoardArea?): String =
        "“${TaskTitleRules.display(title)}” · " + (area?.let(::label) ?: NO_AREA.lowercase())

    /** The undo bar's line for a drop on a list: "Moved “Update rate card” to Side hustle". */
    fun listLine(title: String, list: BoardArea?): String =
        "Moved “${TaskTitleRules.display(title)}” to " + (list?.let(::label) ?: WeekBoardRules.SOMEDAY)
}
