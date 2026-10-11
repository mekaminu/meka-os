package os.meka.core.domain

/**
 * Checklists in tasks (Meka, 2026-10-10: "a checklist I can tick off… going shopping with a list, check it off as I
 * shop"). The data is the task's existing steps ([ChecklistItem], synced `checklist_item` entities); these are the
 * plain, non-AI rules the task detail reads on both apps: what one typed or pasted line adds, the order rows show in
 * (still to tick first, then "Ticked"), the line under the label, when to offer completing the task, and the positions
 * a reordered item takes.
 */
data class ChecklistView(
    /** "Checklist", or "Routine steps" for a repeating task (each occurrence brings them back unticked). */
    val label: String,
    /** Still to tick, in the list's order. */
    val toTick: List<ChecklistItem>,
    /** Ticked, in the list's order (under "Ticked"). */
    val ticked: List<ChecklistItem>,
    /** "3 of 7 ticked", "7 items", "All 7 ticked"; null while the list is empty. */
    val line: String?,
    /** Every item ticked and the task still open: offer "Complete the task?". */
    val offerComplete: Boolean,
    /** Something ticked: "Untick all" to reuse the list. */
    val canUntickAll: Boolean,
) {
    val isEmpty: Boolean get() = toTick.isEmpty() && ticked.isEmpty()
    val size: Int get() = toTick.size + ticked.size
}

object ChecklistRules {
    /** At most this many items from one paste. */
    const val MAX_ADD = 30
    const val MAX_TEXT = 200
    const val ADD_HINT = "Add an item… (or paste a list)"
    const val TICKED_LABEL = "Ticked"
    const val UNTICK_ALL = "Untick all"
    const val COMPLETE_OFFER = "All ticked · complete the task?"
    /** Room between positions when a list is renumbered, so later moves can land between two items. */
    const val GAP = 1024L

    private val bullet = Regex("""^(?:[-*•·]|\[[ xX]?]|\d{1,3}[.)])\s+""")

    /**
     * What one typed or pasted line adds: "eggs, milk\nbread" → [eggs, milk, bread]. New lines, commas and semicolons
     * separate (never "and", so "fish and chips" stays one item); list bullets ("- ", "• ", "1. ", "[ ] ") come off;
     * blanks and repeats within the paste are dropped; at most [MAX_ADD] items, each cut at [MAX_TEXT].
     */
    fun split(text: String): List<String> =
        text.split('\n', ',', ';')
            .map { it.trim().replace(bullet, "").replace(Regex("\\s+"), " ").trim().take(MAX_TEXT).trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(MAX_ADD)

    /** Positions for [count] new items after [existing]: after the last one and never before [nowMs]. */
    fun positionsAfter(existing: List<ChecklistItem>, count: Int, nowMs: Long): List<Long> {
        val base = maxOf(nowMs, (existing.maxOfOrNull { it.position } ?: Long.MIN_VALUE / 2) + 1)
        return List(count) { base + it }
    }

    fun view(task: Task): ChecklistView {
        val items = task.checklist.sortedBy { it.position }
        val toTick = items.filter { !it.checked }
        val ticked = items.filter { it.checked }
        return ChecklistView(
            label = if (task.isRepeating) "Routine steps" else "Checklist",
            toTick = toTick,
            ticked = ticked,
            line = line(ticked.size, items.size),
            offerComplete = items.isNotEmpty() && toTick.isEmpty() && !task.lifecycle.isTerminal,
            canUntickAll = ticked.isNotEmpty(),
        )
    }

    fun line(ticked: Int, total: Int): String? = when {
        total == 0 -> null
        ticked == 0 -> if (total == 1) "1 item" else "$total items"
        ticked == total -> if (total == 1) "Ticked" else "All $total ticked"
        else -> "$ticked of $total ticked"
    }

    fun addedLine(texts: List<String>): String = when (texts.size) {
        0 -> "Nothing to add"
        1 -> "Added “${texts[0]}”"
        else -> "Added ${texts.size} items"
    }

    fun removedLine(text: String): String = "Removed “$text”"

    fun untickedLine(count: Int): String = if (count == 1) "Unticked 1 item" else "Unticked $count items"

    /**
     * Moving [itemId] to [toIndex] in [items] (the list's order): the positions to write. Only the moved item changes
     * when there is room between its new neighbours; otherwise the whole list is renumbered [GAP] apart (keeping its
     * first position), so the order is the same on every device once synced. Empty when nothing moves.
     */
    fun reorder(items: List<ChecklistItem>, itemId: String, toIndex: Int): Map<String, Long> {
        val sorted = items.sortedBy { it.position }
        val from = sorted.indexOfFirst { it.id == itemId }
        if (from < 0) return emptyMap()
        val to = toIndex.coerceIn(0, sorted.size - 1)
        if (to == from) return emptyMap()
        val moved = sorted[from]
        val rest = sorted.filterIndexed { i, _ -> i != from }
        val before = rest.getOrNull(to - 1)
        val after = rest.getOrNull(to)
        val pos: Long? = when {
            before == null && after != null -> after.position - GAP
            after == null && before != null -> before.position + GAP
            before != null && after != null && after.position - before.position >= 2 ->
                before.position + (after.position - before.position) / 2
            else -> null
        }
        if (pos != null) return mapOf(moved.id to pos)
        val order = rest.toMutableList().apply { add(to, moved) }
        val start = sorted.first().position
        return order.mapIndexedNotNull { i, item ->
            val p = start + i * GAP
            if (p != item.position) item.id to p else null
        }.toMap()
    }
}
