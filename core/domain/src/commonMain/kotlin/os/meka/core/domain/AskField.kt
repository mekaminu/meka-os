package os.meka.core.domain

/**
 * Ask's one field (Fold review 2026-10-09 07:26, item 8): the field asks MEKA and searches at once. The mic sits inside
 * it at the right end, Return asks, and while typing the best matches from Search everything (tasks, calendar, lists,
 * goals and habits; done tasks stay in the full search) appear under it; "See all 12 matches" opens Search with what
 * was typed. The separate Ask and Search buttons are gone. While asking can't work (AI off, the month's budget used,
 * not connected) the same field only searches, and Return opens the full search.
 *
 * Non-AI and local: the matches come from [Search.run] over what is already on the device; nothing is sent until
 * Return asks.
 */
data class AskMatch(
    val hit: SearchHit,
    /** "Task · Planned today 14:00", "Event · Fri 9 Oct · 15:00–16:00", "Waiting for", "Renewal · Due 1 Nov". */
    val line: String,
    /** What a screen reader says: "Task: Dentist, Planned today 14:00". */
    val spoken: String,
    /** A task opens its detail (over Search); a list item, goal or habit opens its place; an event is only shown. */
    val opens: Boolean,
)

data class AskMatches(
    /** The query these matches are for, as typed. */
    val query: String,
    val rows: List<AskMatch>,
    /** Every match Search everything found (done tasks included). */
    val total: Int,
    /** "See all 12 matches" when Search has more than shown; null otherwise. */
    val seeAll: String?,
    /** "No matches · Return asks MEKA" / "No matches for “boiler”" when nothing matched; null otherwise. */
    val empty: String?,
)

object AskFieldRules {
    /** Matches shown under the field (the closed Fold has room for about this many above the keyboard). */
    const val MAX_SHOWN = 5
    /** Typing settles this long before the field searches (as Search everything's own field). */
    const val SETTLE_MS = 120L

    const val PLACEHOLDER_ASK = "Ask or search…"
    const val PLACEHOLDER_SEARCH = "Search everything…"

    fun placeholder(canAsk: Boolean): String = if (canAsk) PLACEHOLDER_ASK else PLACEHOLDER_SEARCH

    /** What the field is called to a screen reader. */
    fun fieldLabel(canAsk: Boolean): String = if (canAsk) "Ask MEKA or search" else "Search everything"

    /** The line under the field before anything is typed. */
    fun idleLine(canAsk: Boolean, mac: Boolean = false): String =
        if (canAsk) "Type to find tasks, events and lists; Return asks MEKA about your day, or to add, move or tick off a task, start a fast or set a timer. Nothing happens until you ${if (mac) "click" else "tap"}."
        else "Type to find tasks, events, lists, goals and habits. Searched on this ${if (mac) "Mac" else "phone"}; nothing leaves it."

    /** The kind as one word in front of a match's line. */
    fun kindWord(kind: SearchKind): String = when (kind) {
        SearchKind.TASK -> "Task"
        SearchKind.EVENT -> "Event"
        SearchKind.WAITING -> "Waiting for"
        SearchKind.DECISION -> "Decision"
        SearchKind.RENEWAL -> "Renewal"
        SearchKind.SOMEDAY -> "Someday"
        SearchKind.GOAL -> "Goal"
        SearchKind.HABIT -> "Habit"
        SearchKind.DONE -> "Done"
    }

    /**
     * True when the matches should show under the field: something searchable is typed and it isn't the question
     * MEKA is answering (or was just asked) — once asked, the answer has the space; typing again brings matches back.
     */
    fun showMatches(typed: String, asked: String?): Boolean {
        if (SearchRules.tokens(typed).isEmpty()) return false
        return asked == null || AskRules.question(typed) != asked
    }

    /**
     * The matches for [typed] from [view], or null while [view] is still for another query (the screen keeps showing
     * the last matches until the new ones arrive) or nothing searchable is typed. At most [MAX_SHOWN], done tasks left
     * out, one from each kind in turn (Search's group order) so a task-heavy query still shows an event or a list item,
     * then listed in Search's order (kind, then best first).
     */
    fun matches(view: SearchView, typed: String, canAsk: Boolean): AskMatches? {
        val tokens = SearchRules.tokens(typed)
        if (tokens.isEmpty() || SearchRules.tokens(view.query) != tokens) return null
        val groups = view.groups.filter { it.kind != SearchKind.DONE }
        val taken = groups.map { 0 }.toMutableList()
        var left = MAX_SHOWN
        var progressed = true
        while (left > 0 && progressed) {
            progressed = false
            for (i in groups.indices) {
                if (left == 0) break
                if (taken[i] < groups[i].hits.size) { taken[i]++; left--; progressed = true }
            }
        }
        val rows = groups.flatMapIndexed { i, g -> g.hits.take(taken[i]) }.map { row(it) }
        val q = typed.trim()
        return AskMatches(
            query = typed,
            rows = rows,
            total = view.total,
            seeAll = if (view.total > rows.size) "See all ${view.total} matches" else null,
            empty = if (view.total == 0) (if (canAsk) "No matches · Return asks MEKA" else "No matches for “$q”") else null,
        )
    }

    fun row(hit: SearchHit): AskMatch {
        val word = kindWord(hit.kind)
        val line = listOfNotNull(word, hit.detail).joinToString(" · ")
        val spoken = "$word: ${hit.title}" + (hit.detail?.let { ", $it" } ?: "")
        val opens = hit.target != SearchTarget.INFO
        return AskMatch(hit, line, spoken, opens)
    }

    /** What Return does: ask MEKA when it can, else open the full search with what was typed (nothing typed: nothing). */
    fun onReturn(typed: String, canAsk: Boolean): AskReturn = when {
        AskRules.question(typed) == null -> AskReturn.NOTHING
        canAsk -> AskReturn.ASK
        SearchRules.tokens(typed).isEmpty() -> AskReturn.NOTHING
        else -> AskReturn.SEARCH
    }
}

enum class AskReturn { NOTHING, ASK, SEARCH }
