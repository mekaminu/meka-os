package os.meka.core.domain

/**
 * Checklists in tasks, slice 3b (Meka, 2026-10-10: "add eggs to the party checklist", "what's left on the shopping
 * list?"): the plain, non-AI rules for checklists in Ask and Talk. A question carries `checklists` lines (each open task
 * with a checklist: what's still to tick and how many are ticked), and the model may propose `add_checklist`
 * ({"title": "Party: eggs, milk"}), read here into a card that does nothing until Meka taps it.
 */
object ChecklistAskRules {
    /** At most this many checklists go with a question (ones with something still to tick first, newest first). */
    const val ASK_LISTS = 6
    /** At most this many still-to-tick items are named on one line. */
    const val ASK_ITEMS = 12
    /** Items one card adds at most. */
    const val MAX_ITEMS = 10
    const val ALREADY = "Those are already on the list"
    const val GONE = "That task isn't open any more"

    private fun tidy(s: String): String = s.replace(Regex("""\s+"""), " ").trim()

    /** How a task's title is matched: lower case, spaces tidied, "the"/"my" and a trailing "checklist"/"list" off. */
    fun key(s: String): String = tidy(s).lowercase()
        .removePrefix("the ").removePrefix("my ")
        .removeSuffix(" checklist").removeSuffix(" list")
        .trim()

    /**
     * The open tasks whose checklist can be asked about: not done or dropped, not a future occurrence waiting for its
     * day, with at least one item; one per repeating series (its earliest open occurrence).
     */
    fun lists(tasks: List<Task>, today: Long): List<Task> =
        tasks.filter { !it.lifecycle.isTerminal && it.checklist.isNotEmpty() && !it.waitsForItsDay(today) }
            .sortedWith(compareBy<Task> { it.showsFromDay ?: Long.MIN_VALUE }.thenByDescending { it.createdAtMs })
            .distinctBy { it.seriesId ?: it.id }

    /**
     * The checklists as MEKA describes them to the model (Ask's `checklists` lines): "Checklists · 2 tasks with
     * checklists", then each "Checklist “Party” · 2 of 5 ticked · to tick: eggs, milk, balloons" (or "· all 5
     * ticked"). Nothing at all while no open task has a checklist.
     */
    fun askLines(tasks: List<Task>, today: Long): List<String> {
        val open = lists(tasks, today)
        if (open.isEmpty()) return emptyList()
        val shown = open.sortedWith(
            compareByDescending<Task> { t -> t.checklist.any { !it.checked } }.thenByDescending { it.createdAtMs },
        ).take(ASK_LISTS)
        val head = "Checklists · ${open.size} " + (if (open.size == 1) "task" else "tasks") + " with checklists" +
            (if (open.size > ASK_LISTS) " · the $ASK_LISTS with most to tick below" else "")
        return listOf(head) + shown.map { t ->
            val v = ChecklistRules.view(t)
            val left = v.toTick.take(ASK_ITEMS).joinToString(", ") { tidy(it.text) } +
                (if (v.toTick.size > ASK_ITEMS) ", and ${v.toTick.size - ASK_ITEMS} more" else "")
            val state = if (v.toTick.isEmpty()) "all ${v.size} ticked" else "${v.ticked.size} of ${v.size} ticked · to tick: $left"
            "${v.label} “${tidy(t.title)}” · $state"
        }
    }

    /**
     * The task a model's name means: an open task with a checklist whose title is that name, else the only one whose
     * title holds it as whole words ("party" → "Party shopping"); else an open task without a checklist named exactly
     * so. Null when none or more than one could be meant.
     */
    fun find(tasks: List<Task>, name: String, today: Long): Task? {
        val k = key(name).takeIf { it.isNotEmpty() } ?: return null
        val withLists = lists(tasks, today)
        withLists.filter { key(it.title) == k }.let { if (it.size == 1) return it[0] else if (it.size > 1) return null }
        val words = Regex("""(^|\W)${Regex.escape(k)}($|\W)""")
        withLists.filter { words.containsMatchIn(key(it.title)) }.let { if (it.size == 1) return it[0] else if (it.size > 1) return null }
        val open = tasks.filter { !it.lifecycle.isTerminal && !it.waitsForItsDay(today) && key(it.title) == k }
            .distinctBy { it.seriesId ?: it.id }
        return open.singleOrNull()
    }

    /**
     * A model's `add_checklist` ("Party: eggs, milk": the task's name, a colon, then the items as a pasted line would
     * be split) as a card: at most [MAX_ITEMS] items, none already waiting to be ticked (a ticked one comes back
     * unticked when tapped). Null when no colon, no task matches, or nothing new.
     */
    fun proposal(tasks: List<Task>, title: String?, today: Long): AskProposal.AddChecklist? {
        val raw = title ?: return null
        val colon = raw.indexOf(':').takeIf { it > 0 } ?: return null
        val task = find(tasks, raw.substring(0, colon), today) ?: return null
        val waiting = task.checklist.filter { !it.checked }.map { it.text.lowercase().trim() }.toSet()
        val items = ChecklistRules.split(raw.substring(colon + 1)).filter { it.lowercase() !in waiting }.take(MAX_ITEMS)
        return if (items.isEmpty()) null else AskProposal.AddChecklist(task.id, tidy(task.title), items)
    }

    /** The card's words: "Add to “Party” · eggs and milk". */
    fun cardLine(p: AskProposal.AddChecklist): String = "Add to “${p.title}” · ${AskRules.shoppingNames(p.items)}"

    /** The undo bar's line: "Added eggs and milk to “Party”". */
    fun doneLine(p: AskProposal.AddChecklist): String = "Added ${AskRules.shoppingNames(p.items)} to “${p.title}”"

    /**
     * What tapping the card writes, against the list as it is now: items ticked already are unticked ([revive]), ones
     * waiting are left, the rest are added ([add]).
     */
    fun plan(current: List<ChecklistItem>, items: List<String>): ChecklistAskPlan {
        val add = mutableListOf<String>()
        val revive = mutableListOf<String>()
        items.forEach { text ->
            val same = current.filter { it.text.lowercase().trim() == text.lowercase().trim() }
            when {
                same.any { !it.checked } -> Unit
                same.isNotEmpty() -> revive += same.first().id
                else -> add += text
            }
        }
        return ChecklistAskPlan(add, revive)
    }
}

/** What a checklist card writes: [add] new items' texts, [revive] ticked items' ids to untick. */
data class ChecklistAskPlan(val add: List<String>, val revive: List<String>) {
    val isEmpty: Boolean get() = add.isEmpty() && revive.isEmpty()
}
